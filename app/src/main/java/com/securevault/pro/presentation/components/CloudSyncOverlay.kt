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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
 * [onCancel] is supplied once the caller decides the wait has run long enough; the system back
 * gesture takes the same path, so an armed exit is reachable with either input.
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
    val pct = percent.coerceIn(0, 100)
    val done = pct >= 100
    Dialog(
        onDismissRequest = { onCancel?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onCancel != null,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // A centered Box inside a verticalScroll is the trap: when the panel is taller than the
        // viewport, centering places its top ABOVE the scroll origin, and a scroll container can
        // never travel to a negative offset — so content was cut off for good. A scrollable Column
        // with a centered arrangement centres the panel only when it fits, and hands the whole
        // height to the scroll when it does not.
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
                        if (done) {
                            ThemedIcon(
                                glyph = Glyph.checkCircle,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(18.dp))
                    if (pct <= 0) {
                        // No step has reported a percentage yet: an indeterminate bar is honest
                        // where a "0%" bar reads as a stalled transfer.
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = VaultColor.accent,
                            trackColor = palette.surfaceOverlay.copy(alpha = 0.08f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceOverlay.copy(alpha = 0.08f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(pct / 100f)
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(Brush.horizontalGradient(listOf(VaultColor.accent, VaultColor.credit)))
                            )
                        }
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
                            text = when {
                                done -> "Done"
                                pct > 0 -> "$pct%"
                                else -> "Working…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (done) VaultColor.credit else VaultColor.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    if (done) {
                        Text(
                            text = "Finished.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            textAlign = TextAlign.Center
                        )
                    } else if (onCancel == null) {
                        Text(
                            text = "Keep this screen open while this finishes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "You can stop waiting and keep using the app - the transfer may still finish on its own, and nothing has been erased.",
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
