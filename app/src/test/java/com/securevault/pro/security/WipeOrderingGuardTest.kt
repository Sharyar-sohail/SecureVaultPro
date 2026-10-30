package com.securevault.pro.security

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The two wipe rules that are load-bearing for the product, pinned to source.
 *
 * 1. **A copy exists before anything is destroyed.** "Erase all data" uploads the sealed vault to
 *    a *visible* Drive archive first and aborts the whole erase - leaving the device untouched -
 *    when that upload fails or stalls. Erasing first and archiving second is the bug this guards.
 * 2. **A wiped device stops sealing.** The realtime engine and the local vault key are released on
 *    every path out of the wipe, including the ones where a cloud step threw.
 *
 * Reading the source is deliberate: the wipe needs Drive, Keystore and a signed-in account, none
 * of which exist in a JVM unit test, and the failure mode being prevented is a future edit
 * reordering the steps - which a behaviour test on a machine with no emulator cannot catch.
 */
class WipeOrderingGuardTest {

    private val wipeService: File? by lazy {
        listOf(
            File("src/main/java/com/securevault/pro/security/LocalWipeService.kt"),
            File("app/src/main/java/com/securevault/pro/security/LocalWipeService.kt")
        ).firstOrNull { it.isFile }
    }

    @Test
    fun `the archive is uploaded before anything local is erased`() {
        val body = wipeAllBody()
        val archive = body.indexOf("archiveVaultSnapshot")
        val localWipe = body.indexOf("repository.wipeAllData")
        assertTrue("wipeAll must archive the vault", archive >= 0)
        assertTrue("wipeAll must erase the local vault", localWipe >= 0)
        assertTrue(
            "The pre-wipe archive must be uploaded before any local data is destroyed " +
                "(archive at $archive, local wipe at $localWipe).",
            archive < localWipe
        )
    }

    @Test
    fun `a failed archive aborts the erase`() {
        val body = wipeAllBody()
        val abort = body.substringAfter(archiveFailureBranch())
        assertTrue("The abort must report that nothing was erased", abort.contains("Nothing was erased"))
        assertTrue(
            "The abort must report localCleared = false so the UI cannot claim a wipe happened",
            abort.contains("localCleared = false")
        )
        assertTrue(
            "The abort must return before the local erase runs",
            body.indexOf("return@withContext") < body.indexOf("repository.wipeAllData")
        )
    }

    @Test
    fun `the cloud copies are erased after the local vault`() {
        val body = wipeAllBody()
        val localWipe = body.indexOf("repository.wipeAllData")
        val cloudErase = body.indexOf("deleteAllCloudBackups")
        assertTrue("wipeAll must clear the Drive appDataFolder copies", cloudErase >= 0)
        assertTrue(
            "Order is archive -> local wipe -> cloud erase (local at $localWipe, cloud at $cloudErase)",
            localWipe < cloudErase
        )
    }

    @Test
    fun `the realtime engine and vault key are always released`() {
        val text = requireSource().readText()
        val body = wipeAllBody()
        assertTrue(
            "The key release must sit in a finally block, so a throwing cloud step cannot skip it",
            Regex("finally\\s*\\{[\\s\\S]{0,400}releaseRealtimeKeyring\\(\\)").containsMatchIn(body)
        )
        val release = text.substringAfter("private suspend fun releaseRealtimeKeyring")
        assertTrue("stopAndClear must be awaited - it drops the device vault key", release.contains("stopAndClear"))
        assertTrue("the keyring's own device copy must be cleared", release.contains("clearDeviceKey"))
        assertTrue("a stalled engine must not hang the wipe", release.contains("attemptWithin"))
        assertTrue("a failed engine stop must be recorded", release.contains("noteFault"))
    }

