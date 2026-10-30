package com.securevault.pro.crypto.stego

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Passphrase-to-key derivation for stego envelopes, behind an interface so the algorithm core stays
 * runnable on a bare JVM and so tests can afford fewer rounds than production.
 *
 * The default [Pbkdf2StegoKeyDeriver] is PBKDF2-HMAC-SHA256 at [StegoLimits.PBKDF2_ITERATIONS]
 * rounds — the identical cost policy the vault's own `security.KeyDerivation` uses. It is a
 * *separate* helper on purpose: a stego envelope must never be able to perturb vault key material,
 * so nothing here calls into `security/`, and nothing in `security/` calls into here.
 */
interface StegoKeyDeriver {
    fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
}

class Pbkdf2StegoKeyDeriver : StegoKeyDeriver {

    override fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        require(passphrase.isNotEmpty()) { "An empty passphrase is not a key" }
        require(salt.isNotEmpty()) { "A derived key always needs a per-carrier salt" }
        val spec = PBEKeySpec(passphrase, salt, iterations, keyBytes * 8)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
    }
}

/**
 * The key schedule: everything that decides *where* in a carrier an envelope lives, and under which
 * subkey it opens, is derived from the passphrase itself. There is no role field, no "decoy" bit and
 * no ordering convention that could tell an observer which of two passphrases is the important one.
 */
object StegoKeySchedule {

    private const val SLOTS = "svp.stego.v1.slots"
    private const val ENVELOPE = "svp.stego.v1.envelope"
    private const val IV = "svp.stego.v1.iv"

    private val random = SecureRandom()

    fun salt(bytes: Int = StegoLimits.SALT_BYTES): ByteArray = ByteArray(bytes).also(random::nextBytes)

    /** Per-envelope key: the salt is the carrier's, so one image can hold two unrelated keys. */
    fun envelopeKey(
        deriver: StegoKeyDeriver,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = StegoLimits.PBKDF2_ITERATIONS
    ): ByteArray = deriver.derive(passphrase, context(salt, ENVELOPE), iterations, StegoLimits.KEY_BYTES)

    /** Deterministic AES-GCM nonce for one block: unique per (carrier, block), never reused. */
    fun ivFor(salt: ByteArray, blockIndex: Int, deriver: StegoKeyDeriver): ByteArray {
        val digest = deriver.sha256(context(salt, IV + blockIndex.toChar()))
        return digest.copyOfRange(0, StegoLimits.IV_BYTES)
    }

    /**
     * The block an envelope prefers, as a function of the key that opens it. Two passphrases on one
     * carrier therefore land in slots neither can predict from the other, and a reader holding only
     * the image sees the same opaque ciphertext either way.
     */
    fun blockAddressFor(key: ByteArray, salt: ByteArray, blockCount: Int, deriver: StegoKeyDeriver): Int {
        if (blockCount <= 1) return 0
        val digest = deriver.sha256(context(salt, SLOTS) + key)
        val value = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
        return (value and 0xFFFF) % blockCount
    }

    /**
     * Assigns one slot per envelope from the key schedule, deterministically resolving collisions by
     * walking forward. Callers get back the slot of each input key, in input order.
     */
    fun assignSlots(
        keys: List<ByteArray>,
        salt: ByteArray,
        deriver: StegoKeyDeriver
    ): IntArray {
        val count = keys.size
        if (count == 0) return IntArray(0)
        val taken = BooleanArray(count)
        val slots = IntArray(count) { -1 }
        keys.forEachIndexed { index, key ->
            val preferred = blockAddressFor(key, salt, count, deriver)
            var probe = preferred
            var guard = 0
            while (guard < count && taken[probe]) {
                probe = (probe + 1) % count
                guard += 1
            }
            if (guard >= count) return IntArray(count) { it }
            slots[index] = probe
            taken[probe] = true
        }
        return slots
    }

    private fun context(salt: ByteArray, purpose: String): ByteArray =
        salt + purpose.toByteArray(Charsets.UTF_8)

    /** Wipes a passphrase the UI was holding, so the secret leaves no copy behind in memory. */
    fun clear(passphrase: CharArray?) {
        if (passphrase == null) return
        for (index in passphrase.indices) passphrase[index] = ' '
    }
}
