package com.securevault.pro.presentation.screens.scanner



import android.Manifest

import android.content.pm.PackageManager

import android.net.Uri

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.PickVisualMediaRequest

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

import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.MaterialTheme

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

import androidx.core.content.ContextCompat

import androidx.hilt.navigation.compose.hiltViewModel

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.securevault.pro.presentation.components.GhostButton

import com.securevault.pro.presentation.components.GlassCard

import com.securevault.pro.presentation.components.GlassScaffold

import com.securevault.pro.presentation.components.GradientButton

import com.securevault.pro.presentation.components.LocalSecureClipboard

import com.securevault.pro.presentation.components.TagChip

import com.securevault.pro.presentation.components.ThemedIcon

import com.securevault.pro.presentation.components.readDocumentBytes

import com.securevault.pro.presentation.components.vaultGutter

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import com.securevault.pro.presentation.theme.VaultColor

import com.securevault.pro.presentation.theme.VaultShapes

import com.securevault.pro.presentation.theme.VaultRadius

import com.securevault.pro.presentation.theme.VaultType

import com.securevault.pro.presentation.components.Glyph



@Composable

fun OcrScannerScreen(

    onBack: () -> Unit,

    viewModel: OcrScannerViewModel = hiltViewModel()

) {

    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val clipboard = LocalSecureClipboard.current

    var pendingCapture by remember { mutableStateOf<CaptureTarget?>(null) }

    var captureError by remember { mutableStateOf<String?>(null) }



    val cameraLauncher = rememberLauncherForActivityResult(

        ActivityResultContracts.TakePicture()

    ) { success ->

        val target = pendingCapture

        pendingCapture = null

        if (target == null) return@rememberLauncherForActivityResult

        if (success) {

            scope.launch {

                runCatching {

                    withContext(Dispatchers.IO) { target.file.readBytes() }

                }.onSuccess { bytes ->

                    if (bytes.isEmpty()) {

                        captureError = "The camera returned an empty photo. Try again."

                    } else {

                        captureError = null

                        viewModel.scan(bytes)

                    }

                }.onFailure {

                    captureError = it.message ?: "The photo could not be read."

                }

                runCatching { withContext(Dispatchers.IO) { target.file.delete() } }

            }

        } else {

            runCatching { target.file.delete() }

        }

    }



    val galleryLauncher = rememberLauncherForActivityResult(

        ActivityResultContracts.PickVisualMedia()

    ) { uri: Uri? ->

        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {

            runCatching {

                readDocumentBytes(context, uri, limit = 25L * 1024L * 1024L)

            }.onSuccess(viewModel::scan)

                .onFailure {

                    captureError = it.message ?: "That image could not be opened."

                }

        }

    }



    val launchCapture: () -> Unit = launch@{

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {

            captureError = "This device has no camera. Choose an image from the gallery instead."

            return@launch

        }

        val target = try {

            newCaptureTarget(context)

        } catch (failure: Throwable) {

            captureError = "The camera could not be prepared on this device."

            return@launch

        }

        captureError = null

        pendingCapture = target

        try {

            cameraLauncher.launch(target.uri)

        } catch (failure: Throwable) {

            pendingCapture = null

            runCatching { target.file.delete() }

            captureError = "No camera app is available. Choose an image from the gallery instead."

        }

    }



    val cameraPermissionLauncher = rememberLauncherForActivityResult(

        ActivityResultContracts.RequestPermission()

    ) { granted ->

        if (granted) {

            launchCapture()

        } else {

            captureError = "Camera access is required to scan. Allow it in Settings → Apps → SECURE VAULT PRO → Permissions, or pick an image from the gallery."

        }

    }



    val launchCamera: () -> Unit = {

        val granted = ContextCompat.checkSelfPermission(

            context,

            Manifest.permission.CAMERA

        ) == PackageManager.PERMISSION_GRANTED

        if (granted) launchCapture() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

    }



    val openGallery: () -> Unit = {

        try {

            galleryLauncher.launch(

                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

            )

        } catch (failure: Throwable) {

            captureError = "This device does not expose a photo picker. Install a gallery app and try again."

        }

    }



    GlassScaffold(

        title = "OCR scanner",

        subtitle = "Auto crop · on-device text extraction",

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

                GlassCard(accent = VaultColor.accent) {

                    Row(verticalAlignment = Alignment.CenterVertically) {

                        Box(

                            modifier = Modifier

                                .size(56.dp)

                                .clip(VaultShapes.card)

                                .background(VaultColor.accent.copy(alpha = 0.12f))

                                .border(1.dp, VaultColor.accent.copy(alpha = 0.3f), VaultShapes.card),

                            contentAlignment = Alignment.Center

                        ) {

                            ThemedIcon(

                                glyph = Glyph.documentScanner,

                                contentDescription = null,

                                tint = VaultColor.accent,

                                modifier = Modifier.size(27.dp)

                            )

                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {

                            Text(

                                text = if (state.hasImage) "Image captured" else "Lift text off paper",

                                style = MaterialTheme.typography.titleMedium,

                                color = VaultColor.textStrong

                            )

                            Text(

                                text = if (state.hasImage) {

                                    "Recognition completes locally. Nothing is uploaded."

                                } else {

                                    "Each photo is straightened, cropped and enhanced, then read entirely on this device."

                                },

                                style = MaterialTheme.typography.bodySmall,

                                color = VaultColor.textBody,

                                lineHeight = 18.sp

                            )

                        }

                    }

                    if (!state.hasImage) {

                        Spacer(Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                            GradientButton(

                                text = "Take photo",

                                icon = Glyph.cameraAlt,

                                modifier = Modifier.weight(1f),

                                enabled = !state.processing,

                                onClick = launchCamera

                            )

                            GhostButton(

                                text = "Gallery",

                                icon = Glyph.photoLibrary,

                                accent = VaultColor.accent,

                                modifier = Modifier.weight(1f),

                                enabled = !state.processing,

                                onClick = openGallery

                            )

                        }

                    }

                }

            }



            if (state.processing) {

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

                                    text = "Reading pixels",

                                    style = MaterialTheme.typography.titleSmall,

                                    color = VaultColor.textStrong

                                )

                                Text(

                                    text = "Text recognition runs in the background.",

                                    style = MaterialTheme.typography.bodySmall,

                                    color = VaultColor.textBody

                                )

                            }

                            TagChip("ML KIT", accent = VaultColor.accent)

                        }

                    }

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



            state.preview?.let { preview ->

                item {

                    GlassCard(

                        accent = VaultColor.accent,

                        contentPadding = PaddingValues(0.dp)

                    ) {

                        Box {

                            Image(

                                bitmap = preview.asImageBitmap(),

                                contentDescription = "Scanned page preview",

                                contentScale = ContentScale.Crop,

                                modifier = Modifier

                                    .fillMaxWidth()

                                    .height(188.dp)

                                    .clip(VaultShapes.card)

                            )

                            Row(

                                modifier = Modifier

                                    .align(Alignment.TopStart)

                                    .padding(10.dp)

                                    .clip(VaultRadius.pill())

                                    .background(VaultColor.background.copy(alpha = 0.72f))

                                    .border(1.dp, VaultColor.hairline, VaultRadius.pill())

                                    .padding(horizontal = 9.dp, vertical = 4.dp),

                                verticalAlignment = Alignment.CenterVertically

                            ) {

                                ThemedIcon(

                                    glyph = Glyph.lock,

                                    contentDescription = null,

                                    tint = VaultColor.credit,

                                    modifier = Modifier.size(11.dp)

                                )

                                Spacer(Modifier.width(5.dp))

                                Text(

                                    text = "LOCAL ONLY",

                                    style = VaultType.micro(),

                                    color = VaultColor.credit

                                )

                            }

                        }

                    }

                }

            }



            if (state.text.isNotBlank()) {

                item {

                    GlassCard(accent = VaultColor.credit) {

                        Row(verticalAlignment = Alignment.CenterVertically) {

                            Text(

                                text = "RECOGNIZED TEXT",

                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),

                                color = VaultColor.credit,

                                modifier = Modifier.weight(1f)

                            )

                            Text(

                                text = "${state.text.length} chars",

                                style = MaterialTheme.typography.labelSmall,

                                color = VaultColor.textFaint

                            )

                            Spacer(Modifier.width(4.dp))

                            Box(

                                modifier = Modifier

                                    .clip(VaultShapes.chip)

                                    .background(VaultColor.accent.copy(alpha = 0.1f))

                                    .clickable { clipboard.copy(state.text, "OCR text") }

                                    .padding(horizontal = 8.dp, vertical = 4.dp)

                            ) {

                                Row(verticalAlignment = Alignment.CenterVertically) {

                                    ThemedIcon(

                                        glyph = Glyph.contentCopy,

                                        contentDescription = "Copy recognized text",

                                        tint = VaultColor.accent,

                                        modifier = Modifier.size(13.dp)

                                    )

                                    Spacer(Modifier.width(4.dp))

                                    Text(

                                        text = "COPY",

                                        style = VaultType.micro(),

                                        color = VaultColor.accent

                                    )

                                }

                            }

                        }

                        Spacer(Modifier.height(10.dp))

                        Box(

                            modifier = Modifier

                                .fillMaxWidth()

                                .heightIn(max = 240.dp)

                                .clip(VaultShapes.button)

                                .background(VaultColor.background.copy(alpha = 0.45f))

                                .border(1.dp, VaultColor.hairline.copy(alpha = 0.7f), VaultShapes.button)

                                .padding(12.dp)

                        ) {

                            Text(

                                text = state.text,

                                style = MaterialTheme.typography.bodyMedium,

                                color = VaultColor.textStrong,

                                lineHeight = 21.sp,

                                modifier = Modifier.verticalScroll(rememberScrollState())

                            )

                        }

                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                            GradientButton(

                                text = if (state.noteSaved) "Note sealed" else "Save as note",

                                icon = Glyph.notes,

                                modifier = Modifier.weight(1f),

                                enabled = !state.noteSaved,

                                loading = state.savingNote,

                                onClick = viewModel::saveAsNote

                            )

                            GhostButton(

                                text = if (state.imageSaved) "Image sealed" else "Seal image",

                                icon = Glyph.lock,

                                accent = if (state.imageSaved) VaultColor.credit else VaultColor.accent,

                                modifier = Modifier.weight(1f),

                                enabled = !state.imageSaved && !state.savingImage,

                                onClick = viewModel::saveImage

                            )

                        }

                    }

                }

            }



            if (state.hasImage) {

                item {

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {

                        GhostButton(

                            text = "Copy text",

                            icon = Glyph.contentCopy,

                            accent = VaultColor.accent,

                            enabled = state.text.isNotBlank(),

                            modifier = Modifier.weight(1f),

                            onClick = { clipboard.copy(state.text, "OCR text") }

                        )

                        GhostButton(

                            text = "Scan another",

                            icon = Glyph.replay,

                            accent = VaultColor.warning,

                            modifier = Modifier.weight(1f),

                            onClick = viewModel::clearResult

                        )

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

                            text = "The camera frame, OCR result and saved note are processed and encrypted on this device. No image ever leaves the vault.",

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