    /**
     * There used to be a second, unarchived destruction path (`wipeLocalOnly`) that the dead man's
     * switch ran on a timer. It is gone on purpose: [wipeAll] is the only way local vault material
     * can be destroyed, and rule 1 above is the only thing standing between a stalled Drive and a
     * permanently lost vault.
     */
    @Test
    fun `no wipe path can destroy local data without an archive`() {
        val text = requireSource().readText()
        assertTrue(
            "wipeLocalOnly must not come back - it deleted the Keystore key with no copy made",
            !Regex("fun\\s+wipeLocalOnly").containsMatchIn(text)
        )
        val destructions = Regex("repository\\.wipeAllData\\(\\)").findAll(text).count()
        assertTrue(
            "Exactly one local destruction site may exist, inside the archive-gated wipeAll (found $destructions)",
            destructions == 1
        )
    }

    /** Every network step of the wipe is bounded, so a stalled Drive can never hang the flow. */
    @Test
    fun `every wipe network step has its own timeout`() {
        val text = requireSource().readText()
        for (step in listOf(
            "archiveVaultSnapshot", "deleteAllCloudBackups", "signOutOfDrive",
            "stopAndClear", "eraseCloudData"
        )) {
            val bounded = Regex(
                "(attemptWithin|withTimeout)\\s*\\([^)]*\\)\\s*\\{[^}]{0,160}" +
                    Regex.escape(step) + "\\("
            ).containsMatchIn(text)
            assertTrue("$step is not wrapped by a bounded runner (attemptWithin/withTimeout)", bounded)
        }
        assertTrue(
            "the bounded runner must re-throw a cancelled scope, never report it as a fault",
            Regex("catch\\s*\\(\\s*e:\\s*CancellationException\\s*\\)\\s*\\{\\s*throw e").containsMatchIn(text)
        )
    }

    /**
     * "Erase all data" used to leave the whole realtime tree — every module table plus the keyring
     * that opens it — on the account. The delete must run, must be bounded, and must only run once a
     * Drive archive exists, because deleting the keyring without a copy is unrecoverable by design.
     */
    @Test
    fun `the realtime sync tree is erased after the archive exists`() {
        val body = wipeAllBody()
        val archive = body.indexOf("archiveVaultSnapshot")
        val realtimeErase = body.indexOf("eraseCloudData")
        assertTrue("wipeAll must clear the realtime nodes, not only Drive", realtimeErase >= 0)
        assertTrue(
            "Order is archive -> realtime erase (archive at $archive, realtime at $realtimeErase)",
            archive < realtimeErase
        )
        assertTrue(
            "The realtime erase must be gated on a completed archive, never run on its own",
            Regex("archiveName\\s*!=\\s*null\\s*\\)\\s*\\{[\\s\\S]{0,200}eraseCloudData").containsMatchIn(body)
        )
        assertTrue(
            "A stalled realtime erase must still report the count and the reason to the UI",
            body.contains("realtimeDeleted") && body.contains("REALTIME_ERASE_TIMEOUT_MS")
        )
    }

    /** The engine delete is bounded and removes all three roots, not just the record tables. */
    @Test
    fun `the engine erase covers data meta and keyring`() {
        val engine = listOf(
            File("src/main/java/com/securevault/pro/data/remote/realtime/RealtimeEngine.kt"),
            File("app/src/main/java/com/securevault/pro/data/remote/realtime/RealtimeEngine.kt")
        ).firstOrNull { it.isFile }
        assumeTrue("RealtimeEngine.kt is not next to this run", engine != null)
        val erase = engine!!.readText().substringAfter("suspend fun eraseCloudData")
        for (path in listOf("dataRoot", "meta(", "keyring(")) {
            assertTrue("The erase must remove the $path node", erase.contains("RealtimePaths.$path"))
        }
        assertTrue("Every removal is bounded", erase.contains("withTimeout"))
        assertTrue("The receive channel is dropped before deleting", erase.contains("quiet()"))
    }

    private fun archiveFailureBranch(): String =
        "if (archiveInfo == null) {"

    private fun requireSource(): File {
        val file = wipeService
        assumeTrue("LocalWipeService.kt is not next to this test run", file != null)
        return file!!
    }

    private fun wipeAllBody(): String = requireSource().readText()
        .substringAfter("suspend fun wipeAll(")
        .substringBefore("private suspend fun releaseRealtimeKeyring")
}
