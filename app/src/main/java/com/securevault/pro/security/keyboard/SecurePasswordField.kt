package com.securevault.pro.security.keyboard

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes

/**
 * The secret field that the whole app types through.
 *
 * How it defeats a keylogger, layer by layer — and which of these the code actually enforces:
 *
 *  1. The editor is `enabled = false` **and** `readOnly = true`. Compose does not start a text input
 *     session for a disabled editor, so no `InputConnection` is handed to the system IME for this
 *     field: there is nothing for a logging IME to write into, and the value can only move through
 *     [SecureTextBuffer], which the key grid drives.
 *  2. When the field takes focus, `LocalSoftwareKeyboardController.hide()` is called and
 *     [SecureIme.detachFromSystemKeyboard] runs against the window token, so a keyboard left open by
 *     a previous field comes down instead of hovering over the secure board.
 *  3. While the field owns focus, every hardware/physical-keyboard event is consumed
 *     (`onPreviewKeyEvent` returns true), so a bluetooth keyboard cannot feed the value either.
 *  4. The keyboard options still say what they must for the surfaces where an IME *is* attached —
 *     no autocorrect, `KeyboardType.Password` for a secret, `KeyboardType.Text` otherwise — see
 *     [SecureIme.secureKeyboardOptions] for what each maps to, and [SecureIme] for the honest scope
 *     of `IME_FLAG_NO_PERSONALIZED_LEARNING`.
 *  5. Screen capture is blocked at window level by [WindowSecurityFlags]; `MainActivity` and
 *     `VaultApp` own when it is on, and this class only reads it.
 *
 * The caret is drawn from the text layout ([androidx.compose.ui.text.TextLayoutResult.getCursorRect])
 * so a masked field still shows where the next character lands even though the editor itself is inert.
 *
 * @param visible when true the value is shown in plain. The field also offers its own reveal
 *   control, which only ever adds plain text on top of what the caller already asked for.
 * @param onSubmit fired by the board's Enter key; when null, Enter inserts a newline for a
 *   multi-line field and dismisses the board for a single-line one.
 */
