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
        // The opening order lives in LegacyKeyPlan so it can be tested without Android; the sync
        // manager must keep delegating to it rather than re-inventing an order of its own.
        val plan = sourceFile("java/com/securevault/pro/security/backup/LegacyKeyPlan.kt")
        assumeTrue("LegacyKeyPlan.kt is not next to this test run", plan != null)
        val candidates = plan!!.readText()
        assertTrue("old envelopes must still open", candidates.contains("PRIMARY_CLOUD_KEY"))
        assertTrue("the user's own key must be tried", candidates.contains("vaultSecret"))
        assertTrue(
            "the passphrase the owner typed must be offered, or a reinstall has no secret at all",
            candidates.contains("passphrase")
        )
        assertFalse("opening candidates must not gain a writer role", candidates.contains("createEnvelope"))
        assertFalse("opening candidates must not gain a writer role", candidates.contains("sealPayload"))

        val sync = syncManagerSource().readText()
        assertTrue("the sync manager must use the shared plan", sync.contains("LegacyKeyPlan.ordered("))
    }

    /** The owner's rule: one exact key on the common path, never a numbered sweep. */
    @Test
    fun `the common restore is one exact key, not a sweep`() {
        val sync = syncManagerSource().readText()
        assertTrue(
            "a v3 copy must be opened with the single primary key",
            sync.contains("LegacyKeyPlan.primary(")
        )
        assertFalse(
            "the overlay must never print a key counter again",
            sync.contains("Exact legacy key")
        )
        assertFalse(
            "the progress overlay must not advertise a derivation total",
            sync.contains("of \$total")
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

    private fun argumentWindows(text: String, callee: String): List<String> {
        val windows = mutableListOf<String>()
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
