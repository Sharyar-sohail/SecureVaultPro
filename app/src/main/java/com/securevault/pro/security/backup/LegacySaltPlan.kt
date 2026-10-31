package com.securevault.pro.security.backup

/**
 * The salts past readers fed to PBKDF2, for the two file shapes this product has written.
 *
 * The original reader did not trust the salt it found: it decoded it and then derived once more
 * from `salt.copyOf(32)`, which zero-pads a short salt and truncates a long one. A file sealed under
 * a 16-byte salt therefore only opens when the salt is padded, and the current opener — which used
 * the bytes as written — could never reproduce that key. This object is the fix: the written salt
 * first (identical to the padded form for the 32-byte salts every current writer emits), then the
 * padded form.
 *
 * The second shape is the earliest Drive backup: a bare `iv.ciphertext` blob with no salt at all.
 * That writer kept the salt in a separate `key.salt` file in the same Drive folder, and fell back to
 * the account address padded to 32 bytes when the file was missing.
 */
object LegacySaltPlan {

    /** The width every writer's `generateSalt()` has produced, and what the original padded to. */
    const val WIDTH = 32

    /** Salt variants for an envelope that carries its own salt, in the order to derive them. */
    fun variants(written: ByteArray): List<ByteArray> {
        if (written.isEmpty()) return listOf(ByteArray(WIDTH))
        val variants = mutableListOf(written)
        if (written.size != WIDTH) variants += written.copyOf(WIDTH)
        return dedupe(variants)
    }

    /**
     * Salt variants for a blob that carries none: the Drive `key.salt` file first, then a zero salt,
     * then each address padded/truncated to 32 bytes exactly as the original did.
     */
    fun fallback(driveSalt: ByteArray?, addresses: List<String>): List<ByteArray> {
        val variants = mutableListOf<ByteArray>()
        if (driveSalt != null && driveSalt.isNotEmpty()) variants += driveSalt
        variants += ByteArray(WIDTH)
        addresses.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { variants += padAddress(it) }
        return dedupe(variants)
    }

    /** `email.padEnd(32, '0').substring(0, 32)` — the original reader's address-derived salt. */
    fun padAddress(address: String): ByteArray =
        address.padEnd(WIDTH, '0').substring(0, WIDTH).toByteArray(Charsets.UTF_8)

    private fun dedupe(salts: List<ByteArray>): List<ByteArray> {
        val seen = mutableListOf<ByteArray>()
        return salts.filter { candidate ->
            val known = seen.any { it.contentEquals(candidate) }
            if (!known) seen += candidate
            !known
        }
    }
}
