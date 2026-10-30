package com.securevault.pro.crypto.stego

/**
 * GF(256) with the primitive polynomial x^8+x^4+x^3+x^2+1 (0x11D) and 2 as generator element.
 *
 * The same field backs the [ReedSolomon] error-control code and the 3-of-3 dispersal, so one table
 * of exponent/logarithm entries is the whole arithmetic: every multiply is two lookups and an add.
 */
object Gf256 {

    private const val PRIMITIVE = 0x11D
    const val FIELD_SIZE = 256
    const val ORDER = 255

    private val exponent = IntArray(512)
    private val logarithm = IntArray(FIELD_SIZE)

    init {
        var value = 1
        for (index in 0 until ORDER) {
            exponent[index] = value
            logarithm[value] = index
            value = value shl 1
            if (value and FIELD_SIZE != 0) value = value xor PRIMITIVE
        }
        for (index in ORDER until exponent.size) exponent[index] = exponent[index - ORDER]
    }

    fun mul(first: Int, second: Int): Int {
        val a = first and 0xFF
        val b = second and 0xFF
        if (a == 0 || b == 0) return 0
        return exponent[logarithm[a] + logarithm[b]]
    }

    fun div(numerator: Int, denominator: Int): Int {
        val a = numerator and 0xFF
        val b = denominator and 0xFF
        require(b != 0) { "Division by zero in GF(256)" }
        if (a == 0) return 0
        return exponent[(logarithm[a] - logarithm[b] + ORDER) % ORDER]
    }

    fun pow(value: Int, power: Int): Int {
        val base = value and 0xFF
        if (base == 0) return 0
        if (power == 0) return 1
        val log = (logarithm[base].toLong() * power.toLong()) % ORDER.toLong()
        return exponent[log.toInt()]
    }

    fun inverse(value: Int): Int {
        val element = value and 0xFF
        require(element != 0) { "Zero has no inverse in GF(256)" }
        return exponent[ORDER - logarithm[element]]
    }

    fun add(first: Int, second: Int): Int = (first xor second) and 0xFF

    /** Distinctness helper for the dispersal tests: the field has no zero divisors. */
    fun isElement(value: Int): Boolean = value in 0 until FIELD_SIZE
}

/**
 * Systematic Reed–Solomon over GF(256), shortened from RS(255,223): [parity] check symbols per
 * block, correcting up to parity/2 wrong bytes anywhere inside the block.
 *
 * Decoding follows the standard path — syndromes, Berlekamp–Massey for the error locator, a Chien
 * search for the positions — and recovers the error *magnitudes* by solving the small Vandermonde
 * system the syndromes define rather than hand-rolling Forney's formula. A square Vandermonde
 * matrix over distinct locators is always invertible, the system is at most 16x16 per block, and
 * the decoder re-tests every syndrome after correction and refuses the block if one is still
 * nonzero. That last gate is deliberate: a watermark that cannot be corrected has to say so,
 * because a silently mis-corrected seed phrase is worse than no answer at all.
 */
class ReedSolomon(val parity: Int = StegoLimits.RS_PARITY_CHUNK) {

    companion object {
        /** g(x) = (x+a^0)(x+a^1)...(x+a^(parity-1)), coefficients in descending degree order. */
        fun buildGenerator(nsym: Int): IntArray {
            var poly = intArrayOf(1)
            for (index in 0 until nsym) {
                poly = multiply(poly, intArrayOf(1, Gf256.pow(2, index)))
            }
            return poly
        }

        /** Descending-order polynomial multiplication. */
        fun multiply(first: IntArray, second: IntArray): IntArray {
            val out = IntArray(first.size + second.size - 1)
            for (i in first.indices) {
                for (j in second.indices) {
                    out[i + j] = out[i + j] xor Gf256.mul(first[i], second[j])
                }
            }
            return out
        }

        /** Descending-order addition, aligned on the constant term. */
        fun add(first: IntArray, second: IntArray): IntArray {
            val width = maxOf(first.size, second.size)
            val out = IntArray(width)
            for (index in 0 until first.size) out[width - first.size + index] = first[index]
            for (index in 0 until second.size) {
                val at = width - second.size + index
                out[at] = out[at] xor second[index]
            }
            return out
        }

        fun scale(poly: IntArray, factor: Int): IntArray =
            IntArray(poly.size) { Gf256.mul(poly[it], factor) }

        /** Horner evaluation of a descending-order polynomial at x. */
        fun evaluate(poly: IntArray, x: Int): Int {
            var total = 0
            for (coefficient in poly) total = Gf256.mul(total, x) xor coefficient
            return total
        }

        /** Payload bytes one block can carry alongside [ReedSolomon.parity] check symbols. */
        const val DATA_PER_BLOCK = Gf256.ORDER - StegoLimits.RS_PARITY_CHUNK
        const val BLOCK_LENGTH = Gf256.ORDER
    }

