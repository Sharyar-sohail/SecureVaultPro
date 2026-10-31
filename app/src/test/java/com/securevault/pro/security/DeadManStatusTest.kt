package com.securevault.pro.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The countdown arithmetic behind the dead man's switch, pinned as plain JVM tests.
 *
 * These are the edges that decide whether the switch acts at all: an unarmed switch, an armed one
 * that has never had a check-in, the exact deadline instant, and one millisecond either side. The
 * Android/Drive enforcement path is deliberately not tested here - it cannot run without a
 * signed-in account, and its safety comes from aborting rather than from arithmetic.
 */
class DeadManStatusTest {

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val CHECK_IN = 1_700_000_000_000L
    }

    @Test
    fun `an unarmed switch with no check in is never due and shows the full interval`() {
        val status = DeadManStatus(armed = false, intervalDays = 7, lastCheckInAt = 0L)

        assertEquals(0L, status.dueAt())
        assertEquals(7, status.daysRemaining(now = CHECK_IN))
        assertFalse(status.isDue(now = Long.MAX_VALUE))
    }

    @Test
    fun `an armed switch with no check in has no deadline to enforce`() {
        val status = DeadManStatus(armed = true, intervalDays = 7, lastCheckInAt = 0L)

        assertEquals(0L, status.dueAt())
        assertEquals(7, status.daysRemaining(now = CHECK_IN))
        assertFalse("A missing check-in must never be read as overdue", status.isDue(now = Long.MAX_VALUE))
    }

    @Test
    fun `the deadline is the check in plus the interval in whole days`() {
        val status = DeadManStatus(armed = true, intervalDays = 3, lastCheckInAt = CHECK_IN)

        assertEquals(CHECK_IN + 3L * DAY_MS, status.dueAt())
    }

    @Test
    fun `exactly at the deadline the switch is due and zero days remain`() {
        val status = DeadManStatus(armed = true, intervalDays = 5, lastCheckInAt = CHECK_IN)
        val deadline = status.dueAt()

        assertTrue(status.isDue(now = deadline))
        assertEquals(0, status.daysRemaining(now = deadline))
    }

    @Test
    fun `one millisecond before the deadline is not due and a day still remains`() {
        val status = DeadManStatus(armed = true, intervalDays = 5, lastCheckInAt = CHECK_IN)
        val justBefore = status.dueAt() - 1L

        assertFalse(status.isDue(now = justBefore))
        assertEquals(1, status.daysRemaining(now = justBefore))
    }

    @Test
    fun `one millisecond after the deadline is overdue and zero days remain`() {
        val status = DeadManStatus(armed = true, intervalDays = 5, lastCheckInAt = CHECK_IN)
        val justAfter = status.dueAt() + 1L

        assertTrue(status.isDue(now = justAfter))
        assertEquals(0, status.daysRemaining(now = justAfter))
    }

    @Test
    fun `a partial day rounds up so the countdown never reads zero early`() {
        val status = DeadManStatus(armed = true, intervalDays = 2, lastCheckInAt = CHECK_IN)

        assertEquals(2, status.daysRemaining(now = CHECK_IN))
        assertEquals(2, status.daysRemaining(now = CHECK_IN + 1L))
        assertEquals(1, status.daysRemaining(now = CHECK_IN + DAY_MS))
        assertEquals(1, status.daysRemaining(now = status.dueAt() - 1L))
    }
}
