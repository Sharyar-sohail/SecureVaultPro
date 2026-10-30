package com.securevault.pro.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor

/**
 * Blocking, full-screen progress surface for Google Drive sign-in and vault restore.
 * Back/outside taps are ignored so the post-sign-in pipeline cannot be interrupted.
 * [onCancel] is supplied only once the caller decides the wait has run long enough,
 * so a hung request can never trap the user on a screen with no way out.
 */
@Composable
fun CloudOperationOverlay(
    visible: Boolean,
    title: String,
    detail: String,
    percent: Int,
    onCancel: (() -> Unit)? = null,
    cancelLabel: String = "Stop waiting and continue"
) {
    if (!visible) return
    val palette = VaultPaletteController.current
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // A centered Box inside a verticalScroll is the trap: when the panel is taller than the
        // viewport, centering places its top ABOVE the scroll origin, and a scroll container can
        // never travel to a negative offset — so the first stage row and the title were cut off for
        // good. A scrollable Column with a centered arrangement centres the panel only when it
        // fits, and hands the whole height to the scroll when it does not.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(VaultColor.background.copy(alpha = 0.97f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                accent = VaultColor.accent,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(VaultColor.accent.copy(alpha = 0.22f), VaultColor.credit.copy(alpha = 0.08f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 3.dp,
                            color = VaultColor.accent
                        )
                        ThemedIcon(
                            glyph = Glyph.cloudDownload,
                            contentDescription = null,
                            tint = VaultColor.accent,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = VaultColor.textStrong,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(palette.surfaceOverlay.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((percent.coerceIn(0, 100) / 100f))
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(VaultColor.accent, VaultColor.credit)))
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "End-to-end encrypted",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                        Text(
                            text = "${percent.coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    OverlayStageRow(
                        icon = if (percent >= 45) Glyph.checkCircle else Glyph.cloudDownload,
                        label = "Downloading data from Google Drive...",
                        state = when {
                            percent >= 45 -> StageState.DONE
                            percent >= 8 -> StageState.ACTIVE
                            else -> StageState.PENDING
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    OverlayStageRow(
                        icon = if (percent >= 65) Glyph.checkCircle else Glyph.lock,
                        label = "Decrypting vault...",
                        state = when {
                            percent >= 65 -> StageState.DONE
                            percent >= 45 -> StageState.ACTIVE
                            else -> StageState.PENDING
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    OverlayStageRow(
                        icon = if (percent >= 100) Glyph.checkCircle else Glyph.systemUpdate,
                        label = "Importing data into app...",
                        state = when {
                            percent >= 100 -> StageState.DONE
                            percent >= 65 -> StageState.ACTIVE
                            else -> StageState.PENDING
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    if (onCancel == null) {
                        Text(
                            text = "Keep this screen open while your vault is restored.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "This is taking longer than expected. You can stop waiting and set up a vault on this device instead - nothing has been erased.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        GhostButton(
                            text = cancelLabel,
                            accent = VaultColor.accent,
                            onClick = onCancel
                        )
                    }
                }
            }
        }
    }
}

private enum class StageState { PENDING, ACTIVE, DONE }

@Composable
private fun OverlayStageRow(
    icon: Glyph,
    label: String,
    state: StageState
) {
    val tint = when (state) {
        StageState.DONE -> VaultColor.credit
        StageState.ACTIVE -> VaultColor.accent
        StageState.PENDING -> VaultColor.textFaint.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (state == StageState.PENDING) VaultColor.textFaint else VaultColor.textStrong,
            fontWeight = if (state == StageState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.width(8.dp))
        if (state == StageState.ACTIVE) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.6.dp,
                color = VaultColor.accent
            )
        }
    }
}
