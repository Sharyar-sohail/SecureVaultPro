package com.securevault.pro.data.remote.realtime

import com.securevault.pro.data.remote.firebase.KeyringCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * The `{e,u,r,d}` node contract (A2a): sealing only ever uses the per-user vault key, the map the
 * database hands back is coerced instead of cast, and a node that is not exactly the spec shape is
 * rejected rather than guessed at.
 */
class A2aRealtimeEnvelopeTest {

    private val codec = RealtimeRecordCodec()

    /**
     * A1's convention: a fresh runtime key, never a literal secret. The seed is `System.nanoTime()`
     * so no key value is ever written into this file, a fixture or a log.
     */
    private fun runtimeKey(): ByteArray =
        KeyringCodec.generateVaultKey(SecureRandom().apply { setSeed(System.nanoTime()) })

    private fun samplePlaintext(): String =
        codec.encode(RealtimeModule.NOTES, A2aRealtimeSamples.NOTE)

    @Test
    fun sealOpenRoundTripWithARuntimeKey() {
        val key = runtimeKey()
        val plain = samplePlaintext()
        val envelope = RealtimeEnvelope.seal(plain, key, A2aRealtimeSamples.BASE + A2aRealtimeSamples.MINUTE, 11L)
        assertNotNull(envelope)
        assertEquals(plain, RealtimeEnvelope.open(key, envelope!!))
        val reopened = RealtimeEnvelope.fromCloudMap(envelope.toCloudMap())
        assertEquals(envelope, reopened)
        assertEquals(A2aRealtimeSamples.NOTE, decodedNote(key, reopened))
        assertEquals(RealtimeModule.NOTES, codec.decodeAny(RealtimeEnvelope.open(key, reopened!!))?.module)
    }

    @Test
    fun theNodeCarriesOnlyCiphertext() {
        val key = runtimeKey()
        val plain = samplePlaintext()
        val envelope = RealtimeEnvelope.seal(plain, key, A2aRealtimeSamples.BASE, 3L)!!
        val node = envelope.toCloudMap()
        assertEquals(RealtimeEnvelope.FIELDS, node.keys.toList())
        for ((field, value) in node) {
            if (value is String) {
                assertFalse("$field leaked plaintext", value.contains("fix-note-0001") || value.contains("Sample"))
            }
        }
        assertFalse(envelope.e, envelope.e.contains("fix-note-0001"))
        assertTrue(envelope.e, envelope.e.contains('.'))
        assertEquals(2, envelope.e.split('.').size)
    }

    @Test
    fun aWrongKeyOrATamperedSealOpensToNull() {
        val key = runtimeKey()
        val envelope = RealtimeEnvelope.seal(samplePlaintext(), key, A2aRealtimeSamples.BASE, 1L)!!
        assertNull(RealtimeEnvelope.open(runtimeKey(), envelope))
        assertNull(RealtimeEnvelope.open(ByteArray(KeyringCodec.KEY_BYTES), envelope))
        assertNull(RealtimeEnvelope.open(key.copyOf(16), envelope))
        val parts = envelope.e.split('.')
        val flipped = parts[0] + "." + flipLastBase64Char(parts[1])
        val tampered = envelope.copy(e = flipped)
        assertNotEquals(envelope.e, tampered.e)
        assertNull(RealtimeEnvelope.open(key, tampered))
        assertNull(RealtimeEnvelope.open(key, envelope.copy(e = "no-separator-here")))
    }

    @Test
    fun sealingRefusesAnythingThatIsNotTheVaultKey() {
        assertNull(RealtimeEnvelope.seal(samplePlaintext(), ByteArray(31), A2aRealtimeSamples.BASE, 1L))
        assertNull(RealtimeEnvelope.seal(samplePlaintext(), ByteArray(64), A2aRealtimeSamples.BASE, 1L))
        assertNull(RealtimeEnvelope.seal("", runtimeKey(), A2aRealtimeSamples.BASE, 1L))
    }

