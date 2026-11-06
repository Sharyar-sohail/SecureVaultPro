package com.securevault.pro.data.remote.google

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's release requirement: every data-entry write must automatically wake the Drive
 * backup — no manual "Save & Sync" press needed. This test reads the shipped Kotlin sources and
 * proves the trigger chain end to end: the repository emits a mutation for every user-visible
 * save/delete/toggle, the AutoSyncManager collects that flow and debounces into
 * CloudSyncManager.syncNowSilently(), the file vault routes its listener through VaultRepository,
 * the crypto ViewModel fires its own signals, and the editor screens popBackStack after save
 * without ever calling syncNow themselves. If a future edit removes a notify call from a save
 * path, the offending function name is printed in the failure message.
 */
class AutoSyncTriggerCoverageTest {

    private val vaultRepoSource: String by lazy { source("data/repository/VaultRepository.kt") }
    private val autoSyncSource: String by lazy { source("data/remote/google/AutoSyncManager.kt") }
    private val cloudSyncSource: String by lazy { source("data/remote/google/CloudSyncManager.kt") }
    private val fileVaultSource: String by lazy { source("data/file/FileVaultRepository.kt") }
    private val appSource: String by lazy { source("VaultApplication.kt") }
    private val cryptoVmSource: String by lazy { source("presentation/screens/crypto/CryptoWalletViewModel.kt") }
    private val navSource: String by lazy { source("presentation/navigation/VaultApp.kt") }

    // ---------- (a) the mutation flow feeds the debounced Drive trigger ----------

    @Test
    fun `VaultRepository exposes the mutations flow the AutoSyncManager collects`() {
        assertTrue(
            "VaultRepository must expose `val mutations: Flow<VaultMutation>` (the auto-sync trigger wire)",
            vaultRepoSource.contains("val mutations: Flow<VaultMutation>")
        )
        assertTrue(
            "VaultRepository must emit through _mutations.tryEmit, otherwise no sync ever wakes",
            vaultRepoSource.contains("_mutations.tryEmit(")
        )
        assertTrue(
            "AutoSyncManager must collect VaultRepository.mutations — the 500 ms debounce only starts here",
            autoSyncSource.contains("vaultRepository.mutations.collect")
        )
        assertTrue(
            "AutoSyncManager must wake the debounced loop with wake.trySend(Unit)",
            autoSyncSource.contains("wake.trySend(Unit)")
        )
        assertTrue(
            "AutoSyncManager must delay on DEBOUNCE_MS before uploading",
            autoSyncSource.contains("delay(DEBOUNCE_MS)") && autoSyncSource.contains("DEBOUNCE_MS = 500L")
        )
        assertTrue(
            "AutoSyncManager must upload through cloudSyncManager.syncNowSilently() — that is the Drive backup",
            autoSyncSource.contains("cloudSyncManager.syncNowSilently()")
        )
        assertTrue(
            "CloudSyncManager must keep exposing canAccessDrive() for the trigger gate",
            cloudSyncSource.contains("fun canAccessDrive(): Boolean")
        )
        assertTrue(
            "The trigger loop must only sync a REAL session (decoy edits must never reach the owner's Drive)",
            autoSyncSource.contains("sessionManager.mode.value != SessionMode.REAL")
        )
        assertTrue(
            "AutoSyncManager must start from VaultApplication.onCreate — before the first edit",
            appSource.contains("autoSyncManager.start()")
        )
    }

    @Test
    fun `every VaultRepository save delete toggle import wipe path signals a mutation`() {
        val functions = memberFunctions(vaultRepoSource)
        val signalers = notifyCallersOf(functions)
        val offenders = mutableListOf<String>()
        for ((name, body) in functions) {
            if (!isTrackedWrite(name) || body.contains(PRIVATE_MARK)) continue
            val signalsDirectly = body.contains("notifyRecord(") || body.contains("notifyMutation(")
            val signalsIndirectly = findDelegatedCall(name, body, signalers) != null
            if (!signalsDirectly && !signalsIndirectly) offenders += name
        }
        assertTrue(
            "These VaultRepository write paths save/delete/toggle Room rows WITHOUT calling notifyRecord " +
                "or notifyMutation, so no background Drive sync would fire: ${offenders.joinToString(", ")}",
            offenders.isEmpty()
        )
    }

