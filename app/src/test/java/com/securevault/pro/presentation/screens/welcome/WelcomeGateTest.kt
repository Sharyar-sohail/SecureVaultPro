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
        val calls = source.split(Regex("(?<!fun )WelcomeOption\\(")).drop(1)
        assertEquals("expected three tile call sites", 3, calls.size)
        for (call in calls) {
            val gate = Regex("enabled = ([^,]+),").find(call)?.groupValues?.get(1)?.trim()
            assertEquals("tile gate must be busy-or-cloud, found: $gate", "!state.busy && !cloudState.active", gate)
        }
    }

    @Test
    fun `the password retry card is gated by a key mismatch, never shown up front`() {
        assertTrue(
            "a key-mismatch retry card is required so a keyring-sealed backup is not a dead end",
            source.contains("if (state.restoreNeedsSecret)")
        )
        val card = source.substringAfter("if (state.restoreNeedsSecret)")
            .substringBefore("Spacer(Modifier.height(30.dp))")
        assertTrue(
            "the retry action must need a non-blank secret and a free screen",
            card.contains("state.restoreSecret.trim().isNotEmpty() && !state.busy")
        )
        // The card may not contain any tile, so the three-ways-in gate stays exactly three.
        assertFalse("a tile crept into the retry card", card.contains("WelcomeOption("))
    }

    @Test
    fun `no phrase prompt of any kind stands between the owner and a restore`() {
        assertFalse("a seed sheet came back to the gate", source.contains("showSeedRestore"))
        assertFalse("a seed tab came back to the import sheet", source.contains("\"Seed phrase\""))
        assertFalse("a passphrase dialog came back to the gate", source.contains("showDrivePassphraseDialog"))
        assertFalse("the import sheet asks for a passphrase again", source.contains("Passphrase (optional)"))
        assertFalse("the seed section is drawn pre-login again", source.contains("RecoverySeedSection"))
        assertFalse("a secure password field is back on the gate", source.contains("SecurePasswordField"))
    }

    @Test
    fun `the import sheet opens a file first and asks for a secret only if the file needs one`() {
        assertTrue("the import dialog lost its file action", source.contains("backupPicker.launch(arrayOf(\"*/*\"))"))
        val dialog = source.substringAfter("if (showImportDialog)")
            .substringBefore("Only an attempt this screen started")
        assertFalse("the sheet must not ask before the file is chosen", dialog.contains("VaultTextField("))
        assertFalse(
            "the sheet may not promise that no secret is ever needed",
            dialog.contains("Nothing is typed")
        )
        assertTrue(
            "the sheet must say when a passphrase will be asked for",
            dialog.contains("asks for")
        )
    }

    @Test
    fun `the google tile carries no phrase in front of the restore`() {
        val tile = source.substringAfter("title = \"Continue with Google\"")
            .substringBefore("WelcomeOption(")
        assertTrue("the Google tap must use the one-tap restore", tile.contains("viewModel.onGoogleTileTapped()"))
        assertTrue("a new account must still reach Google's picker", tile.contains("launchGoogle()"))
        assertFalse("the Google tap must never open the passphrase sheet", tile.contains("showDrivePassphraseDialog = true"))
        assertFalse("no seed phrase may stand on the Google path", tile.contains("Seed phrase"))
        assertFalse("the tile copy must not promise a phrase prompt", tile.contains("with your passphrase if needed"))
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

    /**
     * A Google tap that finds backups must never leave the owner staring at the gate.
     *
     * 3.5.4 ended a successful Drive connection with a red card telling the owner to press
     * "Setup vault". The gate now hands over on its own whenever the ViewModel says a vault is
     * reachable, and the card only turns red for a genuine fault.
     */
    @Test
    fun `a connected drive hands the gate forward by itself and never in red`() {
        assertTrue(
            "the gate must advance on its own once a restore is reachable",
            source.contains("if (flowStarted && state.readyForSetup) onFinished()")
        )
        assertTrue(
            "the card's colour and icon must both follow the error state",
            source.contains("if (state.error != null || state.noticeIsError) VaultColor.debit else VaultColor.credit") &&
                source.contains("if (state.error != null || state.noticeIsError) Glyph.errorOutline else Glyph.checkCircle")
        )
        assertFalse("no tile may send the owner to a setup button", source.contains("Setup vault"))
        val card = source.substringAfter("if (state.restoreNeedsSecret)")
            .substringBefore("Spacer(Modifier.height(30.dp))")
        assertTrue("the only typed secret at the gate is an export passphrase", card.contains("Export passphrase"))
        assertFalse("the gate never asks for a master password for a cloud backup", card.contains("Master password"))
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
