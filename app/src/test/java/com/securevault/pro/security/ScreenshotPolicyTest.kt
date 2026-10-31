package com.securevault.pro.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screenshots are blocked unless the owner explicitly allows them.
 *
 * The preference is stored as `screenshot_blocked` so an install that has never touched the
 * switch defaults to `true`. The Settings control is the inverse ("Allow screenshots"), which
 * must start OFF. These read the shipped source because the policy lives in three places that
 * have to agree: the default, the switch, and the window flag.
 */
class ScreenshotPolicyTest {

    private val appLock by lazy { source("security/AppLockManager.kt") }
    private val settingsState by lazy { source("presentation/screens/settings/SettingsViewModel.kt") }
    private val settingsScreen by lazy { source("presentation/screens/settings/SettingsScreen.kt") }
    private val app by lazy { source("presentation/navigation/VaultApp.kt") }
    private val activity by lazy { source("MainActivity.kt") }

    @Test
    fun `an untouched install blocks screenshots`() {
        assertTrue(
            "the stored preference must default to blocked",
            appLock.contains("it[Keys.SCREENSHOT_BLOCKED] ?: true")
        )
        assertTrue(
            "the settings state must start blocked",
            settingsState.contains("val screenshotBlocked: Boolean = true")
        )
    }

    @Test
    fun `the settings switch is an opt-in to allow screenshots`() {
        assertTrue("the control must be named for allowing", settingsScreen.contains("title = \"Allow screenshots\""))
        assertTrue("the switch must show the inverse of the block flag", settingsScreen.contains("checked = !state.screenshotBlocked"))
        assertTrue("the switch must write the inverse", settingsScreen.contains("onChanged = viewModel::setAllowScreenshots"))
        assertTrue("the setter must invert into the stored flag", settingsState.contains("setScreenshotBlocked(!enabled)"))
        assertFalse("the old block-labelled control is gone", settingsScreen.contains("title = \"Block screenshots\""))
    }

    @Test
    fun `the login and recovery surfaces stay photographable`() {
        assertFalse(
            "the activity must not raise FLAG_SECURE before the vault is open: welcome, setup, " +
                "lock and rescue all live in that window and the owner has to be able to report them",
            activity.contains("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
        )
        assertTrue(
            "the rescue report must clear any flag the vault left behind",
            activity.contains("SideEffect { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }")
        )
    }

    @Test
    fun `FLAG_SECURE covers the unlocked vault only`() {
        assertTrue(
            "the flag must be tied to the unlocked stage as well as the preference",
            app.contains("val protect = state.stage == AppStage.UNLOCKED && screenshotBlocked && !CrashLogger.safeRender")
        )
        assertTrue(
            "the flag must still be raised for the unlocked vault",
            app.contains("activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
        )
        assertTrue(
            "every other stage must clear it",
            app.contains("activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)")
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
