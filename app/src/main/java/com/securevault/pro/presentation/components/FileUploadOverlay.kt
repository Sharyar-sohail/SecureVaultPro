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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor

/**
 * Blocking progress surface shown while an imported file is read, sealed and
 * mirrored to Drive. It stays up until the cloud sync settles so the user can
 * see that the upload actually left the device.
 *
 * [onStop] is the mandatory escape: this dialog swallows the back button on purpose, so a caller
 * that hands it a cancellable operation must also hand it a way to stop waiting.
 */
@Composable
fun FileUploadOverlay(
    visible: Boolean,
    title: String,
    detail: String,
    percent: Int,
    cloud: Boolean,
    failed: Boolean,
    onStop: (() -> Unit)? = null
) {
    if (!visible) return
    val palette = VaultPaletteController.current
    val accent = if (failed) VaultColor.debit else VaultColor.accent
    val safePercent = percent.coerceIn(0, 100)
    val complete = safePercent >= 100 && !failed
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // Same trap as the sync overlay: a centered Box inside a verticalScroll puts the top of an
        // over-tall panel above the scroll origin, where no scroll offset can reach it. A
        // scrollable Column centres the panel only while it fits, and yields the full height to the
        // scroll once it does not.
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
                accent = accent,
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
                                    listOf(accent.copy(alpha = 0.22f), VaultColor.credit.copy(alpha = 0.08f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!complete) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(56.dp),
                                strokeWidth = 3.dp,
                                color = accent
                            )
                        }
                        ThemedIcon(
                            glyph = when {
                                failed -> Glyph.errorOutline
                                complete -> Glyph.checkCircle
                                else -> Glyph.cloudUpload
                            },
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = title.ifBlank { "Uploading file" },
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
                                .fillMaxWidth(safePercent / 100f)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        if (failed) listOf(VaultColor.debit, VaultColor.warning) else listOf(VaultColor.accent, VaultColor.credit)
                                    )
                                )
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "AES-256-GCM sealed",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                        Text(
                            text = "$safePercent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    UploadStageRow(
                        icon = Glyph.description,
                        label = "Reading file...",
                        done = safePercent >= 45 || failed,
                        active = safePercent in 1..44 && !failed
                    )
                    Spacer(Modifier.height(10.dp))
                    UploadStageRow(
                        icon = Glyph.lock,
                        label = "Encrypting file...",
                        done = safePercent >= 60 || failed,
                        active = safePercent in 45..59 && !failed
                    )
                    Spacer(Modifier.height(10.dp))
                    UploadStageRow(
                        icon = Glyph.cloudUpload,
                        label = if (cloud) "Uploading to Google Drive..." else "Saving to this device...",
                        done = safePercent >= 100 && !failed,
                        active = safePercent >= 60 && safePercent < 100 && !failed
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = when {
                            failed -> "Your file remains safe in the local vault. Cloud sync will retry."
                            cloud -> "Keep this screen open while the encrypted file is mirrored to Drive."
                            else -> "The file is sealed in the local vault."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        textAlign = TextAlign.Center
                    )
                    if (onStop != null && !complete) {
                        Spacer(Modifier.height(16.dp))
                        GhostButton(
                            text = "Stop and go back",
                            icon = Glyph.stop,
                            accent = accent,
                            onClick = onStop
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Nothing half-written is kept.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadStageRow(
    icon: Glyph,
    label: String,
    done: Boolean,
    active: Boolean
) {
    val tint = when {
        done -> VaultColor.credit
        active -> VaultColor.accent
        else -> VaultColor.textFaint.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = if (done) Glyph.checkCircle else icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (active || done) VaultColor.textStrong else VaultColor.textFaint,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.width(8.dp))
        if (active) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.6.dp,
                color = VaultColor.accent
            )
        }
    }
}
