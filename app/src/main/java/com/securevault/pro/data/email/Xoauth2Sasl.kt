package com.securevault.pro.data.email

import java.io.ByteArrayOutputStream

/**
 * The SASL pieces that must be byte-exact: XOAUTH2 (RFC 5805 plus Google's profile) and the plain
 * user/password forms.
 *
 * Both live in one pure object so a test can assert the exact bytes; nothing here touches a socket,
 * and the access token or password is only ever present in the returned array, never in a String
 * that the module logs or stores.
 */
object Xoauth2Sasl {

    /**
     * The XOAUTH2 initial response: `user=<user>^Aauth=Bearer <token>^A^A`.
     *
     * The `^A` is US-ASCII 0x01 and there is a trailing empty attribute, so the array ends with two
     * of them. This exact shape is what Gmail, Workspace, Outlook and Yahoo all expect on the wire.
     */
    fun initialResponse(user: String, accessToken: String): ByteArray {
        val builder = ByteArrayOutputStream(user.length + accessToken.length + 24)
        builder.writeBytes("user=")
        builder.writeBytes(user)
        builder.write(Ctrl.STX)
        builder.writeBytes("auth=Bearer ")
        builder.writeBytes(accessToken)
        builder.write(Ctrl.STX)
        builder.write(Ctrl.STX)
        return builder.toByteArray()
    }

    /** Same bytes, base64'd: what goes after `AUTHENTICATE XOAUTH2 ` or on the continuation line. */
    fun encodedInitialResponse(user: String, accessToken: String): String =
        java.util.Base64.getEncoder().encodeToString(initialResponse(user, accessToken))

    /** The empty SASL response that cancels a challenge, so the server sends its real failure. */
    fun cancelResponse(): ByteArray = ByteArray(0)

    /**
     * Reads a server-side XOAUTH2 challenge back into the fields Google puts in it. Servers send
     * `base64({“status”:“invalid_token”,“saslError”…})` on a failure continuation.
     */
    fun describeChallenge(challenge: String): String {
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(challenge.trim()), Charsets.UTF_8)
        }.getOrDefault(challenge)
        return when {
            decoded.contains("invalid_token", true) || decoded.contains("expired", true) ->
                "The Google token had expired. Sign in again to refresh it."
            decoded.contains("AUTH_FAILED", true) ->
                "Google refused the token for this mailbox."
            decoded.contains("invalid_grant", true) ->
                "The Google authorisation for mail was revoked; reconnect the account."
            decoded.isBlank() -> "Google refused the XOAUTH2 sign-in."
            else -> "Google refused the XOAUTH2 sign-in: ${decoded.take(160)}"
        }
    }

    /** The byte 0x01 separator that RFC 5805 uses between SASL attributes. */
    private object Ctrl {
        const val STX: Int = 1
    }

    private fun ByteArrayOutputStream.writeBytes(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }
}

/** `AUTH PLAIN`'s token: `authzid \0 authcid \0 password`, no trailing separator. */
object SaslPlain {
    fun initialResponse(authorizationId: String, user: String, password: String): ByteArray {
        val builder = ByteArrayOutputStream(authorizationId.length + user.length + password.length + 2)
        builder.write(authorizationId.toByteArray(Charsets.UTF_8))
        builder.write(0)
        builder.write(user.toByteArray(Charsets.UTF_8))
        builder.write(0)
        builder.write(password.toByteArray(Charsets.UTF_8))
        return builder.toByteArray()
    }

    /** The usual shape, where the authorization id equals the authentication id. */
    fun initialResponse(user: String, password: String): ByteArray = initialResponse(user, user, password)

    fun encoded(user: String, password: String): String =
        java.util.Base64.getEncoder().encodeToString(initialResponse(user, password))
}

/**
 * Address text as an owner types it, turned into the envelope addresses SMTP needs and the display
 * strings the message list shows.
 *
 * Deliberately small and strict: it splits on commas and semicolons outside quotes, keeps a
 * `Display <a@b>` pair together and rejects anything that is not `local@domain`. It is not an RFC
 * 5322 parser, and it does not pretend to be — the server is the final authority on a address.
 */
object MailAddresses {

    /** Splits `a@b, "Name; still" <c@d>; e@f` into individual address strings. */
    fun split(raw: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var angle = 0
        for (character in raw) {
            when {
                quote != null -> {
                    current.append(character)
                    if (character == quote) quote = null
                }
                character == '"' || character == '\'' -> {
                    quote = character
                    current.append(character)
                }
                character == '<' -> {
                    angle++
                    current.append(character)
                }
                character == '>' -> {
                    angle = (angle - 1).coerceAtLeast(0)
                    current.append(character)
                }
                (character == ',' || character == ';') && angle == 0 -> {
                    addIfPresent(out, current)
                }
                character == '\r' || character == '\n' -> current.append(' ')
                else -> current.append(character)
            }
        }
        addIfPresent(out, current)
        return out
    }

