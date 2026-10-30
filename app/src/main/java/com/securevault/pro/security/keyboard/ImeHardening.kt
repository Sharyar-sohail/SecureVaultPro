package com.securevault.pro.security.keyboard

import android.content.Context
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * The window-level capture block, in one place, so a screen can ask for it without restating the
 * flag it does not own.
 *
 * Two callers already set `FLAG_SECURE` and neither is a keyboard: `MainActivity.onCreate` puts it
 * up before the first frame (so a screenshot is refused from the moment the process is visible),
 * and `VaultApp` re-asserts or clears it against the owner's *Screenshot block* preference,
 * except while `CrashLogger.safeRender` is on — after repeated hard kills the app chooses to be
 * readable rather than unphotographable. Those two are the policy. This helper is the primitive
 * they, and any new full-screen secure surface, call, and it deliberately does not introduce a
 * third policy: it only writes the flag, and it can read it back.
 */
object WindowSecurityFlags {

    val FLAG: Int = WindowManager.LayoutParams.FLAG_SECURE

    /** Blocks screen capture, recording and the preview in the recent-apps switcher. */
    fun enforce(window: Window) {
        window.addFlags(FLAG)
    }

    /** Releases the block. Only the existing preference/safe-render policy should call this. */
    fun release(window: Window) {
        window.clearFlags(FLAG)
    }

    /** Whether capture is blocked right now, read from the live window rather than from a setting. */
    fun isEnforced(window: Window): Boolean =
        (window.attributes.flags and FLAG) != 0
}

/**
 * IME hygiene for the fields the app keeps on screen.
 *
 * Read the honest scope of each member:
 * - [secureKeyboardOptions] is applied to every secure field and is what stops the system
 *   autocorrect/dictionary path on the ones that still have an IME attached.
 * - [disablePersonalizedLearning] sets `IME_FLAG_NO_PERSONALIZED_LEARNING` on a *View-based*
 *   editor. That is the API that exists: Compose 1.7.6 (the version this app resolves) exposes no
 *   raw `imeOptions` on `KeyboardOptions`, and `TextInputServiceAndroid` assigns
 *   `EditorInfo.imeOptions` from its own state, so a Compose text field cannot be given this flag
 *   through the public modifier API. The secure fields in this package therefore do not need it —
 *   they never attach an IME at all ([detachFromSystemKeyboard] and the read-only editor in
 *   `SecurePasswordField`), which is a stronger guarantee than the flag gives.
 */
object SecureIme {

    /** The platform constant, named once so call sites say what they mean. */
    const val NO_PERSONALIZED_LEARNING_FLAG: Int = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    /**
     * Options for a field the owner is typing a secret into: no autocorrect, no candidate
     * dictionary, and the password input type when the value is secret. `KeyboardType.Password`
     * maps to `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD`, the variation the platform
     * already treats as not-to-be-learned and not-to-be-suggested.
     */
    fun secureKeyboardOptions(
        secret: Boolean,
        imeAction: ImeAction = ImeAction.Next,
        multiLine: Boolean = false,
    ): KeyboardOptions = KeyboardOptions(
        autoCorrectEnabled = false,
        keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
        imeAction = if (multiLine) ImeAction.Default else imeAction,
    )

    /**
     * Sets the no-personalized-learning flag on a View that owns an input connection.
     *
     * `imeOptions` belongs to `TextView`, not to every `View`, so a plain View is returned untouched
     * rather than crashing the screen that hosted it. Existing bits are kept, so this can be called
     * on a field that already has `actionDone` set.
     */
    fun <T : View> disablePersonalizedLearning(view: T): T {
        if (view is android.widget.TextView) {
            view.imeOptions = view.imeOptions or NO_PERSONALIZED_LEARNING_FLAG
        }
        return view
    }

    /**
     * Pulls the soft keyboard down for this window, if any IME is showing. Called by the secure
     * field when it takes focus: the secure board is the only thing that may type into it, so an
     * open system keyboard is a bug rather than a preference.
     */
    fun detachFromSystemKeyboard(view: View) {
        val token = view.windowToken ?: return
        val manager = view.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        manager.hideSoftInputFromWindow(token, 0)
    }
}