    @Test
    fun liveRecordsEmitAnExplicitNullTombstoneAndTombstonesAMillisecond() {
        val key = runtimeKey()
        val live = RealtimeEnvelope.seal(samplePlaintext(), key, A2aRealtimeSamples.BASE, 5L)!!
        assertTrue(live.toCloudMap().containsKey(RealtimeEnvelope.FIELD_D))
        assertNull(live.toCloudMap()[RealtimeEnvelope.FIELD_D])
        assertFalse(live.isTombstone())
        val tombstone = RealtimeEnvelope.seal(samplePlaintext(), key, A2aRealtimeSamples.BASE, 5L, A2aRealtimeSamples.BASE)!!
        assertEquals(A2aRealtimeSamples.BASE, tombstone.d)
        assertTrue(tombstone.isTombstone())
        assertEquals(tombstone, RealtimeEnvelope.fromCloudMap(tombstone.toCloudMap()))
    }

    // ---------------------------------------------------------------- coercion

    @Test
    fun clocksCoerceFromIntLongDoubleAndString() {
        val clock = A2aRealtimeSamples.BASE
        for (value in listOf(clock, clock.toDouble(), clock.toString(), " $clock ")) {
            val node = node(e = A2aRealtimeSamples.FAKE_SEAL, u = value, r = 7, d = null)
            val parsed = RealtimeEnvelope.fromCloudMap(node)
            assertNotNull("u as ${value.javaClass.simpleName}($value) rejected", parsed)
            assertEquals(clock, parsed!!.u)
        }
        // A small Int clock is still a clock: the type is coerced, not trusted.
        assertEquals(123456L, RealtimeEnvelope.fromCloudMap(node(u = 123456, r = 1))!!.u)
        // A fractional millisecond clock is truncated, never rounded up.
        assertEquals(clock, RealtimeEnvelope.fromCloudMap(node(u = clock.toDouble() + 0.4))!!.u)
        assertEquals(7L, RealtimeEnvelope.fromCloudMap(node(u = clock, r = 7.0))!!.r)
        assertEquals(7L, RealtimeEnvelope.fromCloudMap(node(u = clock, r = "7"))!!.r)
        assertEquals(7L, RealtimeEnvelope.fromCloudMap(node(u = clock, r = 7.toLong()))!!.r)
    }

    @Test
    fun tombstoneFieldAcceptsAbsentNullAndAnyNumberType() {
        val clock = A2aRealtimeSamples.BASE
        val tomb = A2aRealtimeSamples.BASE + 1
        assertNull(RealtimeEnvelope.fromCloudMap(mapOf(RealtimeEnvelope.FIELD_E to A2aRealtimeSamples.FAKE_SEAL, RealtimeEnvelope.FIELD_U to clock, RealtimeEnvelope.FIELD_R to 1))!!.d)
        assertEquals(tomb, RealtimeEnvelope.fromCloudMap(node(u = clock, r = 1, d = tomb))!!.d)
        assertEquals(tomb, RealtimeEnvelope.fromCloudMap(node(u = clock, r = 1, d = tomb.toDouble()))!!.d)
        assertEquals(tomb, RealtimeEnvelope.fromCloudMap(node(u = clock, r = 1, d = tomb.toString()))!!.d)
        assertEquals(0L, RealtimeEnvelope.fromCloudMap(node(u = clock, r = 1, d = 0))!!.d)
    }

    @Test
    fun malformedNodesAreRefusedNotGuessed() {
        val clock = A2aRealtimeSamples.BASE
        val rejections = listOf(
            null,
            emptyMap<String, Any?>(),
            node(u = clock, r = 1).minus(RealtimeEnvelope.FIELD_E),
            node(u = clock, r = 1).minus(RealtimeEnvelope.FIELD_U),
            node(u = clock, r = 1).minus(RealtimeEnvelope.FIELD_R),
            node(e = "", u = clock, r = 1),
            node(e = "   ", u = clock, r = 1),
            node(e = "no-separator", u = clock, r = 1),
            node(u = clock, r = 1).plus("title" to "plaintext leak"),
            node(u = clock, r = 1).plus("plain" to samplePlaintext()),
            node(u = -1, r = 1),
            node(u = clock, r = -1),
            node(u = clock, r = 1, d = -1),
            node(u = clock, r = 1, d = "not-a-clock"),
            node(u = "not-a-clock", r = 1),
            node(u = true, r = 1),
            node(u = listOf(1, 2), r = 1),
            node(u = clock, r = mapOf("a" to 1)),
            node(u = clock, r = "seven"),
            node(u = MAX_CLOCK + 1, r = 1),
            node(u = clock, r = 1, d = MAX_CLOCK + 1)
        )
        for (raw in rejections) {
            assertNull("accepted $raw", RealtimeEnvelope.fromCloudMap(raw))
        }
    }