@Composable
fun SecurePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visible: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val dock = reachableSecureKeyboardDock()
    val focusRequester = remember { FocusRequester() }

    // Identity token for the dock: it is how the dock knows *this* field owns the board, so a
    // second field taking over — or this one leaving composition — never drops the wrong board.
    val boardOwner = remember { Any() }

    // The field's live on-screen bounds, refreshed by placement into a plain slot: no snapshot
    // state, so being positioned never recomposes anything, and the tap-outside check can read
    // the freshest rectangle the moment a pointer event lands.
    val fieldBounds = remember { arrayOfNulls<Rect>(1) }

    var buffer by remember { mutableStateOf(SecureTextBuffer.of(value)) }
    var layout by remember {
        mutableStateOf(SecureKeyboardLayoutState(SecureKeyboardLayoutState.newSessionSeed()))
    }
    var engaged by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var caretRect by remember { mutableStateOf<Rect?>(null) }

    /**
     * The last string this field handed to its caller, in a plain slot rather than observed state:
     * reading it must never recompose anything, and writing it must never recompose anything either.
     */
    val lastEmitted = remember { arrayOf(value) }

    // The caller owns the value (a ViewModel field can be cleared by an error, a draft restore or a
    // successful submit). Adopt an outside change, keeping the caret where it still fits. An echo of
    // what this field just handed out is not such a change: it arrives a recomposition late and
    // would write an older string over a newer one, losing the character typed in between.
    LaunchedEffect(value) {
        if (value != buffer.text && value != lastEmitted[0]) {
            buffer = SecureTextBuffer(value, cursor = buffer.cursor.coerceAtMost(value.length))
            lastEmitted[0] = value
        }
    }

    LaunchedEffect(engaged) {
        if (engaged) {
            keyboard?.hide()
            SecureIme.detachFromSystemKeyboard(view)
        }
    }

    fun emitNext(text: String) {
        lastEmitted[0] = text
        onValueChange(text)
    }

    fun handle(command: SecureKeyCommand) {
        when (command) {
            is SecureKeyCommand.Insert -> {
                buffer = buffer.insert(command.text)
                emitNext(buffer.text)
            }

            SecureKeyCommand.Backspace -> {
                buffer = buffer.backspace()
                emitNext(buffer.text)
            }

            SecureKeyCommand.Enter -> when {
                onSubmit != null -> {
                    engaged = false
                    onSubmit()
                }

                !singleLine -> {
                    buffer = buffer.appendNewline()
                    emitNext(buffer.text)
                }

                else -> engaged = false
            }

            SecureKeyCommand.Ignored -> Unit
        }
    }

    fun press(key: SecureKey) {
        val (command, nextLayout) = SecureKeyboardEngine.press(key, layout)
        layout = nextLayout
        handle(command)
    }

    // The inline board is handed one stable handler for the whole session, and that handler reads
    // through a plain slot refreshed on every composition. A fresh lambda per character would make
    // the key grid look like changed input and rebuild all forty caps for each keystroke; a stale
    // one would type into the wrong field. This is neither.
    val boardTap = remember { SecureFieldTap() }
    boardTap.press = ::press
    boardTap.dismiss = { engaged = false }
    val pressBoard: (SecureKey) -> Unit = remember(boardTap) { { key -> boardTap.press(key) } }
    val closeBoard: () -> Unit = remember(boardTap) { { boardTap.dismiss() } }
    val boardOffset = remember { Modifier.padding(top = 10.dp) }

    // Docked mode: the host owns the board, this field publishes its layout to it. The publish is
    // synchronous, behind an identity check, and keyed on the layout — never on the text. The panel
    // draws the layout, not the value, so a keystroke that leaves shift/layer/shuffle alone writes
    // no dock state at all; the frame that paints the pressed key is the frame that shows it.
    if (dock != null) {
        if (engaged) {
            dock.publish(
                owner = boardOwner,
                layout = layout,
                bounds = fieldBounds[0],
                onKeyPress = pressBoard,
                onDismiss = closeBoard,
            )
        } else {
            dock.clearIfOwner(boardOwner)
        }
        DisposableEffect(dock) {
            onDispose { dock.clearIfOwner(boardOwner) }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SecureFieldChrome(
            label = label,
            value = buffer.text,
            cursor = buffer.cursor,
            singleLine = singleLine,
            masked = !visible && !revealed,
            engaged = engaged,
            caretRect = caretRect,
            onCaretRect = { caretRect = it },
            revealAvailable = !visible,
            revealed = revealed,
            onToggleReveal = { revealed = !revealed },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusEvent { event -> if (!event.hasFocus) engaged = false }
                .onPreviewKeyEvent { engaged }
                .onGloballyPositioned { coords ->
                    val rect = coords.boundsInRoot()
                    fieldBounds[0] = rect
                    if (engaged) dock?.reportBounds(boardOwner, rect)
                }
                .clickable {
                    engaged = true
                    runCatching { focusRequester.requestFocus() }
                },
        )

        if (dock == null && engaged) {
            SecureKeyboardPanel(
                layout = layout,
                onKeyPress = pressBoard,
                onDismiss = closeBoard,
                modifier = boardOffset,
            )
        }
    }
}

/**
 * Field chrome plus the inert editor.
 *
 * Split out so the secure board and the field never fight over one modifier: everything that must be
 * focus-sensitive lives on the caller-supplied [modifier], everything cosmetic lives here.
 */
