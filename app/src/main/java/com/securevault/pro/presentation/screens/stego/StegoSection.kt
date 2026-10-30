package com.securevault.pro.presentation.screens.stego

import android.content.Intent
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
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
        StegoFlow.HUB -> "Luminance stego · survives a black-and-white print"
        StegoFlow.HIDE -> "Sealed into the photo's own grain, named like a camera file"
        StegoFlow.REVEAL -> "One carrier, or the shares of a dispersed secret"
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
                    onPickCarrier = { pickRead.launch(arrayOf("image/*")) }
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
                        text = "The secret rides in the photo's luminance, never in a QR block or " +
                            "a visible mark, so a scanner app finds nothing to read.",
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
            body = "Choose a photo, type the secret, and save a grey JPEG that still prints in " +
                "black and white.",
            onClick = viewModel::openHide
        )
    }
    item {
        EntryCard(
            icon = Glyph.visibility,
            accent = VaultColor.decorative,
            title = "Read a photo",
            body = "Open one carrier, or all three of a dispersed set, and read the verdict the " +
                "engine returns.",
            onClick = viewModel::openReveal
        )
    }
    item {
        GlassCard {
            SectionHeader("What the app actually promises")
            Spacer(Modifier.height(8.dp))
            FactLine(Glyph.image, "Grey luminance only — the mark stays a few levels off the original picture.")
            FactLine(Glyph.pictureAsPdf, "Built for paper: a black-and-white photocopy at 16 grey levels still reads.")
            FactLine(Glyph.qrCodeScanner, "No QR, no barcode, no side channel — a lens app finds nothing to decode.")
            FactLine(Glyph.shuffle, "Deniability: two passphrases, two messages, and the file looks the same either way.")
            FactLine(Glyph.timer, "Read once: carriers you save can be removed after the first successful read.")
            if (state.ledger.copies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${state.ledger.copies.size} saved carrier" +
                        if (state.ledger.copies.size == 1) " is" else "s are" +
                        " tracked for read-once removal.",
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
                supportingText = "Without it the carrier reads as locked; the bytes are sealed " +
                    "with AES-GCM under a PBKDF2 key."
            )
        }
    }
    item {
        GlassCard {
            ToggleLine(
                title = "Read once",
                body = "Track the copy you save, and offer to remove it after the first read.",
                checked = state.readOnce,
                enabled = !state.busy,
                onCheckedChange = viewModel::setReadOnce
            )
            Spacer(Modifier.height(12.dp))
            ToggleLine(
                title = "Two passphrases (deniable)",
                body = "A decoy message opens with its own passphrase. The carrier shows no role bit, " +
                    "so the file is the same shape whichever story is being told.",
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
    }
}

// ---------------------------------------------------------------------------------------------
// Reveal
// ---------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.revealItems(
    state: StegoUiState,
    viewModel: StegoViewModel,
    onPickCarrier: () -> Unit
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
            GhostButton(
                text = "Add a photo",
                icon = Glyph.photoLibrary,
                enabled = !state.busy && state.reads.size < SecretShares.MAX_SHARES,
                accent = VaultColor.decorative,
                onClick = onPickCarrier
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
            CarrierThumb(plane = carrier.plane, modifier = Modifier.size(56.dp))
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
private fun CarrierThumb(plane: LumaPlane, modifier: Modifier = Modifier) {
    val bitmap = remember(plane) { StegoImages.previewOf(plane).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Crop
    )
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
                grain = report.grainLabel
            ),
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagChip(text = "${report.envelopeCount} envelope" + if (report.envelopeCount == 1) "" else "s")
            TagChip(text = "peak ${report.peakGreyLevel} levels", accent = VaultColor.decorative)
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
            CarrierThumb(plane = artifact.preview, modifier = Modifier.size(64.dp))
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
                Text(
                    text = "This carrier was sealed read-once. The saved copies are listed below; " +
                        "nothing is deleted unless you confirm it.",
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
        }
    }
}

@Composable
private fun SelfDestructCard(state: StegoUiState, viewModel: StegoViewModel) {
    GlassCard(accent = VaultColor.warning) {
        SectionHeader("Read-once copies on this device")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "These were saved by this app. Removing them is a choice, not a timer: nothing is " +
                "deleted until you press the button, and files the app does not own are never touched.",
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody
        )
        Spacer(Modifier.height(8.dp))
        state.destructOffer.forEach { copy ->
            Text(
                text = "• ${copy.label}",
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
