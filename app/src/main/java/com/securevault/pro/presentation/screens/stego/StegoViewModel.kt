package com.securevault.pro.presentation.screens.stego

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.SecretKind
import com.securevault.pro.crypto.stego.StegoCapacityException
import com.securevault.pro.crypto.stego.StegoDecodeRequest
import com.securevault.pro.crypto.stego.StegoInputException
import com.securevault.pro.crypto.stego.StegoLimits
import com.securevault.pro.crypto.stego.StegoRead
import com.securevault.pro.platform.share.VaultShare
import com.securevault.pro.presentation.components.queryDisplayName
import com.securevault.pro.presentation.components.readDocumentBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

@HiltViewModel
class StegoViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(StegoUiState())
    val state: StateFlow<StegoUiState> = _state.asStateFlow()

    /** The read-once ledger's home between launches; metadata only, capped by the ledger itself. */
    private val ledgerStore = StegoLedgerStore(context)

    init {
        // A saved carrier outlives this route and the process, so the reveal list is rebuilt from
        // the private record before the first frame. It is a few kilobytes read once per session.
        val restored = ledgerStore.load()
        if (!restored.isEmpty()) _state.update { it.copy(ledger = restored) }
    }

    private var job: Job? = null
    private var jobSerial = 0

    fun openHub() {
        stopRunningJob()
        _state.update { it.copy(flow = StegoFlow.HUB, busy = false, fault = null, message = null) }
    }

    fun openHide() {
        stopRunningJob()
        _state.update { it.copy(flow = StegoFlow.HIDE, busy = false, fault = null, message = null) }
    }

    fun openReveal() {
        stopRunningJob()
        _state.update { it.copy(flow = StegoFlow.REVEAL, busy = false, fault = null, message = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun cancel() {
        if (!_state.value.busy) return
        stopRunningJob()
        _state.update { it.copy(busy = false, message = "Stopped.") }
    }

    // ---- hide side --------------------------------------------------------------------------

    fun addCarrier(uri: Uri) {
        val current = _state.value
        if (current.busy) return
        if (current.carriers.size >= current.shareTotal) {
            _state.update { it.copy(fault = "This set already holds ${current.shareTotal} cover photo(s).") }
            return
        }
        startJob {
            val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri, "cover.jpg") }
            val bytes = readDocumentBytes(context, uri)
            val plane = withContext(Dispatchers.IO) { StegoImages.planeOf(bytes) }
                ?: throw StegoInputException("That file is not an image this app can read.")
            appendCarrier(StegoCarrier(name = name, plane = plane))
        }
    }

    fun generateCarrier() {
        val current = _state.value
        if (current.busy || current.carriers.size >= current.shareTotal) return
        startJob {
            val seed = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            val plane = withContext(Dispatchers.IO) { StegoImages.coverPlane(seed) }
            appendCarrier(
                StegoCarrier(
                    name = StegoImages.cameraName(seed),
                    plane = plane,
                    generated = true
                )
            )
        }
    }

    fun removeCarrier(index: Int) {
        _state.update { current ->
            current.copy(
                carriers = current.carriers.filterIndexed { at, _ -> at != index },
                artifacts = emptyList(),
                report = null,
                fault = null
            )
        }
    }

    fun setSecret(value: String) {
        _state.update { it.copy(secret = value, fault = null) }
    }

    fun setPassphrase(value: String) {
        _state.update { it.copy(passphrase = value, fault = null) }
    }

    fun setDeniable(enabled: Boolean) {
        _state.update { it.copy(deniable = enabled, fault = null) }
    }

    fun setDecoy(value: String) {
        _state.update { it.copy(decoy = value, fault = null) }
    }

    fun setDecoyPassphrase(value: String) {
        _state.update { it.copy(decoyPassphrase = value, fault = null) }
    }

    fun setShareTotal(total: Int) {
        val safe = total.coerceIn(1, 3)
        _state.update { current ->
            current.copy(
                shareTotal = safe,
                carriers = if (current.carriers.size > safe) current.carriers.take(safe)
                else current.carriers,
                artifacts = emptyList(),
                report = null,
                fault = null
            )
        }
    }

    fun setReadOnce(enabled: Boolean) {
        _state.update { it.copy(readOnce = enabled, fault = null) }
    }

    fun seal() {
        val snapshot = _state.value
        if (snapshot.busy) return
        val blocker = stegoSealBlocker(snapshot)
        if (blocker != null) {
            _state.update { it.copy(fault = blocker) }
            return
        }
        val total = snapshot.shareTotal
        val covers = snapshot.carriers.take(total)
        val message = snapshot.secret.toByteArray(Charsets.UTF_8)
        val decoy = if (snapshot.deniable) snapshot.decoy.toByteArray(Charsets.UTF_8) else null
        val primaryKey = snapshot.passphrase.toCharArray().takeIf { snapshot.passphrase.isNotEmpty() }
        val decoyKey = snapshot.decoyPassphrase.toCharArray()
            .takeIf { snapshot.deniable && snapshot.decoyPassphrase.isNotEmpty() }

        startJob {
            val codec = StegoCodec.production()
            var artifacts: List<StegoArtifact> = emptyList()
            var report: StegoSealReport? = null
            withContext(Dispatchers.IO) {
                val pieces = if (total == 1) listOf(message) else codec.sharesFor(message, total)
                val built = ArrayList<StegoArtifact>(total)
                var first: StegoCodec.Sealed? = null
                for (index in pieces.indices) {
                    if (!isActive) return@withContext
                    val sealed = codec.sealCarrier(
                        carrier = covers[index].plane,
                        primary = pieces[index],
                        primaryPassphrase = primaryKey,
                        decoy = decoy,
                        decoyPassphrase = decoyKey,
                        shareTotal = total,
                        shareIndex = index,
                        oneRead = snapshot.readOnce
                    )
                    if (first == null) first = sealed
                    built += StegoArtifact(
                        key = "carrier-$index-${System.currentTimeMillis()}",
                        displayName = StegoImages.cameraName(SEAL_SEED + index),
                        shareIndex = index,
                        shareTotal = total,
                        jpegBytes = StegoImages.jpegOf(sealed.marked),
                        preview = previewPlane(sealed.marked)
                    )
                }
                if (!isActive) return@withContext
                val sealed = first ?: return@withContext
                val plane = covers.first().plane
                artifacts = built
                report = StegoSealReport(
                    latticeLabel = "${sealed.lattice.name.lowercase()} lattice",
                    chipRate = sealed.chipRate,
                    capacityUsedPercent = capacityUsedPercent(sealed.capacityUsed),
                    secretBytes = max(message.size, decoy?.size ?: 0),
                    perEnvelopeLimit = StegoCapacity.maxSecretBytes(plane, sealed.envelopeCount),
                    envelopeCount = sealed.envelopeCount,
                    peakGreyLevel = sealed.peakGreyLevel.roundToInt(),
                    carrierDescription = StegoImages.describe(plane),
                    grainLabel = "LL spread ${StegoImages.textureOf(plane).spread.roundToInt()}",
                    fileName = built.firstOrNull()?.displayName ?: StegoImages.cameraName(SEAL_SEED),
                    readOnce = snapshot.readOnce
                )
            }
            val doneReport = report ?: return@startJob
            val doneArtifacts = artifacts
            _state.update {
                it.copy(
                    busy = false,
                    artifacts = doneArtifacts,
                    report = doneReport,
                    handoff = null,
                    fault = null,
                    message = if (total > 1) {
                        "Sealed ${doneArtifacts.size} shares — every one is needed."
                    } else {
                        "Sealed ${doneReport.fileName}"
                    }
                )
            }
        }
    }

    fun shareArtifact(key: String) {
        val artifact = _state.value.artifacts.firstOrNull { it.key == key } ?: return
        startJob {
            val outcome = withContext(Dispatchers.IO) {
                VaultShare.shareImage(context, artifact.jpegBytes, artifact.displayName)
            }
            _state.update {
                it.copy(
                    busy = false,
                    handoff = StegoHandoff(artifact.displayName, outcome),
                    message = outcome.message("Handed ${artifact.displayName} to the share sheet.")
                )
            }
        }
    }

    fun saveArtifact(key: String) {
        val artifact = _state.value.artifacts.firstOrNull { it.key == key } ?: return
        startJob {
            val saved = withContext(Dispatchers.IO) { writePicture(artifact) }
            if (saved == null) {
                _state.update {
                    it.copy(fault = "${artifact.displayName} could not be written to this device's pictures.")
                }
                return@startJob
            }
            val registered = _state.value.ledger.register(
                OwnedCopy(
                    key = key,
                    label = artifact.displayName,
                    path = saved.location,
                    createdAt = System.currentTimeMillis()
                )
            )
            ledgerStore.save(registered)
            _state.update { current ->
                current.copy(
                    busy = false,
                    artifacts = current.artifacts.map {
                        if (it.key == key) it.copy(writtenTo = saved.location) else it
                    },
                    ledger = registered,
                    message = "Saved ${artifact.displayName} to ${saved.described}."
                )
            }
        }
    }

    fun resetHide() {
        stopRunningJob()
        _state.update {
            it.copy(
                flow = StegoFlow.HIDE,
                busy = false,
                carriers = emptyList(),
                secret = "",
                passphrase = "",
                deniable = false,
                decoy = "",
                decoyPassphrase = "",
                shareTotal = 1,
                readOnce = false,
                artifacts = emptyList(),
                report = null,
                handoff = null,
                fault = null,
                message = "Cleared."
            )
        }
    }

    // ---- reveal side ------------------------------------------------------------------------

    fun addRead(uri: Uri) {
        val current = _state.value
        if (current.busy) return
        if (current.reads.size >= SecretShares.MAX_SHARES) {
            _state.update { it.copy(fault = "One reveal reads at most ${SecretShares.MAX_SHARES} carriers.") }
            return
        }
        startJob {
            val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri, "carrier.jpg") }
            val bytes = readDocumentBytes(context, uri)
            val plane = withContext(Dispatchers.IO) { StegoImages.planeOf(bytes) }
                ?: throw StegoInputException("That file is not an image this app can read.")
            _state.update {
                it.copy(
                    reads = it.reads + StegoCarrier(name = name, plane = plane),
                    findings = emptyList(),
                    heldShares = emptyList(),
                    readShareTotal = 1,
                    outcome = null,
                    revealedSecretVisible = false,
                    fault = null,
                    message = "Ready to read $name"
                )
            }
        }
    }

    fun removeRead(index: Int) {
        _state.update {
            it.copy(
                reads = it.reads.filterIndexed { at, _ -> at != index },
                findings = emptyList(),
                heldShares = emptyList(),
                readShareTotal = 1,
                outcome = null,
                revealedSecretVisible = false,
                fault = null
            )
        }
    }

    fun setReadPassphrase(value: String) {
        _state.update { it.copy(readPassphrase = value, fault = null) }
    }

    fun setReadSecondPassphrase(value: String) {
        _state.update { it.copy(readSecondPassphrase = value, fault = null) }
    }

    fun reveal() {
        val snapshot = _state.value
        if (snapshot.busy || snapshot.reads.isEmpty()) return
        val passphrases = offeredPassphrases(snapshot.readPassphrase, snapshot.readSecondPassphrase)
        val reads = snapshot.reads.take(SecretShares.MAX_SHARES)
        val firstTyped = snapshot.readPassphrase.isNotEmpty()
        val secondTyped = snapshot.readSecondPassphrase.isNotEmpty()

        startJob {
            val codec = StegoCodec.production()
            var findings: List<StegoFinding> = emptyList()
            var held: List<SecretShares.Share> = emptyList()
            var heldTotal = 1
            var revealedSecret: ByteArray? = null
            var revealedKind = SecretKind.MESSAGE
            var revealedFinding: StegoCodec.Finding? = null
            var revealedNames: List<String> = emptyList()
            var decisive: StegoFinding? = null

            withContext(Dispatchers.IO) {
                val collected = ArrayList<StegoFinding>(reads.size)
                val shares = LinkedHashMap<Int, ByteArray>()
                var total = 1
                var secret: ByteArray? = null
                var kind = SecretKind.MESSAGE
                var revealed: StegoCodec.Finding? = null
                var revealedFrom: List<String> = emptyList()
                for (carrier in reads) {
                    if (!isActive) return@withContext
                    val finding = codec.scan(StegoDecodeRequest(carrier.plane, passphrases))
                    val flat = codec.looksEmpty(carrier.plane)
                    val read = finding.read
                    val branch = stegoBranchOf(read, shares.keys.toList(), flat)
                    var shareIndex: Int? = null
                    var shareTotal = 1
                    if (read is StegoRead.ShareRecovered) {
                        shareIndex = read.shareIndex
                        shareTotal = read.shareTotal
                        total = read.shareTotal
                        shares[read.shareIndex] = read.shareBytes
                    }
                    collected += StegoFinding(
                        carrierName = carrier.name,
                        branch = branch,
                        detail = finding.summary,
                        shareIndex = shareIndex,
                        shareTotal = shareTotal,
                        confidenceLabel = StegoConfidence.label(finding.confidence)
                    )
                    if (read is StegoRead.Revealed && secret == null) {
                        secret = read.secret
                        kind = read.kind
                        revealed = finding
                        revealedFrom = listOf(carrier.name)
                    }
                }
                findings = collected
                held = shares.map { SecretShares.Share(it.key, it.value) }.sortedBy { it.index }
                heldTotal = total
                revealedSecret = secret
                revealedKind = kind
                revealedFinding = revealed
                revealedNames = revealedFrom
                decisive = decisiveFindingOf(collected)
            }

            val heldNow = held
            val totalNow = heldTotal
            val secretNow = revealedSecret
            val kindNow = revealedKind
            val findingNow = revealedFinding
            val namesNow = revealedNames
            val rebuilt = if (heldNow.isNotEmpty()) rebuildSecret(heldNow, totalNow) else null

            val outcome = when {
                secretNow != null -> {
                    val branch = if (findingNow?.revealed?.oneRead == true) {
                        StegoBranch.READ_ONCE_REVEALED
                    } else {
                        StegoBranch.REVEALED
                    }
                    StegoRevealOutcome(
                        branch = branch,
                        headline = stegoBranchTitle(branch),
                        explanation = listOfNotNull(
                            findingNow?.summary,
                            openedWithSentence(
                                findingNow?.revealed?.openedWithKey ?: 0,
                                firstTyped,
                                secondTyped
                            )
                        ).joinToString(" "),
                        kindLabel = kindNow.label,
                        confidenceLabel = findingNow?.let { StegoConfidence.label(it.confidence) }.orEmpty(),
                        recovered = StegoRecovery.recover(kindNow, secretNow),
                        readOnce = branch == StegoBranch.READ_ONCE_REVEALED
                    )
                }
                rebuilt != null -> StegoRevealOutcome(
                    branch = StegoBranch.SHARE_COMPLETE,
                    headline = stegoBranchTitle(StegoBranch.SHARE_COMPLETE),
                    explanation = dispersalStatus(heldNow.map { it.index }, totalNow),
                    kindLabel = SecretKind.KEYFILE.label,
                    recovered = StegoRecovery.recover(SecretKind.KEYFILE, rebuilt)
                )
                heldNow.isNotEmpty() -> StegoRevealOutcome(
                    branch = StegoBranch.SHARE_PARTIAL,
                    headline = stegoBranchTitle(StegoBranch.SHARE_PARTIAL),
                    explanation = dispersalStatus(heldNow.map { it.index }, totalNow)
                )
                else -> {
                    val branch = decisive?.branch ?: StegoBranch.CARRIES_NOTHING
                    StegoRevealOutcome(
                        branch = branch,
                        headline = stegoBranchTitle(branch),
                        explanation = decisive?.detail ?: "No photo was read, so there is nothing to answer.",
                        confidenceLabel = decisive?.confidenceLabel.orEmpty()
                    )
                }
            }

            val readOnce = outcome.branch == StegoBranch.READ_ONCE_REVEALED
            // An open is recorded against the saved copy that carries the file name just read; the
            // file itself is not touched. A partial share opens nothing, so it marks nothing.
            val openedLabels = when {
                secretNow != null -> namesNow.toSet()
                outcome.branch == StegoBranch.SHARE_COMPLETE -> reads.map { it.name }.toSet()
                else -> emptySet()
            }
            val ledgerBefore = _state.value.ledger
            val ledgerNow = ledgerBefore.markReadNamed(openedLabels)
            if (ledgerNow !== ledgerBefore) ledgerStore.save(ledgerNow)
            val offer = if (readOnce) ledgerNow.copies.filter { it.label in openedLabels } else emptyList()
            _state.update {
                it.copy(
                    busy = false,
                    findings = findings,
                    heldShares = heldNow,
                    readShareTotal = totalNow,
                    outcome = outcome,
                    revealedSecretVisible = stegoBranchShowsSecret(outcome.branch),
                    ledger = ledgerNow,
                    destructOffer = offer,
                    destructRefused = emptyList(),
                    fault = null,
                    message = null
                )
            }
        }
    }

    fun toggleSecretVisible() {
        _state.update { it.copy(revealedSecretVisible = !it.revealedSecretVisible) }
    }

    fun retryReveal() {
        _state.update {
            it.copy(
                readPassphrase = "",
                readSecondPassphrase = "",
                findings = emptyList(),
                heldShares = emptyList(),
                readShareTotal = 1,
                outcome = null,
                revealedSecretVisible = false,
                destructOffer = emptyList(),
                destructRefused = emptyList(),
                fault = null,
                message = null
            )
        }
    }

    fun keepDueCopies() {
        _state.update {
            it.copy(
                destructOffer = emptyList(),
                destructRefused = emptyList(),
                message = "Copies kept. Nothing was deleted."
            )
        }
    }

    fun destroyDueCopies() {
        val snapshot = _state.value
        if (snapshot.busy) return
        if (snapshot.destructOffer.isEmpty()) {
            _state.update { it.copy(message = "Nothing is waiting to be removed.") }
            return
        }
        startJob {
            val decision = snapshot.ledger.decide(snapshot.destructOffer.map { it.key })
            val cleared = when (decision) {
                is RemovalDecision.Cleared -> decision.copies
                is RemovalDecision.Partial -> decision.cleared
                RemovalDecision.NothingOwned -> emptyList()
            }
            val refused = when (decision) {
                is RemovalDecision.Partial -> decision.refused
                else -> emptyList()
            }
            val (gone, stuck) = withContext(Dispatchers.IO) { cleared.partition { removeCopy(it) } }
            val goneKeys = gone.map { it.key }.toSet()
            val stillOwed = refused + stuck
            val forgotten = snapshot.ledger.forget(gone.map { it.key })
            if (gone.isNotEmpty()) ledgerStore.save(forgotten)
            _state.update {
                it.copy(
                    busy = false,
                    ledger = forgotten,
                    artifacts = it.artifacts.map { artifact ->
                        if (artifact.key in goneKeys) artifact.copy(writtenTo = null) else artifact
                    },
                    destructOffer = stillOwed,
                    destructRefused = stillOwed,
                    message = if (stillOwed.isEmpty()) {
                        "Removed ${gone.size} saved cop${if (gone.size == 1) "y" else "ies"}."
                    } else {
                        "Removed ${gone.size}; ${stillOwed.size} could not be removed and are listed."
                    }
                )
            }
        }
    }

    fun resetReveal() {
        stopRunningJob()
        _state.update {
            it.copy(
                flow = StegoFlow.REVEAL,
                busy = false,
                reads = emptyList(),
                readPassphrase = "",
                readSecondPassphrase = "",
                findings = emptyList(),
                heldShares = emptyList(),
                readShareTotal = 1,
                outcome = null,
                revealedSecretVisible = false,
                fault = null,
                message = "Cleared."
            )
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private fun startJob(block: suspend () -> Unit) {
        val serial = ++jobSerial
        job?.cancel()
        _state.update { it.copy(busy = true, fault = null, message = null) }
        job = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (capacity: StegoCapacityException) {
                _state.update { it.copy(fault = capacity.message ?: "This photo cannot hold that secret.") }
            } catch (input: StegoInputException) {
                _state.update { it.copy(fault = input.message ?: "The engine refused this image.") }
            } catch (error: Exception) {
                _state.update { it.copy(fault = error.message ?: "The operation could not finish.") }
            } finally {
                _state.update { if (serial == jobSerial) it.copy(busy = false) else it }
            }
        }
    }

    private fun stopRunningJob() {
        job?.cancel()
        job = null
        jobSerial += 1
    }

    private fun appendCarrier(carrier: StegoCarrier) {
        _state.update { current ->
            if (current.carriers.size >= current.shareTotal) {
                current.copy(fault = "This set already holds ${current.shareTotal} cover photo(s).")
            } else {
                current.copy(
                    carriers = current.carriers + carrier,
                    artifacts = emptyList(),
                    report = null,
                    fault = null,
                    message = "Cover added: ${carrier.name}"
                )
            }
        }
    }

    private fun offeredPassphrases(primary: String, secondary: String): List<CharArray?> {
        val offered = ArrayList<CharArray?>(2)
        offered += primary.takeIf { it.isNotEmpty() }?.toCharArray()
        if (secondary.isNotEmpty()) offered += secondary.toCharArray()
        return offered
    }

    private fun decisiveFindingOf(findings: List<StegoFinding>): StegoFinding? {
        val order = listOf(
            StegoBranch.REVEALED,
            StegoBranch.READ_ONCE_REVEALED,
            StegoBranch.SHARE_COMPLETE,
            StegoBranch.SHARE_PARTIAL,
            StegoBranch.LOCKED,
            StegoBranch.REFUSED,
            StegoBranch.DAMAGED,
            StegoBranch.FLAT_CARRIER,
            StegoBranch.CARRIES_NOTHING
        )
        return order.firstNotNullOfOrNull { branch -> findings.firstOrNull { it.branch == branch } }
    }

    private fun openedWithSentence(index: Int, firstTyped: Boolean, secondTyped: Boolean): String = when {
        !firstTyped && index == 0 ->
            "It opened with no passphrase: a carrier sealed without one uses a public key derived " +
                "from its own salt, so its contents are obfuscated rather than protected."
        index == 1 ->
            "The message opened with your second passphrase; the first did not open this carrier."
        secondTyped ->
            "The message opened with your first passphrase. The photo does not mark the roles: a " +
                "second envelope, if any, opens only with a different key."
        else -> "The message opened with the passphrase you typed."
    }

    private fun previewPlane(plane: LumaPlane): LumaPlane {
        val longest = max(plane.width, plane.height)
        if (longest <= StegoImages.PREVIEW_LONGEST || longest <= 0) return plane
        val scale = StegoImages.PREVIEW_LONGEST.toFloat() / longest
        return StegoPlanes.resampled(
            plane,
            max(1, (plane.width * scale).roundToInt()),
            max(1, (plane.height * scale).roundToInt())
        )
    }

    private fun writePicture(artifact: StegoArtifact): SavedCopy? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, artifact.displayName)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$SAVED_FOLDER"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val wrote = runCatching {
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(artifact.jpegBytes)
                    stream.flush()
                } ?: error("no output stream for $uri")
            }.isSuccess
            if (!wrote) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            SavedCopy(
                described = "${Environment.DIRECTORY_PICTURES}/$SAVED_FOLDER/${artifact.displayName}",
                location = uri.toString()
            )
        } else {
            val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            val folder = File(pictures, SAVED_FOLDER).apply { mkdirs() }
            val target = File(folder, artifact.displayName)
            val wrote = runCatching {
                FileOutputStream(target).use { stream ->
                    stream.write(artifact.jpegBytes)
                    stream.flush()
                }
            }.isSuccess
            if (!wrote) {
                runCatching { target.delete() }
                return null
            }
            SavedCopy(described = target.absolutePath, location = target.absolutePath)
        }
    }

    private fun removeCopy(copy: OwnedCopy): Boolean {
        val location = copy.path ?: return false
        return runCatching {
            if (location.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(location), null, null) > 0
            } else {
                val file = File(location)
                file.delete() || !file.exists()
            }
        }.getOrDefault(false)
    }

    private data class SavedCopy(val described: String, val location: String)

    private companion object {
        const val SEAL_SEED = 41
        const val MIME_JPEG = "image/jpeg"
        const val SAVED_FOLDER = "SecureVault"
    }
}

