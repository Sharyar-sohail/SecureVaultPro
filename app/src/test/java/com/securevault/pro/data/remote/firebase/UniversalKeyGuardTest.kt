package com.securevault.pro.data.remote.firebase

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The rule that makes the web companion possible: the retired universal cloud key may open an old
 * backup, and it may never seal a new one.
 *
 * This is the same guard `tools/blogger-theme/generate.test.js` applies to the shipped theme
 * (line 36), turned on the writer. It reads sources rather than behaviour, because the failure it
 * prevents is a future edit reaching for the old constant again — the one change that would put a
 * secret in the browser bundle or split the two frontends back apart.
 */
class UniversalKeyGuardTest {

    /**
     * The shared prefix of every retired universal key, assembled from two fragments so that this
     * file can scan the identity package without tripping on its own search string. A runtime value,
     * never a constant, so the concatenation is not folded back into one literal.
     */
    private val banned = "SecureVaultPro" + "_Universal"

    private val bannedConstants = listOf(
        "PRIMARY_CLOUD_KEY",
        "LEGACY_CLOUD_KEY",
        "LEGACY_CLOUD_KEY_V1",
        "LEGACY_CLOUD_KEY_V2"
    )

    @Test
    fun `no file in the firebase package mentions the universal key`() {
        val root = sourceRoot()
        assumeTrue("the app sources are not next to this test run", root != null)
        val dir = File(root!!, "java/com/securevault/pro/data/remote/firebase")
        val files = dir.listFiles()?.filter { it.extension == "kt" }.orEmpty()
        assertTrue("the identity layer must exist: $dir", files.isNotEmpty())
        for (file in files) {
            assertFalse("${file.name} must never carry the universal key", file.readText().contains(banned))
        }
    }

    /**
     * Every seal call in the sync manager must be handed the user's own key. The retired constants
     * stay legal in the file — they are the read path for a vault that predates the keyring — but
     * they may not appear as an argument to a writer.
     */
    @Test
    fun `no new write path seals with the universal key`() {
        val file = syncManagerSource()
        val text = file.readText()

        assertTrue("the keyring must be the writer: ${file.name}", text.contains("sealingSecret()"))

        for (callee in listOf("createEnvelope(", "sealPayload(")) {
            val windows = argumentWindows(text, callee)
            assertTrue("looking for $callee found nothing - the guard would be vacuous", windows.isNotEmpty())
            for (window in windows) {
                for (constant in bannedConstants) {
                    assertFalse(
                        "$callee is a writer and must not be handed $constant:\n$window",
                        window.contains(constant)
                    )
                }
                assertFalse("$callee is a writer and must not inline the literal", window.contains(banned))
            }
        }
    }

    /**
     * The original reader also swept five shared literals. They are historical decryption
     * candidates now; a writer handed one of them would seal a new vault with a key the whole
     * product shares, so the same writer windows are checked against them.
     */
    @Test
    fun `no write path seals with a shared legacy literal`() {
        val text = syncManagerSource().readText()
        val shared = listOf(
            "SecureVaultPro_Master_Encryption_Key_2026",
            "SecureVaultProKey",
            "secure_vault_pro_secret",
            "vault_default_key",
            "SecureVaultProDefaultKey"
        )
        for (callee in listOf("createEnvelope(", "sealPayload(")) {
            val windows = argumentWindows(text, callee)
            assertTrue("looking for $callee found nothing - the guard would be vacuous", windows.isNotEmpty())
            for (window in windows) {
                for (literal in shared) {
                    assertFalse(
                        "$callee is a writer and must not be handed $literal:\n$window",
                        window.contains(literal)
                    )
                }
            }
        }
    }

    /** The read path is the other half of the rule: an old vault must still open. */
    @Test
    fun `the universal key survives only as a read candidate`() {
        // The exact key lives in LegacyKeyPlan so it can be tested without Android; the sync manager
        // must keep delegating to it rather than re-inventing a key of its own.
        val plan = sourceFile("java/com/securevault/pro/security/backup/LegacyKeyPlan.kt")
        assumeTrue("LegacyKeyPlan.kt is not next to this test run", plan != null)
        val candidates = plan!!.readText()
        assertTrue("old envelopes must still open", candidates.contains("PRIMARY_CLOUD_KEY"))
        assertTrue("the user's own key must be offered", candidates.contains("vaultSecret"))
        assertTrue(
            "the passphrase the owner typed must be offered, or a reinstall has no secret at all",
            candidates.contains("passphrase")
        )
        assertFalse("opening candidates must not gain a writer role", candidates.contains("createEnvelope"))
        assertFalse("opening candidates must not gain a writer role", candidates.contains("sealPayload"))

        val sync = syncManagerSource().readText()
        assertTrue("the sync manager must use the shared plan", sync.contains("LegacyKeyPlan.exactSecret("))
    }

