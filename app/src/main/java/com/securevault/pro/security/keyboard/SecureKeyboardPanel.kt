package com.securevault.pro.security.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultPaletteController

/** One keycap. Narrow enough for ten across a phone, tall enough to hit without looking. */
private val KEY_CAP_HEIGHT = 42.dp

/** The pad a PIN is typed on gets dialer-sized keys, because it has only three of them per row. */
private val PIN_CAP_HEIGHT = 62.dp

/** The board's own chrome, kept thin so the form above it keeps its room. */
private val BOARD_PADDING = 6.dp

/** The gap between keycaps and between the board's rows. */
private val KEY_GAP = 5.dp

/** The header row's fixed height, so no layer can grow or shrink it. */
private val BOARD_HEADER_HEIGHT = 32.dp

/**
 * Every layer is four rows tall; this is the PIN pad's own grid height, and it is the grid height
 * of *every* layer. Letters, numbers and the pad all produce the same total board height, so
 * engaging a field or switching pages resizes nothing and moves the focused field not at all.
 */
private val BOARD_GRID_HEIGHT = PIN_CAP_HEIGHT * 4 + KEY_GAP * 3

/**
 * The tap feedback a keystroke gets. Android's own guidance for a soft-key tap is the light virtual
 * key tick, not the heavier long-press buzz (developer.android.com/develop/ui/views/haptics): a
 * shorter pulse means less motor engagement crowding the frame that has already painted the key.
 * Nothing about a press is timed — see [keyTap] — and the pulse fires only *after* the character has
 * committed, so muting it or missing it can never swallow a keystroke.
 */
private val KEY_HAPTIC = android.view.HapticFeedbackConstants.KEYBOARD_TAP

/**
 * Below this viewport height the board trims itself so its bottom row stays reachable — a landscape
 * phone or a small foldable cover screen cannot hold the full-height dock at the bottom of a
 * `fillMaxSize` host, and without this the last row would be pushed off the edge. Portrait phones sit
 * far above it, so the way the owner types most (upright) is never scaled.
 */
private const val SHORT_VIEWPORT_DP = 500

/** How far a short viewport shrinks the caps. Kept gentle so keys stay legible and still hittable. */
private const val SHORT_VIEWPORT_SCALE = 0.82f

/**
 * Fires the key-press pulse only while the owner keeps vibration on. The tap that reaches this point
 * has already committed its character, so muting the pulse must never swallow the key itself.
 */
private fun android.view.View.pressPulse(enabled: Boolean) {
    if (enabled) performHapticFeedback(KEY_HAPTIC)
}

/**
 * The in-app key grid.
 *
 * This is the only thing that can type into a secure field, and every tile comes from
 * [SecureKeyboardEngine] — the same pure layout the JVM tests assert against, so what a test proves
 * about tile order, shift and layer behaviour is what this draws.
 *
 * A press commits on the finger going *down*, in the same frame it lights the keycap: no release
 * wait, no `Animatable`, no scale or ripple. Keycaps are tapped through `pointerInput` rather than
 * `clickable`, on purpose: a `clickable` key takes focus when tapped, which would drop the field the
 * owner is typing into and fold the board away between characters.
 */
