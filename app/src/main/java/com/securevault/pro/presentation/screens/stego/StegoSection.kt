package com.securevault.pro.presentation.screens.stego

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.StegoLimits
import com.securevault.pro.platform.print.VaultPrint
import com.securevault.pro.platform.share.VaultShare
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultColor

/**
 * The stego workspace: pick a cover, seal a secret into its luminance, hand the file to Share or
 * Print — and the mirror flow that reads one back, including a three-photo dispersal set and a
 * read-once carrier whose saved copies can be destroyed on demand.
 *
 * Every verdict on this screen comes from [StegoUiState]'s pure decision table, and every control
 * is disabled with its reason stated by the view model rather than guessed here. The screen owns
 * none of the crypto: [StegoViewModel] calls the codec, writes the JPEG and keeps the ledger.
 */
@Composable
fun StegoSection(onBack: () -> Unit, viewModel: StegoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var printProblem by remember { mutableStateOf<String?>(null) }
    val printAvailable = remember(context) { VaultPrint.isAvailable(context) }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.addCarrier(uri)
    }
    val pickRead = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.addRead(uri)
    }

    var showScan by remember { mutableStateOf(false) }
    var scanProblem by remember { mutableStateOf<String?>(null) }
    val openScan: () -> Unit = {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            scanProblem = "This device has no camera, so the print cannot be scanned. " +
                "Photograph it and add the photo instead."
        } else {
            showScan = true
        }
    }
    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openScan() else {
            scanProblem = "Camera access was refused, so a print cannot be scanned here. " +
                "Allow it in Settings, or add the photo instead."
        }
    }
    val launchScan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            openScan()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    if (showScan) {
        Dialog(
            onDismissRequest = { showScan = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            StegoScanSheet(
                onDismiss = { showScan = false },
                onPickPhoto = {
                    showScan = false
                    pickRead.launch(arrayOf("image/*"))
                },
                onCapture = { plane ->
                    showScan = false
                    viewModel.addScanned(plane)
                }
            )
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }
    LaunchedEffect(scanProblem) {
        val problem = scanProblem ?: return@LaunchedEffect
        snackbar.showSnackbar(problem)
        scanProblem = null
    }
    LaunchedEffect(printProblem) {
        val problem = printProblem ?: return@LaunchedEffect
        snackbar.showSnackbar(problem)
        printProblem = null
    }

    val title = when (state.flow) {
        StegoFlow.HUB -> "Hidden in photos"
        StegoFlow.HIDE -> "Hide a message"
        StegoFlow.REVEAL -> "Read a photo"
    }
    val subtitle = when (state.flow) {
        StegoFlow.HUB -> "Keeps the photo's own colours · survives a black-and-white print"
        StegoFlow.HIDE -> "Sealed into the photo's own grain, named like a camera file"
        StegoFlow.REVEAL -> "From a file or straight off the printed page"
    }

    GlassScaffold(
        title = title,
        subtitle = subtitle,
        onBack = {
            when (state.flow) {
                StegoFlow.HUB -> onBack()
                StegoFlow.HIDE -> viewModel.openHub()
                StegoFlow.REVEAL -> viewModel.openHub()
            }
        },
        snackbarHostState = snackbar
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = vaultGutter(),
                end = vaultGutter(),
                top = 4.dp,
                bottom = 30.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.fault?.let { fault ->
                item {
                    NoticeCard(
                        text = fault,
                        accent = VaultColor.warning,
                        icon = Glyph.errorOutline
                    )
                }
            }
            if (state.busy) {
                item { WorkingCard(onCancel = viewModel::cancel) }
            }
            when (state.flow) {
                StegoFlow.HUB -> hubItems(state, viewModel)
                StegoFlow.HIDE -> hideItems(
                    state = state,
                    viewModel = viewModel,
                    context = context,
                    onPickCover = { pickCover.launch(arrayOf("image/*")) },
                    printAvailable = printAvailable,
                    onPrintProblem = { printProblem = it }
                )
                StegoFlow.REVEAL -> revealItems(
                    state = state,
                    viewModel = viewModel,
                    onPickCarrier = { pickRead.launch(arrayOf("image/*")) },
                    onScanCarrier = launchScan
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Hub
// ---------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.hubItems(
    state: StegoUiState,
    viewModel: StegoViewModel
) {
    item {
        GlassCard(accent = VaultColor.decorative) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedIconTile(
                    icon = Glyph.image,
                    contentDescription = null,
                    accent = VaultColor.decorative,
                    size = 52.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "A message that is only in the picture",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = "The secret rides in the photo's own shades, never in a QR block or " +
                            "a visible mark, so a scanner app finds nothing to read. The picture " +
                            "keeps the colours it came with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody
                    )
                }
            }
        }
    }
    item {
        EntryCard(
            icon = Glyph.visibilityOff,
            accent = VaultColor.accent,
            title = "Hide a message",
            body = "Choose a photo, type the secret, and save an ordinary-looking picture that " +
                "still reads after a black-and-white photocopy.",
            onClick = viewModel::openHide
        )
    }
    item {
        EntryCard(
            icon = Glyph.visibility,
            accent = VaultColor.decorative,
            title = "Read a photo",
            body = "Open one picture, or all three of a split set, and read back what is hidden — " +
                "with the reason whenever there is nothing to read.",
            onClick = viewModel::openReveal
        )
    }
    item {
        GlassCard {
            SectionHeader("What the app actually promises")
            Spacer(Modifier.height(8.dp))
            FactLine(Glyph.image, "Your photo keeps its colours — the change never goes past a few shades.")
            FactLine(Glyph.pictureAsPdf, "Built for paper: a black-and-white photocopy of it still reads.")
            FactLine(Glyph.qrCodeScanner, "No QR, no barcode, no side channel — a lens app finds nothing to decode.")
            FactLine(Glyph.shuffle, "Deniability: two passphrases, two messages, and the file looks the same either way.")
            FactLine(
                Glyph.timer,
                "Read once: copies this app saves are tracked here, and removal is offered after " +
                    "you open a read-once one — never automatic."
            )
            if (state.ledger.copies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val tracked = state.ledger.copies.size
                val opened = state.openedCopyCount
                Text(
                    text = buildString {
                        append(tracked).append(
                            if (tracked == 1) {
                                " saved carrier from this app is"
                            } else {
                                " saved carriers from this app are"
                            }
                        )
                        append(" tracked on this device")
                        if (opened > 0) append("; ").append(opened).append(" opened so far")
                        append(".")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Hide
// ---------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.hideItems(
    state: StegoUiState,
    viewModel: StegoViewModel,
    context: android.content.Context,
    onPickCover: () -> Unit,
    printAvailable: Boolean,
    onPrintProblem: (String) -> Unit
) {
    item {
        GlassCard {
            SectionHeader(
                title = "Cover photos",
                actionLabel = "Reset",
                onAction = viewModel::resetHide
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "How many photos this secret is split across. One is a normal carrier; two " +
                    "or three means every one of them is needed.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (total in 1..3) {
                    CountChip(
                        value = total,
                        selected = state.shareTotal == total,
                        enabled = !state.busy,
                        onClick = { viewModel.setShareTotal(total) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.dispersalLine,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = "Pick a photo",
                    icon = Glyph.photoLibrary,
                    enabled = !state.busy && state.carriers.size < state.shareTotal,
                    accent = VaultColor.accent,
                    onClick = onPickCover
                )
                GhostButton(
                    text = "Generate",
                    icon = Glyph.autoAwesome,
                    enabled = !state.busy && state.carriers.size < state.shareTotal,
                    accent = VaultColor.decorative,
                    onClick = viewModel::generateCarrier
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A photo you pick comes back with its own colours. A generated cover has no " +
                    "colours of its own, so it is written as a plain black-and-white picture.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
    }
    if (state.carriers.isEmpty()) {
        item {
            EmptyState(
                icon = Glyph.image,
                title = "No cover yet",
                message = "A photo with real grain carries more than a flat one. Generated covers " +
                    "are calibrated for exactly that."
            )
        }
    }
    itemsIndexed(state.carriers) { index, carrier ->
        CarrierCard(
            carrier = carrier,
            index = index,
            total = state.shareTotal,
            busy = state.busy,
            onRemove = { viewModel.removeCarrier(index) }
        )
    }
    item {
        GlassCard {
            SectionHeader("The secret")
            Spacer(Modifier.height(10.dp))
            VaultTextField(
                value = state.secret,
                onValueChange = viewModel::setSecret,
                label = "Message to hide",
                leadingIcon = Glyph.key,
                singleLine = false,
                minLines = 3,
                enabled = !state.busy,
                supportingText = "${state.secretBytes} of ${StegoLimits.MAX_SECRET_BYTES} bytes"
            )
            Spacer(Modifier.height(10.dp))
            VaultTextField(
                value = state.passphrase,
                onValueChange = viewModel::setPassphrase,
                label = "Passphrase",
                leadingIcon = Glyph.lock,
                isPassword = true,
                enabled = !state.busy,
                supportingText = "Without it the message stays sealed; the photo still looks like " +
                    "just a photo."
            )
        }
    }
    item {
        GlassCard {
            ToggleLine(
                title = "Read once",
                body = "Track the copy you save on this device, and offer to remove it after the first " +
                    "read. The image is only removed if you confirm; opening it never deletes it.",
                checked = state.readOnce,
                enabled = !state.busy,
                onCheckedChange = viewModel::setReadOnce
            )
            Spacer(Modifier.height(12.dp))
            ToggleLine(
                title = "Two passphrases (deniable)",
                body = "A decoy message opens with its own passphrase. The file gives no sign of " +
                    "which passphrase is the real one — it is the same picture either way.",
                checked = state.deniable,
                enabled = !state.busy,
                onCheckedChange = viewModel::setDeniable
            )
            if (state.deniable) {
                Spacer(Modifier.height(12.dp))
                VaultTextField(
                    value = state.decoy,
                    onValueChange = viewModel::setDecoy,
                    label = "Decoy message",
                    leadingIcon = Glyph.visibilityOff,
                    singleLine = false,
                    minLines = 2,
                    enabled = !state.busy
                )
                Spacer(Modifier.height(10.dp))
                VaultTextField(
                    value = state.decoyPassphrase,
                    onValueChange = viewModel::setDecoyPassphrase,
                    label = "Decoy passphrase",
                    leadingIcon = Glyph.key,
                    isPassword = true,
                    enabled = !state.busy,
                    supportingText = "Must differ from the real passphrase."
                )
            }
        }
    }
    item {
        val blocker = stegoSealBlocker(state)
        Column(Modifier.fillMaxWidth()) {
            GradientButton(
                text = if (state.shareTotal > 1) {
                    "Seal into ${state.shareTotal} photos"
                } else {
                    "Seal the message"
                },
                icon = Glyph.lock,
                enabled = blocker == null && !state.busy,
                loading = state.busy,
                onClick = viewModel::seal,
                modifier = Modifier.fillMaxWidth()
            )
            if (blocker != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = blocker,
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
        }
    }
    state.report?.let { report ->
        item { SealReportCard(report) }
    }
    if (state.artifacts.isNotEmpty()) {
        item { SectionHeader("Sealed carriers") }
        itemsIndexed(state.artifacts) { _, artifact ->
            ArtifactCard(
                artifact = artifact,
                busy = state.busy,
                printAvailable = printAvailable,
                onSave = { viewModel.saveArtifact(artifact.key) },
                onShare = { viewModel.shareArtifact(artifact.key) },
                onPrint = {
                    VaultShare.printBytes(
                        context = context,
                        bytes = artifact.jpegBytes,
                        displayName = artifact.displayName,
                        onError = onPrintProblem
                    )
                }
            )
        }
        item {
            SendAsPhotoCard(
                survivesSend = state.report?.survivesSend == true,
                sendTested = state.report?.sendTested == true
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reveal
// ---------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.revealItems(
    state: StegoUiState,
    viewModel: StegoViewModel,
    onPickCarrier: () -> Unit,
    onScanCarrier: () -> Unit
) {
    item {
        GlassCard {
            SectionHeader(
                title = "Carriers to read",
                actionLabel = "Reset",
                onAction = viewModel::resetReveal
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add one photo, or every share of a dispersed set. Two passphrase fields are " +
                    "optional: each one is tried, and a wrong key is reported as locked rather than " +
                    "guessed at.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
            Spacer(Modifier.height(12.dp))
            val roomForAnother = !state.busy && state.reads.size < SecretShares.MAX_SHARES
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = "Add a photo",
                    icon = Glyph.photoLibrary,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = roomForAnother,
                    accent = VaultColor.decorative,
                    onClick = onPickCarrier
                )
                GhostButton(
                    text = "Scan a printed photo",
                    icon = Glyph.documentScanner,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = roomForAnother,
                    accent = VaultColor.accent,
                    onClick = onScanCarrier
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Scanning reads the page through the camera and adds it as one more carrier; " +
                    "nothing is decoded until you press read.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
    }
    if (state.reads.isEmpty()) {
        item {
            EmptyState(
                icon = Glyph.image,
                title = "Nothing to read yet",
                message = "Open the file the app saved, or the photocopy you scanned back in."
            )
        }
    }
    itemsIndexed(state.reads) { index, carrier ->
        CarrierCard(
            carrier = carrier,
            index = index,
            total = state.reads.size,
            busy = state.busy,
            onRemove = { viewModel.removeRead(index) }
        )
    }
    item {
        GlassCard {
            VaultTextField(
                value = state.readPassphrase,
                onValueChange = viewModel::setReadPassphrase,
                label = "Passphrase",
                leadingIcon = Glyph.lock,
                isPassword = true,
                enabled = !state.busy,
                supportingText = "A carrier sealed without one opens without a passphrase."
            )
            Spacer(Modifier.height(10.dp))
            VaultTextField(
                value = state.readSecondPassphrase,
                onValueChange = viewModel::setReadSecondPassphrase,
                label = "Second passphrase (optional)",
                leadingIcon = Glyph.key,
                isPassword = true,
                enabled = !state.busy,
                supportingText = "For a deniable carrier whose other story has its own key."
            )
        }
    }
    item {
        GradientButton(
            text = if (state.reads.size > 1) "Read all ${state.reads.size}" else "Read the photo",
            icon = Glyph.visibility,
            enabled = state.canRead,
            loading = state.busy,
            onClick = viewModel::reveal,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (state.findings.isNotEmpty()) {
        item {
            GlassCard {
                SectionHeader("Carrier by carrier")
                Spacer(Modifier.height(8.dp))
                state.findings.forEach { finding ->
                    FindingRow(finding)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    state.outcome?.let { outcome ->
        item { OutcomeCard(state, outcome, viewModel) }
    }
    if (state.heldShares.isNotEmpty()) {
        item {
            GlassCard(accent = VaultColor.decorative) {
                SectionHeader("Shares held")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = dispersalStatus(state.heldShares.map { it.index }, state.readShareTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (share in state.heldShares) {
                        TagChip(text = "Share ${share.index + 1}", accent = VaultColor.decorative)
                    }
                }
            }
        }
    }
    if (state.awaitingSelfDestruct) {
        item { SelfDestructCard(state, viewModel) }
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun EntryCard(
    icon: Glyph,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    onClick: () -> Unit
) {
    GlassCard(accent = accent, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(icon = icon, contentDescription = null, accent = accent, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = VaultColor.textStrong
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody
                )
            }
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = VaultColor.textFaint,
                size = 18.dp
            )
        }
    }
}

@Composable
private fun FactLine(icon: Glyph, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = VaultColor.accentInk,
            size = 16.dp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody
        )
    }
}

@Composable
private fun NoticeCard(text: String, accent: androidx.compose.ui.graphics.Color, icon: Glyph) {
    GlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(glyph = icon, contentDescription = null, tint = accent, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textStrong
            )
        }
    }
}

@Composable
private fun WorkingCard(onCancel: () -> Unit) {
    GlassCard(accent = VaultColor.accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = VaultColor.accent
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Working on the pixels…",
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColor.textStrong,
                modifier = Modifier.weight(1f)
            )
            GhostButton(text = "Stop", icon = Glyph.stop, onClick = onCancel)
        }
    }
}

@Composable
private fun CountChip(value: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = if (selected) VaultColor.accent else VaultColor.textFaint
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (selected) 0.16f else 0.06f), CircleShape)
            .border(1.dp, accent.copy(alpha = if (selected) 0.55f else 0.18f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) VaultColor.accentInk else VaultColor.textBody
        )
    }
}

@Composable
private fun CarrierCard(
    carrier: StegoCarrier,
    index: Int,
    total: Int,
    busy: Boolean,
    onRemove: () -> Unit
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CarrierThumb(carrier = carrier, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = carrier.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(carrier.plane.width).append(" × ").append(carrier.plane.height)
                        if (total > 1) append(" · share ${index + 1} of $total")
                        if (carrier.generated) append(" · generated cover")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
            GhostButton(
                text = "Remove",
                icon = Glyph.delete,
                enabled = !busy,
                accent = VaultColor.debitInk,
                onClick = onRemove
            )
        }
    }
}

@Composable
private fun CarrierThumb(carrier: StegoCarrier, modifier: Modifier = Modifier) {
    val bitmap = remember(carrier) { thumbnailOf(carrier.plane, carrier.color) }
    Thumbnail(bitmap, modifier)
}

@Composable
private fun ArtifactThumb(artifact: StegoArtifact, modifier: Modifier = Modifier) {
    val bitmap = remember(artifact.previewPixels, artifact.preview) {
        val pixels = artifact.previewPixels
        if (pixels != null && artifact.previewWidth > 0 && artifact.previewHeight > 0) {
            StegoImages.bitmapOfArgb(artifact.previewWidth, artifact.previewHeight, pixels)
        } else {
            StegoImages.bitmapOf(artifact.preview)
        }.asImageBitmap()
    }
    Thumbnail(bitmap, modifier)
}

@Composable
private fun Thumbnail(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Crop
    )
}

/**
 * A carrier's thumbnail, in the photo's own colours when it has them.
 *
 * The colour grid is averaged down to the thumbnail's size and tinted there, never tinted at working
 * size: the screen shows what the owner picked, and the carrier's colour array stays the one and only
 * full-size buffer it holds. A generated cover has no colours to wear, so it previews grey — which is
 * honest, because that is exactly the file it writes.
 */
private fun thumbnailOf(plane: LumaPlane, colors: IntArray?): ImageBitmap {
    if (colors == null || colors.isEmpty()) return StegoImages.previewOf(plane).asImageBitmap()
    val grid = StegoImages.previewGridOf(plane)
    val small = StegoPlanes.resampledArgb(plane.width, plane.height, colors, grid.width, grid.height)
    return StegoImages.bitmapOfArgb(grid.width, grid.height, StegoImages.tinted(grid, small))
        .asImageBitmap()
}

@Composable
private fun ToggleLine(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VaultColor.onAccent,
                checkedTrackColor = VaultColor.accent,
                uncheckedThumbColor = VaultColor.textFaint,
                uncheckedTrackColor = VaultColor.hairline
            )
        )
    }
}

@Composable
private fun SealReportCard(report: StegoSealReport) {
    GlassCard(accent = VaultColor.accent) {
        SectionHeader("What the engine measured")
        Spacer(Modifier.height(8.dp))
        Text(
            text = report.capacityLine,
            style = MaterialTheme.typography.titleSmall,
            color = VaultColor.textStrong
        )
        Text(
            text = report.fillLine,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = whyItLooksOrdinary(
                peakGreyLevel = report.peakGreyLevel.toFloat(),
                envelopeCount = report.envelopeCount,
                fileName = report.fileName,
                grain = report.grainLabel,
                keepsColor = report.keepsColor
            ),
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = report.sendLine,
            style = MaterialTheme.typography.bodySmall,
            color = if (report.survivesSend) VaultColor.credit else VaultColor.warning
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagChip(
                text = report.envelopeCount.toString() + if (report.envelopeCount == 1) {
                    " hidden message"
                } else {
                    " hidden messages"
                }
            )
            TagChip(text = "at most ${report.peakGreyLevel} shades off", accent = VaultColor.decorative)
            if (report.survivesSend) {
                TagChip(text = "read back after a photo send", accent = VaultColor.credit)
            } else if (report.sendTested) {
                TagChip(text = "did not pass the photo send", accent = VaultColor.warning)
            }
            if (report.readOnce) TagChip(text = "read once", accent = VaultColor.warning)
        }
    }
}

@Composable
private fun ArtifactCard(
    artifact: StegoArtifact,
    busy: Boolean,
    printAvailable: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtifactThumb(artifact = artifact, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = artifact.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(artifact.sizeBytes / 1024).append(" kB · ")
                        if (artifact.shareTotal > 1) {
                            append("share ${artifact.shareIndex + 1} of ${artifact.shareTotal}")
                        } else {
                            append("single carrier")
                        }
                        if (artifact.saved) append(" · saved")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = if (artifact.saved) "Save again" else "Save",
                icon = Glyph.download,
                enabled = !busy,
                accent = VaultColor.accent,
                onClick = onSave
            )
            GhostButton(
                text = "Share",
                icon = Glyph.share,
                enabled = !busy,
                accent = VaultColor.decorative,
                onClick = onShare
            )
            GhostButton(
                text = "Print",
                icon = Glyph.pictureAsPdf,
                enabled = !busy && printAvailable,
                accent = VaultColor.textBody,
                onClick = onPrint
            )
        }
        if (!printAvailable) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "This device has no print service, so the Print button stays off.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
    }
}

/**
 * The one step an owner can silently lose the message on, stated next to the buttons that hand the
 * file out: a chat app that is told to send a photo repaints that photo. The mark is now sized to
 * survive that repaint, and the seal says out loud whether it measured this picture coming back.
 */
@Composable
private fun SendAsPhotoCard(survivesSend: Boolean, sendTested: Boolean) {
    GlassCard(accent = if (survivesSend) VaultColor.credit else VaultColor.warning) {
        Row {
            ThemedIcon(
                glyph = Glyph.image,
                contentDescription = null,
                tint = if (survivesSend) VaultColor.credit else VaultColor.warning,
                size = 20.dp
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        survivesSend -> "Send it as a photo"
                        sendTested -> "Send it as a photo — this one did not pass"
                        else -> "Send it as a photo — untested on this phone"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        survivesSend ->
                            "Share it the ordinary way, as a photo — no special setting. The mark is " +
                                "written to ride the redraw a chat app performs on the way out."
                        sendTested ->
                            "Share it the ordinary way, as a photo, but this copy may lose the text. A " +
                                "bigger photo or a shorter message does better."
                        else ->
                            "Share it the ordinary way, as a photo. The send test could not run here, so " +
                                "treat this copy as untested rather than broken."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody
                )
            }
        }
    }
}

@Composable
private fun FindingRow(finding: StegoFinding) {
    val refusal = stegoBranchIsRefusal(finding.branch)
    Row(verticalAlignment = Alignment.Top) {
        ThemedIcon(
            glyph = if (refusal) Glyph.errorOutline else Glyph.checkCircle,
            contentDescription = null,
            tint = if (refusal) VaultColor.warning else VaultColor.creditInk,
            size = 18.dp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "${finding.carrierName} — ${stegoBranchTitle(finding.branch).lowercase()}",
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong
            )
            Text(
                text = finding.detail,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
            if (finding.confidenceLabel.isNotBlank()) {
                Text(
                    text = "Confidence ${finding.confidenceLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
        }
    }
}

@Composable
private fun OutcomeCard(state: StegoUiState, outcome: StegoRevealOutcome, viewModel: StegoViewModel) {
    val accent = if (stegoBranchIsRefusal(outcome.branch)) VaultColor.warning else VaultColor.creditInk
    GlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = if (stegoBranchIsRefusal(outcome.branch)) Glyph.lock else Glyph.checkCircle,
                contentDescription = null,
                accent = accent,
                size = 44.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = outcome.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = VaultColor.textStrong
                )
                if (outcome.kindLabel.isNotBlank()) {
                    Text(
                        text = outcome.kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint
                    )
                }
            }
            if (stegoBranchShowsSecret(outcome.branch)) {
                ThemedIcon(
                    glyph = if (state.revealedSecretVisible) Glyph.visibilityOff else Glyph.visibility,
                    contentDescription = if (state.revealedSecretVisible) "Hide the secret" else "Show the secret",
                    tint = VaultColor.accentInk,
                    size = 20.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { viewModel.toggleSecretVisible() }
                        .padding(6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = outcome.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody
        )
        val recovered = outcome.recovered
        if (recovered != null && stegoBranchShowsSecret(outcome.branch)) {
            Spacer(Modifier.height(10.dp))
            when (recovered) {
                is Recovered.Text -> {
                    val shown = if (state.revealedSecretVisible) recovered.text else "••••••"
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(VaultColor.elevated.copy(alpha = 0.7f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = shown,
                            style = MaterialTheme.typography.bodyLarge,
                            color = VaultColor.textStrong
                        )
                    }
                }
                is Recovered.Binary -> {
                    Text(
                        text = "${recovered.size} bytes of binary payload",
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong
                    )
                    if (state.revealedSecretVisible) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = recovered.hexHead,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = VaultColor.textBody
                        )
                    }
                }
            }
            if (outcome.readOnce) {
                Spacer(Modifier.height(8.dp))
                val offered = state.destructOffer.size
                Text(
                    text = if (offered == 0) {
                        "This carrier was sealed read-once. No copy of it saved by this app is " +
                            "tracked on this device, so there is nothing here to remove — the file " +
                            "you opened was left as it was."
                    } else {
                        "This carrier was sealed read-once. " +
                            (if (offered == 1) "One saved copy is" else "$offered saved copies are") +
                            " listed below; nothing is deleted unless you confirm it."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.warning
                )
            }
        }
        if (outcome.branch == StegoBranch.SHARE_PARTIAL) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Add the missing shares and read again; the message is not rebuilt until every " +
                    "one is present.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
        if (stegoBranchIsRefusal(outcome.branch)) {
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Try different passphrases",
                icon = Glyph.refresh,
                accent = VaultColor.accent,
                enabled = !state.busy,
                onClick = viewModel::retryReveal
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "If this picture is a copy of the one this app saved — sent as a photo, or " +
                    "re-saved by another app — open the file exactly as this app saved it, and read " +
                    "that one instead.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
    }
}

@Composable
private fun SelfDestructCard(state: StegoUiState, viewModel: StegoViewModel) {
    GlassCard(accent = VaultColor.warning) {
        SectionHeader("Read-once copies on this device")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "These copies were saved by this app. Opening one only marks it here; it never " +
                "deletes the file. Removing them is a choice, not a timer: nothing is deleted until " +
                "you press the button, and files the app does not own are never touched.",
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody
        )
        Spacer(Modifier.height(8.dp))
        state.destructOffer.forEach { copy ->
            Text(
                text = "• ${copy.label}" + if (state.ledger.isDue(copy.key)) " · opened" else "",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textStrong
            )
        }
        if (state.destructRefused.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${state.destructRefused.size} could not be removed and are still listed.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.warning
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(
                text = "Keep them",
                icon = Glyph.check,
                enabled = !state.busy,
                accent = VaultColor.textBody,
                onClick = viewModel::keepDueCopies
            )
            GhostButton(
                text = "Remove copies",
                icon = Glyph.delete,
                enabled = !state.busy,
                accent = VaultColor.debitInk,
                onClick = viewModel::destroyDueCopies
            )
        }
    }
}