    /** The owner's rule, verbatim: one exact key per file, never a numbered sweep. */
    @Test
    fun `a restore names one exact key and never builds a candidate list`() {
        val sync = syncManagerSource().readText()
        assertTrue(
            "the copy's own key is resolved by the marker plus one device fact",
            sync.contains("LegacyKeyPlan.exactSecret(")
        )
        assertTrue("and handed to the opener as a single secret", sync.contains("openExact("))
        for (sweep in listOf("LegacyKeyPlan.ordered(", "LegacyKeyPlan.restoreChain(", "buildSecrets(")) {
            assertFalse("the restore must not regain a candidate list: $sweep", sync.contains(sweep))
        }

        val plan = sourceFile("java/com/securevault/pro/security/backup/LegacyKeyPlan.kt")
        if (plan != null) {
            val text = plan.readText()
            assertTrue(
                "the exact key 3.1.9 wrote Drive copies with must stay the fallback",
                text.contains("PRIMARY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY")
            )
            for (retired in listOf("LEGACY_CLOUD_KEY_V1", "LEGACY_CLOUD_KEY_V2", "LEGACY_CLOUD_KEY ")) {
                assertFalse("the retired sweep keys must be gone: $retired", text.contains(retired))
            }
        }
    }

    /**
     * The overlay a restore shows is the owner's language, never a note about the code.
     *
     * The regression this pins is the developer sentence that shipped in 3.5.3 — the loading screen
     * printed how the pipeline was implemented ("the way its 3.1.9 reader did") and the failure box
     * counted rejected keys. Both are internal facts; the owner needs neither, and a key counter is
     * the tell that a sweep came back.
     */
    @Test
    fun `a restore tells the owner nothing but plain language`() {
        val sync = syncManagerSource().readText()
        assertFalse(
            "internal implementation notes must never reach the screen",
            sync.contains("3.1.9 reader")
        )
        assertFalse(sync.contains("Opening the backup the way"))
        assertFalse("the overlay must never print a key counter again", sync.contains("Exact legacy key"))
        assertFalse("the progress overlay must not advertise a derivation total", sync.contains("of \$total"))
        assertTrue(
            "the restore line must be a plain user message",
            sync.contains("Restoring your secure data...")
        )

        for (call in overlayCalls(sync)) {
            for (banned in listOf(
                "PBKDF2", "AES", "GCM", "600,000", "iteration", "derivation", "keyring",
                "salt", "envelope", "3.1.9", "candidate"
            )) {
                assertFalse(
                    "the progress overlay is owner-facing and must not say \"$banned\": $call",
                    call.contains(banned, ignoreCase = true)
                )
            }
        }

        val setup = sourceFile("java/com/securevault/pro/presentation/screens/welcome/SetupViewModel.kt")
        assumeTrue("SetupViewModel.kt is not next to this test run", setup != null)
        val text = setup!!.readText()
        assertFalse(
            "onboarding must not prompt for a master password to open Drive copies",
            text.contains("sealed with this account's own vault key")
        )
        for (prompt in listOf("Enter the master password", "enter the master password")) {
            assertFalse("a Drive restore must never ask for a password: $prompt", text.contains(prompt))
        }
    }

