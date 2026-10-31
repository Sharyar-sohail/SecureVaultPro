package com.securevault.pro.security.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    val dock = LocalSecureKeyboardDock.current
    val focusRequester = remember { FocusRequester() }

    var buffer by remember { mutableStateOf(SecureTextBuffer.of(value)) }
    var layout by remember {
        mutableStateOf(SecureKeyboardLayoutState(SecureKeyboardLayoutState.newSessionSeed()))
    }
    var engaged by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var caretRect by remember { mutableStateOf<Rect?>(null) }

    // The caller owns the value (a ViewModel field can be cleared by an error, a draft restore or a
    // successful submit). Adopt an outside change, keeping the caret where it still fits.
    LaunchedEffect(value) {
        if (value != buffer.text) {
            buffer = SecureTextBuffer(value, cursor = buffer.cursor.coerceAtMost(value.length))
        }
    }

    LaunchedEffect(engaged) {
        if (engaged) {
            keyboard?.hide()
            SecureIme.detachFromSystemKeyboard(view)
        }
    }

    fun handle(command: SecureKeyCommand) {
        when (command) {
            is SecureKeyCommand.Insert -> {
                buffer = buffer.insert(command.text)
                onValueChange(buffer.text)
            }

            SecureKeyCommand.Backspace -> {
                buffer = buffer.backspace()
                onValueChange(buffer.text)
            }

            SecureKeyCommand.Enter -> when {
                onSubmit != null -> {
                    engaged = false
                    onSubmit()
                }

                !singleLine -> {
                    buffer = buffer.appendNewline()
                    onValueChange(buffer.text)
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

    // Docked mode: the host owns the board, this field publishes its state to it.
    if (dock != null) {
        LaunchedEffect(engaged, layout, buffer.text) {
            if (engaged) {
                dock.show(layout = layout, onKeyPress = ::press, onDismiss = { engaged = false })
            }
        }
        DisposableEffect(engaged, dock) {
            onDispose { if (engaged) dock.dismiss() }
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
                .clickable {
                    engaged = true
                    runCatching { focusRequester.requestFocus() }
                },
        )

        if (dock == null && engaged) {
            SecureKeyboardPanel(
                layout = layout,
                onKeyPress = ::press,
                onDismiss = { engaged = false },
                modifier = Modifier.padding(top = 10.dp),
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
                    val offset = cursor.coerceAtMost(result.layoutInput.text.length)
                    caretRectOf(result, offset)?.let(onCaretRect)
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
                            .height(rect.height.dp.coerceAtLeast(16.dp))
                            .background(VaultColor.accent)
                    )
                } else {
                    // Before the first layout pass the caret sits at the start of the line, so the
                    // field never looks dead while the owner is choosing where to type.
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(2.dp)
                            .height(20.dp)
                            .background(VaultColor.accent)
                    )
                }
            }
        }
    }
}

/**
 * The caret rectangle for [offset], or null when the layout cannot answer yet.
 *
 * A read-only editor with a visual transformation can be asked for a position outside the painted
 * text for one frame; that is not an error worth crashing the screen over.
 */
private fun caretRectOf(result: TextLayoutResult, offset: Int): Rect? =
    runCatching { result.getCursorRect(offset) }.getOrNull()

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
 * owns the board.
 */
@Stable
class SecureKeyboardDock internal constructor() {
    var layout by mutableStateOf<SecureKeyboardLayoutState?>(null)
        private set

    private var onKeyPress: ((SecureKey) -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null

    /** True while some field is asking for the board. */
    val isEngaged: Boolean get() = layout != null

    internal fun show(
        layout: SecureKeyboardLayoutState,
        onKeyPress: (SecureKey) -> Unit,
        onDismiss: () -> Unit,
    ) {
        this.layout = layout
        this.onKeyPress = onKeyPress
        this.onDismiss = onDismiss
    }

    internal fun press(key: SecureKey) {
        onKeyPress?.invoke(key)
    }

    /** Drop the board and hand the field back, clearing every handler with it. */
    internal fun dismiss() {
        val handler = onDismiss
        layout = null
        onKeyPress = null
        onDismiss = null
        handler?.invoke()
    }
}

/** Provided by [SecureKeyboardHost]; null means "draw the board under the field". */
val LocalSecureKeyboardDock = staticCompositionLocalOf<SecureKeyboardDock?> { null }

/**
 * Hosts the secure board once, at the bottom, for a whole screen.
 *
 * The board is a sibling of the content, not an overlay: while it is up, the content is measured
 * against the height the board really takes (plus the gesture-bar inset), so a screen's own height
 * tiers shrink the form instead of the board covering the action button. When no field is engaged
 * the content gets the whole window back.
 */
@Composable
fun SecureKeyboardHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dock = remember { SecureKeyboardDock() }
    CompositionLocalProvider(LocalSecureKeyboardDock provides dock) {
        Column(modifier = modifier) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content()
            }
            val layout = dock.layout
            if (layout != null) {
                // The surface fill is outside the inset padding so the strip under the gesture bar
                // is part of the board rather than a hole in it.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VaultColor.surface.copy(alpha = 0.97f))
                        .navigationBarsPadding(),
                ) {
                    SecureKeyboardPanel(
                        layout = layout,
                        onKeyPress = { key -> dock.press(key) },
                        onDismiss = { dock.dismiss() },
                    )
                }
            }
        }
    }
}
