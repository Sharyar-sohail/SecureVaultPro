package com.securevault.pro.security.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/**
 * Whether text in this part of the tree must be typed on the in-app board instead of a keyboard the
 * owner installed.
 *
 * Provided once by the app root from the owner's Security setting, and read by every shared text
 * component, so "no third-party keyboard sees my typing" is one switch rather than a per-screen
 * decision. Defaults to on: a surface that forgets to provide it still types privately.
 */
val LocalSecureKeyboardTyping = compositionLocalOf { true }

/**
 * The app board's keycap size, provided by the app root from the owner's Keyboard setting and read by
 * [SecureKeyboardPanel] when it lays out the keys. 0 = normal (today's size), 1 = large, 2 = extra
 * large. Defaults to normal so a surface that forgets to provide it still draws the standard board.
 */
val LocalSecureBoardHeight = compositionLocalOf { 0 }

/**
 * Whether a key press on the app board gives a small buzz, provided by the app root from the owner's
 * Keyboard setting. Defaults to on, matching the switch's own default.
 */
val LocalSecureKeyboardHaptics = compositionLocalOf { true }

/**
 * The typing state behind a field that only the in-app board may write to.
 *
 * This is what [SecurePasswordField] does for a secret, lifted out so any ordinary text field in the
 * app — a note, an email server name, a search box — can be driven by the same board instead of a
 * third-party keyboard. The field keeps owning its value: every accepted edit leaves through
 * [emit], and nothing else can write to it.
 */
@Stable
class SecureBoardEntry internal constructor(
    private val hideSystemKeyboard: () -> Unit,
) {
    var text by mutableStateOf(""); internal set
    var caret by mutableStateOf(0); internal set
    var isEngaged by mutableStateOf(false); internal set
    var layout by mutableStateOf(SecureKeyboardLayoutState(SecureKeyboardLayoutState.newSessionSeed())); internal set

    /** Refreshed on every composition, so a recomposing screen never strands a stale lambda. */
    internal var emit: (String) -> Unit = {}
    internal var singleLine: Boolean = true
    internal var onSubmit: (() -> Unit)? = null

    /** True for a field that only ever holds digits: it gets the pad, and no letters page. */
    internal var numeric: Boolean = false

    /**
     * The last text this entry handed to its caller.
     *
     * A board-typed field is a loop: a key writes [text], [emit] tells the caller, the caller's state
     * comes back as the `value` this entry adopts. That echo lands a recomposition later, so without
     * this it can arrive *after* the next key was already typed and write the older string back —
     * one silently lost character, which is exactly how a fast recovery phrase ends up a word short.
     * An echo of what we just emitted is therefore never treated as an outside change.
     */
    private var lastEmitted: String = ""

    /** The dock this entry publishes to when the screen hosts the board; null in inline mode. */
    internal var dock: SecureKeyboardDock? = null

    /**
     * The field's bounds in root coordinates, written by placement. Not observable state: being
     * positioned must never recompose anyone — the tap-outside check just reads the freshest value.
     */
    internal var bounds: Rect? = null
        set(value) {
            field = value
            value?.let { dock?.reportBounds(this, it) }
        }

    /** Take the field over: the board is up and the system IME is not allowed near it. */
    fun engage() {
        if (numeric && layout.layer != SecureKeyLayer.NUMERIC) {
            layout = layout.copy(layer = SecureKeyLayer.NUMERIC)
        }
        isEngaged = true
        hideSystemKeyboard()
    }

    fun dismiss() {
        isEngaged = false
    }

    /** A tap inside the inert editor moves the caret; it is the only thing the editor itself may do. */
    fun moveCaretTo(index: Int) {
        caret = index.coerceIn(0, text.length)
    }

    internal fun adopt(external: String) {
        if (external == text || external == lastEmitted) return
        text = external
        caret = caret.coerceAtMost(external.length)
        lastEmitted = external
    }

    internal fun press(key: SecureKey) {
        val (command, nextLayout) = SecureKeyboardEngine.press(key, layout)
        // Only seat a new layout when it actually moved (shift arm/disarm, a layer switch, a
        // shuffle). A plain character leaves the layout identical, so writing it would dirty the
        // snapshot for nothing; skipping it keeps an ordinary keystroke from reaching any layout
        // observer, and with it the whole key grid, at all.
        if (nextLayout != layout) layout = nextLayout
        when (command) {
            is SecureKeyCommand.Insert -> apply(SecureTextBuffer(text, caret).insert(command.text))

            SecureKeyCommand.Backspace -> apply(SecureTextBuffer(text, caret).backspace())

            SecureKeyCommand.Enter -> when {
                onSubmit != null -> {
                    isEngaged = false
                    onSubmit?.invoke()
                }

                !singleLine -> apply(SecureTextBuffer(text, caret).insert("\n"))

                else -> isEngaged = false
            }

            SecureKeyCommand.Ignored -> Unit
        }
    }

    private fun apply(buffer: SecureTextBuffer) {
        text = buffer.text
        caret = buffer.cursor
        lastEmitted = text
        emit(text)
    }
}

/**
 * Board state for one field.
 *
 * [value] is the caller's own text, adopted whenever it changes elsewhere (a draft restore, a cleared
 * form, a successful save) so the board never types into a stale buffer.
 *
 * When the screen is wrapped in a [SecureKeyboardHost] the board is published to that host's dock and
 * drawn at the bottom of the window, so engaging a field costs the form no layout height at all — the
 * same way the system keyboard is an overlay the app resizes for, not a box inside the row. With no
 * host, the caller draws the panel itself, under the field.
 */
@Composable
fun rememberSecureBoard(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    numeric: Boolean = false,
): SecureBoardEntry {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val dock = reachableSecureKeyboardDock()
    val entry = remember {
        SecureBoardEntry(
            hideSystemKeyboard = {
                keyboard?.hide()
                SecureIme.detachFromSystemKeyboard(view)
            },
        )
    }
    entry.emit = onValueChange
    entry.singleLine = singleLine
    entry.onSubmit = onSubmit
    entry.numeric = numeric
    entry.dock = dock
    LaunchedEffect(value) { entry.adopt(value) }
    if (dock != null) {
        // Stable handlers for the whole session. The docked field recomposes on every keystroke
        // because it paints the new value, and building `onKeyPress`/`onDismiss` inline would
        // allocate two fresh closures per character. [entry] is remembered and never changes, so
        // these are created once and the same references are published each time.
        val onKeyPress = remember(entry) { { key: SecureKey -> entry.press(key) } }
        val onDismiss = remember(entry) { { entry.dismiss() } }
        // Publish straight from composition, gated on engagement and layout identity — never on the
        // text. The board draws the layout, not the value, so ordinary keystrokes write no dock
        // state and the pressed key paints in the same frame it commits. This entry keeps owning its
        // layout, so shift, layer and shuffle reflect instantly.
        if (entry.isEngaged) {
            dock.publish(
                owner = entry,
                layout = entry.layout,
                bounds = entry.bounds,
                onKeyPress = onKeyPress,
                onDismiss = onDismiss,
            )
        } else {
            dock.clearIfOwner(entry)
        }
        DisposableEffect(entry, dock) {
            onDispose { dock.clearIfOwner(entry) }
        }
    }
    return entry
}
