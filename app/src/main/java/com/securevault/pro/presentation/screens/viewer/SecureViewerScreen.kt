package com.securevault.pro.presentation.screens.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.formatBytes
import com.securevault.pro.platform.print.VaultPrint
import com.securevault.pro.platform.share.VaultShare
import com.securevault.pro.presentation.components.vaultGutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon

@Composable
fun SecureViewerScreen(
    fileId: String,
    onBack: () -> Unit,
    onLock: () -> Unit,
    viewModel: SecureViewerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val printAvailable = remember(context) { VaultPrint.isAvailable(context) }
    val blockedReason = printBlockedReason(
        kind = state.kind,
        hasText = !state.text.isNullOrBlank(),
        printAvailable = printAvailable
    )

    /**
     * One decrypted file out to the chooser through `VaultShare`, which owns the FileProvider grant
     * and reports the stage that failed. The existing viewer cleanup still removes the decrypted
     * copy when this screen is left; nothing here deletes on a timer.
     */
    fun shareDecryptedCopy() {
        val path = state.path ?: return
        val snapshot = state
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                VaultShare.shareFile(
                    context,
                    File(path),
                    snapshot.title,
                    snapshot.mimeType.ifBlank { null }
                )
            }
            snackbar.showSnackbar(
                outcome.message("Share sheet opened · the decrypted viewer copy was handed over.")
            )
        }
    }

    /**
     * Prints what the viewer is showing: the PDF or image itself, or the text it extracted from an
     * office document. `VaultShare.printText` keeps that path on the same outcome contract.
     */
    fun printDocument() {
        val reason = blockedReason
        if (reason != null) {
            scope.launch { snackbar.showSnackbar(reason) }
            return
        }
        val snapshot = state
        val path = snapshot.path
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                when {
                    snapshot.kind == ViewerKind.OFFICE -> VaultShare.printText(
                        context,
                        snapshot.text.orEmpty(),
                        textFileName(snapshot.title)
                    ) { failure -> scope.launch { snackbar.showSnackbar(failure) } }
                    path != null -> VaultShare.printFile(
                        context,
                        File(path),
                        snapshot.title
                    ) { failure -> scope.launch { snackbar.showSnackbar(failure) } }
                    else -> null
                }
            }
            snackbar.showSnackbar(
                outcome?.message(
                    "Print job started · the decrypted document went to the system print service."
                ) ?: "The decrypted copy is no longer available."
            )
        }
    }

    LaunchedEffect(fileId) { viewModel.load(fileId) }
    DisposableEffect(Unit) { onDispose { viewModel.cleanup() } }

    GlassScaffold(
        title = state.title.ifBlank { "Secure viewer" },
        subtitle = when {
            state.error != null -> "Decryption failed"
            state.loading -> "Decrypting inside the vault…"
            else -> "${formatBytes(state.size)} · sealed with AES-256-GCM"
        },
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            val path = state.path
            if (!state.loading && path != null && state.error == null) {
                GlassIconButton(
                    icon = Glyph.share,
                    contentDescription = "Share a decrypted copy",
                    tint = VaultColor.accent,
                    onClick = { shareDecryptedCopy() }
                )
            }
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                tint = VaultColor.accent,
                onClick = onLock
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                state.loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(38.dp),
                    color = VaultColor.accent
                )
                state.error != null -> EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    icon = Glyph.insertDriveFile,
                    title = "Unable to open file",
                    message = state.error.orEmpty()
                )
                else -> ViewerBody(state = state)
                }
            }
            if (!state.loading && state.path != null && state.error == null) {
                ViewerPrintBar(
                    blockedReason = blockedReason,
                    onPrint = { printDocument() }
                )
            }
        }
    }
}

