package com.securevault.pro.data.email

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One SMTP reply: the code, the lines the server sent and whether it was a positive answer. */
data class SmtpReply(val code: Int, val lines: List<String>) {
    val text: String get() = lines.joinToString(" / ")
    val ok: Boolean get() = code in 200..399
    val isContinuation: Boolean get() = code == 354
    val permanentFailure: Boolean get() = code in 500..599

    companion object {
        /**
         * Parses one reply out of [rawLines], the RFC 5321 way: a line whose fourth character is
         * `-` continues the reply, a space ends it.
         */
        fun parse(rawLines: List<String>): SmtpReply {
            val first = rawLines.firstOrNull().orEmpty()
            val code = first.take(3).toIntOrNull() ?: 0
            return SmtpReply(code, rawLines.map { it.trimEnd('\r', '\n') })
        }
    }
}

/**
 * Reads SMTP replies off a stream. Line-based, bounded, and the bytes after the last line of a
 * reply are left untouched — which is what makes the STARTTLS upgrade safe.
 */
class SmtpLineReader(
    private val source: InputStream,
    private val chunkBytes: Int = 4096
) {

    private val buffer = ByteArray(chunkBytes.coerceIn(1, MailTimings.MAX_LINE_BYTES))
    private var fill = 0
    private var cursor = 0
    private var eof = false

    /** Bytes read ahead of the reply that was just returned; must be 0 before a TLS upgrade. */
    val bufferedAhead: Int get() = if (eof) 0 else fill - cursor

    /** Reads one line; null at a clean EOF. */
    private fun readLine(): String? {
        val collected = ByteArrayOutputStream(128)
        while (true) {
            if (cursor >= fill) {
                if (!refill()) {
                    eof = true
                    return if (collected.size() == 0) null else collected.toString(Charsets.UTF_8.name())
                }
            }
            var i = cursor
            var newline = -1
            while (i < fill) {
                if (buffer[i] == LF) {
                    newline = i
                    break
                }
                i++
            }
            if (newline < 0) {
                collected.write(buffer, cursor, fill - cursor)
                cursor = fill
                if (collected.size() > MailTimings.MAX_LINE_BYTES) {
                    throw MailProtocolException("The SMTP server sent an over-long line.")
                }
                continue
            }
            val take = newline - cursor
            if (take > 0) collected.write(buffer, cursor, take)
            cursor = newline + 1
            val bytes = collected.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CR) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.UTF_8)
        }
    }

    private fun refill(): Boolean {
        fill = 0
        cursor = 0
        val read = source.read(buffer, 0, buffer.size)
        if (read <= 0) return false
        fill = read
        return true
    }

    /**
     * One complete reply. A multi-line answer is read until its final line, because issuing the next
     * command after only the first line of a `250-` block is how clients desynchronise.
     */
    @Throws(IOException::class)
    fun readReply(): SmtpReply {
        val lines = ArrayList<String>()
        while (true) {
            val line = readLine() ?: break
            lines += line
            val continues = line.length >= 4 && line[3] == '-'
            if (!continues) break
        }
        if (lines.isEmpty()) throw MailNetworkException("The SMTP server closed the connection without answering.")
        return SmtpReply.parse(lines)
    }

    companion object {
        private val CR: Byte = 13
        private val LF: Byte = 10
    }
}

/**
 * RFC 5321 in the strict order it is meant to happen, over a [MailTransport].
 *
 * The sequence is the contract, and [SmtpEngine] cannot be talked out of it:
 * `EHLO` → (`STARTTLS` → upgrade → `EHLO` again) → `AUTH` → `MAIL FROM` → `RCPT TO`* → `DATA` →
 * the message with dot-stuffing → `.` → `QUIT`.
 *
 * Two invariants that the unit tests assert byte for byte:
 * - `AUTH` is never sent while the transport is not secure, so a password or an OAuth blob can never
 *   cross a bare connection;
 * - after `STARTTLS` the capability list is re-read from a fresh `EHLO`, because the pre-upgrade one
 *   belongs to an attacker-visible session.
 */
