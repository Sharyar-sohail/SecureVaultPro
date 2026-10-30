package com.securevault.pro.presentation.screens.scanner

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.formatBytes
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.readDocumentBytes
import com.securevault.pro.presentation.components.vaultGutter
import kotlinx.coroutines.launch
import com.securevault.pro.platform.print.VaultPrint
import com.securevault.pro.platform.share.VaultShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun FileScannerScreen(
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    viewModel: FileScannerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalSecureClipboard.current
    var pickError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val printAvailable = remember(context) { VaultPrint.isAvailable(context) }
    val canPrint = state.hasResult && VaultShare.isPrintable(state.name)

    fun announce(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /**
     * Prints the file that was just inspected: the decrypted sealed copy once it is in the vault,
     * otherwise the exact bytes on screen. `VaultShare.printBytes` is the only route to the print
     * framework, and its failure stages are named instead of leaving a dead button.
     */
    fun printImported() {
        if (!printAvailable) {
            announce("Print is unavailable: this device has no print service.")
            return
        }
        if (!canPrint) {
            announce("This file type has no printable page. Share hands the file over instead.")
            return
        }
        scope.launch {
            val snapshot = viewModel.state.value
            val bytes = viewModel.exportableBytes()
            if (bytes == null) {
                announce("There is no file to print.")
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                VaultShare.printBytes(context, bytes, snapshot.name) { failure ->
                    scope.launch { snackbar.showSnackbar(failure) }
                }
            }
            snackbar.showSnackbar(
                outcome.message(
                    "Print job started · the decrypted file went to the system print service."
                )
            )
        }
    }

    /** Opens the chooser with one decrypted copy; the chooser itself is the confirmation. */
    fun shareImported() {
        scope.launch {
            val snapshot = viewModel.state.value
            val bytes = viewModel.exportableBytes()
            if (bytes == null) {
                announce("There is no file to share.")
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                VaultShare.shareBytes(
                    context,
                    bytes,
                    snapshot.name,
                    snapshot.mimeType.ifBlank { null }
                )
            }
            snackbar.showSnackbar(
                outcome.message(
                    "Share sheet opened · a decrypted copy was written to the app cache."
                )
            )
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = queryDisplayName(context, uri, "scanned_file")
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = readDocumentBytes(context, uri, limit = 55L * 1024L * 1024L)
                Triple(name, mime, bytes)
            }.onSuccess { (name, mime, bytes) ->
                if (bytes.isEmpty()) {
                    pickError = "That file is empty."
                } else {
                    pickError = null
                    viewModel.inspect(name, mime, bytes)
                }
            }.onFailure {
                pickError = it.message ?: "The selected file could not be read."
            }
        }
    }

    val openPicker: () -> Unit = {
        try {
            picker.launch(arrayOf("*/*"))
        } catch (failure: Throwable) {
            pickError = "This device does not expose a file picker."
        }
    }

    val accent = when (state.risk) {
        FileRisk.HIGH -> VaultColor.debit
        FileRisk.MEDIUM -> VaultColor.warning
        FileRisk.LOW -> VaultColor.credit
        null -> VaultColor.accent
    }
    val riskLabel = when (state.risk) {
        FileRisk.HIGH -> "High risk"
        FileRisk.MEDIUM -> "Review advised"
        FileRisk.LOW -> "Low risk"
        null -> "Awaiting file"
    }

    GlassScaffold(
        title = "File inspector",
        subtitle = "SHA-256 · signature analysis · encrypted vault",
        onBack = onBack,
        snackbarHostState = snackbar
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = vaultGutter(), end = vaultGutter(), top = 4.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(accent = VaultColor.debit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(VaultShapes.card)
                                .background(VaultColor.debit.copy(alpha = 0.12f))
                                .border(1.dp, VaultColor.debit.copy(alpha = 0.3f), VaultShapes.card),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.security,
                                contentDescription = null,
                                tint = VaultColor.debit,
                                modifier = Modifier.size(27.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Inspect before you trust",
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Hashes the file, checks executable signatures and flags risky extensions before sealing it in the vault.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GradientButton(
                        text = if (state.hasResult) "Choose another file" else "Choose a file",
                        icon = Glyph.uploadFile,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.inspecting && !state.sealing,
                        onClick = openPicker
                    )
                }
            }

            (pickError ?: state.error)?.let { message ->
                item {
                    GlassCard(accent = VaultColor.debit, borderColor = VaultColor.debit.copy(alpha = 0.3f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.errorOutline,
                                contentDescription = null,
                                tint = VaultColor.debit,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (state.inspecting) {
                item {
                    GlassCard(accent = VaultColor.accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.4.dp,
                                color = VaultColor.accent
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Inspecting ${state.name}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = VaultColor.textStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Hashing and checking signatures in the background",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColor.textBody
                                )
                            }
                        }
                    }
                }
            }

            if (state.hasResult && !state.inspecting) {
                item {
                    GlassCard(
                        accent = accent,
                        borderColor = accent.copy(alpha = 0.35f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIconTile(
                                icon = when (state.risk) {
                                    FileRisk.HIGH -> Glyph.warning
                                    FileRisk.MEDIUM -> Glyph.errorOutline
                                    else -> Glyph.shield
                                },
                                contentDescription = null,
                                accent = accent,
                                size = 48.dp,
                                iconSize = 24.dp,
                                background = accent.copy(alpha = 0.12f)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = riskLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = accent
                                )
                                Text(
                                    text = state.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColor.textStrong,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TagChip(
                                text = state.risk?.name ?: "—",
                                accent = accent
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        state.reasons.forEach { reason ->
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(5.dp)
                                        .clip(VaultShapes.tag)
                                        .background(accent)
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColor.textBody,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }

                item {
                    GlassCard {
                        Text(
                            text = "FILE FORENSICS",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                            color = VaultColor.textFaint
                        )
                        Spacer(Modifier.height(12.dp))
                        ForensicRow("Detected type", state.detectedType, VaultColor.accent)
                        ForensicRow("MIME", state.mimeType, VaultColor.accent)
                        ForensicRow("Size", formatBytes(state.size), VaultColor.accent)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SHA-256",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint,
                                modifier = Modifier.weight(1f)
                            )
                            GlassIconButton(
                                icon = Glyph.contentCopy,
                                contentDescription = "Copy SHA-256 fingerprint",
                                onClick = { clipboard.copy(state.sha256, "SHA-256") },
                                tint = VaultColor.textBody,
                                background = Color.Transparent,
                                size = 28.dp
                            )
                        }
                        Text(
                            text = state.sha256,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.2.sp
                            ),
                            color = VaultColor.textBody,
                            lineHeight = 17.sp
                        )
                    }
                }

                item {
                    GlassCard(
                        accent = if (state.sealedName != null) VaultColor.credit else accent,
                        borderColor = if (state.sealedName != null) {
                            VaultColor.credit.copy(alpha = 0.35f)
                        } else {
                            VaultColor.hairline
                        }
                    ) {
                        if (state.sealedName != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ThemedIcon(
                                    glyph = Glyph.checkCircle,
                                    contentDescription = null,
                                    tint = VaultColor.credit,
                                    modifier = Modifier.size(23.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "Sealed in the file vault",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = VaultColor.textStrong
                                    )
                                    Text(
                                        text = state.sealedName ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = VaultColor.textBody,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GhostButton(
                                    text = "Open files",
                                    icon = Glyph.folder,
                                    accent = VaultColor.credit,
                                    modifier = Modifier.weight(1f),
                                    onClick = onOpenFiles
                                )
                                GhostButton(
                                    text = "Inspect another",
                                    icon = Glyph.refresh,
                                    accent = VaultColor.accent,
                                    modifier = Modifier.weight(1f),
                                    onClick = viewModel::reset
                                )
                            }
                        } else {
                            Text(
                                text = if (state.tooLargeForVault) {
                                    "This file is larger than the 50 MB vault limit. Inspect only."
                                } else {
                                    "Sealing stores the original bytes with AES-256-GCM in the private phone vault. The file never leaves this device unencrypted."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.tooLargeForVault) VaultColor.warning else VaultColor.textBody,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GradientButton(
                                    text = "Seal in vault",
                                    icon = Glyph.lock,
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.tooLargeForVault && !state.sealing,
                                    loading = state.sealing,
                                    onClick = viewModel::seal
                                )
                                GhostButton(
                                    text = "Discard",
                                    icon = Glyph.refresh,
                                    accent = VaultColor.warning,
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.sealing,
                                    onClick = viewModel::reset
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton(
                                text = "Print",
                                icon = Glyph.description,
                                accent = VaultColor.credit,
                                modifier = Modifier.weight(1f),
                                enabled = canPrint && printAvailable && !state.sealing,
                                onClick = { printImported() }
                            )
                            GhostButton(
                                text = "Share",
                                icon = Glyph.share,
                                accent = VaultColor.accent,
                                modifier = Modifier.weight(1f),
                                enabled = !state.sealing,
                                onClick = { shareImported() }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when {
                                !printAvailable ->
                                    "Print is disabled: this device has no print service. Share still works."
                                !canPrint ->
                                    "This file type has no printable page. Share hands over the original."
                                else ->
                                    "Print sends the decrypted file to the system print service. Share writes one decrypted copy to the app cache."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            if (!state.hasResult && !state.inspecting) {
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(VaultShapes.button)
                                    .background(VaultColor.accent.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                ThemedIcon(
                                    glyph = Glyph.folderOpen,
                                    contentDescription = null,
                                    tint = VaultColor.accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "What gets checked",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = VaultColor.textStrong
                                )
                                Text(
                                    text = "Magic-byte type, dangerous and double extensions, macro formats, executable headers, mismatched MIME types and SHA-256 fingerprint.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColor.textBody,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.button)
                        .background(VaultColor.background.copy(alpha = 0.4f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.security,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "Heuristic inspection is not a substitute for dedicated anti-malware. Files are never opened or executed by this scanner.",
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
private fun ForensicRow(label: String, value: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            modifier = Modifier.width(104.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "•",
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}
