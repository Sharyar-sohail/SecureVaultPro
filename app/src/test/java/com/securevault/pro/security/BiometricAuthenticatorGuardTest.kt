package com.securevault.pro.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The lock-screen rule, enforced by test: unlocking this app is **face or fingerprint only**.
 *
 * Adding `DEVICE_CREDENTIAL` to a `BiometricPrompt` hands the operating system's own PIN /
 * pattern / password sheet to the vault and, on the device that regressed, the owner was left
 * staring at a lock screen that never led back into the app. `SPEC-v1.md` non-negotiable 6 keeps
 * sync out of it as well: no new surface may introduce a device-credential prompt.
 *
 * This is a source guard, not a behaviour test, because the failure it prevents is a future edit
 * reaching for the wider authenticator set - and Robolectric cannot drive a real biometric prompt.
 * `BiometricUtils` is the single choke point today, so the scan covers the whole of `app/src/main`
 * rather than trusting one file to stay the only caller.
 */
class BiometricAuthenticatorGuardTest {

    /**
     * Assembled from two fragments at runtime, never a `const`, so the concatenation is not folded
     * back into one literal and this file can never trip its own scan.
     */
    private val forbiddenToken = "DEVICE" + "_CREDENTIAL"

    private val forbiddenCalls = listOf(
        "setDeviceCredentialAllowed(true)",
        "createConfirmDeviceCredentialIntent"
    )

    @Test
    fun `no main source ever asks for device credential`() {
        val files = mainSources()
        assumeTrue("the app sources are not next to this test run", files.isNotEmpty())
        for (file in files) {
            val code = withoutComments(file.readText())
            assertFalse(
                "${file.name} must never widen unlock to the OS device credential " +
                    "($forbiddenToken): it replaces the biometric sheet with the phone's lock screen.",
                code.contains(forbiddenToken)
            )
            for (call in forbiddenCalls) {
                assertFalse("${file.name} calls $call", code.contains(call))
            }
        }
    }

    /**
     * Every prompt built in the app must state its authenticator set explicitly. An unset builder
     * inherits platform defaults, which is exactly how the regression arrived in the first place.
     */
    @Test
    fun `every biometric prompt declares an explicit authenticator set`() {
        val files = mainSources()
        assumeTrue("the app sources are not next to this test run", files.isNotEmpty())
        val builders = files.filter {
            withoutComments(it.readText()).contains("PromptInfo.Builder")
        }
        assertTrue(
            "expected at least one BiometricPrompt builder in app/src/main - " +
                "if this genuinely went away the guard would be vacuous",
            builders.isNotEmpty()
        )
        for (file in builders) {
            val code = withoutComments(file.readText())
            assertTrue(
                "${file.name} builds a PromptInfo without declaring setAllowedAuthenticators",
                code.contains("setAllowedAuthenticators(")
            )
            assertTrue(
                "${file.name} builds a PromptInfo without a way out (setNegativeButtonText)",
                code.contains("setNegativeButtonText(")
            )
        }
    }

    /**
     * The mask members themselves. Today there is exactly one, in `BiometricUtils`, and it names
     * biometrics only; a future `or Authenticators.PASSWORD` would pass a text scan for the token
     * above and still hand the vault to the OS credential sheet.
     */
    @Test
    fun `only biometric authenticator members are referenced`() {
        val code = mainSources()
            .joinToString("\n") { withoutComments(it.readText()) }
        assumeTrue("no app sources are next to this test run", code.isNotBlank())
        val members = Regex("Authenticators\\.[A-Za-z_]+")
            .findAll(code)
            .map { it.value.removePrefix("Authenticators.") }
            .filter { it.isNotBlank() }
            .toList()
        assertTrue("an authenticator mask must exist - otherwise this guard is vacuous", members.isNotEmpty())
        for (member in members) {
            assertTrue(
                "Unlock may only use biometrics; $member is not a biometric authenticator",
                member.startsWith("BIOMETRIC_")
            )
        }
    }

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main"), File("app/src/main"))
            .firstOrNull { it.isDirectory }
            ?: return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    /** Drops `//` notes (but keeps `https://` intact) and javadoc/KDoc comment lines. */
    private fun String.stripComments(): String = lines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") }
        .joinToString("\n") { line ->
            val cut = line.indexOf("//")
            if (cut > 0 && line[cut - 1] != ':') line.substring(0, cut) else line
        }

    private fun withoutComments(text: String): String = text.stripComments()
}