    // ---------- (b) the realtime fast-push keeps its own copy of the same signal ----------

    @Test
    fun `a named mutation still routes the millisecond realtime push beside the debounce`() {
        assertTrue(
            "AutoSyncManager must push the named record on the realtime fast path (markRecordChanged)",
            autoSyncSource.contains("realtimeEngine.markRecordChanged(")
        )
        assertTrue(
            "AutoSyncManager must push the tombstone on the realtime fast path (markRecordDeleted)",
            autoSyncSource.contains("realtimeEngine.markRecordDeleted(")
        )
        assertTrue(
            "Every mutation must also schedule the signature sweep as the safety net",
            autoSyncSource.contains("realtimeEngine.scheduleReconcile()")
        )
        assertTrue(
            "VaultRepository must validate module names through RealtimeModule.fromSegment before emitting",
            vaultRepoSource.contains("RealtimeModule.fromSegment(module)")
        )
    }

    // ---------- (c) FileVaultRepository + crypto wallets wake the same trigger ----------

    @Test
    fun `FileVaultRepository user writes call notifyMutation and the listener is wired in VaultRepository`() {
        val fileVaultFns = memberFunctions(fileVaultSource)
        val fileVaultSignalers = notifyCallersOf(fileVaultFns)
        val tracked = listOf("saveFile", "saveStream", "importEncryptedOrRaw", "deleteFile")
        val missing = tracked.filter { name ->
            val body = fileVaultFns.firstOrNull { it.first == name }?.second
            when {
                body == null -> true
                body.contains("notifyMutation(") -> false
                else -> findDelegatedCall(name, body, fileVaultSignalers) == null
            }
        }
        assertTrue(
            "These FileVaultRepository write paths neither call notifyMutation nor delegate to a " +
                "function that does, so file edits would never trigger a backup: " +
                "${missing.joinToString(", ")}",
            missing.isEmpty()
        )
        assertTrue(
            "VaultRepository.init must wire fileVault.setMutationListener so file writes reach mutations",
            vaultRepoSource.contains("fileVault.setMutationListener") &&
                vaultRepoSource.contains("notifyMutation(RealtimeModule.FILES.segment")
        )
    }

    @Test
    fun `CryptoWalletViewModel signals a mutation on import refresh delete and send`() {
        val signals = Regex("vaultRepository\\.notifyMutation\\(RealtimeModule\\.CRYPTO_WALLETS\\.segment")
            .findAll(cryptoVmSource)
            .count()
        assertTrue(
            "CryptoWalletViewModel must fire notifyMutation(CRYPTO_WALLETS) at least 4 times " +
                "(import, refresh, delete, send) — found $signals; a wallet write without a signal " +
                "never triggers the background sync",
            signals >= 4
        )
    }

    // ---------- (d) no data-entry screen needs a manual sync button ----------

