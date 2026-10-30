package com.securevault.pro.data.remote.firebase

import com.securevault.pro.security.KeyDerivation

/**
 * The cloud copy of the per-user vault key, exactly as `sync_engine/SPEC-v1.md` defines it at
 * `svp1/users/$uid/keyring`: `{wrap, salt, alg, iters, updatedAt}`.
 *
 * [wrap] is `base64(iv).base64(ciphertext+tag)` of the vault key sealed with
 * PBKDF2(user passphrase, [saltBase64], [iterations]). Nothing here is secret material: the wrap
 * is ciphertext, the salt is public by design, and the class never carries a passphrase or a key.
 * Keeping it free of Android types is what lets the whole protocol be proven in JVM unit tests.
 */
data class KeyringEnvelope(
    val wrap: String,
    val saltBase64: String,
    val algorithm: String,
    val iterations: Int,
    val updatedAt: Long
) {

    /** The node body written to the database. Field names are the contract; the order is stable. */
    fun toCloudMap(): Map<String, Any> = linkedMapOf<String, Any>(
        FIELD_WRAP to wrap,
        FIELD_SALT to saltBase64,
        FIELD_ALG to algorithm,
        FIELD_ITERS to iterations,
        FIELD_UPDATED to updatedAt
    )

    /**
     * The same body with the salt decoded, ready for [KeyringCodec.unwrap].
     * Null when the salt is not valid base64 — a corrupt node must never be half-trusted.
     */
    fun saltBytes(): ByteArray? = KeyringCodec.decodeBase64(saltBase64)

    companion object {
        const val FIELD_WRAP = "wrap"
        const val FIELD_SALT = "salt"
        const val FIELD_ALG = "alg"
        const val FIELD_ITERS = "iters"
        const val FIELD_UPDATED = "updatedAt"

        /** Exactly the five fields the spec allows in a keyring node, in order. */
        val FIELDS: List<String> = listOf(FIELD_WRAP, FIELD_SALT, FIELD_ALG, FIELD_ITERS, FIELD_UPDATED)

        /**
         * Reads a node the database handed back. Realtime Database returns JSON numbers as
         * Long or Double depending on the client, and a browser may write them as strings, so
         * every numeric field is coerced rather than cast. Anything that is not a complete,
         * well-shaped wrap is rejected with null instead of a partial object.
         */
        fun fromCloudMap(raw: Map<String, Any?>?): KeyringEnvelope? {
            val values = raw ?: return null
            val wrap = (values[FIELD_WRAP] as? String)?.trim().orEmpty()
            val salt = (values[FIELD_SALT] as? String)?.trim().orEmpty()
            val alg = (values[FIELD_ALG] as? String)?.trim().orEmpty()
            val iterations = asInt(values[FIELD_ITERS]) ?: return null
            val updatedAt = asLong(values[FIELD_UPDATED]) ?: return null
            if (wrap.isBlank() || !wrap.contains('.')) return null
            if (salt.isBlank() || KeyringCodec.decodeBase64(salt) == null) return null
            if (alg != KeyringCodec.ALGORITHM) return null
            if (iterations <= 0) return null
            if (updatedAt <= 0L) return null
            return KeyringEnvelope(
                wrap = wrap,
                saltBase64 = salt,
                algorithm = alg,
                iterations = iterations,
                updatedAt = updatedAt
            )
        }

        private fun asInt(value: Any?): Int? = when (value) {
            null -> null
            is Int -> value
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

        private fun asLong(value: Any?): Long? = when (value) {
            null -> null
            is Long -> value
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }
}

/**
 * Builds and verifies keyring envelopes for the exact passphrase and iteration count the spec
 * names, so callers cannot accidentally seal a cloud keyring with a weaker profile.
 */
object KeyringProtocol {

    /** The only iteration count a keyring wrap may use, shared with the rest of the app. */
    const val ITERATIONS: Int = KeyDerivation.PBKDF2_ITERATIONS

    fun seal(vaultKey: ByteArray, passphrase: String, derivation: KeyDerivation = KeyDerivation()): Pair<KeyringEnvelope, ByteArray> {
        val salt = KeyringCodec.generateSalt(derivation)
        val wrap = KeyringCodec.seal(vaultKey, passphrase, salt, ITERATIONS, derivation)
        return KeyringEnvelope(
            wrap = wrap,
            saltBase64 = KeyringCodec.encodeBase64(salt),
            algorithm = KeyringCodec.ALGORITHM,
            iterations = ITERATIONS,
            updatedAt = System.currentTimeMillis()
        ) to salt
    }

    /** Null for a wrong passphrase, a tampered wrap, or an envelope that is not our shape. */
    fun open(envelope: KeyringEnvelope, passphrase: String, derivation: KeyDerivation = KeyDerivation()): ByteArray? {
        val salt = envelope.saltBytes() ?: return null
        return KeyringCodec.unwrap(envelope.wrap, passphrase, salt, envelope.iterations, derivation)
    }
}