@Composable
private fun ViewerBody(state: ViewerState) {
    val path = state.path
    if (path == null) {
        EmptyState(
            icon = Glyph.insertDriveFile,
            title = "Preview unavailable",
            message = "The decrypted copy could not be prepared on this device."
        )
        return
    }
    /** Kept here so one tap turns the page and the photo alike, and leaving the file resets it. */
    var rotation by remember(state.id, path) { mutableIntStateOf(0) }
    val rotatable = state.kind == ViewerKind.IMAGE || state.kind == ViewerKind.PDF
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (state.kind) {
                ViewerKind.IMAGE -> ZoomableImage(path = path, title = state.title, rotation = rotation)
                ViewerKind.PDF -> PdfViewer(path = path, rotation = rotation)
                ViewerKind.VIDEO -> MediaViewer(path = path, audio = false)
                ViewerKind.AUDIO -> MediaViewer(path = path, audio = true)
                ViewerKind.TEXT, ViewerKind.OFFICE -> TextDocument(
                    title = state.title,
                    text = state.text,
                    office = state.kind == ViewerKind.OFFICE
                )
                ViewerKind.UNSUPPORTED -> UnsupportedDocument(state)
            }
        }
        if (rotatable) {
            ViewerRotateBar(
                rotation = rotation,
                onRotate = { rotation = (rotation + 90) % 360 },
                onFit = { rotation = 0 }
            )
        }
    }
}

/**
 * What the viewer can decode but not draw: a TIFF, a RAW, an archive.
 *
 * Naming the type is the whole point — "unsupported" with no type next to it leaves the owner
 * guessing whether the vault lost the file or the phone just cannot paint it.
 */
@Composable
private fun UnsupportedDocument(state: ViewerState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vaultGutter(), vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyState(
            icon = Glyph.description,
            title = "Preview not supported",
            message = "This phone has no decoder for ${describeType(state.mimeType, state.title)}, so the " +
                "secure viewer will not guess at its pixels. The stored file is untouched — use Share to " +
                "open an explicit copy in an app that reads this type."
        )
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("File", style = MaterialTheme.typography.labelSmall, color = VaultColor.textFaint)
                Text(state.title, style = MaterialTheme.typography.titleSmall, color = VaultColor.textStrong)
                Text(
                    "${state.mimeType.ifBlank { "unknown type" }} · ${formatBytes(state.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textBody
                )
            }
        }
    }
}

/** The one-line type a row or an error message shows the owner instead of a bare MIME string. */
private fun describeType(mimeType: String, name: String): String {
    val mime = mimeType.lowercase()
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tiff") ->
            "the image type ${ext.ifBlank { mime.substringAfter('/') }}"
        mime.startsWith("video/") -> "the video type ${ext.ifBlank { mime.substringAfter('/') }}"
        mime.startsWith("audio/") -> "the audio type ${ext.ifBlank { mime.substringAfter('/') }}"
        mime == "application/pdf" -> "this PDF"
        mime.isBlank() || mime == "application/octet-stream" ->
            if (ext.isBlank()) "a file with no type recorded" else "the .$ext type"
        else -> mime
    }
}

/**
 * Rotate / fit strip for the two kinds that have an orientation.
 *
 * A photo scanned in landscape and a page photographed sideways were previously readable only by
 * turning the phone, and turning the phone re-laid the whole screen out mid-gesture.
 */
@Composable
private fun ViewerRotateBar(rotation: Int, onRotate: () -> Unit, onFit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = vaultGutter(), vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GhostButton(
            text = "Rotate",
            icon = Glyph.refresh,
            modifier = Modifier.weight(1f),
            onClick = onRotate
        )
        GhostButton(
            text = if (rotation == 0) "Fit" else "Fit · ${rotation}°",
            icon = Glyph.close,
            modifier = Modifier.weight(1f),
            enabled = rotation != 0,
            onClick = onFit
        )
    }
}

@Composable
private fun TextDocument(title: String, text: String?, office: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vaultGutter(), vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (office) {
            Row(
                modifier = Modifier
                    .clip(VaultShapes.input)
                    .background(VaultColor.credit.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Extracted text",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.credit
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Formatting is simplified inside the secure viewer",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textBody
                )
            }
        }
        val body = text
        if (body.isNullOrBlank()) {
            EmptyState(
                icon = Glyph.description,
                title = "No readable text",
                message = if (office) {
                    "This document did not expose any extractable text. Use Share to open a copy in an office app."
                } else {
                    "The file is empty or uses an unsupported text encoding."
                }
            )
        } else {
            SelectionContainer {
                Text(
                    text = body,
                    style = VaultType.mono(),
                    color = VaultColor.textStrong
                )
            }
        }
    }
}

