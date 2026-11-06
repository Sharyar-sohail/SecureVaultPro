package com.securevault.pro.presentation.screens.secureinput

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.OperationProgressCard
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.readDocumentBytes
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.security.keyboard.SecureKeyboardHost
import com.securevault.pro.security.keyboard.SecurePasswordField
import com.securevault.pro.security.keyboard.WindowSecurityFlags
import kotlinx.coroutines.launch

/**
 * The Master Recovery Seed, end to end, in one public entry point.
 *
 * Two flows live behind this call and navigate themselves:
 *
 *  - **Give a seed** — choose 12 words / 24 words / your own phrase → reveal it once, blurred until
 *    a finger holds the control → prove you wrote it down by naming the word at each position →
 *    confirm, and only then seal an encrypted copy of the vault to it.
 *  - **Take a seed back** — type the phrase (validated word by word against the BIP-39 list, with the
 *    offending position named), choose the recovery file, set an unlock passphrase, merge.
 *
 * Both run entirely on the device. There is no Google call, no Drive call and no network anywhere in
 * this package — which is the point: the seed is the way in when Google is not.
 *
 * @param onBack leaves the section.
 * @param startInRestore opens the *take a seed back* flow instead of the generator.
 * @param onRestoreFinished receives the passphrase the owner chose in the pre-setup restore path, so
 *   the welcome screen can carry it into vault setup. Unused when setup is already complete.
 */
@Composable
fun RecoverySeedSection(
    onBack: () -> Unit,
    startInRestore: Boolean = false,
    onRestoreFinished: (String) -> Unit = {},
    viewModel: RecoverySeedViewModel = hiltViewModel()
) {
    var restoreMode by rememberSaveable { mutableStateOf(startInRestore) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalSecureClipboard.current

    LaunchedEffect(restoreMode) {
        viewModel.open(restore = restoreMode)
    }

    // While a phrase is sitting in memory this screen refuses screenshots and the recents preview,
    // whatever the owner's Screenshot-block preference says. Restored on leave.
    val phraseExposed = state.words.isNotEmpty() && state.manageStep != ManageStep.ARCHIVED
    CaptureBlockWhileSeeded(active = phraseExposed)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A null uri is a cancelled sheet, not a fault — nothing is reported for that.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readDocumentBytes(context, uri) }
                .onSuccess { viewModel.acceptFileArchive(it, queryDisplayName(context, uri)) }
                .onFailure { viewModel.reportFault(PICK_READ_FAULT) }
        }
    }
    val copyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) viewModel.saveCopyTo(uri) }

    SecureKeyboardHost(modifier = Modifier.fillMaxSize()) {
        GlassScaffold(
            title = if (restoreMode) "Restore with your seed" else "Master recovery seed",
            subtitle = seedSubtitle(state, restoreMode),
            onBack = {
                viewModel.startOver()
                onBack()
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = vaultGutter(),
                        end = vaultGutter(),
                        top = 6.dp,
                        // The host above lays the board out as a sibling that takes its own height.
                        bottom = 40.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.wordlistFault != null) {
                    EmptyState(
                        icon = Glyph.errorOutline,
                        title = "Word list unavailable",
                        message = state.wordlistFault.orEmpty()
                    )
                    return@Column
                }

                if (!restoreMode) {
                    SeedCreationFlow(
                        state = state,
                        onMode = viewModel::chooseMode,
                        onCustomChange = viewModel::setCustomPhrase,
                        onCustomAccept = viewModel::acceptCustomPhrase,
                        onRegenerate = viewModel::regenerate,
                        onReveal = viewModel::setRevealed,
                        onCopy = { clipboard.copy(state.phrase, "Recovery seed") },
                        onContinue = viewModel::continueFromDisplay,
                        onBackToSeed = viewModel::backFromVerify,
                        onAnswer = viewModel::answerVerify,
                        onWritten = viewModel::setWrittenConfirmed,
                        onFinish = viewModel::finishArchive,
                        onSaveCopy = { copyPicker.launch(RecoverySeedStore.SUGGESTED_COPY_NAME) },
                        onDone = onBack,
                        onSwitchToRestore = { restoreMode = true }
                    )
                } else {
                    SeedRestoreFlow(
                        state = state,
                        onPhrase = viewModel::setTypedPhrase,
                        onSubmitPhrase = viewModel::submitPhrase,
                        onDeviceArchive = viewModel::chooseDeviceArchive,
                        onFileArchive = { filePicker.launch(arrayOf("*/*")) },
                        onContinue = viewModel::continueFromArchive,
                        onBack = viewModel::backToPhrase,
                        onPassphrase = viewModel::setPassphrase,
                        onConfirm = viewModel::setConfirmPassphrase,
                        onSubmitUnlock = viewModel::submitUnlock,
                        onFinished = {
                            val chosen = viewModel.chosenPassphrase()
                            viewModel.startOver()
                            if (state.collectPassphrase && chosen.isNotBlank()) onRestoreFinished(chosen)
                            else onBack()
                        }
                    )
                }

                state.error?.let { error ->
                    MessageCard(text = error, accent = VaultColor.debit, icon = Glyph.errorOutline)
                }
            }
        }
    }
}

