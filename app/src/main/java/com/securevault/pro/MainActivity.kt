package com.securevault.pro

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.securevault.pro.data.imports.SharedInbox
import com.securevault.pro.security.passkey.PasskeyHandoff
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.presentation.navigation.VaultApp
import com.securevault.pro.presentation.screens.diagnostics.RescueScreen
import com.securevault.pro.presentation.theme.VaultFontController
import com.securevault.pro.presentation.theme.VaultFonts
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultPalettes
import com.securevault.pro.security.resetVaultLookToStock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.noteStage("activity/created")
        if (!CrashLogger.safeRender) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        SharedInbox.offer(this, intent)
        PasskeyHandoff.offer(this, intent)

        setContent {
            val inRescue by CrashLogger.rescue.collectAsStateWithLifecycle()
            if (inRescue) {
                CrashLogger.noteStage("rescue/shown")
                // Nothing in this branch goes through Hilt. The recovery screen is what runs
                // when the app itself is the thing that keeps dying, so asking for a full
                // view model — vault repository, sync manager, all of it — would let a broken
                // graph kill the one screen that can still be read.
                RescueScreen(
                    onOpenAnyway = { CrashLogger.noteStage("rescue/opened-anyway") },
                    onResetLook = {
                        CrashLogger.noteStage("rescue/look-reset")
                        VaultPaletteController.apply(VaultPalettes.DEFAULT_ID)
                        VaultFontController.apply(VaultFonts.DEFAULT_ID)
                        lifecycleScope.launch {
                            runCatching { resetVaultLookToStock() }
                        }
                    }
                )
            } else {
                VaultApp()
            }
        }
        CrashLogger.noteStage("activity/content")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SharedInbox.offer(this, intent)
        // A browser passkey sheet lands here: the service that raised it has no Activity of its own,
        PasskeyHandoff.offer(this, intent)
    }

    override fun onStart() {
        super.onStart()
        CrashLogger.markForeground(this)
        if (CrashLogger.safeRender) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        // The watchdog only works if a healthy run says goodbye. Reaching here after a
        // real session means the process lived through whatever it was loading.
        CrashLogger.markBackground(this)
        super.onStop()
    }
}
