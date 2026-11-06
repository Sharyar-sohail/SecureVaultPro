package com.securevault.pro.security.backup

import java.util.Base64

/**
 * What a failed restore can honestly say about the file it could not open.
 *
 * AES-GCM authenticates the whole ciphertext with one tag, so a wrong key, a wrong salt and a
 * corrupted IV are indistinguishable *cryptographically* — all three make `doFinal` throw the same
 * tag failure. They are not indistinguishable *structurally*: salt, IV and tag have fixed widths in
 * every envelope this app has written. So a file whose widths differ names the broken parameter, and
 * a file whose widths all match names the key as the only remaining mismatch. Reporting "corrupted"
 * without saying which is what sent the owner looking for a reinstall.
 */
object EnvelopeDiagnostics {

    /** The width `KeyDerivation.generateSalt()` has produced since the format landed. */
    const val EXPECTED_SALT_BYTES = 32

    /** The width `Cipher.init(ENCRYPT_MODE)` returns for AES/GCM/NoPadding. */
    const val EXPECTED_IV_BYTES = 12

    /** The GCM tag length the app initialises every cipher with. */
    const val EXPECTED_TAG_BYTES = 16

    data class Structure(
        val version: Int,
        val saltBytes: Int,
        val ivBytes: Int?,
        val payloadBytes: Int?,
        val shape: String,
        val statedRounds: Int?
    ) {
        /** Named mismatches, in the order the owner would fix them. Empty when the widths all match. */
        val problems: List<String>
            get() = buildList {
                if (saltBytes != EXPECTED_SALT_BYTES) {
                    add(
                        "Salt mismatch: this file's salt is $saltBytes bytes, and the app writes " +
                            "$EXPECTED_SALT_BYTES."
                    )
                }
                if (ivBytes == null) {
                    add("IV mismatch: no IV could be read from the start of the file.")
                } else if (ivBytes != EXPECTED_IV_BYTES) {
                    add(
                        "IV mismatch: this file's IV is $ivBytes bytes, and the app writes " +
                            "$EXPECTED_IV_BYTES."
                    )
                }
                if (payloadBytes != null && payloadBytes <= EXPECTED_TAG_BYTES) {
                    add(
                        "Ciphertext mismatch: the payload is $payloadBytes bytes, which is too short " +
                            "to hold the $EXPECTED_TAG_BYTES-byte seal."
                    )
                }
            }

        val intact: Boolean get() = problems.isEmpty()
    }

    fun inspect(envelope: BackupEnvelope): Structure {
        val parts = envelope.data.trim().split('.')
        val iv = parts.firstOrNull()?.let(::decode)
        val payloadBytes = when (parts.size) {
            2 -> decode(parts[1])?.size
            3 -> {
                val a = decode(parts[1])?.size
                val b = decode(parts[2])?.size
                if (a == null || b == null) null else a + b
            }
            else -> null
        }
        return Structure(
            version = envelope.version,
            saltBytes = envelope.salt.size,
            ivBytes = iv?.size,
            payloadBytes = payloadBytes,
            shape = envelope.payloadShape,
            statedRounds = envelope.iterations
        )
    }

    /**
     * The failure sentence the owner reads.
     *
     * It says which parameter of the file is broken when one is measurable, and when every width
     * matches it says the copy is locked with a key this device does not hold. It never counts keys
     * or derivations: the restore runs exactly one, so a number here would be either one or noise,
     * and neither tells the owner what to do next.
     */
    fun describe(envelope: BackupEnvelope): String {
        val s = inspect(envelope)
        if (!s.intact) {
            val versionLabel =
                if (envelope.statedVersion) "v${s.version}" else "version not stated"
            return "$DAMAGED_PREFIX Envelope $versionLabel, salt ${s.saltBytes} B, " +
                "IV ${s.ivBytes ?: "?"} B, shape ${s.shape}. ${s.problems.joinToString(" ")}"
        }
        return KEY_NOT_ON_THIS_DEVICE
    }

    /**
     * The failure sentence for the salt-less shape: a bare `iv.ciphertext` blob whose key salt lived
     * in the separate `key.salt` file. The widths are checked exactly as in an envelope, and the
     * sentence names where the salt came from, because "no salt" and "wrong salt file" are different
     * fixes for the owner.
     */
    fun describeBareBlob(data: String, saltSource: String): String {
        val parts = data.trim().split('.')
        val iv = parts.firstOrNull()?.let(::decode)
        val payloadBytes = when (parts.size) {
            2 -> decode(parts[1])?.size
            3 -> {
                val a = decode(parts[1])?.size
                val b = decode(parts[2])?.size
                if (a == null || b == null) null else a + b
            }
            else -> null
        }
        val problems = buildList {
            if (iv == null) {
                add("IV mismatch: no IV could be read from the start of the file.")
            } else if (iv.size != EXPECTED_IV_BYTES) {
                add(
                    "IV mismatch: this file's IV is ${iv.size} bytes, and the app writes " +
                        "$EXPECTED_IV_BYTES."
                )
            }
            if (payloadBytes != null && payloadBytes <= EXPECTED_TAG_BYTES) {
                add(
                    "Ciphertext mismatch: the payload is $payloadBytes bytes, which is too short " +
                        "to hold the $EXPECTED_TAG_BYTES-byte seal."
                )
            }
        }
        if (problems.isNotEmpty()) {
            return "$DAMAGED_PREFIX Salt from $saltSource, IV ${iv?.size ?: "?"} B, " +
                "payload ${payloadBytes ?: "?"} B. ${problems.joinToString(" ")}"
        }
        return KEY_NOT_ON_THIS_DEVICE
    }

    /** What a structurally sound file that will not open under its own key means, in one sentence. */
    const val KEY_NOT_ON_THIS_DEVICE: String =
        "This backup is locked with a key this device doesn't have. Sign in with the Google account " +
            "that created it and try again — if the vault key hasn't reached this phone yet, choose " +
            "the device that made the backup."

    private const val DAMAGED_PREFIX: String =
        "This backup file looks damaged, so it was left untouched and nothing was changed."

    private fun decode(value: String): ByteArray? {
        val clean = value.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.isBlank()) return null
        return runCatching { Base64.getMimeDecoder().decode(clean) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
    }
}