@Composable
private fun SecureFieldChrome(
    label: String,
    value: String,
    cursor: Int,
    singleLine: Boolean,
    masked: Boolean,
    engaged: Boolean,
    caretRect: Rect?,
    onCaretRect: (Rect?) -> Unit,
    revealAvailable: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val caretHeight = with(LocalDensity.current) { bodyStyle.fontSize.toDp() }
    val textColor = if (value.isEmpty()) VaultColor.textFaint else VaultColor.textStrong
    Column(
        modifier = modifier
            .clip(VaultShapes.input)
            .background(VaultColor.elevated.copy(alpha = 0.55f), VaultShapes.input)
            .border(
                width = if (engaged) 1.4.dp else 1.dp,
                color = if (engaged) VaultColor.accent.copy(alpha = 0.65f) else VaultColor.hairline,
                shape = VaultShapes.input,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        SecureFieldHeader(
            label = label,
            revealAvailable = revealAvailable,
            revealed = revealed,
            onToggleReveal = onToggleReveal,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            // Inert editor: it paints the text and reports its layout, and it is the reason an
            // external IME has no input connection to write through.
            BasicTextField(
                value = TextFieldValue(text = value, selection = TextRange(cursor)),
                onValueChange = { /* refused: every edit comes from the secure board */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                enabled = false,
                readOnly = true,
                textStyle = bodyStyle.copy(color = textColor),
                keyboardOptions = SecureIme.secureKeyboardOptions(
                    secret = masked,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                    multiLine = !singleLine,
                ),
                singleLine = singleLine,
                visualTransformation = if (masked) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                cursorBrush = SolidColor(VaultColor.accent),
                onTextLayout = { result ->
                    caretRectOf(result, cursor, caretHeight.value)?.let(onCaretRect)
                },
            )
            if (value.isEmpty() && !engaged) {
                Text(
                    text = "Tap to use the secure board",
                    style = bodyStyle,
                    color = VaultColor.textFaint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (engaged) {
                val rect = caretRect
                if (rect != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .offset(x = rect.left.dp, y = rect.top.dp)
                            .width(2.dp)
                            .height(rect.height.dp.coerceAtLeast(10.dp))
                            .background(VaultColor.accent)
                    )
                } else {
                    // Before the first layout pass the caret sits at the start of the line, so the
                    // field never looks dead while the owner is choosing where to type.
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .width(2.dp)
                            .height(caretHeight)
                            .background(VaultColor.accent)
                    )
                }
            }
        }
    }
}

/**
 * The caret rectangle for [cursor], or null when the layout cannot answer yet.
 *
 * [getCursorRect] returns the whole line box, and a line is taller than the letters inside it —
 * bodyLarge gives 24sp of line for 16sp of text — so a caret copied from it reads as a bar rather
 * than a cursor. This keeps the horizontal position and the baseline the layout measured, and trims
 * the vertical extent to one em sitting on that baseline, which is what the glyphs occupy.
 *
 * A read-only editor with a visual transformation can be asked for a position outside the painted
 * text for one frame; that is not an error worth crashing the screen over.
 */
private fun caretRectOf(result: TextLayoutResult, cursor: Int, emDp: Float): Rect? =
    runCatching {
        val offset = cursor.coerceAtMost(result.layoutInput.text.length)
        val raw = result.getCursorRect(offset)
        val baseline = result.getLineBaseline(result.getLineForOffset(offset))
        Rect(raw.left, baseline - emDp * ASCENT_SHARE, raw.right, baseline + emDp * DESCENT_SHARE)
    }.getOrNull()

private const val ASCENT_SHARE = 0.86f
private const val DESCENT_SHARE = 0.14f

/**
 * Plain slots for the callbacks a board handler needs.
 *
 * The inline board is handed one lambda that never changes, so the key grid is rebuilt when the
 * layout changes and not on every character. The lambdas inside this object are refreshed from the
 * field's own composition, which is how a handler that is stable forever still calls the handler
 * that is current.
 */
private class SecureFieldTap {
    var press: (SecureKey) -> Unit = {}
    var dismiss: () -> Unit = {}
}

@Composable
private fun SecureFieldHeader(
    label: String,
    revealAvailable: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = VaultColor.textFaint,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (revealAvailable) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(VaultShapes.input)
                    .clickable { onToggleReveal() },
                contentAlignment = Alignment.Center,
            ) {
                ThemedIcon(
                    glyph = if (revealed) Glyph.visibilityOff else Glyph.visibility,
                    contentDescription = if (revealed) "Hide the value" else "Show the value",
                    tint = VaultColor.textBody,
                    modifier = Modifier.size(18.dp),
                    animate = false,
                )
            }
        }
    }
}