    /**
     * Reconnecting to Drive after the master password is chosen happens off the setup screen.
     *
     * Signing in and finishing setup is what unwraps the account's vault key, so the copies sealed
     * with it become readable exactly then — but the fetch is a network call, and 3.5.3's regression
     * was running it in front of the PIN pad, which left the owner stuck on the login screen with no
     * sync. Creating the vault must hand over the session first and restore afterwards.
     */
    @Test
    fun `the pending drive restore runs after the vault is handed over, never before`() {
        val setup = sourceFile("java/com/securevault/pro/presentation/screens/welcome/SetupViewModel.kt")
        assumeTrue("SetupViewModel.kt is not next to this test run", setup != null)
        val text = setup!!.readText()
        assertTrue(
            "a Drive restore that needs the account key must be remembered",
            text.contains("private var pendingDriveEmail: String? = null")
        )
        assertFalse("no branch may fall back to asking for a secret", text.contains("restoreNeedsSecret = true"))

        val drive = section(text, "private suspend fun restoreFromDrive(", "fun onRestoreSecretChange")
        assertTrue("a key rejection must queue the Drive account", drive.contains("pendingDriveEmail = email"))
        assertTrue(
            "only a first-pass key rejection may queue it, and the queue must be read out to the UI",
            drive.contains("awaitsOwnUnlock = keyRejected && secret.isNullOrBlank()") &&
                drive.contains("driveRestoreQueued = awaitsOwnUnlock")
        )
        assertTrue("the Drive branch must never open a secret form", drive.contains("restoreNeedsSecret = false"))
        assertTrue(
            "a connected Drive with backups found must never be shown as an error box",
            drive.contains("noticeIsError = keyRejected && !awaitsOwnUnlock")
        )
        assertTrue(
            "a key rejection must still hand the flow forward, so the owner never sits on the welcome tiles",
            drive.contains("readyForSetup = keyRejected")
        )
        for (phrase in listOf("Setup vault", "setup vault", "tap Setup", "click Setup")) {
            assertFalse("the restore is automatic; the owner must not be sent to a button: $phrase",
                drive.contains(phrase))
        }

        val create = section(text, "fun createVault()", "private fun restoreInBackground(")
        assertTrue("creating the vault must hand the session over", create.contains("sessionManager.unlock("))
        assertTrue("the pending restore must be cleared", create.contains("pendingDriveEmail = null"))
        assertTrue(
            "the queued-restore notice must not outlive the handover",
            create.contains("driveRestoreQueued = false")
        )
        assertTrue(
            "the retry must run after the handover, so nothing sits between the PIN pad and the dashboard",
            create.indexOf("restoreInBackground(") > create.indexOf("sessionManager.unlock(")
        )

        val retry = section(text, "private fun restoreInBackground(", "private fun validate(state: SetupUiState)")
        assertTrue(
            "the master password must reach the restore as the keyring secret",
            retry.contains("restoreAnyCloudBackup(email, passphrase = masterPassword)")
        )
        val cap = Regex("const val DRIVE_RETRY_TIMEOUT_MS = ([0-9_]+)L").find(text)
        assertTrue("the retry must be time-capped so it can never hang setup", cap != null)
        val millis = cap!!.groupValues[1].replace("_", "").toLong()
        assertTrue("a $millis ms retry cap must outlast one keyring fetch", millis >= 45_000L)
        assertTrue("a $millis ms retry cap must not be longer than a patient re-open", millis <= 180_000L)
        for (prompt in listOf("Enter the master password", "enter the master password", "type your password")) {
            assertFalse("the retry must report, never prompt: $prompt", retry.contains(prompt))
        }

        val file = section(text, "fun importLocalFile(", "fun createVault()")
        assertTrue(
            "a passphrase-sealed local export still needs the typed passphrase",
            file.contains("FILE_SECRET_PROMPT") && file.contains("restoreNeedsSecret = needsSecret")
        )
    }

    /**
     * Replacing the backup slot in place is how an owner's vault got overwritten by a vault they had
     * not restored. Every slot write now goes through the guard, which preserves bytes it cannot open
     * before it replaces them — so a direct upload over the slot's own file id is the regression to
     * watch for.
     */
    @Test
    fun `no slot write skips the preserve-before-overwrite guard`() {
        val text = syncManagerSource().readText()
        assertTrue("slot writes must go through the guard", text.contains("writeSlot("))
        for (slot in listOf("PRIMARY_BACKUP_NAME", "XML_BACKUP_NAME")) {
            assertFalse(
                "an upload over $slot's existing id would replace a vault unreadable by this device",
                Regex("driveService\\.upload\\(\\s*$slot[^)]*\\?\\.id").containsMatchIn(text)
            )
        }
    }

    /**
     * The theme is what the browser runs. If the literal ever reaches it, every vault in the product
     * is readable by anyone who views source — which is why the generator test forbids it too.
     */
    @Test
    fun `the blogger theme never carries the universal key`() {
        val theme = sourceFile("assets/blogger_theme.xml")
        assumeTrue("the shipped theme is not next to this test run", theme != null)
        val text = theme!!.readText()
        assertFalse("the browser bundle must not contain $banned", text.contains(banned))
        assertFalse("no signing secret may ride in the theme", text.contains("storePassword"))
    }

