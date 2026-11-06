package com.securevault.pro.presentation.screens.welcome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner asked for a boot screen long enough to actually read the capability chips.
 * At 1.25 s the handover fired while the last chip was still fading in, so the infographic row
 * flashed past. These guards pin the hold inside the requested window and prove every chip lands
 * with readable time left on the screen.
 */
class SplashTimingTest {

    private val viewModelSource: String by lazy { source("presentation/AppViewModel.kt") }
    private val screenSource: String by lazy { source("presentation/screens/welcome/SplashScreen.kt") }

    @Test
    fun `the boot screen holds for two to three seconds`() {
        assertTrue(
            "the owner asked for 2-3 seconds, got ${SplashTiming.HOLD_MS} ms",
            SplashTiming.HOLD_MS in 2_000L..3_000L
        )
    }

    @Test
    fun `every chip settles with at least half a second of screen left`() {
        val settled = SplashTiming.LAST_CHIP_SETTLED_MS
        assertTrue(
            "the last chip lands at $settled ms, after the ${SplashTiming.HOLD_MS} ms handover",
            settled < SplashTiming.HOLD_MS
        )
        assertTrue(
            "the chips need readable time before the handover, only had ${SplashTiming.HOLD_MS - settled} ms",
            SplashTiming.HOLD_MS - settled >= 500L
        )
    }

    @Test
    fun `the gate and the chip cascade both read the shared timing`() {
        assertTrue(
            "AppViewModel must delay on the contract, not a literal",
            viewModelSource.contains("delay(SplashTiming.HOLD_MS)")
        )
        assertTrue(
            "the chip animation must stagger from the contract",
            screenSource.contains("SplashTiming.chipDelayMs(index)")
        )
        assertTrue(
            "the chip glide-in duration must come from the contract",
            screenSource.contains("SplashTiming.CHIP_RUN_MS,")
        )
    }

    @Test
    fun `the chip count the screen lays out is the count the timing staggers`() {
        val laidOut = Regex("SplashFeature\\(").findAll(screenSource).count() - 1
        assertEquals(
            "SplashTiming.CHIP_COUNT must cover every chip the splash builds",
            laidOut,
            SplashTiming.CHIP_COUNT
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/securevault/pro/$relative"),
            File("app/src/main/java/com/securevault/pro/$relative"),
            File("../src/main/java/com/securevault/pro/$relative")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw IllegalStateException("cannot locate $relative from ${File(".").absolutePath}")
    }
}
