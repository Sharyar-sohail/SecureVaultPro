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
    fun `FLAG_SECURE is on before the first frame and follows the preference`() {
        assertTrue(
            "the activity must protect the process from its first frame",
            activity.contains("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)")
        )
        assertTrue(
            "the composition must re-assert the flag while blocking is on",
            app.contains("if (screenshotBlocked && !CrashLogger.safeRender)")
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
