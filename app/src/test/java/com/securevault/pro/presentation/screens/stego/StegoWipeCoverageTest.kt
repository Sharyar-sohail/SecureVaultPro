package com.securevault.pro.presentation.screens.stego

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-once ledger is device-local metadata about saved stego carriers, so "Erase all data"
 * has to clear it along with the images. [LocalWipeService] lives in the security package and
 * cannot import the stego store, so it clears the file by name — this guard keeps that name in
 * step with [StegoLedgerStore] instead of trusting a comment.
 */
class StegoWipeCoverageTest {

    private fun source(relative: String): String {
        val cwd = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(cwd)) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("app/src/main not found from $cwd")
        return File(root, relative).readText()
    }

    @Test
    fun `erase all data clears the same preferences file the ledger writes`() {
        val store = source(
            "app/src/main/java/com/securevault/pro/presentation/screens/stego/StegoLedgerStore.kt"
        )
        val wipe = source(
            "app/src/main/java/com/securevault/pro/security/LocalWipeService.kt"
        )
        val name = Regex("""PREFS_NAME\s*=\s*"([^"]+)"""")
            .find(store)?.groupValues?.get(1)
            ?: error("StegoLedgerStore no longer declares PREFS_NAME")
        assertTrue(
            "LocalWipeService must clear the ledger preferences file \"$name\"",
            wipe.contains("\"$name\"")
        )
    }
}
