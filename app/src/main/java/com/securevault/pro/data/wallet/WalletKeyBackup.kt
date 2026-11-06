package com.securevault.pro.data.wallet

/**
 * One wallet's spendable material, sealed so it can travel inside the Drive backup document, and
 * opened again on the phone that receives it.
 *
 * Pure JVM: the small JSON is written and read here by hand, so no Android type and no JSON library
 * is needed to drive the whole path from a test with a fake sealer. Sealing is delegated to
 * [WalletKeyBackupSealer] — mirroring [BitgetSecretSealer] — which on the device is
 * `CryptoManager.encryptWithDerivedKey` / `decryptWithDerivedKey` under this account's own vault
 * key, the same master key the backup document itself is sealed with. No cipher is invented here and
 * no parameter is changed.
 *
 * Where the blob lives: the `keyBackup` field of the Drive backup document, in its JSON and XML
 * forms. It is not a Room column and not a field of `RealtimeFieldSchema`, so it has no path into a
 * realtime record. A document that carries one still carries the wallet's address list and balance
 * rows exactly as before; the blob only adds the means to spend them.
 */

/** The master-key seal/open pair, injected so the codec is exercisable off-device. */
interface WalletKeyBackupSealer {
    fun seal(plaintext: String): String?
    fun open(ciphertext: String?): String?
}

/** Matches the [com.securevault.pro.crypto.WalletImportKind] names, so a row and a blob agree. */
enum class WalletKeyBackupKind { PRIVATE_KEY, SEED_PHRASE }

/** What one blob holds: a private key in hex, or a recovery phrase and its optional passphrase. */
data class WalletKeyMaterial(
    val kind: WalletKeyBackupKind,
    val secret: String,
    val passphrase: String = ""
) {
    /** Never the material: this string ends up in log and exception text. */
    override fun toString(): String = "WalletKeyMaterial(kind=$kind, secret=hidden)"
}

object WalletKeyBackup {

    const val FIELD_KIND = "kind"
    const val FIELD_SECRET = "secret"
    const val FIELD_PASSPHRASE = "passphrase"

    private const val HEX_DIGITS = "0123456789abcdef"
    private const val MASK = '…'

    /**
     * Which kind of material a payload describes, from whatever the row says plus the shape of the
     * value itself — the same rule that has always told a phrase from a key.
     */
    fun kindOf(rawKind: String?, secret: String): WalletKeyBackupKind? = when {
        rawKind.equals(WalletKeyBackupKind.PRIVATE_KEY.name, ignoreCase = true) ->
            WalletKeyBackupKind.PRIVATE_KEY
        rawKind.equals(WalletKeyBackupKind.SEED_PHRASE.name, ignoreCase = true) ->
            WalletKeyBackupKind.SEED_PHRASE
        secret.contains(' ') -> WalletKeyBackupKind.SEED_PHRASE
        isPrivateKeyHex(secret) -> WalletKeyBackupKind.PRIVATE_KEY
        else -> null
    }