/**
 * Holds `FLAG_SECURE` for as long as the seed is on screen.
 *
 * The app's screenshot-block preference is normally the policy, and an owner may well have it off;
 * a recovery seed is the one surface where that preference must not be allowed to expose the words to
 * a capture, a recording or the recent-apps preview. Released on dispose, so the preference is back
 * in charge the moment the screen leaves.
 */
@Composable
private fun CaptureBlockWhileSeeded(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        // `View.window` is not public API, so the flag is taken from the activity that owns this
        // view — the same route `MainActivity` uses for the app-wide block.
        val window = runCatching { view.context.vaultActivity()?.window }.getOrNull()
            ?: return@DisposableEffect onDispose { }
        val wasBlocked = WindowSecurityFlags.isEnforced(window)
        WindowSecurityFlags.enforce(window)
        onDispose {
            if (!wasBlocked) WindowSecurityFlags.release(window)
        }
    }
}

/** Walks a Compose view's context chain to the activity that hosts it, or null. */
private fun android.content.Context.vaultActivity(): android.app.Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}

private fun seedSubtitle(state: RecoverySeedUiState, restore: Boolean): String = when {
    restore -> when (state.restoreStep) {
        RestoreStep.PHRASE -> "offline · no google account"
        RestoreStep.ARCHIVE -> "phrase accepted · choose the recovery file"
        RestoreStep.UNLOCK -> "choose a new unlock passphrase"
        RestoreStep.IMPORTING -> "decrypting and merging on this device"
        RestoreStep.DONE -> "vault open"
    }
    state.manageStep == ManageStep.CHOOSE -> "choose how your master seed is made"
    state.manageStep == ManageStep.CUSTOM_ENTRY -> "type your own phrase · BIP-39 words"
    state.manageStep == ManageStep.DISPLAY -> "write it down · it is shown once"
    state.manageStep == ManageStep.VERIFY -> "prove you have it · ${state.verifyPosition}/${state.words.size}"
    state.manageStep == ManageStep.GATE -> "confirm · then your vault is sealed to it"
    else -> "recovery seed ready"
}

// ---------------------------------------------------------------------------------------------
// giving a seed
// ---------------------------------------------------------------------------------------------

