package com.securevault.pro.presentation.screens.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun ScanHubScreen(
    onBack: () -> Unit,
    onOpenOcr: () -> Unit,
    onOpenDocument: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenFiles: () -> Unit
) {
    val palette = VaultPaletteController.current
    GlassScaffold(
        title = "Scan studio",
        subtitle = "Capture · recognize · encrypt",
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = vaultGutter(), end = vaultGutter(), top = 4.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(accent = VaultColor.credit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(VaultShapes.card)
                                .background(VaultColor.credit.copy(alpha = 0.12f))
                                .border(1.dp, VaultColor.credit.copy(alpha = 0.3f), VaultShapes.card),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.documentScanner,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Turn paper into ciphertext",
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Three on-device scanners share one encrypted pipeline. Results only ever land inside your vault.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TagChip("NO UPLOAD", accent = VaultColor.credit)
                        TagChip("ML KIT", accent = VaultColor.accent)
                        TagChip("AES-256", accent = VaultColor.warning)
                    }
                }
            }

            item {
                ModeCard(
                    icon = Glyph.textFields,
                    title = "OCR text scanner",
                    detail = "Extract selectable text from photos, receipts, whiteboards and screenshots. Save as an encrypted note.",
                    accent = VaultColor.accent,
                    badge = "ML KIT",
                    onClick = onOpenOcr
                )
            }
            item {
                ModeCard(
                    icon = Glyph.documentScanner,
                    title = "Document scanner",
                    detail = "Capture up to 30 pages in any order and assemble a sealed A4 PDF with corrected orientation.",
                    accent = VaultColor.warning,
                    badge = "PDF",
                    onClick = onOpenDocument
                )
            }
            item {
                ModeCard(
                    icon = Glyph.security,
                    title = "File inspector",
                    detail = "Fingerprint any file with SHA-256, detect executable signatures and risky extensions, then seal it.",
                    accent = VaultColor.debit,
                    badge = "SHA-256",
                    onClick = onOpenFile
                )
            }

            item {
                GlassCard {
                    Text(
                        text = "PIPELINE",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        color = VaultColor.textFaint
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        PipelineStep(1, "Capture", "Camera or gallery", VaultColor.accent)
                        PipelineArrow()
                        PipelineStep(2, "Analyze", "On-device only", VaultColor.credit)
                        PipelineArrow()
                        PipelineStep(3, "Seal", "Encrypted vault", VaultColor.warning)
                    }
                }
            }

            item {
                GlassCard(
                    accent = VaultColor.accent,
                    borderColor = VaultColor.credit.copy(alpha = 0.25f),
                    onClick = onOpenFiles
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(VaultShapes.input)
                                .background(VaultColor.credit.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.folder,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Open sealed scans",
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Browse the encrypted file vault",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody
                            )
                        }
                        TagChip("FILES", accent = VaultColor.credit)
                        ThemedIcon(
                            glyph = Glyph.chevronRight,
                            contentDescription = null,
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.button)
                        .background(palette.surfaceOverlay.copy(alpha = 0.03f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.lock,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "Camera frames are analysed in memory and discarded. There is no analytics, no network call and no plaintext scan on disk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textFaint,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: Glyph,
    title: String,
    detail: String,
    accent: Color,
    badge: String,
    onClick: () -> Unit
) {
    GlassCard(
        accent = accent,
        contentPadding = PaddingValues(15.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = icon,
                contentDescription = null,
                accent = accent,
                size = 48.dp,
                iconSize = 23.dp,
                background = accent.copy(alpha = 0.12f)
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(7.dp))
                    TagChip(badge, accent = accent)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = VaultColor.textFaint,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun PipelineStep(index: Int, title: String, detail: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(VaultPaletteController.current.chipShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = VaultColor.textStrong
        )
        Text(
            text = detail,
            style = VaultType.micro(),
            color = VaultColor.textFaint
        )
    }
}

@Composable
private fun PipelineArrow() {
    Text(
        text = "→",
        style = MaterialTheme.typography.titleMedium,
        color = VaultColor.textFaint.copy(alpha = 0.6f)
    )
}
