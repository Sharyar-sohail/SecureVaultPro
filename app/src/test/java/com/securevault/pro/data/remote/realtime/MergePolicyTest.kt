package com.securevault.pro.data.remote.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conflict table of `RECORD-FORMAT-v1.md` §4, pinned as a pure unit test because the
 * delete-resurrection defect this project suffered historically can only be fixed by a rule that is
 * provably right off-device. Higher `u` wins; a tie goes to higher `r`; a tombstone beats a live
 * record at equal `u`; and an older `u` never overwrites a newer one, so a deleted record cannot come
 * back.
 */
class MergePolicyTest {

    private fun live(u: Long, r: Long) = MergeState(u, r, deleted = false)
    private fun tomb(u: Long, r: Long) = MergeState(u, r, deleted = true)

    @Test
    fun `a first-seen record is applied, live or deleted`() {
        val liveDecision = MergePolicy.decide(live(10, 1), null)
        assertEquals(MergeOutcome.APPLIED_REMOTE, liveDecision.outcome)
        assertTrue(liveDecision.applyRemote)
        assertFalse(liveDecision.deleteWins)

        val tombDecision = MergePolicy.decide(tomb(10, 1), null)
        assertEquals(MergeOutcome.APPLIED_TOMBSTONE, tombDecision.outcome)
        assertTrue(tombDecision.applyRemote)
        assertTrue(tombDecision.deleteWins)
        assertNull(tombDecision.localU)
    }

    @Test
    fun `higher u wins outright, and an older u is always rejected`() {
        val newerRemote = MergePolicy.decide(live(20, 1), live(10, 9))
        assertEquals(MergeOutcome.APPLIED_REMOTE, newerRemote.outcome)
        assertTrue(newerRemote.applyRemote)

        val newerRemoteDelete = MergePolicy.decide(tomb(20, 1), live(10, 9))
        assertEquals(MergeOutcome.APPLIED_TOMBSTONE, newerRemoteDelete.outcome)

        // Even a *newer* revision with an *older* clock loses — `u` dominates `r`.
        val olderRemote = MergePolicy.decide(live(5, 999), live(10, 1))
        assertEquals(MergeOutcome.REJECTED_OLDER, olderRemote.outcome)
        assertFalse(olderRemote.applyRemote)
    }

    @Test
    fun `a tombstone beats a live record at equal u, whichever side it is on`() {
        val remoteTombstone = MergePolicy.decide(tomb(10, 3), live(10, 9))
        assertEquals(MergeOutcome.APPLIED_TOMBSTONE, remoteTombstone.outcome)
        assertTrue(remoteTombstone.applyRemote)

        // Local tombstone at equal u must win — this is the resurrection guard: a late live node from
        // another device does not revive a deleted record.
        val localTombstone = MergePolicy.decide(live(10, 9), tomb(10, 1))
        assertEquals(MergeOutcome.KEPT_LOCAL_NEWER, localTombstone.outcome)
        assertFalse(localTombstone.applyRemote)
        assertTrue(localTombstone.deleteWins)
    }

    @Test
    fun `equal u and equal liveness breaks the tie on r, keeping local on an exact tie`() {
        val remoteHigherR = MergePolicy.decide(live(10, 5), live(10, 2))
        assertEquals(MergeOutcome.APPLIED_REMOTE, remoteHigherR.outcome)

        val localHigherR = MergePolicy.decide(live(10, 2), live(10, 5))
        assertEquals(MergeOutcome.KEPT_LOCAL_NEWER, localHigherR.outcome)
        assertFalse(localHigherR.applyRemote)

        val tie = MergePolicy.decide(live(10, 4), live(10, 4))
        assertEquals(MergeOutcome.KEPT_LOCAL_NEWER, tie.outcome)
        assertFalse(tie.applyRemote)
    }

    @Test
    fun `the decision reports the clocks that settled it for logging`() {
        val d = MergePolicy.decide(live(3, 4), live(7, 8))
        assertEquals(3L, d.incomingU)
        assertEquals(4L, d.incomingR)
        assertEquals(7L, d.localU)
        assertEquals(8L, d.localR)
        assertEquals(MergeOutcome.REJECTED_OLDER, d.outcome)
        assertFalse(d.deleteWins)
    }

    @Test
    fun `a deleted record never resurrects under any older-clock remote`() {
        // Local is already gone (a tombstone at a recent u); an older live node must not bring it back.
        val decision = MergePolicy.decide(live(100, 1), tomb(500, 1))
        assertEquals(MergeOutcome.REJECTED_OLDER, decision.outcome)
        assertFalse(decision.applyRemote)
        assertTrue("the record stays deleted", decision.deleteWins)
    }
}
