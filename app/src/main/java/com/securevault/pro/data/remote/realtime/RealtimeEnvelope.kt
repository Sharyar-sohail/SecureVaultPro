package com.securevault.pro.data.remote.realtime

import com.securevault.pro.data.remote.firebase.KeyringCodec

/**
 * The realtime node body, exactly `{e, u, r, d}` and nothing else — `sync_engine/RECORD-FORMAT-v1.md`
 * §1 and `SPEC-v1.md`'s node layout.
 *
 * - [e] is `base64(iv).base64(ciphertext||tag)` of the record JSON, sealed with AES-256-GCM under the
 *   per-user vault key via [KeyringCodec.sealBytes] — never another key, never a fallback.
 * - [u] is the last-write-wins clock in epoch ms.
 * - [r] is the monotonic per-user revision (from the `meta/rev` transaction).
 * - [d] is `null` while the record is live and the delete epoch-ms when it is a tombstone.
 *
 * The class is free of Firebase and Android types so the whole envelope — sealing, the tolerant
 * number coercion the browser forces on us, and the strict reject-anything-extra rule — is proven in
 * JVM unit tests. [toCloudMap] is what a write sends; [fromCloudMap] is what a listener reads back.
 */
data class RealtimeEnvelope(
    val e: String,
    val u: Long,
    val r: Long,
    val d: Long?
) {

    /**
     * The node body written to the database. Field names are the contract; the order is stable; a
     * live record emits `d` as `null`, which the Realtime Database then simply omits on disk.
     */
    fun toCloudMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        FIELD_E to e,
        FIELD_U to u,
        FIELD_R to r,
        FIELD_D to d
    )

    fun isTombstone(): Boolean = d != null

    companion object {
        const val FIELD_E = "e"
        const val FIELD_U = "u"
        const val FIELD_R = "r"
        const val FIELD_D = "d"

        /** The only four keys a data node may carry; anything else marks the node malformed. */
        val FIELDS: List<String> = listOf(FIELD_E, FIELD_U, FIELD_R, FIELD_D)

        /**
         * The largest `u`/`r`/`d` we will accept; guards against a nonsense clock from a foreign
         * writer. 36,500 days (about a thousand years) of milliseconds, spelled as a literal
         * because a `const val` cannot be computed by a function call.
         */
        private const val MAX_CLOCK = 3_153_600_000_000L

        /**
         * Seals [plaintext] under the 32-byte [vaultKey] and stamps the node. A tombstone carries the
         * last known body (so the web can still show what was deleted) plus `d`; a live record sends
         * `d = null`. The key is owned by the caller — nothing here copies or logs it.
         */
        fun seal(
            plaintext: String,
            vaultKey: ByteArray,
            u: Long,
            r: Long,
            deletedAt: Long? = null
        ): RealtimeEnvelope? {
            if (vaultKey.size != KeyringCodec.KEY_BYTES) return null
            if (plaintext.isBlank()) return null
            val cipher = runCatching { KeyringCodec.sealBytes(plaintext.toByteArray(Charsets.UTF_8), vaultKey) }
                .getOrNull() ?: return null
            return RealtimeEnvelope(e = cipher, u = u, r = r, d = deletedAt)
        }

        /** Null when the envelope is malformed or the ciphertext does not authenticate under [vaultKey]. */
        fun open(plaintextKey: ByteArray?, envelope: RealtimeEnvelope): String? {
            val key = plaintextKey ?: return null
            if (key.size != KeyringCodec.KEY_BYTES) return null
            val bytes = KeyringCodec.openBytes(envelope.e, key) ?: return null
            return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        }

        /**
         * Reads a node the database handed back. Realtime returns JSON numbers as `Int`, `Long` or
         * `Double` depending on the client, and a browser may write them as strings, so every numeric
         * field is coerced rather than cast. A node missing `e`, carrying a key outside `{e,u,r,d}`, or
         * holding a non-numeric / out-of-range clock is rejected with `null` — never partially trusted.
         */
        fun fromCloudMap(raw: Map<*, *>?): RealtimeEnvelope? {
            val values = raw ?: return null
            val typed = LinkedHashMap<String, Any?>(values.size)
            for ((key, entry) in values) {
                if (key !is String) return null
                typed[key] = entry
            }
            // Reject any extra key: a node the spec does not describe is not ours to interpret.
            if (!FIELDS.containsAll(typed.keys)) return null

            val e = (typed[FIELD_E] as? String)?.trim()
            if (e.isNullOrBlank() || !e.contains('.')) return null

            val u = asLong(typed[FIELD_U]) ?: return null
            val r = asLong(typed[FIELD_R]) ?: return null
            if (u < 0L || u > MAX_CLOCK) return null
            if (r < 0L) return null

            val d = typed[FIELD_D].let { raw ->
                if (raw == null) return@let null
                asLong(raw) ?: return null
            }
            if (d != null && (d < 0L || d > MAX_CLOCK)) return null

            return RealtimeEnvelope(e = e, u = u, r = r, d = d)
        }

        private fun asLong(value: Any?): Long? = when (value) {
            null -> null
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }
}