    @Test
    fun `no screen requires a manual save-and-sync button anywhere in the presentation layer`() {
        val root = sourceRoot()
        val offenders = File(root, "presentation").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                // Decode bytes explicitly: some shipped sources are not valid UTF-8, and an
                // invalid sequence must map to U+FFFD instead of throwing — the marker strings
                // we look for are pure ASCII, so replacement never hides a hit.
                val text = f.readBytes().toString(Charsets.UTF_8)
                text.contains("Save & Sync") || text.contains("Save and Sync")
            }
            .map { it.name }
            .toList()
        assertTrue(
            "Data entry must auto-sync; these screens still advertise a manual save-and-sync button: " +
                offenders.joinToString(", "),
            offenders.isEmpty()
        )
    }

    @Test
    fun `editor screens popBackStack after save and their ViewModels never call syncNow`() {
        val pops = Regex("onSaved = \\{ navController\\.popBackStack\\(\\) \\}").findAll(navSource).count()
        assertTrue(
            "The editor routes (entry, card, note, contact, totp) must popBackStack via onSaved — " +
                "found only $pops wirings, expected at least 5",
            pops >= 5
        )
        val editors = listOf(
            "presentation/screens/vault/EntryEditorViewModel.kt",
            "presentation/screens/notes/NoteEditorViewModel.kt",
            "presentation/screens/wallet/CardEditorViewModel.kt",
            "presentation/screens/contacts/ContactEditorViewModel.kt",
            "presentation/screens/auth/TotpEditorViewModel.kt",
            "presentation/screens/crypto/CryptoWalletViewModel.kt"
        )
        val offenders = editors.filter { path ->
            val text = source(path)
            text.contains("syncNow") || text.contains("AutoSyncManager")
        }
        assertTrue(
            "Editor ViewModels must only save through the repository (the mutation wakes the sync); " +
                "these call syncNow / AutoSyncManager directly, making the button a crutch for a broken " +
                "trigger: ${offenders.joinToString(", ")}",
            offenders.isEmpty()
        )
    }

    // ---------- helpers ----------

    /** `suspend fun`/`fun` headers at class-member indentation (4 spaces), paired with their body text. */
    private fun memberFunctions(src: String): List<Pair<String, String>> {
        val header = Regex("""^    (private )?(suspend )?fun (\w+)""")
        val lines = src.lines()
        val starts = ArrayList<Triple<Int, String, Boolean>>()
        for ((index, line) in lines.withIndex()) {
            val match = header.find(line)
            if (match != null) starts.add(Triple(index, match.groupValues[3], match.groupValues[1].isNotBlank()))
        }
        return starts.mapIndexed { position, start ->
            val end = if (position + 1 < starts.size) starts[position + 1].first else lines.size
            val body = lines.subList(start.first, end).joinToString("\n")
            start.second to if (start.third) body + PRIVATE_MARK else body
        }
    }

    private fun isTrackedWrite(name: String): Boolean =
        name.startsWith("save") || name.startsWith("delete") || name.startsWith("toggle") ||
            name.startsWith("import") || name == "wipeAllData" || name == "reloadVaultState"

    private fun notifyCallersOf(functions: List<Pair<String, String>>): Set<String> =
        functions
            .filter { (name, body) ->
                !body.contains(PRIVATE_MARK) &&
                    (body.contains("notifyRecord(") || body.contains("notifyMutation("))
            }
            .map { it.first }
            .toSet()

    /** A tracked write passes when it delegates to another public repository write function that signals. */
    private fun findDelegatedCall(name: String, body: String, signalers: Set<String>): String? {
        val targets = Regex("""\b((?:save|delete|toggle|import|wipe)\w+)\(""")
            .findAll(body)
            .map { it.groupValues[1] }
            .filter { it != name && it in signalers }
        return targets.firstOrNull()
    }

    private fun sourceRoot(): String {
        val candidates = listOf(
            "src/main/java/com/securevault/pro",
            "app/src/main/java/com/securevault/pro",
            "../src/main/java/com/securevault/pro"
        )
        return candidates.firstOrNull { File(it).isDirectory }
            ?: throw IllegalStateException("cannot locate the source root from ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/securevault/pro/$relative"),
            File("app/src/main/java/com/securevault/pro/$relative"),
            File("../src/main/java/com/securevault/pro/$relative")
        )
        return candidates.firstOrNull { it.isFile }?.readBytes()?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("cannot locate $relative from ${File(".").absolutePath}")
    }

    private companion object {
        /** Tag appended to private function bodies so delegation checks can ignore them. */
        const val PRIVATE_MARK = "\u0000private"
    }
}
