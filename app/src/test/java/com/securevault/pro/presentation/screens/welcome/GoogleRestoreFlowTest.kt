package com.securevault.pro.presentation.screens.welcome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the rule the owner had to report twice: the Google account is the authentication.
 *
 * A tap on "Continue with Google" may open Google's own picker when this device has no usable
 * grant. It may never open a passphrase or seed screen, and a dead token may never be retried in
 * place — it is cleared so the next tap reaches the picker instead of repeating the same fault.
 */
class GoogleRestoreFlowTest {

    private val viewModel by lazy { source("SetupViewModel.kt") }
    private val screen by lazy { source("OnboardingScreen.kt") }

    @Test
    fun `the google tap is one entry point and it decides picker or restore`() {
        assertTrue(
            "the screen must ask the view model, not branch on a phrase dialog",
            screen.contains("viewModel.onGoogleTileTapped()")
        )
        assertTrue(
            "the view model needs the one-tap entry point",
            viewModel.contains("fun onGoogleTileTapped(): Boolean")
        )
        assertTrue(
            "an account without a Drive grant must still reach Google's picker",
            viewModel.contains("hasDriveAccess(")
        )
        val oneTapCalls = Regex("restoreFromDrive\\(email\\)").findAll(viewModel).count()
        assertTrue(
            "both the cached-account tap and the post-picker hand-off must restore without a phrase, found $oneTapCalls",
            oneTapCalls >= 2
        )
    }

    @Test
    fun `the restore path has no passphrase parameter and no passphrase state`() {
        val body = viewModel.substringAfter("private suspend fun restoreFromDrive(")
            .substringBefore("fun importLocalFile(")
        assertFalse(
            "the restore must not read any typed secret",
            body.contains("passphrase", ignoreCase = true)
        )
        assertTrue(
            "the walk must be handed nothing but the account",
            body.contains("restoreAnyCloudBackup(email)")
        )
        assertFalse("the gate still carries passphrase state", viewModel.contains("restorePassphrase"))
        assertFalse("the gate still exposes a passphrase setter", viewModel.contains("setRestorePassphrase"))
    }

    @Test
    fun `the screen offers no passphrase or seed entry point at all`() {
        assertFalse("a Drive phrase sheet is back", screen.contains("showDrivePassphraseDialog"))
        assertFalse("a passphrase field is back", screen.contains("SecurePasswordField"))
        assertFalse("the seed sheet is back", screen.contains("RecoverySeedSection"))
        assertFalse("a seed tab is back", screen.contains("\"Seed phrase\""))
    }

    @Test
    fun `a dead token is cleared so the next tap reaches the picker`() {
        assertTrue(
            "an authentication fault must be recognised",
            viewModel.contains("DriveFaults.needsFreshSignIn(")
        )
        assertTrue(
            "the dead credential must be dropped",
            viewModel.contains("cloudSyncManager.signOutOfDrive()")
        )
        assertTrue(
            "the owner must see what to do, not a raw status code",
            viewModel.contains("notice = DriveFaults.SIGN_IN_AGAIN")
        )
        val forced = Regex("forcePicker = true").findAll(viewModel).count()
        assertTrue("both consent-fault branches must force the picker next tap, found $forced", forced >= 2)
        assertTrue(
            "the tile must honour the forced picker instead of reusing a cached grant",
            viewModel.contains("val usable = !forcePicker &&")
        )
    }

    @Test
    fun `sign-in only demands the grant a restore actually needs`() {
        val service = sourceFrom("data/remote/google/GoogleDriveService.kt")
        val body = service.substringAfter("suspend fun completeSignIn(")
            .substringBefore("private fun signInFailure(")
        assertTrue(
            "app-data access is what a restore reads",
            body.contains("Scope(DriveScopes.DRIVE_APPDATA)")
        )
        assertFalse(
            "demanding the file scope here rejected a granted sign-in and replayed the same error",
            body.contains("Scope(DriveScopes.DRIVE_FILE)")
        )
    }

    @Test
    fun `the listing wait outlasts the drive call instead of faking a connection error`() {
        val timeout = Regex("const val LIST_TIMEOUT_MS = ([0-9_]+)L").find(viewModel)
            ?: throw IllegalStateException("LIST_TIMEOUT_MS is missing")
        val millis = timeout.groupValues[1].replace("_", "").toLong()
        assertTrue("a $millis ms list cut-off races the 45 s Drive call", millis >= 60_000L)
        assertFalse(
            "the misleading connection wording is gone",
            viewModel.contains("Drive is not answering. Check the connection")
        )
    }

    private fun source(name: String): String = sourceFrom("presentation/screens/welcome/$name")

    private fun sourceFrom(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/securevault/pro/$relative"),
            File("app/src/main/java/com/securevault/pro/$relative"),
            File("../src/main/java/com/securevault/pro/$relative")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw IllegalStateException("cannot locate $relative from ${File(".").absolutePath}")
    }
}
