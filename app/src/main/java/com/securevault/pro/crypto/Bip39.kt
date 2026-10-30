package com.securevault.pro.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Bip39 {

    val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

    fun normalize(mnemonic: String): String =
        mnemonic.trim().lowercase().replace(Regex("\\s+"), " ")

    fun words(mnemonic: String): List<String> =
        normalize(mnemonic).split(' ').filter { it.isNotBlank() }

    fun hasValidWordCount(mnemonic: String): Boolean = words(mnemonic).size in VALID_WORD_COUNTS

    /** Full checksum validation against the BIP39 English word list. */
    fun validate(mnemonic: String, english: List<String>): Boolean {
        val parts = words(mnemonic)
        if (parts.size !in VALID_WORD_COUNTS) return false
        val indexes = IntArray(parts.size)
        parts.forEachIndexed { index, word ->
            val position = english.indexOf(word)
            if (position < 0) return false
            indexes[index] = position
        }
        val totalBits = parts.size * 11
        val checksumBits = parts.size / 3
        val entropyBits = totalBits - checksumBits
        val bits = BooleanArray(totalBits)
        for (i in indexes.indices) {
            for (bit in 0 until 11) {
                bits[i * 11 + bit] = (indexes[i] ushr (10 - bit)) and 1 == 1
            }
        }
        val entropy = ByteArray(entropyBits / 8)
        for (i in 0 until entropyBits) {
            if (bits[i]) {
                entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
        }
        val digest = Hash.sha256(entropy)
        for (i in 0 until checksumBits) {
            val expected = (digest[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1
            if (bits[entropyBits + i] != expected) return false
        }
        return true
    }

    fun toSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalized = normalize(mnemonic)
        val salt = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, 2048, 512)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec)
            .encoded
    }
}
