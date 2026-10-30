package com.securevault.pro.presentation.crypto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's directive, enforced by the build: the Bitget API-key screen is gone from the crypto
 * section, and no screen in it may quietly grow a second one.
 *
 * The sealed-credential data layer stays — only the UI is removed — so this test reads the shipped
 * sources under `presentation/screens/crypto` rather than the whole app. Comments are skipped
 * because they draw nothing; a string literal or call that would put "API key" on screen is not.
 */
class BitgetApiKeyUiIsGoneTest {

    private val forbiddenFiles = listOf("CryptoVioletPanel.kt", "CryptoVioletViewModel.kt")

    /** Tokens that only ever belong to the removed API-key surface. */
    private val bannedTokens = listOf("bitget", "api key", "connect bitget", "read-only")

    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val nested = File(dir, "app")
            if (File(nested, "src/main/java").isDirectory) return nested
            dir = dir.parentFile
            hops++
        }
        return File("")
    }

    private fun cryptoSources(): List<File> {
        val root = File(moduleDir(), "src/main/java/com/securevault/pro/presentation/screens/crypto")
        return root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
    }

    private fun isComment(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    @Test
    fun `the crypto section still ships its wallet sources`() {
        val names = cryptoSources().map { it.name }
        assertTrue(
            "expected the crypto wallet sources, found $names",
            "CryptoWalletScreen.kt" in names && "CryptoWalletViewModel.kt" in names
        )
    }

    @Test
    fun `the API-key panel and its view model are deleted`() {
        val names = cryptoSources().map { it.name }
        for (file in forbiddenFiles) {
            assertFalse("$file must not exist in the crypto section", file in names)
        }
        assertEquals(
            "the crypto section should hold exactly the wallet screen and its view model",
            listOf("CryptoWalletScreen.kt", "CryptoWalletViewModel.kt"),
            names
        )
    }

    @Test
    fun `no crypto source puts an API key on screen`() {
        val offenders = cryptoSources().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                if (isComment(code) || code.isEmpty()) return@mapIndexedNotNull null
                val hit = bannedTokens.any { code.contains(it, ignoreCase = true) }
                if (hit) "${file.name}:${index + 1}: $code" else null
            }
        }
        assertTrue("API-key UI must stay removed, found:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
}