/**
 * The board a secure field would rather use than drawing one under itself.
 *
 * A screen that wants the keyboard pinned to the bottom of the viewport wraps its content in
 * [SecureKeyboardHost]; every [SecurePasswordField] inside then publishes its layout to this dock
 * instead of stacking a board under the field. One field is engaged at a time — the last one tapped
 * owns the board, and a tap outside that field hands the board away or drops it (see
 * [SecureKeyboardHost]).
 *
 * Publishing is synchronous and identity-gated: [publish] is called straight from the field's
 * composition, and it only writes the observable [layout] when the layout itself changed. A
 * keystroke that does not move shift, the layer or the shuffle therefore writes no dock state and
 * recomposes nothing — the value lives in the field, and the board never needed it.
 */
@Stable
class SecureKeyboardDock internal constructor() {
    var layout by mutableStateOf<SecureKeyboardLayoutState?>(null)
        private set

    /** The engaged field's bounds in root coordinates; read at tap time, never by composition. */
    internal var ownerBounds: Rect? = null

    /** The board strip's own bounds, so a tap that lands on the keyboard is never "outside". */
    internal var boardBounds: Rect? = null

    /** This host's bounds in root coordinates: the transform from event space to rectangle space. */
    internal var hostBounds: Rect? = null

    /**
     * The view tree the board strip is drawn in. A field compares itself against this before
     * publishing: a bottom sheet or dialog composes in its own window, so a board published here
     * would land behind that sheet and the field would look like it opens no keyboard at all.
     */
    internal var boardView: View? = null

    private var owner: Any? = null

    /** Plain mirror of the last published layout: the identity gate that keeps publishing free. */
    private var publishedLayout: SecureKeyboardLayoutState? = null

