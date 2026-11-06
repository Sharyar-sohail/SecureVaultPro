package com.securevault.pro.presentation.screens.secureinput

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.securevault.pro.data.backup.BackupCounts
import com.securevault.pro.data.backup.BackupEngine
import com.securevault.pro.data.backup.BackupParser
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

/** Which of the three seeds the owner picked on the choose step. */
enum class SeedMode { WORDS_12, WORDS_24, CUSTOM }

/** The words the owner is being *given*. */
enum class ManageStep { CHOOSE, CUSTOM_ENTRY, DISPLAY, VERIFY, GATE, ARCHIVED }

/** The words the owner is *bringing back*. */
enum class RestoreStep { PHRASE, ARCHIVE, UNLOCK, IMPORTING, DONE }

enum class SeedFlow { CREATE, RESTORE }

/** Where the archive being opened came from: the app's own copy, or a file the owner picked. */
enum class ArchiveSource { NONE, DEVICE, FILE }

/**
 * Everything the seed screen shows. Kept as one immutable snapshot so the `when`-on-step UI cannot
 * disagree with itself, and so no secret is ever a Compose parameter of its own.
 *
 * [phrase] holds the seed in memory while the owner is looking at it or proving it. It is cleared on
 * [RecoverySeedViewModel.startOver] and on leaving the flow, and it is never written to disk, to the
 * clipboard by the app itself, or into a log.
 */
data class RecoverySeedUiState(
    val flow: SeedFlow = SeedFlow.CREATE,
    val wordlistReady: Boolean = false,
    val wordlistFault: String? = null,
    val manageStep: ManageStep = ManageStep.CHOOSE,
    val mode: SeedMode = SeedMode.WORDS_24,
    val phrase: String = "",
    val words: List<String> = emptyList(),
    val revealed: Boolean = false,
    val verifyPosition: Int = 0,
    val verifyOptions: List<String> = emptyList(),
    val verifyWrongMessage: String? = null,
    val writtenConfirmed: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val record: RecoverySeedRecord? = null,
    val copyMessage: String? = null,
    val restoreStep: RestoreStep = RestoreStep.PHRASE,
    val typedPhrase: String = "",
    val validation: PhraseValidation? = null,
    val archiveSource: ArchiveSource = ArchiveSource.NONE,
    val deviceArchiveAvailable: Boolean = false,
    val deviceArchiveLabel: String = "",
    val fileArchiveName: String = "",
    val collectPassphrase: Boolean = false,
    val passphrase: String = "",
    val confirmPassphrase: String = "",
    val progress: Int = 0,
    val progressStage: String = "",
    val restoreCounts: BackupCounts? = null,
    val restoreWarnings: List<String> = emptyList()
) {
    val wordsPerRow: Int get() = if (words.size > 12) 3 else 2

    /** The archive is only sealed once the owner has proved they hold the phrase. */
    val canFinish: Boolean get() = writtenConfirmed && !busy && phrase.isNotBlank()

    val passphraseReady: Boolean
        get() = passphrase.length >= 8 && passphrase == confirmPassphrase
}

/**
 * Drives both halves of the Master Recovery Seed: handing the owner a phrase they can write down
 * (choose → generate/accept → reveal once → prove → archive) and taking one back
 * (phrase → archive → new unlock passphrase → merge).
 *
 * No network, no Google, no Firebase: the only data sources are the app's own database
 * ([BackupEngine]) and the archive file this flow writes. That is deliberate — a recovery seed that
 * needed Drive to work would not be a recovery seed.
 */