/**
 * The one list of conditions under which a seal may start, shared by the button and the engine
 * call so a disabled control and a refused job can never disagree.
 *
 * Two of these are not capacity rules but honesty rules: a decoy without its own passphrase is
 * sealed under the primary key, where the reveal can surface it as the only message the carrier
 * has — so the UI refuses that configuration instead of shipping it.
 */
internal fun stegoSealBlocker(state: StegoUiState): String? = when {
    !SecretShares.isSupported(state.shareTotal) ->
        "This flow disperses across one to three photos."
    state.carriers.size < state.shareTotal ->
        "This set needs ${state.shareTotal} cover photos; ${state.carriers.size} picked."
    state.secret.isBlank() -> "Type the secret you want to hide."
    state.secretBytes > StegoLimits.MAX_SECRET_BYTES ->
        "The secret is ${state.secretBytes} bytes; one envelope holds ${StegoLimits.MAX_SECRET_BYTES}."
    state.deniable && state.passphrase.isBlank() ->
        "A deniable carrier needs a passphrase on the real message."
    state.deniable && state.decoy.isBlank() -> "Type the decoy message."
    state.deniable && state.decoyPassphrase.isBlank() ->
        "Give the decoy its own passphrase."
    state.deniable && state.decoyPassphrase == state.passphrase ->
        "The two passphrases must differ, or both stories open with the same key."
    else -> null
}