    @Test
    fun nonStringKeysRejectTheWholeNode() {
        assertNull(
            RealtimeEnvelope.fromCloudMap(
                mapOf<Any, Any?>(0 to A2aRealtimeSamples.FAKE_SEAL, RealtimeEnvelope.FIELD_U to 1L, RealtimeEnvelope.FIELD_R to 1L)
            )
        )
    }

    @Test
    fun resolverRefusesAMalformedRemoteNodeBeforeAnyClockComparison() {
        val local = node(u = A2aRealtimeSamples.BASE, r = 5L)
        val leaking = node(u = A2aRealtimeSamples.BASE + 1, r = 6L).plus("password" to "leaked")
        val result = RealtimeMergeResolver.resolveNodes(local, leaking)
        assertEquals(RealtimeMergeResolver.Decision.REJECTED_REMOTE_MALFORMED, result.decision)
        assertEquals(RealtimeMergeResolver.Action.KEEP_LOCAL, result.action)
        assertTrue(result.remoteDropped)
        assertFalse(result.applyRemote)
        assertEquals(A2aRealtimeSamples.BASE, result.localU)
        val absent = RealtimeMergeResolver.resolveNodes(local, null)
        assertEquals(RealtimeMergeResolver.Decision.NO_REMOTE_NODE, absent.decision)
        assertEquals(RealtimeMergeResolver.Action.NO_OP, absent.action)
        assertTrue(absent.describe(), absent.describe().startsWith("NO_REMOTE_NODE"))
        // The same node through the typed path must reach the same verdict as the raw-map path.
        val newer = RealtimeEnvelope.fromCloudMap(node(u = A2aRealtimeSamples.BASE + 1, r = 6L))
        assertEquals(
            RealtimeMergeResolver.Decision.APPLIED_REMOTE,
            RealtimeMergeResolver.resolve(RealtimeEnvelope.fromCloudMap(local), newer).decision
        )
        assertEquals(
            RealtimeMergeResolver.Decision.APPLIED_REMOTE,
            RealtimeMergeResolver.resolveNodes(local, node(u = A2aRealtimeSamples.BASE + 1, r = 6L)).decision
        )
    }

    private fun decodedNote(key: ByteArray, envelope: RealtimeEnvelope?): Any? =
        envelope?.let { codec.decode(RealtimeModule.NOTES, RealtimeEnvelope.open(key, it)) }

    /** A node map in exactly the shape the Firebase client hands a listener. */
    private fun node(
        e: Any? = A2aRealtimeSamples.FAKE_SEAL,
        u: Any? = A2aRealtimeSamples.BASE,
        r: Any? = 1L,
        d: Any? = null
    ): MutableMap<String, Any?> = linkedMapOf(
        RealtimeEnvelope.FIELD_E to e,
        RealtimeEnvelope.FIELD_U to u,
        RealtimeEnvelope.FIELD_R to r,
        RealtimeEnvelope.FIELD_D to d
    )

    /** Corrupts one interior base64 character, so the GCM tag can no longer authenticate. */
    private fun flipLastBase64Char(body: String): String {
        val index = body.length / 2
        val original = body[index]
        return body.substring(0, index) + (if (original == 'A') 'B' else 'A') + body.substring(index + 1)
    }

    private companion object {
        /** The same ceiling the envelope enforces: about a thousand years of milliseconds. */
        val MAX_CLOCK: Long = 10L * TimeUnit.DAYS.toMillis(36_500L)
    }
}
