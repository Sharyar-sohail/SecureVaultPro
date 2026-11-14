package com.securevault.pro.presentation.screens.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.OperationProgressCard
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
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
fun DocumentScannerScreen(
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    viewModel: DocumentScannerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var captureError by remember { mutableStateOf<String?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var pendingReview by remember { mutableStateOf<LiveCapture?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val printAvailable = remember(context) { VaultPrint.isAvailable(context) }

    fun announce(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /**
     * Hands the decrypted PDF to the system print service through `VaultShare.printBytes`, so the
     * failure stages (no print service, unprintable, staging refused) are named on screen instead
     * of a button that appears to work and prints nothing.
     */
    fun printDocument() {
        if (!printAvailable) {
            announce("Print is unavailable: this device has no print service.")
            return
        }
        scope.launch {
            val bytes = viewModel.exportablePdf()
            if (bytes == null) {
                announce("There is no page to print yet.")
                return@launch
            }
            val name = viewModel.exportableName()
            val outcome = withContext(Dispatchers.IO) {
                VaultShare.printBytes(context, bytes, name) { failure ->
                    scope.launch { snackbar.showSnackbar(failure) }
                }
            }
            snackbar.showSnackbar(
                outcome.message(
                    "Print job started · the decrypted pages went to the system print service."
                )
            )
        }
    }

    /** Opens the chooser with one decrypted PDF copy; the chooser itself is the confirmation. */
    fun shareDocument() {
        scope.launch {
            val bytes = viewModel.exportablePdf()
            if (bytes == null) {
                announce("There is no page to share yet.")
                return@launch
            }
            val name = viewModel.exportableName()
            val outcome = withContext(Dispatchers.IO) { VaultShare.sharePdf(context, bytes, name) }
            snackbar.showSnackbar(
                outcome.message(
                    "Share sheet opened · a decrypted PDF copy was written to the app cache."
                )
            )
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val images = ArrayList<ByteArray>()
            var totalBytes = 0L
            for (uri in uris.take(12)) {
                val bytes = runCatching {
                    readDocumentBytes(context, uri, limit = 20L * 1024L * 1024L)
                }.getOrNull() ?: continue
                totalBytes += bytes.size
                if (totalBytes > 48L * 1024L * 1024L) break
                images += bytes
            }
            if (images.isEmpty()) {
                captureError = "Those images could not be opened."
            } else {
                captureError = null
                viewModel.addPages(images)
            }
        }
    }

    val openCamera: () -> Unit = open@{
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            captureError = "This device has no camera. Pick pages from the gallery instead."
            return@open
        }
        captureError = null
        showCamera = true
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCamera()
        } else {
            captureError = "Camera access is required to scan pages. Allow it in Settings → Apps → SECURE VAULT PRO → Permissions, or use Gallery."
        }
    }

    val launchCamera: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) openCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val openGallery: () -> Unit = {
        try {
            galleryLauncher.launch("image/*")
        } catch (failure: Throwable) {
            captureError = "This device does not expose a document picker."
        }
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            DocumentCameraSheet(
                onDismiss = { showCamera = false },
                onCaptured = { capture ->
                    showCamera = false
                    pendingReview = capture
                }
            )
        }
    }

    pendingReview?.let { capture ->
        Dialog(
            onDismissRequest = { pendingReview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            PageReviewSheet(
                capture = capture,
                position = state.pageCount + 1,
                onRetake = {
                    pendingReview = null
                    showCamera = true
                },
                onDismiss = { pendingReview = null },
                onConfirm = { jpeg ->
                    pendingReview = null
                    captureError = null
                    viewModel.addScannedPage(jpeg)
                }
            )
        }
    }

    GlassScaffold(
        title = "Document scanner",
        subtitle = "Live edge detect · crop · enhance",
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
                GlassCard(accent = VaultColor.warning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(VaultShapes.card)
                                .background(VaultColor.warning.copy(alpha = 0.12f))
                                .border(1.dp, VaultColor.warning.copy(alpha = 0.3f), VaultShapes.card),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.documentScanner,
                                contentDescription = null,
                                tint = VaultColor.warning,
                                modifier = Modifier.size(27.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Paper, receipts, IDs",
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Point at a page and it is tracked live, shuttered when steady, then straightened. Adjust any corner or switch to full-frame before the page is kept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 18.sp
                            )
                        }
                        TagChip("${state.pageCount}/30", accent = VaultColor.warning)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton(
                            text = if (state.pages.isEmpty()) "Scan first page" else "Add page",
                            icon = Glyph.cameraAlt,
                            modifier = Modifier.weight(1f),
                            enabled = !state.processing && !state.sealing && state.pageCount < 30,
                            onClick = launchCamera
                        )
                        GhostButton(
                            text = "Gallery",
                            icon = Glyph.photoLibrary,
                            accent = VaultColor.warning,
                            modifier = Modifier.weight(1f),
                            enabled = !state.processing && !state.sealing && state.pageCount < 30,
                            onClick = openGallery
                        )
                    }
                }
            }

            if (state.processing) {
                item {
                    OperationProgressCard(
                        title = "Preparing pages",
                        detail = "Rotating, compressing and sealing previews",
                        percent = (state.progress.coerceIn(0f, 1f) * 100).toInt(),
                        active = true
                    )
                }
            }

            if (state.sealing) {
                item {
                    OperationProgressCard(
                        title = "Sealing PDF",
                        detail = "AES-256-GCM · private phone vault",
                        percent = (state.progress.coerceIn(0f, 1f) * 100).toInt(),
                        active = true
                    )
                }
            }

            (captureError ?: state.error)?.let { message ->
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

            if (state.sealedName != null) {
                item {
                    GlassCard(accent = VaultColor.credit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.checkCircle,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "PDF sealed",
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
                                text = "New document",
                                icon = Glyph.refresh,
                                accent = VaultColor.accent,
                                modifier = Modifier.weight(1f),
                                onClick = viewModel::reset
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton(
                                text = "Print",
                                icon = Glyph.description,
                                accent = VaultColor.credit,
                                modifier = Modifier.weight(1f),
                                enabled = printAvailable,
                                onClick = { printDocument() }
                            )
                            GhostButton(
                                text = "Share",
                                icon = Glyph.share,
                                accent = VaultColor.accent,
                                modifier = Modifier.weight(1f),
                                onClick = { shareDocument() }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (printAvailable) {
                                "Print sends the decrypted pages to the system print service. Share writes one decrypted copy to the app cache and opens the chooser."
                            } else {
                                "Print is disabled: this device has no print service. Share still works."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            if (state.pages.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PAGES",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                            color = VaultColor.textFaint,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${state.pageCount} captured",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.warning
                        )
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.pages, key = { it.id }) { page ->
                            val index = state.pages.indexOfFirst { it.id == page.id } + 1
                            Box(
                                modifier = Modifier
                                    .width(112.dp)
                                    .height(148.dp)
                                    .clip(VaultShapes.button)
                                    .background(VaultColor.background.copy(alpha = 0.6f))
                                    .border(1.dp, VaultColor.hairline, VaultShapes.button)
                            ) {
                                Image(
                                    bitmap = page.thumbnail.asImageBitmap(),
                                    contentDescription = "Page $index",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(7.dp)
                                        .clip(CircleShape)
                                        .background(VaultColor.background.copy(alpha = 0.8f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$index",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultColor.warning,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (!state.sealing && !state.processing) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(VaultColor.background.copy(alpha = 0.82f))
                                            .border(1.dp, VaultColor.hairline, CircleShape)
                                            .clickable { viewModel.removePage(page.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ThemedIcon(
                                            glyph = Glyph.close,
                                            contentDescription = "Remove page $index",
                                            tint = VaultColor.debit,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (state.pageCount < 30 && !state.sealing && !state.processing) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .width(112.dp)
                                        .height(148.dp)
                                        .clip(VaultShapes.button)
                                        .background(VaultColor.warning.copy(alpha = 0.06f))
                                        .border(1.dp, VaultColor.warning.copy(alpha = 0.28f), VaultShapes.button)
                                        .clickable(onClick = launchCamera),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        ThemedIcon(
                                            glyph = Glyph.add,
                                            contentDescription = null,
                                            tint = VaultColor.warning,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "Add page",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = VaultColor.warning
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    GlassCard(
                        accent = VaultColor.credit,
                        borderColor = VaultColor.credit.copy(alpha = 0.3f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.pictureAsPdf,
                                contentDescription = null,
                                tint = VaultColor.credit,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${state.pageCount} page PDF",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = VaultColor.textStrong
                                )
                                Text(
                                    text = "A4 · white background · fits to page",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColor.textBody
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        GradientButton(
                            text = if (state.sealing) "Sealing…" else "Seal encrypted PDF",
                            icon = Glyph.lock,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.sealing && !state.processing,
                            loading = state.sealing,
                            onClick = viewModel::sealPdf
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton(
                                text = "Print pages",
                                icon = Glyph.description,
                                accent = VaultColor.credit,
                                modifier = Modifier.weight(1f),
                                enabled = printAvailable && !state.sealing && !state.processing,
                                onClick = { printDocument() }
                            )
                            GhostButton(
                                text = "Share PDF",
                                icon = Glyph.share,
                                accent = VaultColor.accent,
                                modifier = Modifier.weight(1f),
                                enabled = !state.sealing && !state.processing,
                                onClick = { shareDocument() }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (printAvailable) {
                                "These ${state.pageCount} pages leave the vault as one decrypted PDF for the printer or the chooser."
                            } else {
                                "Print is disabled: this device has no print service. Share still hands over one decrypted PDF."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            lineHeight = 15.sp
                        )
                    }
                }
            } else if (!state.processing) {
                item {
                    GlassCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(VaultShapes.card)
                                    .background(VaultColor.warning.copy(alpha = 0.1f))
                                    .border(1.dp, VaultColor.warning.copy(alpha = 0.22f), VaultShapes.card),
                                contentAlignment = Alignment.Center
                            ) {
                                ThemedIcon(
                                    glyph = Glyph.documentScanner,
                                    contentDescription = null,
                                    tint = VaultColor.warning,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "No pages yet",
                                style = MaterialTheme.typography.titleSmall,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Scan a receipt, contract or ID page to begin.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(accent = VaultColor.accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.lock,
                            contentDescription = null,
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = "Pages are processed in the background and saved only as an encrypted file inside your vault. Nothing enters the gallery or the cloud unencrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textFaint,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}
