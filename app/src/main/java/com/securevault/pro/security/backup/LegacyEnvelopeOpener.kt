package com.securevault.pro.security.backup

import com.securevault.pro.security.KeyDerivation
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which class of secret opened an envelope. Reported to the owner so a restore can say *how* the
 * vault came back; never carries the secret itself.
 */
enum class OpeningSecretKind {
    /** This device's own vault key, from the keyring. The shape every current backup is sealed in. */
    VAULT_KEY,

    /** A passphrase the owner typed (export passphrase, master passphrase, or seed-derived phrase). */
    PASSPHRASE,

    /** One of the retired universal strings. Read-only: it opens old files and never writes new ones. */
    RETIRED_UNIVERSAL_KEY,

    /** The account address, which the earliest builds used as the sealing secret. */
    ACCOUNT_EMAIL,

    /** Anything else offered as a candidate. */
    OTHER
}

/** One secret offered to [LegacyEnvelopeOpener] together with what it is, so warnings can be honest. */
data class OpeningSecret(val value: String, val kind: OpeningSecretKind)

/**
 * Opens backup envelopes written by *any* past build of this app.
 *
 * The format has never changed — PBKDF2-HMAC-SHA256 over a per-file salt, AES-256-GCM,
 * `base64(iv).base64(ciphertext+tag)` — but the secret sealed with it has, and so did the iteration
 * count a couple of builds used. An owner's file is therefore only "corrupted" when the app stops
 * looking: this class sweeps every secret the vault might have been sealed with, across every
 * derivation profile any release ever shipped, and returns the plaintext that authenticates.
 *
 * Nothing here writes, deletes, or re-seals. Opening an old file and replacing it are deliberately
 * separate decisions, because a file that opens is proof of a key, not permission to overwrite it.
 *
 * Pure JVM on purpose: `android.util.Base64` and Keystore make a unit test impossible, and this is
 * the one path where "it compiles" is not evidence.
 */
@Singleton
class LegacyEnvelopeOpener @Inject constructor(
    private val derivation: KeyDerivation
) {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** The profile every release since the format landed has used. */
        const val CURRENT_ITERATIONS = KeyDerivation.PBKDF2_ITERATIONS

        /** The PIN-profile count some pre-keyring builds sealed a backup with. */
        const val PIN_PROFILE_ITERATIONS = KeyDerivation.PIN_ITERATIONS

        /** A pre-600k build's count, kept so those files still open. */
        const val EARLIER_PROFILE_ITERATIONS = 120_000

        /**
         * Counts used by builds whose exact profile was never recorded. They are cheap to derive
         * and are swept last, so an unreadable modern file pays almost nothing for the safety net.
         */
        val FALLBACK_PROFILE_ITERATIONS = listOf(100_000, 50_000, 10_000)

        /**
         * Every count to try, most likely first. The envelope's own stated count leads when it has
         * one, so a self-describing file is opened in a single pass.
         */
        val ITERATION_PROFILES = listOf(
            CURRENT_ITERATIONS,
            PIN_PROFILE_ITERATIONS,
            EARLIER_PROFILE_ITERATIONS
        ) + FALLBACK_PROFILE_ITERATIONS
    }

    /** [json] is the vault body; the rest is what the caller needs to describe the restore honestly. */
    data class Opened(
        val json: String,
        val kind: OpeningSecretKind,
        val iterations: Int,
        val secretFingerprint: String
    ) {
        val openedWithRetiredKey: Boolean get() = kind != OpeningSecretKind.VAULT_KEY
    }

    private val keyCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            if (size > 24) {
                eldest?.value?.fill(0)
                return true
            }
            return false
        }
    }

    /**
     * Try every [secrets] against every iteration profile until one authenticates.
     *
     * Order matters for honesty, not for correctness: the secrets that should open the file come
     * first, so a genuine match is found before the sweep reaches the retired strings. GCM
     * authenticates, so a wrong secret can never yield a plausible-looking body — there is no
     * partial match to guard against, only a wasted PBKDF2.
     */
    fun open(
        salt: ByteArray,
        data: String,
        secrets: List<OpeningSecret>,
        iterationProfiles: List<Int> = ITERATION_PROFILES
    ): Opened? {
        if (salt.size < 8 || data.isBlank()) return null
        val profiles = buildList {
            iterationProfiles.forEach { if (it > 0 && it !in this) add(it) }
        }
        for (secret in secrets) {
            if (secret.value.isBlank()) continue
            for (iterations in profiles) {
                val key = derivedKey(secret.value, salt, iterations)
                val json = decrypt(data, key) ?: continue
                return Opened(
                    json = json,
                    kind = secret.kind,
                    iterations = iterations,
                    secretFingerprint = fingerprint(secret.value)
                )
            }
        }
        return null
    }

    /** True when [data] is this envelope's own payload under [secret] at [iterations]. */
    fun opensWith(salt: ByteArray, data: String, secret: String, iterations: Int = CURRENT_ITERATIONS): Boolean {
        if (salt.size < 8 || data.isBlank() || secret.isBlank()) return false
        return decrypt(data, derivedKey(secret, salt, iterations)) != null
    }

    /**
     * AES-256-GCM over the dotted blob.
     *
     * The shipped shape is `iv.ciphertext` with the tag inside the ciphertext. Two older shapes are
     * still tolerated because files in owners' Drive predate the change: a separate trailing tag,
     * and the same triple with the tag and ciphertext halves swapped. All three authenticate, so a
     * wrong guess returns null rather than garbage.
     */
    fun decrypt(data: String, key: ByteArray): String? {
        val parts = data.trim().split('.')
        if (parts.isEmpty()) return null
        val iv = decodeBase64OrNull(parts.firstOrNull().orEmpty()) ?: return null
        if (parts.size == 2) {
            val payload = decodeBase64OrNull(parts[1]) ?: return null
            return attempt(iv, payload, key)
        }
        if (parts.size == 3) {
            val a = decodeBase64OrNull(parts[1]) ?: return null
            val b = decodeBase64OrNull(parts[2]) ?: return null
            return attempt(iv, a + b, key) ?: attempt(iv, b + a, key)
        }
        val tail = parts.drop(1).joinToString(".").let { decodeBase64OrNull(it) } ?: return null
        return attempt(iv, tail, key)
    }

    private fun attempt(iv: ByteArray, payload: ByteArray, key: ByteArray): String? {
        if (iv.size != 12 || payload.size <= TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * PBKDF2 for one (secret, salt, count) triple, kept for the sweep.
     *
     * A failed restore used to re-derive 600,000 rounds for every candidate on every retry, which on
     * a handset is several seconds per key and looked exactly like a hung app. The cache is bounded
     * and zeroed on eviction; it holds derived keys, never passphrases.
     */
    private fun derivedKey(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val cacheKey = "$iterations|${base64(salt)}|${fingerprint(secret)}"
        synchronized(keyCache) { keyCache[cacheKey] }?.let { return it }
        val derived = derivation.deriveKey(secret.toCharArray(), salt, iterations)
        synchronized(keyCache) { keyCache[cacheKey] = derived }
        return derived
    }

    private fun fingerprint(secret: String): String =
        Base64.getEncoder().encodeToString(derivation.sha256(secret.toByteArray(Charsets.UTF_8)))

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64OrNull(value: String): ByteArray? {
        val clean = value.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.isBlank()) return null
        return runCatching { Base64.getMimeDecoder().decode(clean) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
    }
}