/**
 * A 48 MP photo decoded straight from disk is ~190 MB of ARGB pixels — more than this process is
 * allowed, and the phone simply died with no message on the way back. The decoder therefore halves
 * the image until both the long edge and the total pixel count fit a stated budget, and drops to
 * 16-bit colour for a still-huge raster: nothing on this screen needs the eighth bit of a scan.
 */
private const val MAX_IMAGE_EDGE = 2200
private const val MAX_IMAGE_PIXELS = 9_000_000L
private const val LOW_BIT_DEPTH_PIXELS = 5_000_000L

/** Decodes [path] inside the budget above, or null when this phone has no decoder for it. */
private fun decodeBounded(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE ||
        bounds.outWidth.toLong() * bounds.outHeight.toLong() / (sample.toLong() * sample) > MAX_IMAGE_PIXELS
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    if (bounds.outWidth.toLong() * bounds.outHeight.toLong() / (sample.toLong() * sample) > LOW_BIT_DEPTH_PIXELS) {
        options.inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}

/** Turns a decoded raster by [degrees] without ever holding the original at full size. */
private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap = runCatching {
    val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
    Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}.getOrDefault(source)

@Composable
private fun ZoomableImage(path: String, title: String, rotation: Int) {
    val source by produceState<Bitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) { decodeBounded(path) }
    }
    val image by produceState<Result<ImageBitmap>?>(initialValue = null, key1 = source, key2 = rotation) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val base = source ?: error("This image cannot be decoded")
                if (rotation % 360 == 0) base.asImageBitmap() else rotateBitmap(base, rotation).asImageBitmap()
            }
        }
    }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = {
                    // A second tap gives the image back at one scale, because pan/zoom on a phone
                    // has no other reset and a lost photo is one gesture from looking deleted.
                    scale = 1f
                    offset = Offset.Zero
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val result = image
        when {
            result == null -> CircularProgressIndicator(
                color = VaultColor.accent,
                modifier = Modifier.size(34.dp)
            )
            result.isSuccess -> Image(
                bitmap = result.getOrThrow(),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
            else -> EmptyState(
                icon = Glyph.brokenImage,
                title = "Preview unavailable",
                message = "This image could not be decoded on this phone. The stored file is untouched — " +
                    "use Share to open it in an app that reads this type."
            )
        }
    }
}