class SmtpEngine(
    private val transport: MailTransport,
    private val output: OutputStream,
    private val reader: SmtpLineReader
) {

    /** The `250` capabilities of the current (post-TLS) session. */
    var extensions: Set<String> = emptySet()
        private set

    var greeting: String = ""
        private set

    private var closed = false

    /** Reads the `220 …` greeting; the very first thing any SMTP conversation does. */
    @Throws(IOException::class)
    fun readGreeting(): String {
        val reply = reader.readReply()
        if (reply.code != 220) throw MailProtocolException("Unexpected SMTP greeting: ${reply.text}")
        greeting = reply.text
        return greeting
    }

    /** `EHLO <name>`; records the capability tokens and fails loudly when the server is not ESMTP. */
    @Throws(IOException::class)
    fun ehlo(serverName: String): Set<String> {
        val reply = command("EHLO $serverName")
        if (reply.code == 250) {
            // Every keyword of every line is kept: `250-AUTH PLAIN LOGIN XOAUTH2` must show up as
            // AUTH, PLAIN, LOGIN and XOAUTH2, otherwise the mechanism checks below are blind.
            extensions = reply.lines.drop(1)
                .flatMap { line -> line.trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
                .map { it.uppercase() }
                .toSet()
            return extensions
        }
        if (reply.code == 500 || reply.code == 502) {
            // A server with no EHLO is not usable for STARTTLS or SASL; HELO alone cannot carry
            // this module's requirements, so it is reported instead of silently downgraded.
            throw MailProtocolException(
                "The server ${transport.peerHost} does not support EHLO, so it cannot offer the " +
                    "encrypted sign-in this app requires."
            )
        }
        throw MailProtocolException("EHLO was refused: ${reply.text}")
    }

    /**
     * `STARTTLS` and the upgrade. Returns the transport the caller must switch to; the engine is
     * single-use by design so a stale plaintext reader can never be reused after the switch.
     */
    @Throws(IOException::class)
    fun startTls(host: String): MailTransport {
        if (!extensions.any { it.equals("STARTTLS", ignoreCase = true) }) {
            throw MailProtocolException(
                "$host does not advertise STARTTLS on this port. Switch the setup form to SSL/TLS " +
                    "with port 465."
            )
        }
        val reply = command("STARTTLS")
        if (reply.code != 502 && !reply.ok) throw MailProtocolException("STARTTLS was refused: ${reply.text}")
        if (reply.code == 502) throw MailProtocolException("$host does not implement STARTTLS.")
        if (!reply.lines.firstOrNull().orEmpty().startsWith("220")) {
            throw MailProtocolException("STARTTLS did not answer 220: ${reply.text}")
        }
        if (reader.bufferedAhead != 0) {
            throw MailProtocolException(
                "The server sent more than its 220 answer, so the connection was closed instead of " +
                    "risking a broken TLS session."
            )
        }
        val socket = transport as? SocketTransport
            ?: throw MailProtocolException("This transport cannot be upgraded to TLS.")
        return MailConnector.upgradeToTls(socket, host, reader.bufferedAhead)
    }

    /** `AUTH PLAIN` (base64 of `user\0password\0user`) or `AUTH LOGIN`, whichever the server offers. */
    @Throws(IOException::class)
    fun authPassword(user: String, password: String) {
        requireSecureForAuth()
        when {
            extensions.contains("AUTH") && supportsMechanism("PLAIN") -> authPlain(user, password)
            supportsMechanism("LOGIN") -> authLogin(user, password)
            else -> throw MailProtocolException(
                "${transport.peerHost} offers no PLAIN or LOGIN sign-in. Use an app password or " +
                    "check the outgoing server name."
            )
        }
    }

    private fun authPlain(user: String, password: String) {
        val blob = SaslPlain.initialResponse(user, password)
        val reply = command("AUTH PLAIN ${base64(blob)}")
        reportAuthFailure(reply, user)
    }

    private fun authLogin(user: String, password: String) {
        val start = command("AUTH LOGIN")
        if (!start.ok && start.code != 334) reportAuthFailure(start, user)
        // AUTH LOGIN asks for each half on its own continuation line, base64'd, never quoted.
        expectContinuation(start)
        val who = command(base64(user.toByteArray(Charsets.UTF_8)))
        expectContinuation(who)
        val secret = command(base64(password.toByteArray(Charsets.UTF_8)))
        reportAuthFailure(secret, user)
    }

    /** `AUTH XOAUTH2 <base64(user\1auth=Bearer token\1\1)>`, Google's IMAP/SMTP SASL profile. */
    @Throws(IOException::class)
    fun authXoauth2(user: String, accessToken: String) {
        requireSecureForAuth()
        if (!supportsMechanism("XOAUTH2")) {
            throw MailProtocolException(
                "${transport.peerHost} does not offer XOAUTH2, so Google sign-in cannot be used here."
            )
        }
        val initial = Xoauth2Sasl.initialResponse(user, accessToken)
        val reply = command("AUTH XOAUTH2 ${base64(initial)}")
        if (!reply.ok) {
            // Google answers a bad token with a 3xx challenge holding its own JSON error; the SASL
            // exchange has to be cancelled with an empty line before the real failure arrives.
            if (reply.code in 300..399) {
                val finalReply = command("")
                reportAuthFailure(finalReply, user)
            }
            reportAuthFailure(reply, user)
        }
    }

    /** `MAIL FROM:<address>` with the RFC 5321 size hint when the server understands SMTPUTF8-free SIZE. */
    @Throws(IOException::class)
    fun mailFrom(address: String, approxBytes: Long = 0L) {
        val parameters = if (approxBytes > 0L && extensions.contains("SIZE")) " SIZE=$approxBytes" else ""
        val reply = command("MAIL FROM:<$address>$parameters")
        if (!reply.ok) throw MailProtocolException("The server would not accept the sender: ${reply.text}")
    }

    /** `RCPT TO:<address>`, one per recipient; at least one must be accepted. */
    @Throws(IOException::class)
    fun rcptTo(addresses: List<String>): List<String> {
        val refused = ArrayList<String>()
        for (address in addresses) {
            val reply = command("RCPT TO:<$address>")
            if (!reply.ok) refused += "${MailAddresses.display(address)}: ${reply.text.take(120)}"
        }
        if (refused.size == addresses.size) {
            throw MailProtocolException("No recipient was accepted — ${refused.first()}")
        }
        return refused
    }

    /** `DATA`, then the message with dot-stuffing, then the terminating dot. */
    @Throws(IOException::class)
    fun data(body: ByteArray): String {
        val start = command("DATA")
        if (!start.isContinuation && start.code != 354) {
            throw MailProtocolException("The server refused to start a message: ${start.text}")
        }
        val writer = DotStuffedWriter(output)
        writer.write(body)
        writer.finish()
        val done = reader.readReply()
        if (!done.ok) throw MailProtocolException("The message was rejected: ${done.text}")
        return done.text
    }

    /** Streams a message built elsewhere (headers plus attachments) through the stuffing writer. */
    @Throws(IOException::class)
    fun dataFromSource(source: MessageBodySource): String {
        val start = command("DATA")
        if (!start.isContinuation && start.code != 354) {
            throw MailProtocolException("The server refused to start a message: ${start.text}")
        }
        val writer = DotStuffedWriter(output)
        source.writeThrough(writer)
        writer.finish()
        val done = reader.readReply()
        if (!done.ok) throw MailProtocolException("The message was rejected: ${done.text}")
        return done.text
    }

    @Throws(IOException::class)
    fun quit() {
        if (closed) return
        closed = true
        runCatching { command("QUIT") }
        runCatching { transport.close() }
    }

    // ------------------------------------------------------------------ wire helpers

    private fun supportsMechanism(name: String): Boolean = extensions.contains(name.uppercase())

    private fun requireSecureForAuth() {
        if (!transport.isSecure) {
            throw MailProtocolException(
                "Refusing to sign in to ${transport.peerHost} over a connection that is not encrypted."
            )
        }
    }

    private fun expectContinuation(reply: SmtpReply) {
        if (reply.code != 334 && reply.code != 354) {
            throw MailProtocolException("The server did not ask for the next credential part: ${reply.text}")
        }
    }

    private fun reportAuthFailure(reply: SmtpReply, user: String) {
        if (reply.ok) return
        val why = when (reply.code) {
            534, 535, 504 ->
                "$user could not sign in. ${reply.text.take(160)} " +
                    "For Yahoo, iCloud or Outlook this usually means an app password is required."
            530 -> "The server wants TLS before sign-in: ${reply.text.take(120)}"
            421, 450, 451, 452 -> "The server is busy or refusing right now: ${reply.text.take(140)}"
            else -> "Sign-in failed: ${reply.text.take(160)}"
        }
        throw MailAuthException(why)
    }

    @Throws(IOException::class)
    private fun command(line: String): SmtpReply {
        if (closed) throw MailNetworkException("The SMTP connection is already closed.")
        write((line + "\r\n").toByteArray(Charsets.UTF_8))
        return reader.readReply()
    }

    @Throws(IOException::class)
    private fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}

/** A message body that can be written out in bounded chunks; attachments come through this. */
interface MessageBodySource {
    /** Total bytes the source will write, so SIZE hints and progress reports can be honest. */
    fun contentLength(): Long

    /** Writes every raw byte into [sink], which owns the dot-stuffing; sources must not pre-stuff. */
    fun writeThrough(sink: DotStuffedWriter)
}