    private val generator: IntArray = buildGenerator(parity)

    /** Appends [parity] check symbols to `data`. */
    fun encode(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Nothing to protect" }
        require(data.size <= Gf256.ORDER - parity) {
            "A block holds at most ${Gf256.ORDER - parity} payload bytes, found ${data.size}"
        }
        val register = IntArray(parity)
        for (byte in data) {
            val feedback = (byte.toInt() and 0xFF) xor register[0]
            for (index in 0 until parity - 1) {
                register[index] = register[index + 1] xor Gf256.mul(generator[index + 1], feedback)
            }
            register[parity - 1] = Gf256.mul(generator[parity], feedback)
        }
        val out = ByteArray(data.size + parity)
        data.copyInto(out)
        for (index in 0 until parity) out[data.size + index] = register[index].toByte()
        return out
    }

    /**
     * Corrects one received block (payload followed by check symbols) and returns its payload, or
     * null when the damage exceeds the code's reach — never a guess.
     */
    fun decode(received: ByteArray): ByteArray? {
        if (received.size < parity + 1 || received.size > Gf256.ORDER) return null
        val syndromes = syndromesOf(received)
        if (syndromes.none { it != 0 }) return received.copyOfRange(0, received.size - parity)

        val locator = errorLocator(syndromes) ?: return null
        val positions = chienSearch(locator, received.size) ?: return null
        if (positions.size != locator.size - 1) return null

        val degrees = IntArray(positions.size) { received.size - 1 - positions[it] }
        val magnitudes = magnitudes(syndromes, degrees) ?: return null
        val repaired = received.copyOf()
        for (index in positions.indices) {
            repaired[positions[index]] =
                (repaired[positions[index]].toInt() xor magnitudes[index]).toByte()
        }

        for (value in syndromesOf(repaired)) if (value != 0) return null
        return repaired.copyOfRange(0, repaired.size - parity)
    }

    /** S_k = C(a^k) for k in 0 until parity, reading the block as a descending polynomial. */
    private fun syndromesOf(block: ByteArray): IntArray {
        val out = IntArray(parity)
        for (index in 0 until parity) {
            val x = Gf256.pow(2, index)
            var value = 0
            for (byte in block) value = Gf256.mul(value, x) xor (byte.toInt() and 0xFF)
            out[index] = value
        }
        return out
    }

    /** Berlekamp–Massey, returning the error locator in descending degree order, or null. */
    private fun errorLocator(syndromes: IntArray): IntArray? {
        var locator = intArrayOf(1)
        var previous = intArrayOf(1)
        for (index in 0 until parity) {
            var delta = syndromes[index]
            for (step in 1 until locator.size) {
                delta = delta xor Gf256.mul(locator[locator.size - 1 - step], syndromes[index - step])
            }
            previous = previous.copyOf(previous.size + 1)
            if (delta != 0) {
                if (previous.size > locator.size) {
                    val shifted = scale(previous, delta)
                    previous = scale(locator, Gf256.inverse(delta))
                    locator = shifted
                }
                locator = add(locator, scale(previous, delta))
            }
        }
        var trimmed = locator
        while (trimmed.isNotEmpty() && trimmed[0] == 0) {
            trimmed = trimmed.copyOfRange(1, trimmed.size)
        }
        if (trimmed.isEmpty()) return null
        val errors = trimmed.size - 1
        if (errors * 2 > parity) return null
        return trimmed
    }

    /**
     * Chien search: the locator is `prod(1 - X_j x)`, so a root at `a^i` marks the symbol carried
     * on `X_j = a^(i-1)`, i.e. array position `i - 1` (mod block length). Mapping the root straight
     * onto `length - 1 - i` finds the symbols mirrored inside the block and hands every magnitude
     * solve the wrong degrees, which is why only the final byte was ever repaired.
     */
    private fun chienSearch(locator: IntArray, blockLength: Int): IntArray? {
        val found = ArrayList<Int>(locator.size)
        for (index in 0 until blockLength) {
            if (evaluate(locator, Gf256.pow(2, index)) == 0) {
                found.add((index + blockLength - 1) % blockLength)
            }
        }
        return found.toIntArray()
    }

