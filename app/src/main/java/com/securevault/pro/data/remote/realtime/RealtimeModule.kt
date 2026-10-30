package com.securevault.pro.data.remote.realtime

/**
 * The seven record segments the realtime engine mirrors, in the exact order `RealtimePaths.MODULES`
 * declares them and the order the web renders them in.
 *
 * Deliberately a plain value with no Firebase or Android types so the codec, the envelope and the
 * merge policy can all be proven in JVM unit tests; the transport layer maps a module to the Room
 * DAO (or, for files, the [com.securevault.pro.data.file.FileVaultRepository]) that owns it.
 */
enum class RealtimeModule(val segment: String) {
    NOTES("notes"),
    ENTRIES("entries"),
    CARDS("cards"),
    TOTPS("totps"),
    CONTACTS("contacts"),
    CRYPTO_WALLETS("cryptoWallets"),
    FILES("files");

    companion object {
        /** The seven segments in the frozen contract order. */
        val SEGMENTS: List<String> = entries.map { it.segment }

        /** The modules that carry an `updatedAt` column in Room (their `u` clock lives in the entity). */
        private val WithUpdatedAt = setOf(NOTES, ENTRIES, CONTACTS, CRYPTO_WALLETS)

        /** Cards and TOTP have no `updatedAt`; their `u` is set by the realtime layer at write time. */
        fun hasEntityUpdatedAt(module: RealtimeModule): Boolean = module in WithUpdatedAt

        fun fromSegment(segment: String?): RealtimeModule? =
            segment?.let { value -> entries.firstOrNull { it.segment == value } }

        /** True when [segment] is one of the seven the engine is allowed to touch. */
        fun isKnownSegment(segment: String?): Boolean = fromSegment(segment) != null
    }
}
