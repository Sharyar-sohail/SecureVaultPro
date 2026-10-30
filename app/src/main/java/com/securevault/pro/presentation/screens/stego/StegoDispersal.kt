package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.Gf256
import java.security.SecureRandom

/**
 * All-or-nothing dispersal of one secret across two to four carrier images.
 *
 * One byte position of the secret becomes a polynomial over GF(256) whose constant term is that
 * secret byte and whose remaining coefficients are fresh random bytes. Carrier *k* carries the
 * polynomial evaluated at a fixed, published abscissa, so:
 *
 *  - with fewer than `total` carriers there is one unknown more than there are equations, which
 *    means every secret of the same length remains equally likely — two of three photographs are
 *    indistinguishable from noise, and an examiner cannot prove a third ever existed;
 *  - with all of them, Lagrange interpolation at zero rebuilds the byte exactly.
 *
 * The field is the same [Gf256] the Reed–Solomon layer builds its tables from, so the app carries
 * one arithmetic rather than two. The abscissae are powers of two (1, 2, 4, 8): distinct, non-zero,
 * and never colliding inside a set of at most four.
 *
 * This is a *split*, not a key: every share is as long as the secret, and the share index travels
 * in the carrier's directory (`shareTotal`/`shareIndex`), never inside the share bytes.
 */
object SecretShares {

    /** Matches `StegoEncodeRequest`'s rule that a set holds at most four carriers. */
    const val MAX_SHARES = 4
    const val MIN_SHARES = 1

    private val ABSCISSAE = intArrayOf(1, 2, 4, 8)
    private val random = SecureRandom()

    fun isSupported(total: Int): Boolean = total in MIN_SHARES..MAX_SHARES

    /** The evaluation point of one share, or null when the index is outside a legal set. */
    fun abscissaOf(index: Int): Int? = ABSCISSAE.getOrNull(index)

    /**
     * Splits `secret` into [total] shares, returned in share-index order.
     *
     * `coefficients` is the randomness source; production uses [SecureRandom] and the tests pass a
     * deterministic supplier so a split is reproducible. Never wire this to a constant or to
     * something derived from the secret: predictable coefficients degrade the split into an
     * obfuscation, which is exactly the property the owner is relying on.
     */
    fun split(secret: ByteArray, total: Int, coefficients: () -> Int = { random.nextInt() and 0xFF }): List<ByteArray> {
        require(isSupported(total)) { "A dispersal set holds $MIN_SHARES..$MAX_SHARES carriers, asked for $total" }
        if (secret.isEmpty()) return emptyList()
        val shares = List(total) { ByteArray(secret.size) }
        for (position in secret.indices) {
            val poly = IntArray(total)
            poly[0] = secret[position].toInt() and 0xFF
            for (degree in 1 until total) poly[degree] = coefficients() and 0xFF
            for (index in 0 until total) {
                shares[index][position] = evaluate(poly, ABSCISSAE[index]).toByte()
            }
        }
        return shares
    }

    /** Horner evaluation over GF(256); coefficients are stored in ascending degree order. */
    private fun evaluate(poly: IntArray, x: Int): Int {
        var total = 0
        for (degree in poly.indices.reversed()) total = Gf256.mul(total, x) xor poly[degree]
        return total
    }

    /** A share the reader recovered: which carrier it came out of, and its bytes. */
    data class Share(val index: Int, val bytes: ByteArray) {
        val length: Int get() = bytes.size

        override fun equals(other: Any?): Boolean =
            other is Share && other.index == index && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = index * 31 + bytes.contentHashCode()
    }

    /**
     * Rebuilds the secret, or null when the set is incomplete or inconsistent.
     *
     * Completeness is checked first and answered honestly: a partial set returns null rather than a
     * guess, because half a seed phrase is worse than none — it invites the owner to act on a wrong
     * answer.
     */
    fun join(shares: List<Share>, total: Int): ByteArray? {
        if (!isSupported(total)) return null
        val distinct = shares.distinctBy { it.index }
        if (distinct.size != total) return null
        if (distinct.any { it.index !in 0 until total }) return null
        val length = distinct.first().bytes.size
        if (length == 0) return null
        if (distinct.any { it.bytes.size != length }) return null
        val out = ByteArray(length)
        for (position in 0 until length) {
            var rebuilt = 0
            for (i in 0 until total) {
                val xi = ABSCISSAE[i]
                var basis = 1
                for (j in 0 until total) {
                    if (i == j) continue
                    val xj = ABSCISSAE[j]
                    val denominator = Gf256.add(xi, xj)
                    if (denominator == 0) return null
                    // Interpolating at x = 0, so each factor is (0 - xj) / (xi - xj) = xj / (xi ^ xj).
                    basis = Gf256.mul(basis, Gf256.div(xj, denominator))
                }
                val yi = distinct.first { it.index == i }.bytes[position].toInt() and 0xFF
                rebuilt = rebuilt xor Gf256.mul(yi, basis)
            }
            out[position] = rebuilt.toByte()
        }
        return out
    }

    /** Convenience for the app layer: shares already held in index order, nothing missing. */
    fun joinInOrder(shares: List<ByteArray>): ByteArray? {
        if (shares.isEmpty()) return null
        val length = shares.first().size
        if (shares.any { it.size != length }) return null
        return join(shares.mapIndexed { index, bytes -> Share(index, bytes) }, shares.size)
    }

    /**
     * True when the set on hand cannot say anything about the secret. The UI quotes this instead of
     * claiming "unreadable", which is the honest wording: the shares read fine, the *message* does
     * not exist until the last one arrives.
     */
    fun revealsNothing(held: Int, total: Int): Boolean = held < total

    /** Shares still missing from a partial set, as indices the owner should look for. */
    fun missingIndices(held: List<Int>, total: Int): List<Int> =
        (0 until total).filter { it !in held.toSet() }
}