    /**
     * Solves `sum_j e_j * X_j^k = S_k` for the error magnitudes by Gauss–Jordan elimination over
     * GF(256), then verifies the answer against *all* syndromes, including the ones the solver did
     * not use. A locator that does not fit the syndromes means the damage is beyond parity/2.
     */
    private fun magnitudes(syndromes: IntArray, degrees: IntArray): IntArray? {
        val count = degrees.size
        if (count == 0) return IntArray(0)
        if (count > syndromes.size) return null
        val locators = IntArray(count) { Gf256.pow(2, degrees[it]) }
        val matrix = Array(count) { row ->
            val line = IntArray(count + 1)
            for (column in 0 until count) line[column] = Gf256.pow(locators[column], row)
            line[count] = syndromes[row]
            line
        }
        for (pivot in 0 until count) {
            var row = pivot
            while (row < count && matrix[row][pivot] == 0) row += 1
            if (row == count) return null
            if (row != pivot) {
                val held = matrix[pivot]
                matrix[pivot] = matrix[row]
                matrix[row] = held
            }
            val normalise = Gf256.inverse(matrix[pivot][pivot])
            for (column in pivot..count) {
                matrix[pivot][column] = Gf256.mul(matrix[pivot][column], normalise)
            }
            for (other in 0 until count) {
                if (other == pivot) continue
                val factor = matrix[other][pivot]
                if (factor == 0) continue
                for (column in pivot..count) {
                    matrix[other][column] =
                        matrix[other][column] xor Gf256.mul(matrix[pivot][column], factor)
                }
            }
        }
        val solution = IntArray(count) { matrix[it][count] }
        for (index in syndromes.indices) {
            var rebuilt = 0
            for (column in 0 until count) {
                rebuilt = rebuilt xor Gf256.mul(solution[column], Gf256.pow(locators[column], index))
            }
            if (rebuilt != syndromes[index]) return null
        }
        return solution
    }

    /** Encodes every [DATA_PER_BLOCK]-sized chunk and concatenates the full blocks. */
    fun encodeChunks(payload: ByteArray): ByteArray {
        val chunks = (payload.size + DATA_PER_BLOCK - 1) / DATA_PER_BLOCK
        val out = ByteArray(chunks * (DATA_PER_BLOCK + parity))
        var offset = 0
        for (chunk in 0 until chunks) {
            val start = chunk * DATA_PER_BLOCK
            val end = minOf(start + DATA_PER_BLOCK, payload.size)
            val bytes = ByteArray(DATA_PER_BLOCK)
            payload.copyOfRange(start, end).copyInto(bytes)
            val encoded = encode(bytes)
            encoded.copyInto(out, offset)
            offset += encoded.size
        }
        return out
    }

    /**
     * Reverse of [encodeChunks]: every block must survive or the whole frame is refused. Returns the
     * payload with its tail padding still attached, which the frame parser strips using the lengths
     * it carries.
     */
    fun decodeChunks(received: ByteArray): ByteArray? {
        val blockLength = DATA_PER_BLOCK + parity
        if (received.isEmpty() || received.size % blockLength != 0) return null
        val chunks = received.size / blockLength
        val out = ByteArray(chunks * DATA_PER_BLOCK)
        for (chunk in 0 until chunks) {
            val block = received.copyOfRange(chunk * blockLength, (chunk + 1) * blockLength)
            val data = decode(block) ?: return null
            if (data.size != DATA_PER_BLOCK) return null
            data.copyInto(out, chunk * DATA_PER_BLOCK)
        }
        return out
    }

    /** How many bits one encoded frame of `payloadBytes` costs, so the chip rate can be planned. */
    fun encodedBitCount(payloadBytes: Int): Int {
        if (payloadBytes <= 0) return 0
        val chunks = (payloadBytes + DATA_PER_BLOCK - 1) / DATA_PER_BLOCK
        return chunks * (DATA_PER_BLOCK + parity) * 8
    }

    fun encodedByteCount(payloadBytes: Int): Int {
        if (payloadBytes <= 0) return 0
        val chunks = (payloadBytes + DATA_PER_BLOCK - 1) / DATA_PER_BLOCK
        return chunks * (DATA_PER_BLOCK + parity)
    }
}