@Composable
private fun PdfViewer(path: String, rotation: Int) {
    val holder = remember(path) {
        runCatching {
            val descriptor = ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            descriptor to PdfRenderer(descriptor)
        }.getOrNull()
    }
    if (holder == null) {
        EmptyState(
            icon = Glyph.description,
            title = "PDF cannot be rendered",
            message = "Android's built-in PDF reader opens no password-protected document and rejects a " +
                "damaged one. The file itself is still sealed in the vault — use Share to open it in a " +
                "PDF app."
        )
        return
    }
    val renderer = holder.second
    val lock = remember(path) { Mutex() }
    DisposableEffect(path) {
        onDispose {
            runCatching { renderer.close() }
            runCatching { holder.first.close() }
        }
    }
    val pages = renderer.pageCount
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "$pages page${if (pages == 1) "" else "s"} · scroll to read",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        items(pages) { index ->
            PdfPage(renderer = renderer, lock = lock, index = index, pages = pages, rotation = rotation)
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, lock: Mutex, index: Int, pages: Int, rotation: Int) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = renderer,
        key2 = index,
        key3 = rotation
    ) {
        value = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val page = renderer.openPage(index)
                    try {
                        val targetWidth = 1100
                        val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                        val scaledHeight = (page.height * scale).coerceAtLeast(1f)
                        // Tall pages are downscaled so a single bitmap cannot exhaust the heap.
                        val maxHeight = 3000f
                        val renderWidth: Int
                        val renderHeight: Int
                        if (scaledHeight > maxHeight) {
                            val shrink = maxHeight / scaledHeight
                            renderWidth = (targetWidth * shrink).toInt().coerceAtLeast(1)
                            renderHeight = maxHeight.toInt()
                        } else {
                            renderWidth = targetWidth
                            renderHeight = scaledHeight.toInt()
                        }
                        val output = Bitmap.createBitmap(
                            renderWidth,
                            renderHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        output.eraseColor(android.graphics.Color.WHITE)
                        page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val turned = if (rotation % 360 == 0) output else rotateBitmap(output, rotation)
                        if (turned !== output) runCatching { output.recycle() }
                        turned.asImageBitmap()
                    } finally {
                        page.close()
                    }
                }.getOrNull()
            }
        }
    }
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        val page = bitmap
        if (page == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = VaultColor.accent, modifier = Modifier.size(26.dp))
            }
        } else {
            Image(
                bitmap = page,
                contentDescription = "PDF page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShapes.button)
            )
            Text(
                text = "Page ${index + 1} of $pages",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun MediaViewer(path: String, audio: Boolean) {
    /**
     * The player framework gives a code pair and nothing else; a black rectangle with no explanation
     * is what the owner used to get for an unsupported codec or a damaged container.
     */
    var playbackError by remember(path) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (audio) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(VaultShapes.card)
                    .background(VaultColor.credit.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.volumeUp,
                    contentDescription = null,
                    tint = VaultColor.credit,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Audio plays inside the encrypted vault",
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "No external player is opened",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
            Spacer(Modifier.height(18.dp))
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (audio) 120.dp else 300.dp)
                .clip(VaultShapes.card)
                .background(VaultColor.void),
            factory = { ctx ->
                val player = VideoView(ctx)
                val controller = MediaController(ctx)
                controller.setAnchorView(player)
                player.setMediaController(controller)
                player.setOnPreparedListener { controller.show(0) }
                player.setOnErrorListener { _, _, _ ->
                    playbackError = if (audio) {
                        "This phone's player cannot open that audio format. The memo is still sealed in the " +
                            "vault — use Share to hand a copy to a player that can."
                    } else {
                        "This video uses a codec this phone will not decode, or the file is damaged. The " +
                            "stored copy is untouched — use Share to open it in another player."
                    }
                    true
                }
                player.setVideoPath(path)
                player
            },
            onRelease = { view ->
                runCatching { view.stopPlayback() }
            }
        )
        playbackError?.let { failure ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShapes.input)
                    .background(VaultColor.debit.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemedIcon(
                    glyph = Glyph.errorOutline,
                    contentDescription = null,
                    tint = VaultColor.debit,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = failure,
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textBody,
                    lineHeight = 15.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (!audio && playbackError == null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Tap the video to reveal controls",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The Print action for this document, or the one line explaining why it is disabled.
 *
 * Audio and video have no page a printer could rasterise, and a file type with neither a renderable
 * page nor extractable text has nothing to lay out. Saying which of the three it is beats a grey
 * button with no reason next to it.
 */
internal fun printBlockedReason(
    kind: ViewerKind,
    hasText: Boolean,
    printAvailable: Boolean
): String? = when {
    kind == ViewerKind.AUDIO ->
        "Print is disabled: a printer has no page for audio. Share plays or sends it instead."
    kind == ViewerKind.VIDEO ->
        "Print is disabled: a printer has no page for video. Share sends it instead."
    kind == ViewerKind.OFFICE && !hasText ->
        "Print is disabled: no text could be extracted from this document."
    kind == ViewerKind.UNSUPPORTED ->
        "Print is disabled: this file type has no printable page. Share opens it elsewhere."
    !printAvailable ->
        "Print is disabled: this device has no print service."
    else -> null
}

/** The `.txt` name `VaultShare.printText` stages extracted office text under. */
private fun textFileName(title: String): String {
    val stem = title.substringBeforeLast('.', title).ifBlank { "document" }
    return "$stem.txt"
}

/**
 * The bottom action strip. Print is the only control here: Share lives in the top bar and both go
 * through `VaultShare`, so the chooser stays the single confirmation point for a decrypted copy.
 */
@Composable
private fun ViewerPrintBar(
    blockedReason: String?,
    onPrint: () -> Unit
) {
    GlassCard(
        modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 10.dp),
        accent = if (blockedReason == null) VaultColor.credit else VaultColor.hairline
    ) {
        GhostButton(
            text = "Print",
            icon = Glyph.description,
            accent = if (blockedReason == null) VaultColor.credit else VaultColor.textBody,
            modifier = Modifier.fillMaxWidth(),
            enabled = blockedReason == null,
            onClick = onPrint
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = blockedReason ?: "Printing sends one decrypted copy to the system print service. The decrypted cache copy leaves when you leave this screen.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            lineHeight = 15.sp
        )
    }
}
