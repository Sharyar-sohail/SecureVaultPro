package com.securevault.pro.data.remote.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole conflict table (A2a), pinned twice over: [MergePolicy] owns the rule and its
 * `MergeOutcome` names are what the web logs; [RealtimeMergeResolver] adds the envelope validation
 * and the richer decision vocabulary the Android sync log reports.
 *
 * Base clock used below is 1000L purely to keep the table readable; the rule is unit-free.
 */
class A2aMergePolicyTest {

    private val live = MergeState(u = 1000L, r = 5L, deleted = false)

    @Test
    fun newerRemoteWinsAndOlderRemoteIsDropped() {
        assertOutcomes(MergeOutcome.APPLIED_REMOTE, MergePolicy.decide(MergeState(1001L, 1L, false), live))
        assertOutcomes(MergeOutcome.REJECTED_OLDER, MergePolicy.decide(MergeState(999L, 99L, false), live))
        // The clock dominates the revision: a big `r` on an old `u` still loses.
        val dropped = MergePolicy.decide(MergeState(999L, 99L, false), live)
        assertEquals(999L, dropped.incomingU)
        assertEquals(1000L, dropped.localU)
        assertEquals(99L, dropped.incomingR)
        assertEquals(5L, dropped.localR)
    }

    @Test
    fun equalClocksAreBrokenByRevisionAndATieKeepsLocal() {
        assertOutcomes(MergeOutcome.APPLIED_REMOTE, MergePolicy.decide(MergeState(1000L, 6L, false), live))
        assertOutcomes(MergeOutcome.KEPT_LOCAL_NEWER, MergePolicy.decide(MergeState(1000L, 4L, false), live))
        assertOutcomes(MergeOutcome.KEPT_LOCAL_NEWER, MergePolicy.decide(MergeState(1000L, 5L, false), live))
        assertFalse(MergePolicy.decide(MergeState(1000L, 5L, false), live).applyRemote)
    }

    @Test
    fun aTombstoneBeatsALiveRecordAtEqualClock() {
        val tomb = MergeState(u = 1000L, r = 4L, deleted = true)
        val decision = MergePolicy.decide(tomb, live)
        assertEquals(MergeOutcome.APPLIED_TOMBSTONE, decision.outcome)
        assertTrue(decision.applyRemote)
        assertTrue(decision.deleteWins)
        // And the other direction: a live remote cannot revive a locally deleted record.
        val revived = MergePolicy.decide(MergeState(1000L, 99L, false), MergeState(1000L, 4L, true))
        assertEquals(MergeOutcome.KEPT_LOCAL_NEWER, revived.outcome)
        assertFalse(revived.applyRemote)
        assertTrue("the record stays deleted", revived.deleteWins)
    }

    @Test
    fun tombstonePrecedenceDoesNotDependOnRevision() {
        for (remoteR in 0L..64L) {
            val decision = MergePolicy.decide(MergeState(1000L, remoteR, true), live)
            assertEquals("r=$remoteR", MergeOutcome.APPLIED_TOMBSTONE, decision.outcome)
        }
        for (localR in 0L..64L) {
            val kept = MergePolicy.decide(MergeState(1000L, localR, false), MergeState(1000L, localR, true))
            assertEquals("local r=$localR", MergeOutcome.KEPT_LOCAL_NEWER, kept.outcome)
            assertTrue(kept.deleteWins)
        }
    }

    @Test
    fun anOlderTombstoneNeverResurrectsOverANewerLiveRecord() {
        val decision = MergePolicy.decide(MergeState(999L, 99L, true), live)
        assertEquals(MergeOutcome.REJECTED_OLDER, decision.outcome)
        assertFalse(decision.applyRemote)
        assertFalse(decision.deleteWins)
    }

    @Test
    fun newerLiveRecordBeatsAnOlderLocalTombstone() {
        val tombstonedLocal = MergeState(u = 1000L, r = 4L, deleted = true)
        val decision = MergePolicy.decide(MergeState(1001L, 1L, false), tombstonedLocal)
        assertEquals(MergeOutcome.APPLIED_REMOTE, decision.outcome)
        assertTrue(decision.applyRemote)
        assertFalse(decision.deleteWins)
    }

    @Test
    fun bothSidesTombstonedStillBreaksByRevision() {
        val localTomb = MergeState(u = 1000L, r = 4L, deleted = true)
        assertOutcomes(MergeOutcome.APPLIED_TOMBSTONE, MergePolicy.decide(MergeState(1000L, 6L, true), localTomb))
        assertOutcomes(MergeOutcome.KEPT_LOCAL_NEWER, MergePolicy.decide(MergeState(1000L, 4L, true), localTomb))
    }

    @Test
    fun firstSnapshotAdoptsTheRemote() {
        val fresh = MergePolicy.decide(MergeState(1000L, 1L, false), null)
        assertEquals(MergeOutcome.APPLIED_REMOTE, fresh.outcome)
        assertTrue(fresh.applyRemote)
        assertNullLocal(fresh)
        val freshTombstone = MergePolicy.decide(MergeState(1000L, 1L, true), null)
        assertEquals(MergeOutcome.APPLIED_TOMBSTONE, freshTombstone.outcome)
        assertTrue(freshTombstone.deleteWins)
        assertNullLocal(freshTombstone)
    }

    // ---------------------------------------------------------------- envelope level