@Composable
fun SecureKeyboardPanel(
    layout: SecureKeyboardLayoutState,
    onKeyPress: (SecureKey) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalView.current
    val keyHapticsOn = LocalSecureKeyboardHaptics.current
    val palette = VaultPaletteController.current
    val rows = remember(layout) { SecureKeyboardEngine.rows(layout) }
    val isPinPad = layout.layer == SecureKeyLayer.NUMERIC
    // The owner picks the board size. Only the keycaps scale, never the gaps, and the grid is derived
    // from the pad's own scaled caps so every layer still resolves to one identical height — the
    // equal-height rule that stops the window jumping between letters and numbers.
    //
    // A short viewport shrinks that one shared scale, never the gaps, so the caps and the grid height
    // move together and every layer stays the same height as every other — the fit cannot reintroduce
    // the page-to-page bounce the equal-height rule prevents. Portrait is untouched (factor 1), so the
    // size the owner chose still means exactly the same thing where he does nearly all his typing.
    val configuredCapScale = when (LocalSecureBoardHeight.current) {
        1 -> 1.2f
        2 -> 1.4f
        else -> 1f
    }
    val viewportCapScale =
        if (LocalConfiguration.current.screenHeightDp < SHORT_VIEWPORT_DP) SHORT_VIEWPORT_SCALE
        else 1f
    val capScale = configuredCapScale * viewportCapScale
    val capHeight = (if (isPinPad) PIN_CAP_HEIGHT else KEY_CAP_HEIGHT) * capScale
    val gridHeight = (BOARD_GRID_HEIGHT - KEY_GAP * 3) * capScale + KEY_GAP * 3

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VaultColor.surface.copy(alpha = 0.97f))
            .border(1.dp, VaultColor.hairline, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(horizontal = 8.dp, vertical = BOARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BOARD_HEADER_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(start = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemedIcon(
                    glyph = Glyph.lock,
                    contentDescription = null,
                    tint = if (layout.shuffled) VaultColor.accent else VaultColor.textFaint,
                    modifier = Modifier.size(13.dp),
                    animate = false,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when {
                        isPinPad -> "Number pad"
                        layout.shuffled -> "Shuffled"
                        else -> "Secure board"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (layout.shuffled) VaultColor.accent else VaultColor.textFaint,
                )
            }
            // Caret navigation and buffer controls
            ControlCap(
                shape = palette.chipShape,
                contentDescription = "Move caret left",
                onClick = { onKeyPress(SecureKey.CursorLeft) },
                haptics = haptics,
                hapticsEnabled = keyHapticsOn,
            ) {
                ThemedIcon(
                    glyph = Glyph.arrowBack,
                    contentDescription = "Left",
                    tint = VaultColor.textBody,
                    modifier = Modifier.size(14.dp),
                    animate = false,
                )
            }
            Spacer(Modifier.width(4.dp))
            ControlCap(
                shape = palette.chipShape,
                contentDescription = "Move caret right",
                onClick = { onKeyPress(SecureKey.CursorRight) },
                haptics = haptics,
                hapticsEnabled = keyHapticsOn,
            ) {
                ThemedIcon(
                    glyph = Glyph.arrowForward,
                    contentDescription = "Right",
                    tint = VaultColor.textBody,
                    modifier = Modifier.size(14.dp),
                    animate = false,
                )
            }
            Spacer(Modifier.width(4.dp))
            ControlCap(
                shape = palette.chipShape,
                contentDescription = "Clear all text",
                onClick = { onKeyPress(SecureKey.Clear) },
                haptics = haptics,
                hapticsEnabled = keyHapticsOn,
            ) {
                ThemedIcon(
                    glyph = Glyph.close,
                    contentDescription = "Clear",
                    tint = VaultColor.textBody,
                    modifier = Modifier.size(14.dp),
                    animate = false,
                )
            }
            Spacer(Modifier.width(4.dp))
            ControlCap(
                shape = palette.chipShape,
                contentDescription = "Shuffle the board",
                onClick = { onKeyPress(SecureKey.Shuffle) },
                haptics = haptics,
                hapticsEnabled = keyHapticsOn,
                active = layout.shuffled,
            ) {
                ThemedIcon(
                    glyph = Glyph.shuffle,
                    contentDescription = "Shuffle the board",
                    tint = if (layout.shuffled) VaultColor.accent else VaultColor.textBody,
                    modifier = Modifier.size(14.dp),
                    animate = false,
                )
            }
            Spacer(Modifier.width(4.dp))
            ControlCap(
                shape = palette.chipShape,
                contentDescription = "Hide secure keyboard",
                onClick = onDismiss,
                haptics = haptics,
                hapticsEnabled = keyHapticsOn,
            ) {
                ThemedIcon(
                    glyph = Glyph.expandMore,
                    contentDescription = "Hide secure keyboard",
                    tint = VaultColor.textBody,
                    modifier = Modifier.size(16.dp),
                    animate = false,
                )
            }
        }

        // Weighted rows fill the grid exactly: the pad's dialer-sized caps fill their slots, and the
        // letter caps keep their scaled height with the extra air shared by the rows around them.
        // The grid is the same height on every layer at any board size, which is what keeps the
        // window from jumping when the owner switches letters <-> numbers or an amount opens the pad.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
            verticalArrangement = Arrangement.spacedBy(KEY_GAP),
        ) {
            rows.forEach { row ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            if (isPinPad) KEY_GAP * 2 else KEY_GAP
                        ),
                    ) {
                        val sidePadding = if (isPinPad) 4.dp else 0.dp
                        row.forEach { key ->
                            val accent = when {
                                key.kind == SecureKeyKind.SHIFT && layout.shifted -> VaultColor.accent
                                key.kind == SecureKeyKind.SHUFFLE && layout.shuffled -> VaultColor.accent
                                key.kind == SecureKeyKind.LAYER -> VaultColor.credit
                                else -> VaultColor.textStrong
                            }
                            KeyCap(
                                key = key,
                                accent = accent,
                                shape = palette.chipShape,
                                onKeyPress = onKeyPress,
                                haptics = haptics,
                                hapticsEnabled = keyHapticsOn,
                                bigText = isPinPad,
                                modifier = Modifier
                                    .weight(if (key.kind == SecureKeyKind.SPACE) 2.4f else 1f)
                                    .padding(horizontal = sidePadding)
                                    .height(capHeight),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(
    key: SecureKey,
    accent: Color,
    shape: Shape,
    onKeyPress: (SecureKey) -> Unit,
    haptics: android.view.View,
    hapticsEnabled: Boolean,
    modifier: Modifier = Modifier,
    bigText: Boolean = false,
) {
    val pressed = remember { mutableStateOf(false) }
    val isPressed = pressed.value
    val keyBg = if (isPressed) VaultColor.accent.copy(alpha = 0.90f) else VaultColor.elevated.copy(alpha = 0.85f)
    val keyBorder = if (isPressed) VaultColor.accent else VaultColor.hairline
    val keyBorderWidth = if (isPressed) 2.dp else 1.dp
    val ink = if (isPressed) VaultColor.background else accent

    Box(
        modifier = modifier
            .clip(shape)
            .background(keyBg, shape)
            .border(keyBorderWidth, keyBorder, shape)
            .keyTap(key = key, onKeyPress = onKeyPress, haptics = haptics, hapticsEnabled = hapticsEnabled, pressed = pressed),
        contentAlignment = Alignment.Center,
    ) {
        when (key.kind) {
            SecureKeyKind.SHIFT -> ThemedIcon(
                glyph = Glyph.arrowUpward,
                contentDescription = "Shift",
                tint = ink,
                modifier = Modifier.size(16.dp),
                animate = false,
            )

            SecureKeyKind.BACKSPACE -> ThemedIcon(
                glyph = Glyph.backspace,
                contentDescription = "Delete",
                tint = ink,
                modifier = Modifier.size(17.dp),
                animate = false,
            )

            SecureKeyKind.ENTER -> ThemedIcon(
                glyph = Glyph.arrowForward,
                contentDescription = "Enter",
                tint = ink,
                modifier = Modifier.size(16.dp),
                animate = false,
            )

            SecureKeyKind.SHUFFLE -> ThemedIcon(
                glyph = Glyph.shuffle,
                contentDescription = "Shuffle the board",
                tint = ink,
                modifier = Modifier.size(15.dp),
                animate = false,
            )

            SecureKeyKind.SPACE -> Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(3.dp)
                    .background(ink.copy(alpha = 0.75f), RoundedCornerShape(2.dp)),
            )

            else -> Text(
                text = key.label,
                style = if (bigText) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyMedium,
                color = ink,
                fontWeight = if (isPressed) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ControlCap(
    shape: Shape,
    contentDescription: String,
    onClick: () -> Unit,
    haptics: android.view.View,
    hapticsEnabled: Boolean,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    val pressed = remember { mutableStateOf(false) }
    val highlight = VaultColor.accent.copy(alpha = 0.24f)
    Box(
        modifier = Modifier
            .height(24.dp)
            .width(40.dp)
            .clip(shape)
            .background(
                if (active) VaultColor.accent.copy(alpha = 0.18f)
                else VaultColor.elevated.copy(alpha = 0.6f),
                shape
            )
            .border(1.dp, VaultColor.hairline, shape)
            .drawWithContent {
                drawContent()
                if (pressed.value) drawRect(color = highlight)
            }
            .pointerInput(contentDescription) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed.value = true
                    haptics.pressPulse(hapticsEnabled)
                    onClick()
                    try {
                        while (awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }) Unit
                    } finally {
                        pressed.value = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A press on a keycap. Deliberately not `clickable`: focus belongs to the field, not to the board,
 * and a `clickable` commits only when the finger lifts. This fires the moment the finger lands —
 * the character is written and the cap is lit in the same frame.
 *
 * Nothing about a press is timed. The earlier version `delay`ed inside the tap handler to clear the
 * highlight, which held the gesture loop out of its next wait, so a second key struck while the
 * first was still lighting up was swallowed. A recovery phrase is typed fast, and a dropped
 * character reads as a board that "fails to register the words". Here the press is committed on the
 * way down and the loop only waits for the finger to leave, so the next key is never blocked.
 */
private fun Modifier.keyTap(
    key: SecureKey,
    onKeyPress: (SecureKey) -> Unit,
    haptics: android.view.View,
    hapticsEnabled: Boolean,
    pressed: MutableState<Boolean>,
): Modifier = pointerInput(key.id, key.label) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        pressed.value = true
        onKeyPress(key)
        haptics.pressPulse(hapticsEnabled)
        try {
            while (awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }) Unit
        } finally {
            pressed.value = false
        }
    }
}
