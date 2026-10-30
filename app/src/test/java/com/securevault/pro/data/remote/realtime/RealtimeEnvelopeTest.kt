package com.securevault.pro.data.remote.realtime

import com.securevault.pro.data.remote.firebase.KeyringCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `{e, u, r, d}` node body from `RECORD-FORMAT-v1.md` §1 — the exact four keys, nothing else,
 * sealed only under the per-user vault key. This is the shape the browser reads, so coercion of the
 * Int/Long/Double/String numbers the Realtime Database and JS hand back is pinned here, as is the
 * reject-anything-extra rule that keeps a malformed node from ever reaching the cipher.
 */
class RealtimeEnvelopeTest {

    private val key = ByteArray(32) { (it % 251).toByte() }
    private val plain = """{"id":"n-1","title":"Hello","content":"world"}"""

    @Test
    fun `the node body is exactly e u r d in order`() {
        val envelope = RealtimeEnvelope.seal(plain, key, u = 1_700_000_000_000L, r = 5L)
        assertNotNull(envelope)
        assertEquals(listOf("e", "u", "r", "d"), envelope!!.toCloudMap().keys.toList())
        // The cipher is the envelope shape KeyringCodec defines: base64(iv).base64(ct+tag).
        assertTrue(envelope.e.contains('.'))
        assertTrue(envelope.e.split('.').first().isNotBlank())
    }

    @Test
    fun `a sealed node opens back to the same plaintext under the key`() {
        val envelope = RealtimeEnvelope.seal(plain, key, u = 1L, r = 1L)!!
        val fromMap = RealtimeEnvelope.fromCloudMap(envelope.toCloudMap())
        assertNotNull(fromMap)
        assertEquals(envelope, fromMap)
        assertEquals(plain, RealtimeEnvelope.open(key, fromMap!!))
    }

    @Test
    fun `the wrong key yields no plaintext, never garbage`() {
        val envelope = RealtimeEnvelope.seal(plain, key, u = 1L, r = 1L)!!
        assertNull(RealtimeEnvelope.open(ByteArray(32) { 9 }, envelope))
        // A non-32-byte key is refused outright, not truncated.
        assertNull(RealtimeEnvelope.open(ByteArray(16), envelope))
    }

    @Test
    fun `live records emit a null tombstone and deletes carry the epoch ms`() {
        val live = RealtimeEnvelope.seal(plain, key, u = 5L, r = 1L)!!
        assertFalse(live.isTombstone())
        assertTrue(live.toCloudMap().containsKey("d"))
        assertNull(live.d)

        val dead = RealtimeEnvelope.seal(plain, key, u = 5L, r = 2L, deletedAt = 999L)!!
        assertTrue(dead.isTombstone())
        assertEquals(999L, dead.d)
        assertTrue(RealtimeEnvelope.fromCloudMap(dead.toCloudMap())!!.isTombstone())
    }

    /**
     * The Realtime Database returns numbers as Long or Double depending on the writer, and JS may
     * write a String, so `u`/`r`/`d` are coerced, never cast.
     */
    @Test
    fun `numeric fields are accepted in every shape the database can return`() {
        val e = RealtimeEnvelope.seal(plain, key, u = 1L, r = 1L)!!.e
        for (u in listOf<Any>(1_700_000_000_000L, 1.7E12, "1700000000000")) {
            for (r in listOf<Any>(7, 7L, 7.0, "7")) {
                val parsed = RealtimeEnvelope.fromCloudMap(mapOf("e" to e, "u" to u, "r" to r, "d" to null))
                assertNotNull("$u/$r must parse", parsed)
            }
        }
        val fromStrings = RealtimeEnvelope.fromCloudMap(mapOf("e" to e, "u" to "1700000000000", "r" to "7", "d" to "888"))
        assertEquals(1_700_000_000_000L, fromStrings?.u)
        assertEquals(7L, fromStrings?.r)
        assertEquals(888L, fromStrings?.d)
    }

    @Test
    fun `a malformed node is rejected rather than half trusted`() {
        val e = RealtimeEnvelope.seal(plain, key, u = 1L, r = 1L)!!.e
        val good = mapOf<String, Any?>("e" to e, "u" to 1_700_000_000_000L, "r" to 1L, "d" to null)
        assertNotNull(RealtimeEnvelope.fromCloudMap(good))

        assertNull(RealtimeEnvelope.fromCloudMap(null))
        assertNull(RealtimeEnvelope.fromCloudMap(emptyMap<String, Any?>()))
        // Any key outside {e,u,r,d} means the node is not ours.
        assertNull(RealtimeEnvelope.fromCloudMap(good + ("extra" to 1)))
        assertNull(RealtimeEnvelope.fromCloudMap(good + ("plaintext" to plain)))
        // Missing or malformed cipher.
        assertNull(RealtimeEnvelope.fromCloudMap(good.filterKeys { it != "e" }))
        assertNull(RealtimeEnvelope.fromCloudMap(good + ("e" to "no-dot-here")))
        assertNull(RealtimeEnvelope.fromCloudMap(good + ("e" to "")))
        // Missing clock/revision.
        for (missing in listOf("u", "r")) {
            assertNull(RealtimeEnvelope.fromCloudMap(good.filterKeys { it != missing }))
        }
        // Absurd or negative clocks are refused; a nonsense `d` too.
        assertNull(RealtimeEnvelope.fromCloudMap(good + ("u" to -1)))
        assertNull(RealtimeEnvelope.fromCloudMap(good + ("u" to "not a number")))
        assertNull(RealtimeEnvelope.fromCloudMap(good + ("d" to 9.2E18)))
    }

    @Test
    fun `a seal under a wrong-length key never produces a node`() {
        assertNull(RealtimeEnvelope.seal(plain, ByteArray(16), u = 1L, r = 1L))
        // And a real generated key works.
        val generated = KeyringCodec.generateVaultKey()
        assertNotNull(RealtimeEnvelope.seal(plain, generated, u = 1L, r = 1L))
    }
}