    private var onKeyPress: ((SecureKey) -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null

    /** True while some field is asking for the board. */
    val isEngaged: Boolean get() = layout != null

    /** Claim the board for [owner] and show [layout]. Cheap: it writes state only on a real change. */
    internal fun publish(
        owner: Any,
        layout: SecureKeyboardLayoutState,
        bounds: Rect?,
        onKeyPress: (SecureKey) -> Unit,
        onDismiss: () -> Unit,
    ) {
        this.owner = owner
        this.onKeyPress = onKeyPress
        this.onDismiss = onDismiss
        if (bounds != null) ownerBounds = bounds
        if (publishedLayout != layout) {
            publishedLayout = layout
            this.layout = layout
        }
    }

    /** Keep the tap-outside check honest when the engaged field moves (a scroll, a resize). */
    internal fun reportBounds(owner: Any, bounds: Rect) {
        if (owner === this.owner) ownerBounds = bounds
    }

    internal fun press(key: SecureKey) {
        onKeyPress?.invoke(key)
    }

    /** Drop the board and hand the field back, clearing every handler with it. */
    internal fun dismiss() {
        val handler = onDismiss
        clear()
        handler?.invoke()
    }

    /** Release the board only if [owner] still holds it: a departing field must not cut another's. */
    internal fun clearIfOwner(owner: Any) {
        if (owner === this.owner) dismiss()
    }

    private fun clear() {
        owner = null
        onKeyPress = null
        onDismiss = null
        ownerBounds = null
        publishedLayout = null
        layout = null
    }
}

/** Provided by [SecureKeyboardHost]; null means "draw the board under the field". */
val LocalSecureKeyboardDock = staticCompositionLocalOf<SecureKeyboardDock?> { null }

/**
 * The dock the calling composition can actually reach.
 *
 * A bottom sheet or dialog composes in its own window, so a board published to a host in the
 * activity window lands behind the sheet and the field looks like it opens no keyboard at all.
 * Where the two views differ this returns null, which is exactly the instruction to draw the board
 * under the field instead.
 */
@Composable
fun reachableSecureKeyboardDock(): SecureKeyboardDock? {
    val dock = LocalSecureKeyboardDock.current
    return if (dock != null && dock.boardView === LocalView.current) dock else null
}

/**
 * Hosts the secure board once, at the bottom, for a whole screen.
 *
 * The board is a sibling of the content, not an overlay: while it is up, the content is measured
 * against the height the board really takes (plus the gesture-bar inset), so a screen's own height
 * tiers shrink the form instead of the board covering the action button. When no field is engaged
 * the content gets the whole window back. The board's height is constant across every layer, so
 * this single relayout is the only one an owner ever sees — the screen resizes once, smoothly,
 * instead of bouncing per layer or per keystroke.
 *
 * Inset handling is deliberate: this column carries the [Modifier.imePadding] for the whole path
 * and the dock strip carries [Modifier.navigationBarsPadding]; the content must not re-apply either
 * on the same path, or the window pays the inset twice and jumps.
 */
@Composable
fun SecureKeyboardHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dock = remember { SecureKeyboardDock() }
    dock.boardView = LocalView.current
    CompositionLocalProvider(LocalSecureKeyboardDock provides dock) {
        // imePadding() is what makes this behave like the system keyboard: an IME that opens for any
        // field takes its height from the content column instead of sliding over it, and the gesture
        // bar inset is already part of the dock's own strip.
        //
        // The inset is then consumed, so exactly one layer pays for the keyboard. Without that, every
        // screen below a host — and three of them host themselves — asks the same inset for itself,
        // and the content is lifted by two or three keyboard heights at once. That double lift is the
        // jump the owner feels when the board opens.
        Column(
            modifier = modifier
                .imePadding()
                .consumeWindowInsets(WindowInsets.ime)
                .onGloballyPositioned { coords -> dock.hostBounds = coords.boundsInRoot() }
                .dismissBoardOnOutsideTap(dock),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content()
            }
            // The strip reads the dock's layout in its own recomposition scope, so publishing a
            // layout change never re-runs [content] above it.
            SecureDockStrip(dock)
        }
    }
}

@Composable
private fun SecureDockStrip(dock: SecureKeyboardDock) {
    val layout = dock.layout ?: return
    // The surface fill is outside the inset padding so the strip under the gesture bar
    // is part of the board rather than a hole in it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(VaultColor.surface.copy(alpha = 0.97f))
            .navigationBarsPadding()
            .onGloballyPositioned { coords -> dock.boardBounds = coords.boundsInRoot() },
    ) {
        SecureKeyboardPanel(
            layout = layout,
            onKeyPress = { key -> dock.press(key) },
            onDismiss = { dock.dismiss() },
        )
    }
}

/**
 * Auto-minimise: while a board is up, a tap anywhere that is neither a key on the board nor the
 * field that owns it closes the board — no close button required.
 *
 * The input observes events in [PointerEventPass.Initial], so the parent sees the down *before*
 * any child consumes it: nothing is eaten. A tap on another field dismisses this owner on the way
 * down and engages the new field on the way up — the board moves in a single gesture.
 */
private fun Modifier.dismissBoardOnOutsideTap(dock: SecureKeyboardDock): Modifier =
    pointerInput(dock) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (dock.isEngaged) {
                    val down = event.changes.firstOrNull { change ->
                        change.pressed && !change.previousPressed
                    }
                    if (down != null) {
                        // Positions arrive in this host's own space; [SecureKeyboardDock.hostBounds]
                        // shifts them into the root space the field and board rectangles use. Every
                        // host fills its window section from its own origin, so the shift is the
                        // honest transform rather than a guess.
                        val origin = dock.hostBounds?.topLeft ?: Offset.Zero
                        val position = origin + down.position
                        val onField = dock.ownerBounds?.contains(position) == true
                        val onBoard = dock.boardBounds?.contains(position) == true
                        if (!onField && !onBoard) dock.dismiss()
                    }
                }
            }
        }
    }
