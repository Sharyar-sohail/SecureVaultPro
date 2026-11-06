package com.securevault.pro.data.email

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * IMAP4rev1 over a [MailTransport]. Real commands, real bytes, no third-party library.
 *
 * The engine owns the protocol and nothing else: it opens no socket, reads no preference and writes
 * no database. That is what lets the whole class be driven from a JVM test with an in-memory pipe.
 *
 * Rules the class will not break:
 * - it never authenticates on a transport that is not inside a verified TLS session;
 * - every command carries a tag and every answer is read to that tag, so a stalled server is a
 *   timeout the screen can report, never a hung thread;
 * - a `{nnn}` literal is never turned into a String before it is parsed; big literals are streamed.
 */
class ImapEngine(
    private val transport: MailTransport,
    private val output: OutputStream,
    private val reader: ImapResponseReader
) {

    private val tagCounter = AtomicInteger(0)
    private var closed = false

    /** The greeting and CAPABILITY: what the server can do decides whether STARTTLS is allowed. */
    var capability: Set<String> = emptySet()
        private set

    var greeting: String = ""
        private set

    /** True once LOGIN/AUTHENTICATE succeeded. No command is issued before it. */
    var authenticated: Boolean = false
        private set

    /**
     * True when the server advertised `UIDPLUS` (RFC 4315), i.e. it accepts a UID set on
     * `UID EXPUNGE`.
     *
     * This decides what a delete may do. Without the extension the only way to empty a mailbox is
     * `EXPUNGE`, which removes *every* message already flagged deleted in that folder — including
     * ones the owner did not just select, from any app. That is a data-destroying side effect this
     * client will never cause, so without the capability a delete stops at flagging the named
     * messages and says so.
     */
    val supportsUidPlus: Boolean
        get() = capability.any { it.equals("UIDPLUS", ignoreCase = true) }

    /** True when the server understands the `IDLE` command; recorded for the sync loop's own use. */
    val supportsIdle: Boolean
        get() = capability.any { it.equals("IDLE", ignoreCase = true) }

    fun nextTag(): String = "A%04d".format(tagCounter.incrementAndGet())

    /** Reads the `* OK …` greeting. Must be the first thing done on a connection. */
    @Throws(IOException::class)
    fun readGreeting(): String {
        val reply = reader.readReply()
            ?: throw MailNetworkException("The mail server accepted the connection but sent nothing.")
        greeting = reply.text
        if (reply.code.equals("PREAUTH", true) || greeting.contains("PREAUTH", true)) authenticated = true
        return greeting
    }

    /** `CAPABILITY` — and the pre-STARTTLS answer, which is what tells us an upgrade is offered. */
    @Throws(IOException::class)
    fun capabilityCommand(): Set<String> {
        val tag = nextTag()
        writeCommand("$tag CAPABILITY")
        val exchange = reader.readUntilTag(tag)
        capability = exchange.untagged.firstOrNull { it.text.startsWith("CAPABILITY", true) }
            ?.let { ImapResponseParser.capability(it) }
            ?: parseCapabilityFromGreeting(greeting)
        return capability
    }

    private fun parseCapabilityFromGreeting(line: String): Set<String> {
        val marker = line.indexOf("[CAPABILITY")
        if (marker < 0) return emptySet()
        return line.substring(marker + "[CAPABILITY".length).trim(']', ' ', '\n', '\r')
            .split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    }

    /**
     * `STARTTLS`, then the upgrade. Refuses when the server did not offer it, and refuses to continue
     * if the answer is anything but `OK` — a downgrade here would silently expose the password.
     */
    @Throws(IOException::class)
    fun startTls(host: String): MailTransport {
        if (!supportsStartTls(capability) && !greetingContainsCapability("STARTTLS")) {
            throw MailProtocolException(
                "$host would not encrypt this connection, so it was closed before any password was " +
                    "sent. Use the secure port instead — usually 993 for reading mail."
            )
        }
        val tag = nextTag()
        writeCommand("$tag STARTTLS")
        val exchange = reader.readUntilTag(tag)
        if (!exchange.ok) {
            throw MailProtocolException(
                "$host refused to switch this connection to an encrypted one, so it was closed " +
                    "before any password was sent. Try the secure port — usually 993 for reading mail."
            )
        }
        if (reader.bufferedAhead != 0) {
            throw MailProtocolException(
                "The server sent unexpected data while switching to encryption, so this connection " +
                    "was closed rather than risk a corrupt secure session."
            )
        }
        val socket = transport as? SocketTransport
            ?: throw MailProtocolException("This connection cannot be switched to an encrypted one.")
        return MailConnector.upgradeToTls(socket, host, reader.bufferedAhead)
    }

    private fun greetingContainsCapability(name: String): Boolean =
        greeting.contains("[CAPABILITY", ignoreCase = true) &&
            greeting.contains(name, ignoreCase = true)

    /** Password login: `A0001 LOGIN "user" "pass"`. Only on a secure transport. */
    @Throws(IOException::class)
    fun login(user: String, password: String) {
        requireSecureForAuth()
        val tag = nextTag()
        writeCommand("$tag LOGIN ${quote(user)} ${quote(password)}")
        val exchange = reader.readUntilTag(tag)
        if (!exchange.ok) {
            throw MailAuthException(
                describeAuthFailure(exchange, "The mail server refused that address and password.")
            )
        }
        authenticated = true
    }

    /**
     * `AUTHENTICATE XOAUTH2` with the SASL initial response on the continuation line, per RFC 5805
     * and Google's XOAUTH2 profile. A `+ <base64>` continuation after the response is Google's
     * "invalid-token" JSON challenge: it is answered with an empty line so the server sends the
     * real tagged failure instead of waiting.
     */
    @Throws(IOException::class)
    fun authenticateXoauth2(user: String, accessToken: String) {
        requireSecureForAuth()
        val initial = Xoauth2Sasl.initialResponse(user, accessToken)
        val tag = nextTag()
        writeCommand("$tag AUTHENTICATE XOAUTH2")
        val answer = reader.readReplyUntil(ImapReplyKind.CONTINUATION)
            ?: throw MailNetworkException("The mail server closed the connection during sign-in.")
        if (answer.kind != ImapReplyKind.CONTINUATION) {
            // The server rejected the mechanism outright; its tagged answer says why.
            throw MailAuthException(
                describeAuthFailure(
                    ImapResponseReader.TaggedExchange(answer, emptyList(), null),
                    "Google sign-in was refused."
                )
            )
        }
        writeBytes(base64Lines(initial))
        val exchange = reader.readUntilTag(tag)
        if (!exchange.ok) {
            if (exchange.continuation != null) {
                // The server wanted a second response: send the empty reply that cancels the SASL
                // exchange, then read the tagged answer it produces. Google uses exactly this shape
                // to hand back its `invalid_token` JSON.
                writeBytes(Xoauth2Sasl.cancelResponse())
                writeBytes("\r\n")
                val retry = reader.readUntilTag(tag)
                throw MailAuthException(
                    Xoauth2Sasl.describeChallenge(exchange.continuation.text) +
                        " " + describeAuthFailure(retry, "Google refused the mail token for $user.")
                )
            }
            throw MailAuthException(describeAuthFailure(exchange, "Google refused the mail token for $user."))
        }
        authenticated = true
    }

    /**
     * Turns a tagged failure into one sentence the owner can act on, appended to [base].
     *
     * The reply code alone is useless here: every provider answers a wrong password, a locked
     * account and a disabled IMAP switch with the same `NO`. The parenthesised response codes and
     * the free text underneath them are what separate "type the password again" from "turn on IMAP
     * in Gmail settings", so both are inspected and the honest fallback quotes the server rather
     * than inventing a reason.
     */
    private fun describeAuthFailure(
        exchange: ImapResponseReader.TaggedExchange,
        base: String
    ): String {
        val detail = exchange.completed.text
        val folded = detail.lowercase(Locale.US)
        val reason = when {
            exchange.completed.code.equals("PREAUTH", ignoreCase = true) ->
                "the session was already signed in"
            folded.contains("authenticationfailed") ->
                "the server has no such address, or the password does not match it"
            folded.contains("app-password") || folded.contains("app password") ||
                (folded.contains("use an app specific password") || folded.contains("apps with less secure")) ->
                "this account needs an app password: turn on 2-Step Verification and create one, then use it instead of the normal password"
            folded.contains("imap") && (folded.contains("disabled") || folded.contains("not enabled") ||
                folded.contains("turn on") || folded.contains("enable")) ->
                "this mailbox is not allowed to be opened by other mail apps - turn that access on " +
                    "in the provider's own account settings"
            folded.contains("too many") || folded.contains("rate") || folded.contains("try again later") ||
                folded.contains("temporary") ->
                "the server is throttling sign-in attempts - wait a minute and try again"
            folded.contains("account") && (folded.contains("locked") || folded.contains("disabled") ||
                folded.contains("suspended")) ->
                "the provider has locked this account; sign in on the web to see why"
            folded.contains("unsupported") || folded.contains("mechanism") ||
                exchange.completed.code.equals("BAD", ignoreCase = true) ->
                "the server does not accept this sign-in method over this connection"
            folded.contains("unavailable") ->
                "the server is unavailable right now"
            else -> exchange.failureReason
        }
        val quoted = if (reason.equals(exchange.failureReason, ignoreCase = true)) detail else reason
        return "$base ${quoted.take(200).ifBlank { "The server gave no reason." } }"
    }

    /**
     * `SELECT inbox` / `EXAMINE inbox`, returning the counts the server reported.
     *
     * The reply is read from both places the protocol puts the numbers: the untagged status lines
     * carry the message count, and the tagged `OK` carries the folder's generation number
     * (`UIDVALIDITY`) and the number the next arriving message will get (`UIDNEXT`). A client that
     * ignores the tagged answer has no way to notice that the server renumbered the folder, and then
     * acts on messages the owner is not looking at.
     */
    @Throws(IOException::class)
    fun select(wireName: String, readOnly: Boolean = false): MailboxStatus {
        val tag = nextTag()
        val command = if (readOnly) "EXAMINE" else "SELECT"
        writeCommand("$tag $command ${mailboxArgument(wireName)}")
        val exchange = reader.readUntilTag(tag)
        if (!exchange.ok) {
            throw MailProtocolException(
                "The folder ${decodeFolderName(wireName)} could not be opened on the mail server."
            )
        }
        val status = exchange.untagged.firstOrNull { it.text.contains("EXISTS", true) }
            ?.let { ImapResponseParser.selectStatus(it) } ?: MailboxStatus(0, 0, 0, 0)
        val unseen = exchange.untagged.firstOrNull { it.text.contains("UNSEEN", true) }
            ?.let { ImapResponseParser.selectStatus(it) }
        val tagged = ImapResponseParser.selectStatus(exchange.completed)
        return status.copy(
            unseen = status.unseen.takeIf { it != 0 } ?: unseen?.unseen ?: tagged.unseen,
            uidValidity = tagged.uidValidity.takeIf { it != 0L }
                ?: status.uidValidity.takeIf { it != 0L }
                ?: (unseen?.uidValidity ?: 0L),
            uidNext = tagged.uidNext
        )
    }

    /** `LIST "" "*"`, with the special names filtered the way the folder screen needs them. */
    @Throws(IOException::class)
    fun list(reference: String = "", mailbox: String = "*"): List<ImapFolderListing> {
        val tag = nextTag()
        writeCommand("$tag LIST ${mailboxArgument(reference)} ${mailboxArgument(mailbox)}")
        val exchange = reader.readUntilTag(tag)
        if (!exchange.ok) throw MailProtocolException("The mail server would not show its list of folders.")
        return exchange.untagged.mapNotNull { reply ->
            if (!reply.text.startsWith("LIST", true)) null else ImapResponseParser.list(reply)
        }
    }

    /** `UID SEARCH` with the two predicates the mail module actually uses. */
    @Throws(IOException::class)
    fun uidSearch(criteria: String): List<Long> {
        val tag = nextTag()
        writeCommand("$tag UID SEARCH $criteria")
        val exchange = reader.readUntilTag(tag)
        if (!exchange.ok) throw MailProtocolException("The search of this folder did not complete.")
        return exchange.untagged.firstOrNull { it.text.startsWith("SEARCH", true) }
            ?.let { ImapResponseParser.search(it) }
            ?: emptyList()
    }

    /**
     * `UID FETCH lo:hi (items)` with bodies streamed through [sink] when they are big.
     *
     * [items] is the parenthesised FETCH item list; the caller names the sections it wants, so this
     * method never has to guess that a header request should not pull 20 MB of attachment.
     */
    @Throws(IOException::class)
    fun uidFetch(
        sequenceSet: String,
        items: String,
        silent: Boolean = true,
        sinkFactory: (section: String, declaredBytes: Long) -> ImapResponseReader.LiteralSink? = { _, _ -> null }
    ): List<ImapFetchItem> {
        val tag = nextTag()
        val bodyItems = if (silent) items.replace("BODY[", "BODY.PEEK[") else items
        writeCommand("$tag UID FETCH $sequenceSet ($bodyItems)")
        val out = ArrayList<ImapFetchItem>()
        while (true) {
            val reply = reader.readReplyWithSink(sinkFactory) ?: break
            when (reply.kind) {
                ImapReplyKind.COMPLETED -> if (reply.tag == tag) {
                    if (!reply.text.contains("OK", true) && !reply.text.contains("PREAUTH", true)) {
                        // A FETCH that failed after some untagged answers still reports what arrived.
                        if (out.isEmpty()) {
                            throw MailProtocolException("The message text could not be read from the mail server.")
                        }
                    }
                    return out
                }
                ImapReplyKind.UNTAGGED -> ImapResponseParser.fetch(reply)?.let { out += it }
                ImapReplyKind.CONTINUATION -> throw MailProtocolException(
                    "The mail server interrupted the download with an unexpected request, so this " +
                        "connection was closed."
                )
                ImapReplyKind.SEND_ECHO -> Unit
            }
        }
        throw MailNetworkException("The connection ended before the message arrived.")
    }

    /** `UID STORE n +FLAGS (\Seen)` — the flag writes the mail module makes. */
    @Throws(IOException::class)
    fun uidStore(uids: List<Long>, flags: List<String>, replace: Boolean = false): Boolean {
        if (uids.isEmpty() || flags.isEmpty()) return true
        val operator = if (replace) "FLAGS" else "+FLAGS"
        val tag = nextTag()
        val set = uids.joinToString(",")
        writeCommand("$tag UID STORE $set $operator (${flags.joinToString(" ")})")
        val exchange = reader.readUntilTag(tag)
        return exchange.ok
    }

    /**
     * `UID STORE n -FLAGS (\Flagged)` — the subtraction form, which is how a star is really cleared.
     *
     * The alternative is rewriting the whole flag set, and a client that guesses that set can drop a
     * flag another app put there: a message the owner marked as answered or kept as a draft would
     * silently lose that mark. Removing only the one flag asked for cannot damage anything else, so
     * this is the form the screens use for both star and unread.
     */
    @Throws(IOException::class)
    fun uidRemoveFlags(uids: List<Long>, flags: List<String>): Boolean {
        if (uids.isEmpty() || flags.isEmpty()) return true
        val tag = nextTag()
        writeCommand("$tag UID STORE ${uids.joinToString(",")} -FLAGS (${flags.joinToString(" ")})")
        return reader.readUntilTag(tag).ok
    }

    /** `UID COPY` between folders, used by the archive action on the message screen. */
    @Throws(IOException::class)
    fun uidCopy(uids: List<Long>, targetWireName: String): Boolean {
        if (uids.isEmpty()) return true
        val tag = nextTag()
        writeCommand(
            "$tag UID COPY ${uids.joinToString(",")} ${mailboxArgument(targetWireName)}"
        )
        return reader.readUntilTag(tag).ok
    }

    /**
     * Marks the named messages deleted and, when the server supports removing just those, removes
     * them; the module never deletes without the owner asking.
     *
     * The removal step is `UID EXPUNGE <the same uid list>` (RFC 4315), which takes a set argument —
     * the bare `UID EXPUNGE` this method used to send is not a valid command, every server answers it
     * with a refusal, and the owner's delete silently never happened while the screen said it had.
     *
     * When the capability is absent this stops after the flag and says so. The only other way to
     * reclaim space is `EXPUNGE`, which removes every message already marked deleted in the whole
     * folder — including ones the owner deleted from another app and never asked to destroy here.
     * Guessing on the owner's mail data is not a trade this client makes.
     */
    @Throws(IOException::class)
    fun uidDelete(uids: List<Long>, expunge: Boolean): Boolean {
        if (uids.isEmpty()) return true
        val set = uids.joinToString(",")
        val tag = nextTag()
        writeCommand("$tag UID STORE $set +FLAGS (\\Deleted)")
        val stored = reader.readUntilTag(tag).ok
        if (!stored || !expunge || !supportsUidPlus) return stored
        val expungeTag = nextTag()
        writeCommand("$expungeTag UID EXPUNGE $set")
        return reader.readUntilTag(expungeTag).ok
    }

    /** `NOOP`, the cheap keep-alive the sync loop uses between folders. */
    @Throws(IOException::class)
    fun noop(): Boolean {
        val tag = nextTag()
        writeCommand("$tag NOOP")
        return reader.readUntilTag(tag).ok
    }

    /** `LOGOUT` then close. Never throws: a server that ignores LOGOUT is not the owner's problem. */
    fun logout() {
        if (closed) return
        closed = true
        runCatching {
            val tag = nextTag()
            writeCommand("$tag LOGOUT")
            reader.readUntilTag(tag)
        }
        runCatching { transport.close() }
    }

    // ------------------------------------------------------------------ wire helpers

    private fun requireSecureForAuth() {
        if (!transport.isSecure) {
            throw MailProtocolException(
                "Refusing to sign in over a connection that is not encrypted."
            )
        }
    }

    @Throws(IOException::class)
    private fun writeCommand(line: String) {
        if (closed) throw MailNetworkException("The connection to the mail server is already closed.")
        // The command text can carry a password on the LOGIN line: it is written to the socket and
        // never returned, logged or stored anywhere.
        writeBytes(line + "\r\n")
    }

    @Throws(IOException::class)
    private fun writeBytes(text: String) {
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    @Throws(IOException::class)
    private fun writeBytes(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** The SASL initial response goes out base64-encoded, wrapped at 76 characters like any AUTH blob. */
    private fun base64Lines(bytes: ByteArray): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        return encoded.chunked(76).joinToString("\r\n") + "\r\n"
    }

    companion object {
        /** `A0001 LOGIN …` and friends: an atom-safe quoted string with backslash escapes. */
        fun quote(value: String): String {
            val builder = StringBuilder(value.length + 2)
            builder.append('"')
            for (character in value) {
                if (character == '"' || character == '\\') builder.append('\\')
                if (character.code < 32 || character.code == 127) continue
                builder.append(character)
            }
            builder.append('"')
            return builder.toString()
        }

        /** A mailbox name: quoted when it needs it, `INBOX` left bare as the spec prefers. */
        fun mailboxArgument(wireName: String): String =
            if (wireName.equals("INBOX", ignoreCase = true)) "INBOX" else quote(wireName)

        fun supportsStartTls(capability: Set<String>): Boolean =
            capability.any { it.equals("STARTTLS", ignoreCase = true) }

        /** Modified UTF-8 (RFC 3501) wire name to what the folder screen shows. */
        fun decodeFolderName(wireName: String): String = ModifiedUtf7.decode(wireName)

        /** The reverse, for a folder name typed by the owner. */
        fun encodeFolderName(display: String): String = ModifiedUtf7.encode(display)

        /** Builds the engine over a transport, wiring the reader to the same streams. */
        fun attach(
            transport: MailTransport,
            streamedLiteralDirectory: java.io.File? = null
        ): ImapEngine {
            val stream = transport.input
            val sink = object : ImapResponseReader.LiteralSink {
                private var target: java.io.File? = null
                private val chunk = ByteArrayOutputStream()
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (target == null) {
                        streamedLiteralDirectory?.mkdirs()
                        target = java.io.File(
                            streamedLiteralDirectory ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "."),
                            "imap-literal-${System.nanoTime()}.bin"
                        )
                    }
                    target?.appendBytes(bytes, offset, length)
                }

                override fun path(): String = target?.absolutePath ?: ""
            }
            val reader = ImapResponseReader(stream).apply { literalSink = sink }
            return ImapEngine(transport, transport.output, reader)
        }

        private fun java.io.File.appendBytes(bytes: ByteArray, offset: Int, length: Int) {
            java.io.FileOutputStream(this, true).use { it.write(bytes, offset, length) }
        }

        /** Standard IMAP literal framing used by the engine's own tests. */
        internal fun literalFraming(section: String, bytes: ByteArray): String =
            "$section {${bytes.size}}" + "\r\n"

        internal fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
    }
}
