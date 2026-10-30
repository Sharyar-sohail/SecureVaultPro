package com.securevault.pro.presentation.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf

class SecureClipboardController(private val context: Context) {

    var clearAfterSeconds: Int = 30

    private val clipboard: ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val handler = Handler(Looper.getMainLooper())

    fun copy(text: String, hint: String = "Secure value") {
        if (text.isBlank() || clipboard == null) return
        try {
            val clip = ClipData.newPlainText(hint, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clipboard.setPrimaryClip(clip)
            handler.removeCallbacksAndMessages(null)
            val seconds = clearAfterSeconds.coerceIn(10, 120)
            handler.postDelayed({ clear() }, seconds * 1000L)
            Toast.makeText(context, "Copied · clears in ${seconds}s", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    fun clear() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard?.clearPrimaryClip()
            } else {
                clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Throwable) {
            // Clipboard may be locked by another app; nothing else to clear.
        }
    }
}

val LocalSecureClipboard = staticCompositionLocalOf<SecureClipboardController> {
    error("Secure clipboard was not provided")
}

fun secureClipboard(context: Context): SecureClipboardController =
    SecureClipboardController(context.applicationContext)