    @Test
    fun resolverNamesTheWinnerAndTheDroppedWrite() {
        val local = node(1000L, 5L, null)
        assertEquals(
            RealtimeMergeResolver.Decision.APPLIED_REMOTE,
            RealtimeMergeResolver.resolve(local, node(1001L, 1L, null)).decision
        )
        assertEquals(
            RealtimeMergeResolver.Decision.APPLIED_TOMBSTONE,
            RealtimeMergeResolver.resolve(local, node(1001L, 1L, 1001L)).decision
        )
        val dropped = RealtimeMergeResolver.resolve(local, node(999L, 99L, null))
        assertEquals(RealtimeMergeResolver.Decision.KEPT_LOCAL_OLDER_REMOTE, dropped.decision)
        assertEquals(MergeOutcome.REJECTED_OLDER, dropped.outcome)
        assertEquals(RealtimeMergeResolver.Action.KEEP_LOCAL, dropped.action)
        assertTrue("a dropped write must be reportable", dropped.remoteDropped)
        assertTrue(dropped.describe(), dropped.describe().contains("u=1000/999"))
        assertTrue(dropped.describe(), dropped.describe().contains("r=5/99"))
        assertEquals(
            RealtimeMergeResolver.Decision.KEPT_LOCAL_TIE,
            RealtimeMergeResolver.resolve(local, node(1000L, 5L, null)).decision
        )
        assertEquals(MergeOutcome.KEPT_LOCAL_NEWER, RealtimeMergeResolver.resolve(local, node(1000L, 5L, null)).outcome)
    }

    @Test
    fun resolverTombstoneRulesMatchTheMergePolicy() {
        val localLive = node(1000L, 5L, null)
        val tombAtEqualClock = RealtimeMergeResolver.resolve(localLive, node(1000L, 4L, 1000L))
        assertEquals(RealtimeMergeResolver.Decision.APPLIED_TOMBSTONE, tombAtEqualClock.decision)
        assertEquals(RealtimeMergeResolver.Action.DELETE_LOCAL, tombAtEqualClock.action)
        assertTrue(tombAtEqualClock.applyRemote)
        assertEquals(1000L, tombAtEqualClock.remoteD)
        val localTomb = node(1000L, 4L, 1000L)
        val kept = RealtimeMergeResolver.resolve(localTomb, node(1000L, 99L, null))
        assertEquals(RealtimeMergeResolver.Decision.KEPT_LOCAL_TIE, kept.decision)
        assertEquals(RealtimeMergeResolver.Action.KEEP_LOCAL, kept.action)
        assertEquals(1000L, kept.localD!!)
        assertNull(kept.remoteD)
    }

    @Test
    fun resolverRejectsInvalidEnvelopesInEitherDirection() {
        val good = node(1000L, 5L, null)
        val badSeal = RealtimeEnvelope(e = "no-separator", u = 1001L, r = 6L, d = null)
        val rejected = RealtimeMergeResolver.resolve(good, badSeal)
        assertEquals(RealtimeMergeResolver.Decision.REJECTED_REMOTE_MALFORMED, rejected.decision)
        assertFalse(rejected.applyRemote)
        assertTrue(rejected.remoteDropped)

        val badLocal = RealtimeEnvelope(e = A2aRealtimeSamples.FAKE_SEAL, u = -1L, r = 5L, d = null)
        val repaired = RealtimeMergeResolver.resolve(badLocal, good)
        assertEquals(RealtimeMergeResolver.Decision.REJECTED_LOCAL_MALFORMED, repaired.decision)
        assertTrue(repaired.applyRemote)
        assertEquals(RealtimeMergeResolver.Action.WRITE_REMOTE, repaired.action)

        val bothBad = RealtimeMergeResolver.resolve(badLocal, badSeal)
        assertEquals(RealtimeMergeResolver.Decision.REJECTED_REMOTE_MALFORMED, bothBad.decision)
        // The shipped resolver reports an unapplied remote as KEEP_LOCAL; the invariant that
        // matters is that garbage from the network never overwrites anything and is logged.
        assertFalse(bothBad.applyRemote)
        assertTrue(bothBad.remoteDropped)

        val none = RealtimeMergeResolver.resolve(good, null)
        assertEquals(RealtimeMergeResolver.Decision.NO_REMOTE_NODE, none.decision)
        assertEquals(RealtimeMergeResolver.Action.NO_OP, none.action)
        val firstTombstone = RealtimeMergeResolver.resolve(null, node(9L, 1L, 9L))
        assertEquals(RealtimeMergeResolver.Decision.APPLIED_TOMBSTONE, firstTombstone.decision)
    }

    @Test
    fun nothingInTheVocabularyIsSilentlyMissing() {
        val names = RealtimeMergeResolver.Decision.values().map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "APPLIED_REMOTE", "APPLIED_TOMBSTONE", "KEPT_LOCAL_OLDER_REMOTE", "KEPT_LOCAL_TIE",
            "NO_REMOTE_NODE", "REJECTED_REMOTE_MALFORMED", "REJECTED_LOCAL_MALFORMED"
        )))
        val outcomes = MergeOutcome.values().map { it.name }.toSet()
        assertTrue(outcomes.containsAll(setOf("APPLIED_REMOTE", "APPLIED_TOMBSTONE", "KEPT_LOCAL_NEWER", "REJECTED_OLDER")))
        assertEquals(7, RealtimeMergeResolver.Decision.values().size)
        assertEquals(4, RealtimeMergeResolver.Action.values().size)
    }

    private fun node(u: Long, r: Long, d: Long?): RealtimeEnvelope =
        RealtimeEnvelope(e = A2aRealtimeSamples.FAKE_SEAL, u = u, r = r, d = d)

    private fun assertOutcomes(expected: MergeOutcome, actual: MergeDecision) =
        assertEquals(expected, actual.outcome)

    private fun assertNullLocal(decision: MergeDecision) {
        assertNull(decision.localU)
        assertNull(decision.localR)
    }
}
