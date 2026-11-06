package com.securevault.pro.data.remote.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The routing decision that replaced the old "every mutation re-scans all seven modules" behaviour.
 * A mutation that names its record ([MutationRouter.plan] with a valid segment and a non-blank id)
 * must resolve to exactly one record-scoped millisecond push for the right module; anything that
 * cannot name a record — a null module, a blank id, or a mistyped segment — must resolve to the
 * safety-net sweep, never to a push at some other node. This is provable off-device because
 * [MutationRouter] and [RealtimeModule] carry no Firebase or Android types, unlike the engine
 * itself, which cannot be instantiated on the JVM.
 */
class MutationRouterTest {

    @Test
    fun `a named record resolves to exactly one record-scoped push per module`() {
        val expected = listOf(
            "notes" to RealtimeModule.NOTES,
            "entries" to RealtimeModule.ENTRIES,
            "cards" to RealtimeModule.CARDS,
            "totps" to RealtimeModule.TOTPS,
            "contacts" to RealtimeModule.CONTACTS,
            "cryptoWallets" to RealtimeModule.CRYPTO_WALLETS,
            "files" to RealtimeModule.FILES
        )
        for ((segment, module) in expected) {
            val plan = MutationRouter.plan(segment, "record-1", deleted = false)
            assertEquals(PushPlan.Record(module, "record-1", deleted = false), plan)
            // The push targets the node the web reads: the plan's segment round-trips verbatim.
            assertEquals(segment, (plan as PushPlan.Record).module.segment)
        }
    }

    @Test
    fun `a named delete resolves to the tombstone push for that same record`() {
        val plan = MutationRouter.plan("entries", "pw-42", deleted = true)
        assertEquals(PushPlan.Record(RealtimeModule.ENTRIES, "pw-42", deleted = true), plan)
        // One plan, one push: it is a Record, so the router never also demands a blind sweep write.
        assertNotEquals(PushPlan.Sweep, plan)
    }

    @Test
    fun `an identity-free signal still resolves to the sweep`() {
        // Bulk imports, restores and wipes emit exactly this: no module, no id.
        assertSame(PushPlan.Sweep, MutationRouter.plan(null, null, deleted = false))
        assertSame(PushPlan.Sweep, MutationRouter.plan(null, null, deleted = true))
        // A module without an id cannot aim a single-node write either.
        assertSame(PushPlan.Sweep, MutationRouter.plan("notes", null, deleted = false))
        assertSame(PushPlan.Sweep, MutationRouter.plan("notes", "", deleted = false))
        assertSame(PushPlan.Sweep, MutationRouter.plan("notes", "   ", deleted = false))
        // An id without a module is equally unnamed.
        assertSame(PushPlan.Sweep, MutationRouter.plan(null, "record-1", deleted = false))
    }

    @Test
    fun `a bogus segment is rejected so a call-site typo cannot aim at the wrong node`() {
        // A typo ("passwords", "wallets") or wrong case ("Entries") must fall back to the sweep,
        // because the engine validates with RealtimeModule.fromSegment instead of trusting callers.
        assertSame(PushPlan.Sweep, MutationRouter.plan("passwords", "record-1", deleted = false))
        assertSame(PushPlan.Sweep, MutationRouter.plan("wallets", "record-1", deleted = false))
        assertSame(PushPlan.Sweep, MutationRouter.plan("Entries", "record-1", deleted = false))
        assertSame(PushPlan.Sweep, MutationRouter.plan("entry", "record-1", deleted = false))

        // The validator itself: only the seven frozen segments resolve, and they are exactly seven.
        assertNull(RealtimeModule.fromSegment("passwords"))
        assertNull(RealtimeModule.fromSegment("Entries"))
        assertNull(RealtimeModule.fromSegment(""))
        assertNull(RealtimeModule.fromSegment(null))
        assertEquals(7, RealtimeModule.SEGMENTS.size)
        for (segment in RealtimeModule.SEGMENTS) {
            assertEquals(segment, RealtimeModule.fromSegment(segment)?.segment)
        }
        assertEquals(true, RealtimeModule.isKnownSegment("cryptoWallets"))
        assertEquals(false, RealtimeModule.isKnownSegment("crypto_wallets"))
    }
}
