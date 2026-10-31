package com.securevault.pro.security.backup

import java.util.Base64
import java.util.Locale

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
                        "Salt mismatch: this file's salt is $saltBytes bytes and it is fed to PBKDF2 " +
                            "as-is, while the app derives with $EXPECTED_SALT_BYTES bytes."
                    )
                }
                if (ivBytes == null) {
                    add("IV mismatch: no IV could be decoded from the first dotted part of the body.")
                } else if (ivBytes != EXPECTED_IV_BYTES) {
                    add(
                        "IV mismatch: this file's IV is $ivBytes bytes; AES-GCM is initialised with " +
                            "$EXPECTED_IV_BYTES."
                    )
                }
                if (payloadBytes != null && payloadBytes <= EXPECTED_TAG_BYTES) {
                    add(
                        "Ciphertext mismatch: the payload is $payloadBytes bytes, which cannot even " +
                            "hold the $EXPECTED_TAG_BYTES-byte GCM tag."
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
     * The failure sentence. Names the mismatching parameter when one is measurable, and says plainly
     * that the key is the remaining suspect when none is.
     */
    fun describe(
        envelope: BackupEnvelope,
        secretsTried: Int
    ): String {
        val s = inspect(envelope)
        val rounds = s.statedRounds
            ?.let { String.format(Locale.US, "%,d rounds", it) }
            ?: "round count not stated"
        val layout = "Envelope v${s.version}, salt ${s.saltBytes} B, IV ${s.ivBytes ?: "?"} B, " +
            "tag $EXPECTED_TAG_BYTES B, shape ${s.shape}, $rounds."
        if (!s.intact) {
            return "This backup could not be opened. $layout ${s.problems.joinToString(" ")} " +
                "The envelope itself is structurally different from what this app writes, so the " +
                "format — not your account key — is the mismatch."
        }
        return "Invalid Legacy Key. $layout Salt, IV and tag widths all match the format this app " +
            "writes, so the key is the mismatch: ${rejection(secretsTried)}."
    }

    /**
     * The failure sentence for the salt-less shape: a bare `iv.ciphertext` blob whose key salt lived
     * in the separate `key.salt` file. The widths are checked exactly as in an envelope, and the
     * sentence names where the salt came from, because "no salt" and "wrong salt file" are different
     * fixes for the owner.
     */
    fun describeBareBlob(
        data: String,
        saltSource: String,
        secretsTried: Int
    ): String {
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
                add("IV mismatch: no IV could be decoded from the first dotted part of the body.")
            } else if (iv.size != EXPECTED_IV_BYTES) {
                add(
                    "IV mismatch: this blob's IV is ${iv.size} bytes; AES-GCM is initialised with " +
                        "$EXPECTED_IV_BYTES."
                )
            }
            if (payloadBytes != null && payloadBytes <= EXPECTED_TAG_BYTES) {
                add(
                    "Ciphertext mismatch: the payload is $payloadBytes bytes, which cannot even " +
                        "hold the $EXPECTED_TAG_BYTES-byte GCM tag."
                )
            }
        }
        val layout = "Salt-less backup, salt from $saltSource, IV ${iv?.size ?: "?"} B, " +
            "tag $EXPECTED_TAG_BYTES B, payload ${payloadBytes ?: "?"} B."
        if (problems.isNotEmpty()) {
            return "This backup could not be opened. $layout ${problems.joinToString(" ")} " +
                "The blob is structurally different from what this app writes, so the format — " +
                "not your account key — is the mismatch."
        }
        return "Invalid Legacy Key. $layout IV and tag widths match the format this app writes, " +
            "so the key is the mismatch: ${rejection(secretsTried)}."
    }

    private fun rejection(secretsTried: Int): String =
        if (secretsTried <= 1) {
            "the one exact key this file was written with was rejected by the GCM tag, and this " +
                "device does not hold it. Nothing was guessed"
        } else {
            "$secretsTried keys this account has used were rejected by the GCM tag, so this " +
                "device does not hold the key this file was written with"
        }

    private fun decode(value: String): ByteArray? {
        val clean = value.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.isBlank()) return null
        return runCatching { Base64.getMimeDecoder().decode(clean) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
    }
}