@HiltViewModel
class RecoverySeedViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wordList: RecoveryWordList,
    private val store: RecoverySeedStore,
    private val codec: RecoveryArchiveCodec,
    private val backupEngine: BackupEngine,
    private val sessionManager: SessionManager,
    private val gson: Gson
) : ViewModel() {

    private val _state = MutableStateFlow(RecoverySeedUiState())
    val state: StateFlow<RecoverySeedUiState> = _state.asStateFlow()

    /** Per-session salt for the verify caps, so nobody can read the answer order off a screen recording. */
    private var verifySeed: Long = SecureRandom().nextLong()
    private val answers = mutableListOf<String?>()

    /** Counts the phrase keystrokes so a check that finishes late cannot overtake a newer one. */
    private var typedPhraseRevision = 0

    /** The file the owner picked on the system sheet, held in memory for this flow only. */
    private var pickedBytes: ByteArray? = null

    /**
     * Called once when the section appears.
     *
     * [collectPassphrase] is true only on the pre-setup entry path, where the vault has no unlock
     * passphrase yet and the restore can legitimately hand one to setup. Reaching the same flow from
     * Settings leaves it false: changing an existing master passphrase needs the previous one, which
     * this screen never asks for, so it would silently re-key the cloud copy behind the owner's back.
     */
    fun open(restore: Boolean, collectPassphrase: Boolean = restore) {
        viewModelScope.launch(Dispatchers.IO) {
            val words = wordList.words
            val ready = words.size == RecoverySeedLogic.WORDLIST_SIZE
            val record = runCatching { store.record() }.getOrNull()
            val archiveReady = runCatching { store.hasArchive() }.getOrDefault(false)
            _state.update {
                it.copy(
                    flow = if (restore) SeedFlow.RESTORE else SeedFlow.CREATE,
                    wordlistReady = ready,
                    wordlistFault = if (ready) null else WORDLIST_FAULT,
                    manageStep = if (restore) it.manageStep else ManageStep.CHOOSE,
                    restoreStep = if (restore) RestoreStep.PHRASE else it.restoreStep,
                    collectPassphrase = collectPassphrase,
                    record = record,
                    deviceArchiveAvailable = archiveReady,
                    deviceArchiveLabel = if (archiveReady && record != null) {
                        "Saved here · ${record.archiveCounts.total} items · ${record.archiveBytes / 1024} KB"
                    } else if (archiveReady) {
                        "Saved on this phone"
                    } else {
                        "No recovery file on this phone yet"
                    },
                    error = null
                )
            }
        }
    }

    // ---- choose & build ------------------------------------------------------------------

    fun chooseMode(mode: SeedMode) {
        _state.update {
            it.copy(
                mode = mode,
                error = null,
                manageStep = if (mode == SeedMode.CUSTOM) ManageStep.CUSTOM_ENTRY else ManageStep.CHOOSE
            )
        }
        if (mode != SeedMode.CUSTOM) generate()
    }

    /** Re-rolls the phrase on the DISPLAY step; the previous one is dropped, not kept as an alternative. */
    fun regenerate() {
        _state.update { it.copy(writtenConfirmed = false, revealed = false, verifyWrongMessage = null) }
        generate()
    }

    fun generate() {
        val mode = _state.value.mode
        if (mode == SeedMode.CUSTOM) {
            _state.update { it.copy(manageStep = ManageStep.CUSTOM_ENTRY) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val count = if (mode == SeedMode.WORDS_12) 12 else 24
            runCatching { RecoverySeedLogic.generateMnemonic(count, wordList.words) }
                .onFailure { failure ->
                    _state.update { it.copy(error = failure.message ?: GENERATE_FAULT, busy = false) }
                }
                .onSuccess { phrase ->
                    seedAccepted(phrase, customPhrase = false, step = ManageStep.DISPLAY)
                }
        }
    }

    fun setCustomPhrase(value: String) {
        publishTypedPhrase(value)
        validateTypedPhrase(value)
    }

    /**
     * What the owner types goes on the screen the moment it is typed; only the check behind it runs
     * later. Waiting for the check before showing the text is how a board ends up appearing to skip
     * characters, because a fast typist's next tap lands while the previous one is still in flight.
     */
    private fun publishTypedPhrase(value: String) {
        _state.update { it.copy(typedPhrase = value, error = null) }
    }

    private fun validateTypedPhrase(value: String) {
        val sequence = ++typedPhraseRevision
        viewModelScope.launch(Dispatchers.IO) {
            val validation = RecoverySeedLogic.validatePhrase(value, wordList::positionOf)
            // A slower, earlier check must never overwrite a newer one or label text already replaced.
            if (sequence == typedPhraseRevision && _state.value.typedPhrase == value) {
                _state.update { it.copy(validation = validation) }
            }
        }
    }

    /**
     * Runs [then] with the answer for exactly the text on screen.
     *
     * The check behind typing normally lands long before a button is pressed. When both happen in the
     * same instant the tap wins: the in-flight check is dropped and the tap's own answer is used, so
     * a phrase is never accepted or refused on the strength of an older draft.
     */
    private fun withCurrentPhraseValidation(then: (PhraseValidation) -> Unit) {
        val typed = _state.value.typedPhrase
        val published = _state.value.validation
        if (published != null && published.words.joinToString(" ") == RecoverySeedLogic.normalize(typed)) {
            then(published)
            return
        }
        typedPhraseRevision++
        viewModelScope.launch(Dispatchers.IO) {
            then(RecoverySeedLogic.validatePhrase(typed, wordList::positionOf))
        }
    }

    /**
     * Takes a hand-made phrase. The wordlist check is strict (that is the point of the error
     * messages); a failing checksum is a warning the owner has to look at, not a wall, because a
     * phrase a person invented cannot be expected to satisfy a hash they cannot compute.
     */
    fun acceptCustomPhrase() {
        withCurrentPhraseValidation { validation ->
            if (!validation.canContinue) {
                _state.update { it.copy(error = validation.blockingMessage ?: "Enter your phrase first.") }
                return@withCurrentPhraseValidation
            }
            seedAccepted(validation.words.joinToString(" "), customPhrase = true, step = ManageStep.DISPLAY)
            _state.update { it.copy(typedPhrase = "", validation = null) }
        }
    }

    private fun seedAccepted(phrase: String, customPhrase: Boolean, step: ManageStep) {
        val words = RecoverySeedLogic.wordsOf(phrase)
        verifySeed = SecureRandom().nextLong()
        answers.clear()
        repeat(words.size) { answers.add(null) }
        _state.update {
            it.copy(
                manageStep = step,
                phrase = phrase,
                words = words,
                revealed = false,
                verifyPosition = 0,
                verifyOptions = emptyList(),
                verifyWrongMessage = null,
                writtenConfirmed = false,
                error = null,
                copyMessage = null,
                busy = false
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.rememberSeed(words.size, customPhrase) }
        }
    }

    /** Hold-to-reveal: the blur is lifted only while a finger is genuinely on the control. */
    fun setRevealed(revealed: Boolean) {
        _state.update { it.copy(revealed = revealed) }
    }

    fun continueFromDisplay() {
        val snapshot = _state.value
        if (snapshot.words.isEmpty()) return
        _state.update {
            it.copy(
                manageStep = ManageStep.VERIFY,
                revealed = false,
                verifyPosition = 0,
                verifyWrongMessage = null,
                verifyOptions = optionsFor(0, snapshot.words)
            )
        }
    }

    fun backFromVerify() {
        _state.update { it.copy(manageStep = ManageStep.DISPLAY, verifyPosition = 0) }
    }

    /**
     * One answer in the prove-it step. A wrong cap is reported by position — the same wording the
     * tests pin — and the position is asked again rather than skipped, so nothing is proved by
     * guessing through the last third of a 24-word seed.
     */
    fun answerVerify(word: String) {
        val snapshot = _state.value
        val expected = snapshot.words
        val position = snapshot.verifyPosition
        if (position >= expected.size) return
        if (word != expected[position]) {
            _state.update {
                it.copy(verifyWrongMessage = "“$word” is not word ${position + 1}. Reveal the seed and check it.")
            }
            return
        }
        answers[position] = word
        val next = position + 1
        if (next >= expected.size) {
            // The sheet is only "proved" when every position is filled and matches — the same rule
            // the test pins — so a stale null answer can never slip through to the archive step.
            val outstanding = RecoverySeedLogic.firstMismatch(expected, answers)
            if (outstanding != null) {
                _state.update {
                    it.copy(
                        verifyPosition = outstanding,
                        verifyWrongMessage = "Word ${outstanding + 1} still has to be answered.",
                        verifyOptions = optionsFor(outstanding, expected)
                    )
                }
                return
            }
            _state.update {
                it.copy(
                    manageStep = ManageStep.GATE,
                    verifyPosition = expected.size,
                    verifyWrongMessage = null,
                    error = null
                )
            }
        } else {
            _state.update {
                it.copy(
                    verifyPosition = next,
                    verifyWrongMessage = null,
                    verifyOptions = optionsFor(next, expected)
                )
            }
        }
    }

    private fun optionsFor(position: Int, expected: List<String>): List<String> =
        RecoverySeedLogic.verifyOptions(expected, position, wordList.words, verifySeed)

    fun setWrittenConfirmed(confirmed: Boolean) {
        _state.update { it.copy(writtenConfirmed = confirmed, error = null) }
    }

    /**
     * The confirmation gate: seal the vault now, under a passphrase only the phrase can rebuild.
     *
     * Everything runs on-device — [BackupEngine.buildPayload] reads the six tables and the file
     * index, the codec adds a fresh 32-byte salt and PBKDF2s the recovery passphrase to it, then the
     * archive lands in the app's private directory. Nothing is uploaded anywhere.
     */
    fun finishArchive() {
        val snapshot = _state.value
        if (!snapshot.canFinish) {
            _state.update {
                it.copy(error = if (!snapshot.writtenConfirmed) GATE_FAULT else null)
            }
            return
        }
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = DECOY_FAULT) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            val result = runCatching {
                val payload = backupEngine.buildPayload()
                if (payload.entries.isEmpty() && payload.notes.isEmpty() && payload.cards.isEmpty() &&
                    payload.totps.isEmpty() && payload.contacts.isEmpty() && payload.files.isEmpty() &&
                    payload.cryptoWallets.isEmpty()
                ) {
                    error(EMPTY_VAULT_FAULT)
                }
                val counts = BackupCounts(
                    entries = payload.entries.size,
                    notes = payload.notes.size,
                    cards = payload.cards.size,
                    totps = payload.totps.size,
                    contacts = payload.contacts.size,
                    files = payload.files.size,
                    wallets = payload.cryptoWallets.size
                )
                val bytes = codec.seal(gson.toJson(payload), RecoverySeedLogic.recoveryPassphrase(snapshot.phrase))
                val written = store.writeArchive(bytes, counts)
                    ?: error(ARCHIVE_WRITE_FAULT)
                counts to written
            }
            result
                .onSuccess { (counts, file) ->
                    _state.update {
                        it.copy(
                            busy = false,
                            manageStep = ManageStep.ARCHIVED,
                            revealed = false,
                            phrase = "",
                            words = emptyList(),
                            record = store.record(),
                            copyMessage = "Recovery archive written · ${counts.total} items · ${file.name}"
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(busy = false, error = failure.message ?: ARCHIVE_WRITE_FAULT)
                    }
                }
        }
    }

    /**
     * Copies the archive outside the app, to a location the owner picked.
     *
     * The private directory this flow writes to does not survive a factory reset, so a seed alone
     * cannot rebuild a vault on a wiped phone unless a copy left the app first. This is that copy,
     * and it is offered, never forced.
     */
    fun saveCopyTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = store.readArchive()
            if (bytes == null) {
                _state.update { it.copy(copyMessage = COPY_NO_ARCHIVE_FAULT) }
                return@launch
            }
            val outcome = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error(COPY_OPEN_FAULT)
            }
            _state.update {
                it.copy(copyMessage = if (outcome.isSuccess) {
                    "A copy is now outside the app, in the file you chose."
                } else {
                    outcome.exceptionOrNull()?.message ?: COPY_OPEN_FAULT
                })
            }
        }
    }

    // ---- restore ------------------------------------------------------------------------

    fun setTypedPhrase(value: String) {
        publishTypedPhrase(value)
        validateTypedPhrase(value)
    }

    fun submitPhrase() {
        withCurrentPhraseValidation { validation ->
            if (!validation.canContinue) {
                _state.update { it.copy(error = validation.blockingMessage ?: "Enter your recovery phrase.") }
                return@withCurrentPhraseValidation
            }
            _state.update {
                it.copy(
                    restoreStep = RestoreStep.ARCHIVE,
                    error = null,
                    phrase = validation.words.joinToString(" ")
                )
            }
        }
    }

    fun backToPhrase() {
        _state.update { it.copy(restoreStep = RestoreStep.PHRASE, error = null) }
    }

    fun chooseDeviceArchive() {
        if (!_state.value.deviceArchiveAvailable) {
            _state.update { it.copy(error = DEVICE_ARCHIVE_FAULT) }
            return
        }
        _state.update { it.copy(archiveSource = ArchiveSource.DEVICE, error = null) }
    }

    fun acceptFileArchive(bytes: ByteArray, displayName: String) {
        pickedBytes = bytes
        _state.update {
            it.copy(archiveSource = ArchiveSource.FILE, fileArchiveName = displayName, error = null)
        }
    }

    fun continueFromArchive() {
        val snapshot = _state.value
        if (snapshot.archiveSource == ArchiveSource.NONE) {
            _state.update { it.copy(error = "Choose the recovery file to open.") }
            return
        }
        if (snapshot.collectPassphrase) {
            _state.update { it.copy(restoreStep = RestoreStep.UNLOCK) }
        } else {
            runRestore()
        }
    }

    fun setPassphrase(value: String) {
        _state.update { it.copy(passphrase = value, error = null) }
    }

    fun setConfirmPassphrase(value: String) {
        _state.update { it.copy(confirmPassphrase = value, error = null) }
    }

    fun submitUnlock() {
        val snapshot = _state.value
        val error = when {
            snapshot.passphrase.length < 8 -> "Use at least 8 characters for your unlock passphrase."
            snapshot.passphrase != snapshot.confirmPassphrase -> "The two passphrases do not match."
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        runRestore()
    }

    /**
     * The offline restore: derive the recovery passphrase from the phrase, authenticate the archive,
     * merge it into this device's database.
     *
     * Merging (never replacing) is the standing rule: an archive is an extra copy of the owner's
     * data, and deleting what the phone already holds has to be a decision they take, not something
     * a restore does on their behalf. Records re-seal under this device's own data key on the way in.
     */
    fun runRestore(replace: Boolean = false) {
        val snapshot = _state.value
        val mnemonic = snapshot.phrase
        if (mnemonic.isBlank()) {
            _state.update { it.copy(error = "Enter your recovery phrase first.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    restoreStep = RestoreStep.IMPORTING,
                    busy = true,
                    error = null,
                    progress = 8,
                    progressStage = "Reading the recovery file"
                )
            }
            val bytes = when (snapshot.archiveSource) {
                ArchiveSource.DEVICE -> store.readArchive()
                ArchiveSource.FILE -> pickedBytes
                ArchiveSource.NONE -> null
            }
            if (bytes == null) {
                failRestore("The recovery file could not be read.")
                return@launch
            }
            _state.update { it.copy(progress = 34, progressStage = "Deriving your key from the phrase") }
            val opened = codec.open(bytes, RecoverySeedLogic.recoveryPassphrase(mnemonic))
            val payloadJson = when (opened) {
                is ArchiveOpenResult.Unsealed -> opened.payloadJson
                else -> {
                    failRestore(opened.message ?: "This phrase did not open the recovery file.")
                    return@launch
                }
            }
            _state.update { it.copy(progress = 62, progressStage = "Merging into this vault") }
            val outcome = runCatching {
                val parsed = BackupParser.parse(payloadJson)
                backupEngine.merge(parsed, replace) { _, _ -> } to parsed
            }
            outcome
                .onSuccess { (merge, parsed) ->
                    pickedBytes = null
                    _state.update {
                        it.copy(
                            busy = false,
                            restoreStep = RestoreStep.DONE,
                            progress = 100,
                            progressStage = "Vault restored",
                            phrase = "",
                            typedPhrase = "",
                            restoreCounts = merge.toCounts(),
                            restoreWarnings = parsed.warnings.filter { warning -> warning.isNotBlank() }
                        )
                    }
                }
                .onFailure { failure ->
                    failRestore(failure.message ?: "The recovery file could not be merged.")
                }
        }
    }

    private fun failRestore(message: String) {
        _state.update {
            it.copy(
                busy = false,
                error = message,
                restoreStep = if (it.collectPassphrase) RestoreStep.UNLOCK else RestoreStep.ARCHIVE,
                progress = 0
            )
        }
    }

    /** The passphrase the owner chose in the restore flow, for the welcome screen to hand to setup. */
    fun chosenPassphrase(): String = _state.value.passphrase

    fun backToChoose() {
        startOver()
    }

    /** Drops every in-memory copy of the phrase and returns to the choose step. */
    fun startOver() {
        pickedBytes = null
        answers.clear()
        _state.update {
            it.copy(
                manageStep = ManageStep.CHOOSE,
                restoreStep = RestoreStep.PHRASE,
                phrase = "",
                words = emptyList(),
                typedPhrase = "",
                validation = null,
                revealed = false,
                verifyPosition = 0,
                verifyOptions = emptyList(),
                verifyWrongMessage = null,
                writtenConfirmed = false,
                busy = false,
                error = null,
                copyMessage = null,
                passphrase = "",
                confirmPassphrase = "",
                archiveSource = ArchiveSource.NONE,
                fileArchiveName = "",
                restoreCounts = null,
                restoreWarnings = emptyList(),
                progress = 0,
                progressStage = ""
            )
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Surfaces a fault raised outside the flow — the system file sheet failing to open a row, for
     * instance — without ever leaving a silent failure behind.
     */
    fun reportFault(message: String) {
        _state.update { it.copy(busy = false, error = message) }
    }

    companion object {
        private const val WORDLIST_FAULT =
            "The BIP-39 word list could not be read from this install, so no phrase can be checked. " +
                "Reinstall the app before using a recovery seed."
        private const val GENERATE_FAULT = "This device could not generate a seed. Try again."
        private const val GATE_FAULT = "Confirm you have written the seed down before finishing."
        private const val DECOY_FAULT = "Recovery seeds belong to the primary vault, not a restricted session."
        private const val EMPTY_VAULT_FAULT = "This vault is empty, so there is nothing to seal yet."
        private const val ARCHIVE_WRITE_FAULT = "The recovery file could not be written on this device."
        private const val DEVICE_ARCHIVE_FAULT = "No recovery file is stored on this phone. Use a file instead."
        private const val COPY_NO_ARCHIVE_FAULT = "Finish the seed first — there is no archive to copy yet."
        private const val COPY_OPEN_FAULT = "That location refused the copy. Try another folder."
    }
}
