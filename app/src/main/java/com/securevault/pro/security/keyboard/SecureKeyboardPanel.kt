package com.securevault.pro.security.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultPaletteController

/** The height the board reserves; a secure field is useless if the board cannot be seen. */
val SecureKeyboardHeight: Dp = 236.dp

/**
 * The in-app key grid.
 *
 * This is the only thing that can type into a secure field, and every tile comes from
 * [SecureKeyboardEngine] — the same pure layout the JVM tests assert against, so what a test proves
 * about tile order, shift and layer behaviour is what this draws.
 *
 * Keycaps are tapped through `pointerInput`/`detectTapGestures` rather than `clickable`, on
 * purpose: a `clickable` key takes focus when tapped, which would drop the field the owner is typing
 * into and fold the board away between characters.
 */
@Composable
fun SecureKeyboardPanel(
    layout: SecureKeyboardLayoutState,
    onKeyPress: (SecureKey) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val palette = VaultPaletteController.current
    val rows = remember(layout) { SecureKeyboardEngine.rows(layout) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VaultColor.surface.copy(alpha = 0.97f))
            .border(1.dp, VaultColor.hairline, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (layout.shuffled) "SHUFFLED BOARD · taps map to this order only"
                else "SECURE BOARD · the system keyboard never sees these taps",
                style = MaterialTheme.typography.labelSmall,
                color = if (layout.shuffled) VaultColor.accent else VaultColor.textFaint,
                modifier = Modifier.weight(1f),
            )
            ControlCap(
                shape = palette.chipShape,
                contentDescription = "Hide secure keyboard",
                onClick = onDismiss,
                haptics = haptics,
            ) {
                ThemedIcon(
                    glyph = Glyph.expandMore,
                    contentDescription = "Hide secure keyboard",
                    tint = VaultColor.textBody,
                    modifier = Modifier.size(18.dp),
                    animate = false,
                )
            }
        }

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
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
                        modifier = Modifier
                            .weight(if (key.kind == SecureKeyKind.SPACE) 2.4f else 1f)
                            .height(46.dp),
                    )
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
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(VaultColor.elevated.copy(alpha = 0.85f), shape)
            .border(1.dp, VaultColor.hairline, shape)
            .keyTap(key = key, onKeyPress = onKeyPress, haptics = haptics),
        contentAlignment = Alignment.Center,
    ) {
        when (key.kind) {
            SecureKeyKind.SHIFT -> ThemedIcon(
                glyph = Glyph.arrowUpward,
                contentDescription = "Shift",
                tint = accent,
                modifier = Modifier.size(19.dp),
                animate = false,
            )

            SecureKeyKind.BACKSPACE -> ThemedIcon(
                glyph = Glyph.backspace,
                contentDescription = "Delete",
                tint = accent,
                modifier = Modifier.size(20.dp),
                animate = false,
            )

            SecureKeyKind.ENTER -> ThemedIcon(
                glyph = Glyph.arrowForward,
                contentDescription = "Enter",
                tint = accent,
                modifier = Modifier.size(19.dp),
                animate = false,
            )

            SecureKeyKind.SHUFFLE -> ThemedIcon(
                glyph = Glyph.shuffle,
                contentDescription = "Shuffle the board",
                tint = accent,
                modifier = Modifier.size(18.dp),
                animate = false,
            )

            SecureKeyKind.SPACE -> Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(3.dp)
                    .background(accent.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
            )

            else -> Text(
                text = key.label,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Medium,
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
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .width(64.dp)
            .clip(shape)
            .background(VaultColor.elevated.copy(alpha = 0.6f), shape)
            .border(1.dp, VaultColor.hairline, shape)
            .pointerInput(contentDescription) {
                detectTapGestures {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A press on a keycap. Deliberately not `clickable`: focus belongs to the field, not to the board.
 */
private fun Modifier.keyTap(
    key: SecureKey,
    onKeyPress: (SecureKey) -> Unit,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
): Modifier = pointerInput(key.id, key.label) {
    detectTapGestures {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onKeyPress(key)
    }
}
