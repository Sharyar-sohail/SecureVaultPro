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

    /**
     * The Google account ID. The original same-package app sealed its Drive backup with this, and
     * it cannot be derived from the address, so a restore that skips it can never open those files.
     */
    ACCOUNT_ID,

    /** A retired shared literal (see `LegacyKeyPlan.SHARED_LEGACY_KEYS`). Read-only. */
    SHARED_LEGACY_KEY,

    /** Anything else offered as a candidate. */
    OTHER
}

/**
 * One secret offered to [LegacyEnvelopeOpener] together with what it is, so warnings can be honest.
 *
 * [iterationProfiles] narrows the sweep for that secret. Past builds paired a profile with a writer
 * — the universal-key era used 600k/210k/120k, the keyring era has only ever written 600k — so
 * deriving the impossible pairings only spends time the restore does not have.
 */
data class OpeningSecret(
    val value: String,
    val kind: OpeningSecretKind,
    val iterationProfiles: List<Int>? = null
)

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

        /** The only count a v3 writer ever used. */
        const val CURRENT_ITERATIONS = KeyDerivation.PBKDF2_ITERATIONS

        /** The two extra counts the pre-3.4 reader used for envelopes older than v3. */
        const val LEGACY_V2_ITERATIONS_210K = KeyDerivation.PIN_ITERATIONS
        const val LEGACY_V2_ITERATIONS_120K = 120_000

        /**
         * The four counts the original `CloudSyncManager` restore loop actually iterated over.
         *
         * Copied from the shipped reader (`for (iter in listOf(600_000, 100_000, 10_000, 1_000))`)
         * rather than derived from current writers: a file sealed at any of these counts the old app
         * could open, so this app must too, or a restore that worked on the old build reports
         * "corrupted" on this one.
         */
        val ORIGINAL_READER_PROFILES = listOf(CURRENT_ITERATIONS, 100_000, 10_000, 1_000)

        /**
         * The exact derivation counts the old code used for a file of [version] — nothing else.
         *
         * Every shipped writer used `PBKDF2_ITERATIONS` (600,000), but the original reader also
         * derived at 100,000, 10,000 and 1,000 for every file, and at 210,000 and 120,000 for
         * envelopes older than v3. Those numbers are the whole history; the list is the reader's
         * own, so a file it could open still opens here.
         */
        fun exactProfiles(version: Int?): List<Int> =
            if (version != null && version in 1..2) {
                ORIGINAL_READER_PROFILES + listOf(LEGACY_V2_ITERATIONS_210K, LEGACY_V2_ITERATIONS_120K)
            } else {
                ORIGINAL_READER_PROFILES
            }
    }

    /** [json] is the vault body; the rest is what the caller needs to describe the restore honestly. */
    data class Opened(
        val json: String,
        val kind: OpeningSecretKind,
        val iterations: Int,
        val secretFingerprint: String,
        /** Width of the salt that actually opened the file, so a padded legacy salt can be reported. */
        val saltBytes: Int = 0
    ) {
        val openedWithRetiredKey: Boolean get() = kind != OpeningSecretKind.VAULT_KEY
    }

    /** [budgetExhausted] means the clock or the stop signal ended the sweep, not a wrong key. */
    data class OpenAttempt(
        val opened: Opened?,
        val candidatesTried: Int,
        val budgetExhausted: Boolean
    )

    private val keyCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            if (size > 128) {
                eldest?.value?.fill(0)
                return true
            }
            return false
        }
    }

    /**
     * Sweep every salt a past reader would have paired with the file.
     *
     * The original reader fed PBKDF2 the salt as written *and* a copy padded-or-truncated to 32
     * bytes (`salt.copyOf(32)`), because some writers emitted a shorter salt. A file sealed under
     * that padded salt authenticates under neither the written bytes nor any key, so the salt
     * itself has to be swept, not just the secret. The written bytes are tried first: every
     * current writer emits exactly 32, for which both variants are the same array.
     */
    fun openWithSalts(
        salts: List<ByteArray>,
        data: String,
        secrets: List<OpeningSecret>,
        iterationProfiles: List<Int> = listOf(CURRENT_ITERATIONS),
        budgetMs: Long = Long.MAX_VALUE,
        shouldStop: () -> Boolean = { false },
        onAttempt: ((tried: Int) -> Unit)? = null
    ): OpenAttempt {
        val deadline = if (budgetMs == Long.MAX_VALUE) Long.MAX_VALUE
            else System.nanoTime() + budgetMs * 1_000_000L
        var tried = 0
        var exhausted = false
        for (salt in salts) {
            if (salt.size < 8) continue
            val remaining = if (deadline == Long.MAX_VALUE) Long.MAX_VALUE
            else (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0L) {
                exhausted = true
                break
            }
            val attempt = openWithin(
                salt = salt,
                data = data,
                secrets = secrets,
                iterationProfiles = iterationProfiles,
                budgetMs = remaining,
                shouldStop = shouldStop,
                onAttempt = { onAttempt?.invoke(tried + it) }
            )
            tried += attempt.candidatesTried
            attempt.opened?.let {
                return attempt.copy(opened = it.copy(saltBytes = salt.size), candidatesTried = tried)
            }
            if (attempt.budgetExhausted) {
                exhausted = true
                break
            }
        }
        return OpenAttempt(null, tried, exhausted)
    }

    /** Unbudgeted convenience for callers that already hold one credible secret list (tests, tools). */
    fun open(
        salt: ByteArray,
        data: String,
        secrets: List<OpeningSecret>,
        iterationProfiles: List<Int> = listOf(CURRENT_ITERATIONS)
    ): Opened? = openWithin(salt, data, secrets, iterationProfiles).opened

    /**
     * One bounded sweep, reporting whether the clock ran out instead of a key being wrong.
     *
     * A restore may only spend [budgetMs] here. Each candidate costs a full PBKDF2, so on a handset
     * the unbounded list could run for minutes at 45%, which is indistinguishable from a hung app;
     * the caller turns [budgetExhausted] into a fast, specific failure. [shouldStop] is polled before
     * every derivation so "Stop waiting" aborts within one hash instead of after the whole sweep.
     * [onAttempt] is called before each derivation so a long legitimate sweep reports movement
     * instead of leaving the progress overlay frozen.
     */
    fun openWithin(
        salt: ByteArray,
        data: String,
        secrets: List<OpeningSecret>,
        iterationProfiles: List<Int> = listOf(CURRENT_ITERATIONS),
        budgetMs: Long = Long.MAX_VALUE,
        shouldStop: () -> Boolean = { false },
        onAttempt: ((tried: Int) -> Unit)? = null
    ): OpenAttempt {
        if (salt.size < 8 || data.isBlank()) return OpenAttempt(null, 0, false)
        val profiles = buildList {
            iterationProfiles.forEach { if (it > 0 && it !in this) add(it) }
        }
        val deadline = if (budgetMs == Long.MAX_VALUE) Long.MAX_VALUE
            else System.nanoTime() + budgetMs * 1_000_000L
        var tried = 0
        for (secret in secrets) {
            if (secret.value.isBlank()) continue
            val allowed = secret.iterationProfiles
                ?.takeIf { it.isNotEmpty() }
                ?: profiles
            for (iterations in allowed) {
                if (iterations <= 0) continue
                if (shouldStop()) return OpenAttempt(null, tried, true)
                if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline) {
                    return OpenAttempt(null, tried, true)
                }
                tried++
                onAttempt?.invoke(tried)
                val key = derivedKey(secret.value, salt, iterations)
                val json = decrypt(data, key) ?: continue
                return OpenAttempt(
                    opened = Opened(
                        json = json,
                        kind = secret.kind,
                        iterations = iterations,
                        secretFingerprint = fingerprint(secret.value),
                        saltBytes = salt.size
                    ),
                    candidatesTried = tried,
                    budgetExhausted = false
                )
            }
        }
        return OpenAttempt(null, tried, false)
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
        // The shipped writer always emits a 12-byte IV, but the original reader never refused a
        // different length itself — it handed the bytes to GCM and let the tag decide. Keeping that
        // behaviour means a file from a build that picked another IV length still opens.
        if (iv.isEmpty() || payload.size <= TAG_BITS / 8) return null
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