@Composable
private fun SeedCreationFlow(
    state: RecoverySeedUiState,
    onMode: (SeedMode) -> Unit,
    onCustomChange: (String) -> Unit,
    onCustomAccept: () -> Unit,
    onRegenerate: () -> Unit,
    onReveal: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onContinue: () -> Unit,
    onBackToSeed: () -> Unit,
    onAnswer: (String) -> Unit,
    onWritten: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onSaveCopy: () -> Unit,
    onDone: () -> Unit,
    onSwitchToRestore: () -> Unit
) {
    when (state.manageStep) {
        ManageStep.CHOOSE -> {
            LeadCard(
                icon = Glyph.key,
                title = "One phrase opens everything",
                body = "This seed restores your vault with no Google account, no network and no " +
                    "password hint. Anyone who reads it can open your vault, so it lives on paper " +
                    "or in your head — never in a screenshot.",
                chips = listOf("BIP-39", "AES-256-GCM", "600K PBKDF2")
            )
            ChoiceCard(
                icon = Glyph.notes,
                accent = VaultColor.accent,
                title = "12 words",
                body = "128 bits of device entropy. The standard length: short enough to copy by hand.",
                badge = "STANDARD",
                selected = state.mode == SeedMode.WORDS_12,
                onClick = { onMode(SeedMode.WORDS_12) }
            )
            ChoiceCard(
                icon = Glyph.shield,
                accent = VaultColor.credit,
                title = "24 words",
                body = "256 bits — as much entropy as the vault key itself. Choose it if the vault " +
                    "has to survive you.",
                badge = "MAXIMUM",
                selected = state.mode == SeedMode.WORDS_24,
                onClick = { onMode(SeedMode.WORDS_24) }
            )
            ChoiceCard(
                icon = Glyph.edit,
                accent = VaultColor.decorative,
                title = "My own phrase",
                body = "Type a phrase you made up. Every word must be a real BIP-39 word, and the " +
                    "screen will say which one is not.",
                badge = "CUSTOM",
                selected = false,
                onClick = { onMode(SeedMode.CUSTOM) }
            )
            state.record?.let { record ->
                MessageCard(
                    icon = Glyph.info,
                    accent = VaultColor.textFaint,
                    text = if (record.archiveAtMillis > 0) {
                        "A ${record.wordCount}-word seed is already registered here, with a " +
                            "recovery file holding ${record.archiveCounts.total} items. Making a new " +
                            "seed does not delete the old file — both keep working."
                    } else {
                        "A ${record.wordCount}-word seed is registered here but never finished, so no " +
                            "recovery file exists yet."
                    }
                )
            }
            GhostButton(
                text = "I already have a seed — restore now",
                icon = Glyph.replay,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSwitchToRestore
            )
        }

        ManageStep.CUSTOM_ENTRY -> {
            LeadCard(
                icon = Glyph.edit,
                title = "Your phrase, your rules",
                body = "Use 12, 15, 18, 21 or 24 words. Anything else is refused before it can " +
                    "become a key you cannot rebuild.",
                chips = emptyList()
            )
            GlassCard(accent = VaultColor.decorative) {
                SecurePasswordField(
                    value = state.typedPhrase,
                    onValueChange = onCustomChange,
                    label = "Recovery phrase · tap to use the secure board",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                val validation = state.validation
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${validation?.wordCount ?: 0} words · " +
                        "each word must be a BIP-39 English word",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    lineHeight = 15.sp
                )
                validation?.blockingMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.debit,
                        lineHeight = 17.sp
                    )
                }
                validation?.warningMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.warning,
                        lineHeight = 17.sp
                    )
                }
                if (validation != null && validation.canContinue) {
                    Spacer(Modifier.height(12.dp))
                    GradientButton(
                        text = "Use my phrase",
                        icon = Glyph.check,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCustomAccept
                    )
                }
            }
            GhostButton(
                text = "Back to the choice",
                icon = Glyph.arrowBack,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onMode(SeedMode.WORDS_24) }
            )
        }

        ManageStep.DISPLAY -> {
            LeadCard(
                icon = Glyph.visibility,
                title = "Shown once, then blurred",
                body = "Press and hold to read the words. Let go and they blur again — nothing here " +
                    "is ever stored, and the next step asks you to name them.",
                chips = listOf("${state.words.size} words", "position order matters")
            )
            SeedGrid(
                words = state.words,
                revealed = state.revealed,
                onHoldStart = { onReveal(true) },
                onHoldEnd = { onReveal(false) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.revealed) {
                    GhostButton(
                        text = "Copy once",
                        icon = Glyph.contentCopy,
                        modifier = Modifier.weight(1f),
                        onClick = onCopy
                    )
                }
                GhostButton(
                    text = "Different words",
                    icon = Glyph.refresh,
                    modifier = Modifier.weight(1f),
                    onClick = onRegenerate
                )
            }
            GradientButton(
                text = "Written down — check me",
                icon = Glyph.chevronRight,
                enabled = state.words.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = onContinue
            )
        }

        ManageStep.VERIFY -> {
            val total = state.words.size
            LeadCard(
                icon = Glyph.doneAll,
                title = "Which word is at position ${state.verifyPosition + 1}?",
                body = "You are not typing the seed back — that is where mistakes hide. Name each " +
                    "word in turn. ${total - state.verifyPosition} to go.",
                chips = listOf("${state.verifyPosition}/$total")
            )
            state.verifyWrongMessage?.let { message ->
                MessageCard(text = message, accent = VaultColor.debit, icon = Glyph.errorOutline)
            }
            GlassCard(accent = VaultColor.accent) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.verifyOptions.forEach { option ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(VaultShapes.input)
                                .background(VaultColor.elevated.copy(alpha = 0.6f))
                                .border(1.dp, VaultColor.hairline, VaultShapes.input)
                                .clickable { onAnswer(option) }
                                .padding(horizontal = 16.dp, vertical = 13.dp)
                        ) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                        }
                    }
                }
            }
            GhostButton(
                text = "Back to the seed",
                icon = Glyph.arrowBack,
                modifier = Modifier.fillMaxWidth(),
                onClick = onBackToSeed
            )
        }

        ManageStep.GATE -> {
            LeadCard(
                icon = Glyph.lock,
                title = "Last look before the door shuts",
                body = "From now on this phrase is the only thing standing between your vault and " +
                    "nobody. There is no reset, no support line, no back door.",
                chips = emptyList()
            )
            ConfirmRow(
                checked = state.writtenConfirmed,
                onToggle = onWritten,
                label = "I have written all ${state.words.size} words down, in order, and stored them " +
                    "somewhere safe — on paper or a metal plate, not in a photo, note app or cloud folder."
            )
            GlassCard(accent = VaultColor.warning, borderColor = VaultColor.warning.copy(alpha = 0.3f)) {
                Text(
                    text = "What “confirm” does",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "The vault in this phone is copied and sealed with a key only your phrase " +
                        "can rebuild — a normal v3 backup, PBKDF2 600,000, written into the app and " +
                        "offered as a file you can move anywhere. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
            }
            GradientButton(
                text = if (state.busy) "Sealing your vault…" else "Seal my vault to this seed",
                icon = Glyph.shield,
                enabled = state.canFinish,
                loading = state.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = onFinish
            )
        }

        ManageStep.ARCHIVED -> {
            LeadCard(
                icon = Glyph.checkCircle,
                title = "Your seed is live",
                body = state.copyMessage ?: "The recovery file is on this phone. Keep a copy outside " +
                    "the app too: a factory reset takes the app's private folder with it, and then " +
                    "the phrase alone is not enough.",
                chips = listOf("OFFLINE", "v3 ENVELOPE")
            )
            GhostButton(
                text = "Save a copy I can move somewhere safe",
                icon = Glyph.fileDownload,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSaveCopy
            )
            state.copyMessage?.let { message ->
                MessageCard(text = message, accent = VaultColor.credit, icon = Glyph.info)
            }
            GradientButton(
                text = "Done",
                icon = Glyph.check,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDone
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// taking a seed back
// ---------------------------------------------------------------------------------------------

@Composable
private fun SeedRestoreFlow(
    state: RecoverySeedUiState,
    onPhrase: (String) -> Unit,
    onSubmitPhrase: () -> Unit,
    onDeviceArchive: () -> Unit,
    onFileArchive: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onPassphrase: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmitUnlock: () -> Unit,
    onFinished: () -> Unit
) {
    when (state.restoreStep) {
        RestoreStep.PHRASE -> {
            LeadCard(
                icon = Glyph.replay,
                title = "Type the words exactly as you wrote them",
                body = "Lower-case or upper-case does not matter, and extra spaces are folded away. " +
                    "A word that is not in the BIP-39 list is named by its position, so you know " +
                    "which one to fix.",
                chips = listOf("NO GOOGLE", "NO NETWORK")
            )
            GlassCard(accent = VaultColor.accent) {
                SecurePasswordField(
                    value = state.typedPhrase,
                    onValueChange = onPhrase,
                    label = "Recovery phrase · tap to use the secure board",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                    onSubmit = onSubmitPhrase
                )
                val validation = state.validation
                if (validation != null && validation.words.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${validation.wordCount} words · " +
                            (if (validation.isStandardMnemonic) {
                                "a complete BIP-39 phrase"
                            } else {
                                "not yet a complete phrase"
                            }),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (validation.isStandardMnemonic) VaultColor.credit else VaultColor.textFaint,
                        lineHeight = 15.sp
                    )
                    validation.unknownWords.take(4).forEach { unknown ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = unknown.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.debit,
                            lineHeight = 17.sp
                        )
                    }
                    validation.warningMessage?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.warning,
                            lineHeight = 17.sp
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                GradientButton(
                    text = "Continue",
                    icon = Glyph.chevronRight,
                    enabled = validation?.canContinue == true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSubmitPhrase
                )
            }
        }

        RestoreStep.ARCHIVE -> {
            LeadCard(
                icon = Glyph.folderOpen,
                title = "Which recovery file?",
                body = "The phrase is the key; the file is the vault. Pick the copy you still have — " +
                    "in this app, or one you saved outside it.",
                chips = emptyList()
            )
            ChoiceCard(
                icon = Glyph.phone,
                accent = VaultColor.accent,
                title = "Recovery file on this phone",
                body = state.deviceArchiveLabel.ifBlank { "Stored inside the app's private folder" },
                badge = "FASTEST",
                selected = state.archiveSource == ArchiveSource.DEVICE,
                onClick = onDeviceArchive
            )
            ChoiceCard(
                icon = Glyph.folder,
                accent = VaultColor.warning,
                title = "Choose a file I saved",
                body = if (state.fileArchiveName.isBlank()) {
                    "Open a SecureWallet recovery export from your storage, Downloads or an SD card"
                } else {
                    "Selected · ${state.fileArchiveName}"
                },
                badge = "OFFLINE",
                selected = state.archiveSource == ArchiveSource.FILE,
                onClick = onFileArchive
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    text = "Back",
                    icon = Glyph.arrowBack,
                    modifier = Modifier.weight(1f),
                    onClick = onBack
                )
                GradientButton(
                    text = "Continue",
                    icon = Glyph.chevronRight,
                    enabled = state.archiveSource != ArchiveSource.NONE,
                    modifier = Modifier.weight(2f),
                    onClick = onContinue
                )
            }
        }

        RestoreStep.UNLOCK -> {
            LeadCard(
                icon = Glyph.key,
                title = "Choose a new unlock passphrase",
                body = "The seed opened the vault. This is the day-to-day password you type on the " +
                    "lock screen — it is set now, here, and is not the seed.",
                chips = listOf("8+ CHARACTERS")
            )
            GlassCard(accent = VaultColor.credit) {
                SecurePasswordField(
                    value = state.passphrase,
                    onValueChange = onPassphrase,
                    label = "New unlock passphrase",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                SecurePasswordField(
                    value = state.confirmPassphrase,
                    onValueChange = onConfirm,
                    label = "Confirm it",
                    modifier = Modifier.fillMaxWidth(),
                    onSubmit = onSubmitUnlock
                )
                if (state.passphrase.isNotEmpty() && state.passphrase != state.confirmPassphrase) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "The two passphrases do not match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.debit
                    )
                }
                Spacer(Modifier.height(14.dp))
                GradientButton(
                    text = "Restore my vault",
                    icon = Glyph.shield,
                    enabled = state.passphraseReady,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSubmitUnlock
                )
            }
        }

        RestoreStep.IMPORTING -> {
            OperationProgressCard(
                title = "Restoring offline",
                detail = state.progressStage.ifBlank { "Working on this device" },
                percent = state.progress
            )
            MessageCard(
                icon = Glyph.info,
                accent = VaultColor.textFaint,
                text = "Nothing is sent anywhere and nothing already in the vault is deleted. The file " +
                    "holds your records exactly as this phone stores them, so restoring on this " +
                    "install brings everything back; on a wiped phone, put the copy you saved outside " +
                    "the app next to the words before you start."
            )
        }

        RestoreStep.DONE -> {
            val counts = state.restoreCounts
            LeadCard(
                icon = Glyph.checkCircle,
                title = "Your vault is back",
                body = if (counts != null) {
                    "${counts.total} sealed items merged: ${counts.entries} logins, " +
                        "${counts.notes} notes, ${counts.cards} cards, ${counts.totps} authenticators, " +
                        "${counts.contacts} contacts, ${counts.files} files, ${counts.wallets} wallets."
                } else {
                    "The recovery file was merged into this vault."
                },
                chips = listOf("NO GOOGLE SIGN-IN")
            )
            state.restoreWarnings.take(3).forEach { warning ->
                MessageCard(text = warning, accent = VaultColor.warning, icon = Glyph.errorOutline)
            }
            GradientButton(
                text = "Continue",
                icon = Glyph.chevronRight,
                modifier = Modifier.fillMaxWidth(),
                onClick = onFinished
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// pieces
// ---------------------------------------------------------------------------------------------

/**
 * The seed itself, blurred until a finger is genuinely on the control.
 *
 * Hold rather than toggle: a toggle left on is a screenshot waiting to happen, and a seed that stays
 * on screen after the finger lifts is a seed somebody else can read.
 */
@Composable
private fun SeedGrid(
    words: List<String>,
    revealed: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
) {
    val columns = if (words.size > 12) 3 else 2
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = VaultColor.accent,
        borderColor = VaultColor.accent.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .blur(if (revealed) 0.dp else 16.dp)
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            words.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { columnIndex, word ->
                        val position = rowIndex * columns + columnIndex
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(VaultShapes.chip)
                                .background(VaultColor.elevated.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${position + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint,
                                modifier = Modifier.width(20.dp),
                                textAlign = TextAlign.End,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = word,
                                style = MaterialTheme.typography.bodyMedium,
                                color = VaultColor.textStrong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                    repeat(columns - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (!revealed) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clip(VaultShapes.input)
                    .background(VaultColor.overlay.copy(alpha = 0.10f))
                    .border(1.dp, VaultColor.accent.copy(alpha = 0.4f), VaultShapes.input)
                    .pointerInputHold(onHoldStart, onHoldEnd),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIcon(
                        glyph = Glyph.visibility,
                        contentDescription = null,
                        tint = VaultColor.accent,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "Press and hold to reveal · release to hide",
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColor.accentInk
                    )
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Read them in order: ${words.first()} … ${words.last()}. " +
                    "Release to blur them again.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                lineHeight = 15.sp
            )
        }
    }
}

private fun Modifier.pointerInputHold(onStart: () -> Unit, onEnd: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                onStart()
                tryAwaitRelease()
                onEnd()
            })
        }
    )

@Composable
private fun LeadCard(
    icon: Glyph,
    title: String,
    body: String,
    chips: List<String>
) {
    GlassCard(accent = VaultColor.accent, revealIndex = 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = icon,
                contentDescription = null,
                tint = VaultColor.accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = VaultColor.textStrong,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColor.textBody,
            lineHeight = 20.sp
        )
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { chip -> TagChip(chip) }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    icon: Glyph,
    accent: Color,
    title: String,
    body: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        borderColor = if (selected) accent.copy(alpha = 0.65f) else null,
        selected = selected,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(7.dp))
                    TagChip(badge, accent = accent)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 17.sp
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                ThemedIcon(
                    glyph = Glyph.checkCircle,
                    contentDescription = "Selected",
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                    animate = false
                )
            }
        }
    }
}

@Composable
private fun ConfirmRow(checked: Boolean, onToggle: (Boolean) -> Unit, label: String) {
    val accent = if (checked) VaultColor.credit else VaultColor.textFaint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShapes.input)
            .background(VaultColor.elevated.copy(alpha = if (checked) 0.7f else 0.4f))
            .border(1.dp, accent.copy(alpha = 0.5f), VaultShapes.input)
            .clickable { onToggle(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(VaultShapes.chip)
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent, VaultShapes.chip),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                ThemedIcon(
                    glyph = Glyph.check,
                    contentDescription = "Confirmed",
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                    animate = false
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColor.textBody,
            lineHeight = 19.sp,
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun MessageCard(text: String, accent: Color, icon: Glyph) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        borderColor = accent.copy(alpha = 0.3f),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ThemedIcon(
                glyph = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(17.dp),
                animate = false
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody,
                lineHeight = 17.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Shown when a file the owner picked cannot be opened. A silent no-op on a recovery flow is the worst
 * outcome, so the reason always lands on screen.
 */
private const val PICK_READ_FAULT =
    "That file could not be read. Choose it again, or copy it somewhere the app can open."
