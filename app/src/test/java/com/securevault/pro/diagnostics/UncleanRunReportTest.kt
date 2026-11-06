package com.securevault.pro.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's only instrument is the report on the screen, and the 3.6.8 one he photographed said
 * two untrue things: that the app died "on the vault database" (a background probe had overwritten
 * the screen record minutes earlier) and "after 4904842 ms" (a run length nobody can read). It also
 * named no artifact, because a death with no stack trace wrote no file.
 *
 * These guards pin the three things that make the next such report readable: the screen record
 * belongs to the screens, the dead run's own trail is quoted inside the report, and the report is
 * published where the Files app can reach it.
 */
class UncleanRunReportTest {

    private val logger: String by lazy { source("diagnostics/CrashLogger.kt") }
    private val app: String by lazy { source("VaultApplication.kt") }

    @Test
    fun `a run length reads as time a person can judge`() {
        assertEquals("41 s", RunLength.spoken(41_400L))
        assertEquals("12 min 7 s", RunLength.spoken(727_000L))
        assertEquals("1 h 21 min", RunLength.spoken(4_904_842L))
        assertEquals("an unknown time", RunLength.spoken(-1L))
    }

    @Test
    fun `startup steps never claim the screen the owner was standing on`() {
        assertFalse(
            "a probe runs off the UI thread; writing the screen record from one makes every " +
                "trace-less death look like a death on that subsystem",
            app.contains("noteStage")
        )
        assertEquals(
            "both probe helpers must record themselves as steps, not as stages",
            2,
            Regex("CrashLogger\\.noteProbe\\(name\\)").findAll(app).count()
        )
    }

    @Test
    fun `the trace-less report quotes the dead run and names a file`() {
        assertTrue(
            "the report has to carry the previous run's own steps — a native death leaves no frame",
            logger.contains("previousRunSteps()") &&
                logger.contains("Last steps of that run")
        )
        assertTrue(
            "a report with no stack trace still has to reach the Files app",
            logger.contains("publishToDownloads(appContext, report)")
        )
        assertTrue(
            "the trail path belongs in the report, since that file is the only witness",
            logger.contains("Full trail: ")
        )
        assertTrue(
            "the run length is quoted in words",
            logger.contains("RunLength.spoken(ranFor)")
        )
    }

    @Test
    fun `the steps are read before this run writes its own`() {
        val read = logger.indexOf("val steps = previousRunSteps()")
        val wrote = logger.indexOf("trail(\"launch: unclean")
        assertTrue("the report must quote the dead run's tail", read >= 0 && wrote >= 0)
        assertTrue(
            "reading the trail after this launch line lands would quote this run instead",
            read < wrote
        )
    }

    @Test
    fun `heavy work announces itself in the trail`() {
        val watched = mapOf(
            "data/wallet/CryptoBalanceService.kt" to "CrashLogger.noteEvent",
            "data/imports/WhatsAppImporter.kt" to "CrashLogger.noteEvent",
            "data/remote/google/CloudSyncManager.kt" to "CrashLogger.noteEvent",
            "presentation/screens/scanner/DocumentCameraSheet.kt" to "CrashLogger.noteStage"
        )
        watched.forEach { (path, marker) ->
            assertTrue(
                "$path is work that can outlive the process it started in; it must leave a line",
                source(path).contains(marker)
            )
        }
    }

    @Test
    fun `the screen record is written, and every route has a name for it`() {
        // The 3.7.0 audit wave added CrashLogger.noteScreen and then never called it, so the report
        // kept quoting a stage that says only "unlocked" for every screen inside the vault.
        val nav = source("presentation/navigation/VaultApp.kt")
        assertTrue(
            "the navigation host must publish the screen the owner is standing on, or lastPlace " +
                "falls back to the app state and the report cannot name the screen that died",
            nav.contains("CrashLogger.noteScreen(Routes.screenNameFor(currentRoute))")
        )
        val routes = source("presentation/navigation/Routes.kt")
        val declared = Regex("""const val ([A-Z_][A-Z_0-9]*) =""")
            .findAll(routes)
            .map { it.groupValues[1] }
            .toSet()
        val mapping = routes.substringAfter("fun screenNameFor(")
        val unmapped = declared.filter { !Regex("""\b$it ->""").containsMatchIn(mapping) }
        assertTrue(
            "these routes are reachable but screenNameFor would report them as a raw route: " +
                unmapped.joinToString(", "),
            unmapped.isEmpty()
        )
        assertTrue(
            "an unknown route still has to say something an owner can read",
            mapping.contains("else ->")
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
