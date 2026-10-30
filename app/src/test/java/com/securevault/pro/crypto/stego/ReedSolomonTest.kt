package com.securevault.pro.crypto.stego

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reed–Solomon RS(255,223): 32 check symbols, so 16 wrong symbols per block are correctable and
 * the seventeenth must be *detected*, never guessed.
 *
 * The decoder's final syndrome re-check is the gate under test: a block that cannot be repaired has
 * to come back null, because a silently "repaired" seed phrase is worse than a refusal.
 */
class ReedSolomonTest {

    private val coder = ReedSolomon()
    private val payload = ByteArray(ReedSolomon.DATA_PER_BLOCK) { (it * 7 + 3).toByte() }
    private val encoded = coder.encode(payload)

    private fun corrupt(block: ByteArray, errors: Int): ByteArray {
        val damaged = block.copyOf()
        for (index in 0 until errors) {
            val position = (3 + index * 13) % damaged.size
            damaged[position] = (damaged[position].toInt() xor 0xA5).toByte()
        }
        return damaged
    }

    @Test
    fun aCleanBlockIsReturnedUnchanged() {
        assertEquals(255, encoded.size)
        assertArrayEquals(payload, coder.decode(encoded))
    }

    @Test
    fun correctsExactlyParityHalvesErrors() {
        val errors = coder.parity / 2
        assertEquals("the engine's stated capacity is 16 symbols", 16, errors)
        val repaired = coder.decode(corrupt(encoded, errors))
        assertNotNull("16 errors are inside RS(255,223)'s reach", repaired)
        assertArrayEquals(payload, repaired)
    }

    @Test
    fun oneErrorPastCapacityIsDetectedNotGuessed() {
        val errors = coder.parity / 2 + 1
        assertNull("17 errors must be refused, never mis-corrected", coder.decode(corrupt(encoded, errors)))
        assertNull("24 errors must be refused too", coder.decode(corrupt(encoded, 24)))
    }

    @Test
    fun aSingleDamagedChunkFailsTheWholeFrame() {
        val codeword = coder.encodeChunks(payload)
        assertEquals(255, codeword.size)
        assertArrayEquals(payload, coder.decodeChunks(codeword))
        assertNull("a chunk past capacity must refuse the frame", coder.decodeChunks(corrupt(codeword, 17)))
    }

    @Test
    fun encodedBitCountMatchesTheWireSize() {
        assertEquals(255, coder.encodedByteCount(payload.size))
        assertEquals(255 * 8, coder.encodedBitCount(payload.size))
        assertEquals(0, coder.encodedBitCount(0))
    }
}
