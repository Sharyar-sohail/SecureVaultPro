package com.securevault.pro

import android.app.Application
import com.securevault.pro.data.remote.google.AutoSyncManager
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.presentation.components.BrandIconCatalog
import com.securevault.pro.presentation.theme.VaultFonts
import com.securevault.pro.presentation.theme.VaultPalette
import com.securevault.pro.presentation.theme.VaultPalettes
import com.securevault.pro.security.AppLockManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VaultApplication : Application() {

    @Inject
    lateinit var autoSyncManager: AutoSyncManager

    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var appLockManager: AppLockManager

    /**
     * Application-wide jobs run here. Without the handler, one exception from a sync or
     * migration coroutine reaches the default handler and ends the process — which looks
     * exactly like a crash on the screen the user is standing on.
     */
    private val backgroundScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                runCatching { CrashLogger.noteFault(this, "A background job failed", error) }
            }
    )

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // Every table the first composition reads is touched here, before the UI can die on
        // it. Each of these has taken the app down once from a static initialiser, where the
        // stack is nothing but library frames; naming the subsystem that refused to come up
        // is the difference between a report and a phone that just closes.
        probe("the theme table") { VaultPalettes.all.forEach { VaultPalettes.find(it.id) } }
        probe("the font table") { VaultFonts.all.forEach { VaultFonts.find(it.id) } }
        // Startup only has to prove the board it is about to draw exists. With fifteen
        // palettes, walking all 195 entries here would multiply the static-init coverage
        // of the first frame; the remaining boards are verified off the main path.
        val bootPalette = VaultPalettes.find(VaultPalettes.DEFAULT_ID)
        probe("the icon plates") { verifyIconBoard(bootPalette) }
        autoSyncManager.start()
        backgroundScope.launch {
            probeDeferred("the theme boards") {
                VaultPalettes.all.filter { it.id != bootPalette.id }.forEach { palette ->
                    verifyIconBoard(palette)
                }
            }
            probeDeferred("the settings store") { appLockManager.introSeen.first() }
            probeDeferred("the vault database") { repository.getEntry("startup-probe") }
            CrashLogger.noteEvent("legacy ciphertext migration started")
            runCatching { repository.migrateLegacyCiphertext() }
                .onFailure { CrashLogger.noteFault(this@VaultApplication, "Legacy file migration failed", it) }
            CrashLogger.noteEvent("legacy ciphertext migration finished")
            CrashLogger.noteEvent("startup work finished")
        }
    }

    /** Touches every drawable id and accent colour one theme's board is made of. */
    private fun verifyIconBoard(palette: VaultPalette) {
        BrandIconCatalog.board.forEach { module ->
            BrandIconCatalog.icon(palette.id, module)
            BrandIconCatalog.accent(palette.id, module)
        }
        BrandIconCatalog.ramp(palette.id)
    }

    /**
     * Runs one startup step and reports it by name if it throws. A probe never crashes the
     * app: it opens recovery mode on the next launch instead, with the failing stage written
     * down where the owner can read it. The step itself goes in the trail, not in the screen
     * record — these run long after the owner is standing on a screen.
     */
    private inline fun probe(name: String, block: () -> Unit) {
        try {
            CrashLogger.noteProbe(name)
            block()
        } catch (error: Throwable) {
            CrashLogger.forceRescue(this, "Startup probe failed: $name", error)
        }
    }

    private suspend inline fun probeDeferred(name: String, block: suspend () -> Unit) {
        try {
            CrashLogger.noteProbe(name)
            block()
        } catch (error: Throwable) {
            CrashLogger.forceRescue(this, "Startup probe failed: $name", error)
        }
    }
}