    /** The bare `local@domain` of one address string, lower-cased, or empty when it has none. */
    fun addressOf(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val inside = value.substringAfter('<', "").substringBefore('>').trim()
        val candidate = inside.ifBlank { value.substringAfterLast(',', value).trim() }
        val stripped = candidate.removeSurrounding("\"")
        return if (looksValid(stripped)) stripped.lowercase() else ""
    }

    /** The part before the angle bracket, unquoted, for a `Display Name`. */
    fun displayOf(raw: String): String {
        val value = raw.trim()
        val name = value.substringBefore('<').trim().removeSurrounding("\"").trim()
        return name
    }

    /** `Display <a@b>` when there is a name, the plain address otherwise: what a header line wants. */
    fun display(raw: String): String {
        val address = addressOf(raw)
        val name = displayOf(raw)
        return when {
            address.isBlank() -> raw.trim()
            name.isBlank() -> address
            else -> "${MimeTokens.escapeDisplayName(name)} <$address>"
        }
    }

    fun domainOf(raw: String): String = addressOf(raw).substringAfterLast('@', "")

    fun isProbablyValid(raw: String): Boolean = looksValid(addressOf(raw))

    /**
     * The rule the setup screen uses before it spends a network round trip: a local part, one `@`,
     * a domain with a dot or `localhost`, and no spaces.
     */
    fun looksValid(value: String): Boolean {
        if (value.isBlank() || value.length > 320) return false
        if (value.any { it.code < 33 || it.code == 127 || it == ' ' }) return false
        val at = value.lastIndexOf('@')
        if (at <= 0 || at == value.length - 1) return false
        val domain = value.substring(at + 1)
        if (!domain.contains('.') && domain != "localhost") return false
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains("..")) return false
        return domain.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    /** Recipients for the envelope: To plus Cc, deduplicated, blanks dropped. */
    fun envelopeRecipients(to: List<String>, cc: List<String>): List<String> =
        (to + cc).map { addressOf(it) }.filter { it.isNotBlank() }.distinct()

    private fun addIfPresent(out: MutableList<String>, current: StringBuilder) {
        val value = current.toString().trim()
        if (value.isNotEmpty()) out += value
        current.setLength(0)
    }
}

/**
 * IMAP mailbox names in modified UTF-7 (RFC 3501 section 5.1.3).
 *
 * Folder names are the one part of IMAP that is not ASCII, and `&Acn-` sequences must survive a
 * round trip or a Urdu-labelled folder becomes an unusable name. Pure and total: an undecodable
 * sequence is left as text rather than throwing, because a folder the owner can see is better than
 * an exception on the folder screen.
 */
object ModifiedUtf7 {

    /** Wire name to display name. */
    fun decode(wireName: String): String {
        if (!wireName.contains('&')) return wireName
        val out = StringBuilder(wireName.length)
        var i = 0
        while (i < wireName.length) {
            val c = wireName[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 < wireName.length && wireName[i + 1] == '-') {
                out.append('&')
                i += 2
                continue
            }
            var end = i + 1
            while (end < wireName.length && wireName[end] != '-') end++
            val encoded = wireName.substring(i + 1, end)
            val decoded = runCatching {
                String(java.util.Base64.getUrlDecoder().decode(padBase64(encoded)), Charsets.UTF_16BE)
            }.getOrNull()
            if (decoded == null) {
                out.append(wireName.substring(i, minOf(end, wireName.length)))
            } else {
                out.append(decoded)
            }
            i = if (end < wireName.length) end + 1 else end
        }
        return out.toString()
    }

    /** Display name to wire name, the exact inverse for everything [decode] can read back. */
    fun encode(display: String): String {
        val out = StringBuilder(display.length + 8)
        var run = StringBuilder()
        fun flushRun() {
            if (run.isEmpty()) return
            val bytes = run.toString().toByteArray(Charsets.UTF_16BE)
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .replace('+', ',')
            out.append('&').append(encoded).append('-')
            run = StringBuilder()
        }
        for (c in display) {
            when {
                c == '&' -> {
                    flushRun()
                    out.append("&-")
                }
                c.code < 0x20 || c.code > 0x7E -> run.append(c)
                else -> {
                    flushRun()
                    out.append(c)
                }
            }
        }
        flushRun()
        return out.toString()
    }

    /** IMAP uses `-` as the base64 pad stop, so a short tail needs the padding put back. */
    private fun padBase64(value: String): String {
        val clean = value.replace(',', '+').trimEnd('=')
        val pad = (4 - clean.length % 4) % 4
        return clean + "=".repeat(pad)
    }
}