    /** The same hex rule [WalletKeyVault] applies before it seals a key for the device store. */
    fun isPrivateKeyHex(value: String): Boolean {
        val clean = value.trim().removePrefix("0x").removePrefix("0X")
        return clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /** The blob's plaintext shape, or null when these values are not one wallet's material. */
    fun documentFor(kind: WalletKeyBackupKind, secret: String, passphrase: String = ""): String? {
        val body = when (kind) {
            WalletKeyBackupKind.PRIVATE_KEY -> {
                if (!isPrivateKeyHex(secret)) return null
                val canonical = "0x" + secret.trim().removePrefix("0x").removePrefix("0X").lowercase()
                pair(FIELD_KIND, kind.name) + "," + pair(FIELD_SECRET, canonical)
            }

            WalletKeyBackupKind.SEED_PHRASE -> {
                val words = secret.trim().split(' ').filter { it.isNotBlank() }
                if (words.isEmpty()) return null
                pair(FIELD_KIND, kind.name) +
                    "," + pair(FIELD_SECRET, words.joinToString(" ")) +
                    "," + pair(FIELD_PASSPHRASE, passphrase)
            }
        }
        return "{$body}"
    }

    /**
     * The sealed blob for one wallet, or null when there is nothing safe to write.
     *
     * A value that still shows the key after sealing is refused rather than emitted: this is the
     * only door the spendable material goes through, and no unsealed key passes it.
     */
    fun seal(
        sealer: WalletKeyBackupSealer,
        kind: WalletKeyBackupKind,
        secret: String,
        passphrase: String = ""
    ): String? {
        val document = documentFor(kind, secret, passphrase) ?: return null
        val material = parse(document) ?: return null
        val sealed = sealer.seal(document)?.takeIf { it.isNotBlank() } ?: return null
        return if (leaks(material.secret, sealed) || leaks(material.passphrase, sealed)) null else sealed
    }

    /** The material inside a blob, or null when the blob is absent, will not open, or is not ours. */
    fun open(sealer: WalletKeyBackupSealer, blob: String?): WalletKeyMaterial? {
        if (blob.isNullOrBlank()) return null
        val document = sealer.open(blob)?.takeIf { it.isNotBlank() } ?: return null
        return parse(document)
    }

    /** Reads a blob's plaintext document. Null for anything that is not exactly this small shape. */
    fun parse(document: String?): WalletKeyMaterial? {
        val text = document?.trim() ?: return null
        val fields = readFields(text) ?: return null
        val secret = fields[FIELD_SECRET].orEmpty().trim()
        if (secret.isBlank()) return null
        val kind = kindOf(fields[FIELD_KIND], secret) ?: return null
        val passphrase = if (kind == WalletKeyBackupKind.SEED_PHRASE) fields[FIELD_PASSPHRASE].orEmpty() else ""
        // A document that will not re-produce its own canonical shape was not written here, so it is
        // not trusted to carry a key.
        if (documentFor(kind, secret, passphrase) != text) return null
        return WalletKeyMaterial(kind, secret, passphrase)
    }

    /** True when [candidate] still shows [secret]: the value itself, or its hex without the prefix. */
    fun leaks(secret: String?, candidate: String?): Boolean {
        val value = secret?.trim().orEmpty()
        if (value.isBlank()) return false
        val target = candidate.orEmpty()
        if (target.contains(value)) return true
        val bare = value.removePrefix("0x").removePrefix("0X")
        return bare != value && bare.isNotBlank() && target.contains(bare, ignoreCase = true)
    }

    /**
     * The address rule for a restore. [claimed] is what the backup — or this device's own record of
     * that wallet — says the wallet's address is; [owned] is the address the restored material
     * actually derives. A masked claim (`head…tail`, all the key store keeps) is checked by its head
     * and tail. Null means nothing was claimed, or the claim holds; a sentence means refuse the
     * wallet, because storing a key under a different address would hand someone else's funds over.
     */
    fun addressMismatchReason(claimed: String?, owned: String?): String? {
        val claim = claimed?.trim().orEmpty()
        if (claim.isBlank()) return null
        val address = owned?.trim().orEmpty()
        if (address.isBlank()) {
            return "That wallet's restored key gave no address to check, so it was left out."
        }
        if (addressMatches(claim, address)) return null
        return "The key in the backup does not belong to the address that wallet had ($claim), " +
            "so it was left out of this restore."
    }

    fun addressMatches(claimed: String, owned: String): Boolean {
        if (claimed.contains(MASK)) {
            val parts = claimed.split(MASK)
            val head = parts.firstOrNull().orEmpty()
            val tail = parts.lastOrNull().orEmpty()
            return head.isNotBlank() && tail.isNotBlank() &&
                owned.startsWith(head, ignoreCase = true) &&
                owned.endsWith(tail, ignoreCase = true)
        }
        return sameAddress(claimed, owned)
    }

    /** EVM addresses differ only in casing, so a `0x` pair is compared case-insensitively. */
    fun sameAddress(one: String?, other: String?): Boolean {
        val a = one?.trim().orEmpty()
        val b = other?.trim().orEmpty()
        if (a.isBlank() || b.isBlank()) return false
        return if (a.startsWith("0x") || b.startsWith("0x")) a.equals(b, ignoreCase = true) else a == b
    }

    // ------------------------------------------------------------------ the small JSON

    private fun pair(name: String, value: String): String =
        writeString(name) + ":" + writeString(value)

    private fun writeString(value: String): String {
        val out = StringBuilder(value.length + 8)
        out.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char.code < 0x20) {
                    val code = char.code
                    out.append("\\u")
                        .append(HEX_DIGITS[(code ushr 12) and 15])
                        .append(HEX_DIGITS[(code ushr 8) and 15])
                        .append(HEX_DIGITS[(code ushr 4) and 15])
                        .append(HEX_DIGITS[code and 15])
                } else {
                    out.append(char)
                }
            }
        }
        return out.append('"').toString()
    }

    /**
     * A reader for exactly the two shapes this file writes: a flat object of quoted string values.
     * Anything else — a nested object, a number, a repeated key, an unterminated string — is refused
     * with null, so a foreign body never reaches the key store.
     */
    private fun readFields(document: String): Map<String, String>? {
        if (!document.startsWith("{") || !document.endsWith("}")) return null
        val end = document.length - 1
        val out = LinkedHashMap<String, String>()
        var cursor = 1
        while (true) {
            cursor = skipSpace(document, cursor, end)
            val key = readString(document, cursor, end) ?: return null
            cursor = skipSpace(document, key.next, end)
            if (cursor >= end || document[cursor] != ':') return null
            cursor = skipSpace(document, cursor + 1, end)
            val value = readString(document, cursor, end) ?: return null
            cursor = value.next
            if (out.put(key.value, value.value) != null) return null
            cursor = skipSpace(document, cursor, end)
            if (cursor >= end) break
            if (document[cursor] != ',') return null
            cursor++
        }
        return out.ifEmpty { null }
    }

    private class Read(val value: String, val next: Int)

    private fun skipSpace(text: String, from: Int, end: Int): Int {
        var cursor = from
        while (cursor < end && text[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun readString(text: String, from: Int, end: Int): Read? {
        if (from >= end || text[from] != '"') return null
        val out = StringBuilder()
        var cursor = from + 1
        while (cursor < end) {
            val char = text[cursor]
            if (char == '"') return Read(out.toString(), cursor + 1)
            if (char != '\\') {
                out.append(char)
                cursor++
                continue
            }
            val escape = text.getOrNull(cursor + 1) ?: return null
            when (escape) {
                '"', '\\', '/' -> out.append(escape)
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (cursor + 6 > end) return null
                    val code = text.substring(cursor + 2, cursor + 6).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    cursor += 4
                }
                else -> return null
            }
            cursor += 2
        }
        return null
    }
}