    /**
     * The realtime engine is the newest writer and the most likely place for a future edit to reach
     * for an old constant, because it is the code that seals individual records. Nothing in it may
     * name the retired key in any form — not the literal, not a constant — and it must seal through
     * the keyring. Absent while that package is still being written, so the guard cannot be made
     * vacuous by an empty directory either.
     */
    @Test
    fun `the realtime engine never carries the universal key`() {
        val files = packageFiles("data/remote/realtime")
        assumeTrue("the realtime engine is not in this snapshot yet", files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            assertFalse("${file.name} must never carry the universal key", text.contains(banned))
            for (constant in bannedConstants) {
                assertFalse("${file.name} must not name the retired constant $constant", text.contains(constant))
            }
        }
        val sealsFromTheKeyring = files.any { file ->
            file.readText().let {
                it.contains("Keyring") || it.contains("VaultKey") || it.contains("sealingSecret")
            }
        }
        assertTrue(
            "record sealing must be handed the per-user keyring, not a literal: " +
                files.joinToString { it.name },
            sealsFromTheKeyring
        )
    }

    /**
     * The security package holds the wipe and the keystore. A retired shared secret surfacing
     * there would mean the erase path itself falls back to a key everyone shares.
     */
    @Test
    fun `the security package never carries the universal key`() {
        val files = packageFiles("security")
        assumeTrue("the security package is not in this snapshot", files.isNotEmpty())
        for (file in files) {
            assertFalse(
                "${file.name} must never carry the universal key",
                file.readText().contains(banned)
            )
        }
    }

    /** Every `.kt` file of one package under `app/src/main/java/com/securevault/pro`, or empty. */
    private fun packageFiles(relative: String): List<File> {
        val dir = sourceRoot()?.let { File(it, "java/com/securevault/pro/$relative") } ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.extension == "kt" }.orEmpty()
    }

    /**
     * The sync manager is the only writer of cloud envelopes, so it is the file the rule is checked
     * on. Absent (a job that ships compiled output only) skips rather than passes silently.
     */
    private fun syncManagerSource(): File {
        val file = sourceFile("java/com/securevault/pro/data/remote/google/CloudSyncManager.kt")
        assumeTrue("CloudSyncManager.kt is not next to this test run", file != null)
        return file!!
    }

    /**
     * One function's source, from its signature to the next declaration, so an assertion about the
     * Drive branch cannot be satisfied by the file branch and vice versa.
     */
    private fun section(text: String, signature: String, endMarker: String): String {
        val start = text.indexOf(signature)
        assertTrue("expected $signature in the source", start >= 0)
        return text.substring(start).substringBefore(endMarker)
    }

    private fun argumentWindows(text: String, callee: String): List<String> {        val windows = mutableListOf<String>()
        var search = 0
        while (true) {
            val hit = text.indexOf(callee, search)
            if (hit < 0) break
            val open = hit + callee.length - 1
            var depth = 0
            var cursor = open
            var closed = -1
            while (cursor < text.length) {
                when (text[cursor]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            closed = cursor
                            break
                        }
                    }
                }
                cursor++
            }
            if (closed > open) windows += text.substring(open, closed + 1).whitespaced()
            search = hit + callee.length
        }
        return windows
    }

    /** Collapses the newlines in a multi-line call so a failure message stays readable. */
    private fun String.whitespaced(): String = replace(Regex("\\s+"), " ")

    /**
     * The text of every progress overlay: the string literals handed to `update(...)`.
     *
     * Only literals are collected, because a subtitle that is a variable — the file being
     * downloaded, the current import stage — is not this file's wording to get wrong.
     */
    private fun overlayCalls(text: String): List<String> =
        argumentWindows(text, "update(")
            .map { window ->
                Regex("\"(?:[^\"\\\\]|\\\\.)*\"").findAll(window).joinToString(" ") { it.value }
            }
            .filter { it.isNotBlank() }

    /**
     * Null when the tree is not next to the test run (a job that ships compiled output only);
     * callers assume rather than fail, because the layout of another machine is not a defect in
     * this code. Everything the sources do contain is asserted strictly.
     */
    private fun sourceRoot(): File? = listOf(
        File("src/main"),
        File("app/src/main")
    ).firstOrNull { it.isDirectory }

    private fun sourceFile(relative: String): File? = sourceRoot()?.let { File(it, relative) }?.takeIf { it.isFile }
}
