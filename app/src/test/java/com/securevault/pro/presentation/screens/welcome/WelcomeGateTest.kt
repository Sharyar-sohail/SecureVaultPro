package com.securevault.pro.presentation.screens.welcome

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-login gate is a hard product rule, not a design preference: exactly three ways in,
 * and no path that can hold the screen open without an escape. Both were broken in the field —
 * a fourth tile crept in with the seed-phrase work, and the restore watchdog restarted on every
 * progress report so a live Drive restore never offered Cancel. These tests read the shipped
 * source because the rule lives in the composition, not in a type the JVM can instantiate.
 */
class WelcomeGateTest {

    private val source by lazy { welcomeSource() }

    @Test
    fun `the welcome screen offers exactly three ways in`() {
        val tiles = Regex("(?<!fun )WelcomeOption\\(").findAll(source).count()
        assertEquals("the pre-login gate is three tiles, no more", 3, tiles)
    }

    @Test
    fun `the headline counts the same three ways`() {
        assertTrue("headline still advertises a different count", source.contains("three ways in"))
        assertFalse(source.contains("four ways"))
    }

    @Test
    fun `every tile is disabled only by this screen's own attempt`() {
        val gates = Regex("enabled = ([^,]+),").findAll(source).map { it.groupValues[1].trim() }.toList()
        assertTrue("the tiles carry no enable gate at all", gates.isNotEmpty())
        for (gate in gates) {
            assertEquals("tile gate must be busy-or-cloud, found: $gate", "!state.busy && !cloudState.active", gate)
        }
    }

    @Test
    fun `seed phrase recovery stays reachable but off the tile list`() {
        assertTrue("seed recovery lost from the gate", source.contains("showSeedRestore = true"))
        assertTrue("seed tab lost from the import sheet", source.contains("\"Seed phrase\""))
        val seedTile = source.contains("title = \"Recover with your seed phrase\"")
        assertFalse("seed phrase must be a tab in the import sheet, not a fourth tile", seedTile)
    }

    @Test
    fun `the restore watchdog runs on wall time, not on the progress stream`() {
        val keyed = Regex("LaunchedEffect\\(busy[^)]*\\)").findAll(source).map { it.value }.toList()
        assertEquals("expected exactly one busy watchdog", 1, keyed.size)
        assertEquals("watchdog must not restart on progress", "LaunchedEffect(busy)", keyed.first())
        assertTrue("cancel control never becomes available", source.contains("elapsed >= CANCEL_GRACE_MS"))
        assertTrue("a stalled restore never auto-stops", source.contains("quiet >= AUTO_STOP_MS"))
    }

    @Test
    fun `the stop tick is shorter than the grace so cancel is timely`() {
        val tick = constLong("STOP_TICK_MS")
        val grace = constLong("CANCEL_GRACE_MS")
        val stop = constLong("AUTO_STOP_MS")
        assertTrue("tick $tick must be well under the $grace ms grace", tick in 1 until grace / 4)
        assertTrue("cancel appears before the auto-stop", grace < stop)
    }

    private fun constLong(name: String): Long {
        val match = Regex("private const val $name = ([0-9_]+)L").find(source)
            ?: throw IllegalStateException("$name is not a private const in the welcome screen")
        return match.groupValues[1].replace("_", "").toLong()
    }

    private fun welcomeSource(): String {
        val candidates = listOf(
            File("src/main/java/com/securevault/pro/presentation/screens/welcome/OnboardingScreen.kt"),
            File("app/src/main/java/com/securevault/pro/presentation/screens/welcome/OnboardingScreen.kt"),
            File("../src/main/java/com/securevault/pro/presentation/screens/welcome/OnboardingScreen.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw IllegalStateException("cannot locate OnboardingScreen.kt from ${File(".").absolutePath}")
    }
}
