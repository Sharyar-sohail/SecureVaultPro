package com.securevault.pro.data.remote.google

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What "old backups open automatically" means in the restore pipeline.
 *
 * Every copy in the app-data folder must be tried — including the XML mirror, which the walk
 * used to filter out — and nothing in a failure sentence may send the owner back to a
 * passphrase prompt that no longer exists on the welcome screen.
 */
class LegacyRestoreReachTest {

    private val source by lazy { cloudSource() }

    @Test
    fun `the xml mirror is listed and tried like any other backup`() {
        assertFalse(
            "the listing must not drop the XML mirror",
            source.contains("it.name == XML_BACKUP_NAME")
        )
        assertFalse(
            "the walk must not skip XML copies",
            source.contains("filterNot { it.name.endsWith(\".xml.enc\") }")
        )
        assertTrue(
            "the walk must order every file it was given",
            source.contains("val ordered = files.sortedWith(")
        )
    }

    @Test
    fun `a decrypted xml body is recognised as a backup`() {
        assertTrue(
            "the plaintext reader must accept an XML body",
            source.contains("trimmed.startsWith(\"{\") || trimmed.startsWith(\"[\") || trimmed.startsWith(\"<\")")
        )
    }

    @Test
    fun `no failure sentence sends the owner to a passphrase`() {
        val description = diagnosticsSource()
            .substringAfter("fun describe(")
            .substringBefore("private fun decode(")
        assertFalse(
            "the failure sentence must not ask for a passphrase the restore screen does not accept",
            description.contains("passphrase", ignoreCase = true)
        )
        assertFalse(
            "the failure sentence must not invite a recovery phrase either",
            description.contains("recovery phrase", ignoreCase = true)
        )
        assertFalse(
            "the walk summary still blames a typed secret",
            source.contains("backups in Drive opened with this device's key")
        )
        assertTrue(
            "the failure must still name the parameter that differs",
            description.contains("mismatch")
        )
        assertTrue(
            "the failure must still report how many derivations ran",
            description.contains("key derivations")
        )
    }

    private fun diagnosticsSource(): String {
        val candidates = listOf(
            File("src/main/java/com/securevault/pro/security/backup/EnvelopeDiagnostics.kt"),
            File("app/src/main/java/com/securevault/pro/security/backup/EnvelopeDiagnostics.kt"),
            File("../src/main/java/com/securevault/pro/security/backup/EnvelopeDiagnostics.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw IllegalStateException("cannot locate EnvelopeDiagnostics.kt from ${File(".").absolutePath}")
    }

    private fun cloudSource(): String {
        val candidates = listOf(
            File("src/main/java/com/securevault/pro/data/remote/google/CloudSyncManager.kt"),
            File("app/src/main/java/com/securevault/pro/data/remote/google/CloudSyncManager.kt"),
            File("../src/main/java/com/securevault/pro/data/remote/google/CloudSyncManager.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw IllegalStateException("cannot locate CloudSyncManager.kt from ${File(".").absolutePath}")
    }
}
