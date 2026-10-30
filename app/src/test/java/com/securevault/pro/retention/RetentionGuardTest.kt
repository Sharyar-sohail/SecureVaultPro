package com.securevault.pro.retention

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The owner's standing retention rule, pinned to the whole source tree.
 *
 * The rule, verbatim from the directive: *"DO NOT auto-delete any user data after 30 days. Do not
 * implement a 30-day trash bin. Users may be offline, and data must remain recoverable permanently
 * until the user manually deletes it."*
 *
 * This test exists because that rule was broken more than once and each time it was invisible in
 * review: a `pruneStale` in the share inbox, a keep-last-4 rotation in the Drive backup uploader, a
 * dead-man timer that erased the vault on launch, and a backup file that could ask for the vault to
 * be cleared by itself. None of those were intended as "auto-deletion"; all of them deleted user
 * data without the owner asking. A behaviour test cannot catch them on a machine with no emulator,
 * so the shapes of the calls themselves are what is forbidden here.
 *
 * The whitelist is the honest part. Deleting what the user pressed delete on, erasing the scratch
 * files this app made for itself, and removing a staged copy once it has been imported are all
 * legitimate, and are named rather than waved through.
 */
class RetentionGuardTest {

    private val sourceRoot: File? by lazy {
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java")
        ).firstOrNull { it.isDirectory }
    }

    /**
     * Patterns that mean "something deletes data on its own". Matched against code, not comments,
     * with the leading keyword so a doc sentence mentioning the old bug does not trip the guard.
     */
    private val forbidden: List<Pair<String, Regex>> = listOf(
        "a scheduled purge of stored data" to Regex(
            """fun\s+\w*(purge|prune|sweep|expire|trash|delete|wipe)\w*(Data|Records|Entries|Items|Backups|Snapshots|Installs|Imports|Trash|History|Old|Stale|Expired|Aged)\w*\s*\("""
        ),
        "a delete restricted to records older than a date" to Regex(
            """delete\w*\s*\([^)]*(olderThan|before|cutOff|threshold|retention|expired)""",
            RegexOption.IGNORE_CASE
        ),
        "a hard-coded retention window" to Regex("""(RETENTION_|TRASH_|MAX_SNAPSHOTS|STALE_DELETE|DELETE_AFTER)"""),
        "a backup rotation cap" to Regex("""pruneOldBackups|deleteOldSnapshots"""),
        "the unarchived local wipe" to Regex("""fun\s+wipeLocalOnly"""),
        "deleting by position in a queue" to Regex("""\.drop\(\s*1\s*\)\s*\.let\s*\{\s*\w*[Dd]elete""")
    )

    /**
     * Files whose deletions are of the allowed kind, and why. Every one of them is reachable only
     * from an explicit "erase" the owner pressed, and `LocalWipeService` archives first and aborts
     * the erase if the archive fails - see `WipeOrderingGuardTest`, which pins that ordering.
     */
    private val whitelisted: Set<String> = setOf(
        "LocalWipeService.kt",
        "VaultRepository.kt",
        "CloudSyncManager.kt",
        "GoogleDriveService.kt"
    )

    @Test
    fun `no source file auto-deletes user data on a schedule`() {
        val root = sourceRoot
        assumeTrue("no main source tree next to this test run", root != null)
        val offenders = mutableListOf<String>()
        root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (whitelisted.contains(file.name)) return@forEach
            file.readLines().forEachIndexed { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                for ((meaning, pattern) in forbidden) {
                    if (pattern.containsMatchIn(code)) {
                        offenders += "${file.relativeTo(root)}:${index + 1} — $meaning — $code"
                    }
                }
            }
        }
        assertTrue(
            "User data may only be destroyed by an explicit action on it. Found:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** The rule is only real if the guard actually reads the tree it claims to police. */
    @Test
    fun `the retention guard reads the whole source tree`() {
        val root = sourceRoot
        assumeTrue("no main source tree next to this test run", root != null)
        val kotlinFiles = root!!.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue("The guard expected the whole app source tree, but saw only $kotlinFiles files", kotlinFiles > 200)
    }

    /** Two named behaviours that must stay deleted, spelled out so a reviewer sees the intent. */
    @Test
    fun `the retired deletion paths stay retired`() {
        val root = sourceRoot
        assumeTrue("no main source tree next to this test run", root != null)
        val text = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        for (gone in listOf("purgeTrashBefore", "pruneStale", "deleteStaged(dropped)", "moveToTrash")) {
            assertTrue("`$gone` is an auto-deletion path that was removed on purpose; it must not come back",
                !text.contains(gone))
        }
    }
}
