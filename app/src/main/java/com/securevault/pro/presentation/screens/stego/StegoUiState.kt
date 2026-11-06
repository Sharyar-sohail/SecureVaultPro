package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.SecretKind
import com.securevault.pro.crypto.stego.StegoRead
import com.securevault.pro.platform.share.ShareOutcome

/**
 * The stego feature's view state, and the only place that decides *which verdict a screen shows*.
 *
 * Kept free of `android.*` on purpose. Everything the reveal screen promises — "here is your secret",
 * "needs two more of three", "this photo has been edited", "this photo carries nothing" — is decided
 * by pure functions over the engine's own [StegoRead], so the whole decision table is unit-tested on
 * a JVM instead of being re-derived, differently, inside three composables. The engine already chose
 * the verdict; this file only chooses the words and the colour, and it never contradicts the engine.
 *
 * The rule it is written to hold: exactly one branch per reveal, always labelled. An unlabelled
 * spinner or a silent no-op is the failure mode this feature was opened for.
 */
enum class StegoFlow {
    /** The choice between hiding and revealing. */
    HUB,

    /** Pick a carrier, type a secret, seal it out to a file the owner chose. */
    HIDE,

    /** Pick one or more carriers, offer up to two passphrases, read the verdict. */
    REVEAL
}

/**
 * The closed set of things a reveal can conclude.
 *
 * Mirrors [StegoRead] one-to-one, with two splits the engine cannot make because they need the
 * session's context: a dispersal share that completes the set is a different screen from one that
 * does not, and a flat photo is a different sentence from an ordinary one.
 */
enum class StegoBranch {
    /** The secret, in full, opened by a passphrase the owner typed. */
    REVEALED,

    /** The secret, in full, on a carrier that was sealed read-once. */
    READ_ONCE_REVEALED,

    /** A dispersal share recovered; the set is still incomplete, so the message does not exist yet. */
    SHARE_PARTIAL,

    /** The last share arrived and the split rebuilt the whole secret. */
    SHARE_COMPLETE,

    /** The directory matched but Reed–Solomon could not rebuild the frame: too damaged. */
    DAMAGED,

    /** A carrier was found and read cleanly; none of the offered passphrases open it. */
    LOCKED,

    /** The envelope authenticated but the carrier does not match its own seal. */
    REFUSED,

    /** No directory at all, on a photo with real grain: an ordinary photograph. */
    CARRIES_NOTHING,

    /** No directory, and a band too flat to hold one — the photo could never have been a carrier. */
    FLAT_CARRIER
}

/**
 * Which branch a read belongs to.
 *
 * `held` is every share index this session has already recovered, *before* this carrier; a share is
 * only "complete" once the missing set empties, which is [SecretShares.missingIndices] answering, not
 * an arithmetic guess here. The flat check comes from the codec's own [StegoCodec.looksEmpty] — the
 * same measurement the encoder refuses on — so the screen never promises a mark the engine cannot
 * write or read.
 */
fun stegoBranchOf(
    read: StegoRead,
    held: List<Int> = emptyList(),
    carrierIsFlat: Boolean = false
): StegoBranch = when (read) {
    StegoRead.NoCarrier -> if (carrierIsFlat) StegoBranch.FLAT_CARRIER else StegoBranch.CARRIES_NOTHING
    is StegoRead.Damaged -> StegoBranch.DAMAGED
    is StegoRead.Locked -> StegoBranch.LOCKED
    is StegoRead.Refused -> StegoBranch.REFUSED
    is StegoRead.ShareRecovered ->
        if (SecretShares.missingIndices(held + read.shareIndex, read.shareTotal).isEmpty()) {
            StegoBranch.SHARE_COMPLETE
        } else {
            StegoBranch.SHARE_PARTIAL
        }
    is StegoRead.Revealed ->
        if (read.oneRead) StegoBranch.READ_ONCE_REVEALED else StegoBranch.REVEALED
}

/** Headline for a branch. Every one of these is reachable from the table above. */
fun stegoBranchTitle(branch: StegoBranch): String = when (branch) {
    StegoBranch.REVEALED -> "Secret revealed"
    StegoBranch.READ_ONCE_REVEALED -> "Secret revealed — marked read-once"
    StegoBranch.SHARE_PARTIAL -> "One share recovered"
    StegoBranch.SHARE_COMPLETE -> "All shares recovered"
    StegoBranch.DAMAGED -> "Too damaged to read"
    StegoBranch.LOCKED -> "Locked: wrong passphrase"
    StegoBranch.REFUSED -> "Carrier does not match its seal"
    StegoBranch.CARRIES_NOTHING -> "This photo carries nothing"
    StegoBranch.FLAT_CARRIER -> "This photo could not carry a mark"
}

/** True when the branch is a refusal rather than a result, which decides the card's colour. */
fun stegoBranchIsRefusal(branch: StegoBranch): Boolean = when (branch) {
    StegoBranch.DAMAGED, StegoBranch.LOCKED, StegoBranch.REFUSED,
    StegoBranch.CARRIES_NOTHING, StegoBranch.FLAT_CARRIER -> true
    StegoBranch.REVEALED, StegoBranch.READ_ONCE_REVEALED,
    StegoBranch.SHARE_PARTIAL, StegoBranch.SHARE_COMPLETE -> false
}

/** True when the owner is allowed to see secret material on this branch. */
fun stegoBranchShowsSecret(branch: StegoBranch): Boolean = when (branch) {
    StegoBranch.REVEALED, StegoBranch.READ_ONCE_REVEALED, StegoBranch.SHARE_COMPLETE -> true
    else -> false
}

/**
 * How many passphrase slots the owner actually filled.
 *
 * The engine tries every offered key against every block, so the count is what limits a reveal — one
 * filled slot can only ever answer with one message, two filled slots are what make the deniability
 * decision ([StegoPayloadSelection.choose]) worth running.
 */
fun passphraseSlots(primary: String, secondary: String): Int =
    (if (primary.isNotEmpty()) 1 else 0) + (if (secondary.isNotEmpty()) 1 else 0)

/**
 * The dispersal sentence, built from the indices still missing rather than from a count.
 *
 * "Needs 2 more" is only half of what the owner needs: the carriers are interchangeable to nobody
 * holding them, so the screen names which ones to look for. Numbered from one, because share index
 * zero is a thing only the code believes in.
 */
fun dispersalStatus(held: List<Int>, total: Int): String = when {
    total <= 1 -> "One carrier holds the whole secret."
    held.isEmpty() -> "0 of $total carriers read — the message does not exist until all of them do."
    else -> {
        val missing = SecretShares.missingIndices(held, total)
        if (missing.isEmpty()) {
            "All $total carriers recovered — the split rebuilds the secret now."
        } else {
            "Needs ${missing.size} more of $total · carriers " +
                missing.joinToString(", ") { "${it + 1}" } + " still missing"
        }
    }
}

/** Rebuilds the secret from a complete share set, or null while the set is incomplete. */
fun rebuildSecret(held: List<SecretShares.Share>, total: Int): ByteArray? {
    if (total <= 1 || !SecretShares.isSupported(total)) return null
    if (SecretShares.revealsNothing(held.size, total)) return null
    return SecretShares.join(held.sortedBy { it.index }, total)
}

/** 0..100 for the capacity bar, clamped so a rounding artefact cannot print "101% used". */
fun capacityUsedPercent(utilisation: Double): Int =
    (utilisation.coerceIn(0.0, 1.0) * 100).toInt()

/**
 * Why the marked JPEG still reads as a photograph, in the owner's terms.
 *
 * Three true facts, no marketing: the mark rides in the photo's brightness and stays inside a few
 * grey levels, the file is a normal JPEG that keeps the picture's own colours, and the name is the
 * camera app's shape. A two-envelope carrier adds the one claim that matters most — the file is the
 * same shape whatever it holds.
 *
 * [keepsColor] is honest bookkeeping, not a marketing switch: a photo the owner picked wears its own
 * colours, a generated cover has none to wear and really does leave as a grey JPEG.
 */
fun whyItLooksOrdinary(
    peakGreyLevel: Float,
    envelopeCount: Int,
    fileName: String,
    grain: String,
    keepsColor: Boolean = true
): String =
    "The mark rides in the photo's brightness, never more than ${peakGreyLevel.toInt()} shades off " +
        "the original, on grain this photo already had ($grain). What leaves the app is " +
        if (keepsColor) {
            "an ordinary picture that keeps its own colours, named like a camera file — " +
                "$fileName. "
        } else {
            "an ordinary picture named like a camera file — $fileName. "
        } +
        if (envelopeCount > 1) {
            "It holds two messages, one for each passphrase, and the picture is the same shape " +
                "whichever one is opened."
        } else {
            "One message, and nothing on the surface of the picture that says it is there."
        }

// -------------------------------------------------------------------------------------------------
// State carriers. All plain data; no bitmap, no Uri, no Context, so the whole file compiles on a JVM.
// Arrays are compared and hashed by content, so a recompose cannot mistake one carrier for another.
// -------------------------------------------------------------------------------------------------

/** Null-safe colour-array equality, for the optional ARGB fields below. */
private infix fun IntArray?.sameColorsAs(other: IntArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> this.contentEquals(other)
}

/** One photograph the owner picked or the app generated, plus the plane the engine works on. */
data class StegoCarrier(
    val name: String,
    val plane: LumaPlane,
    val generated: Boolean = false,
    /** The picked photo's own ARGB, on [plane]'s exact grid; null for a generated cover. */
    val color: IntArray? = null
) {
    override fun equals(other: Any?): Boolean =
        other is StegoCarrier && other.name == name && other.generated == generated &&
            other.plane === plane && other.color sameColorsAs color

    override fun hashCode(): Int =
        (name.hashCode() * 31 + plane.hashCode()) * 31 + (color?.contentHashCode() ?: 0)
}

/** A sealed carrier this flow produced: the bytes, the name it wants, and where it landed. */
data class StegoArtifact(
    val key: String,
    val displayName: String,
    val shareIndex: Int,
    val shareTotal: Int,
    val jpegBytes: ByteArray,
    val preview: LumaPlane,
    val writtenTo: String? = null,
    /** The colour preview's grid; zeroed and null when the carrier left as a grey JPEG. */
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val previewPixels: IntArray? = null
) {
    val saved: Boolean get() = !writtenTo.isNullOrBlank()
    val sizeBytes: Int get() = jpegBytes.size

    override fun equals(other: Any?): Boolean =
        other is StegoArtifact && other.key == key && other.writtenTo == writtenTo &&
            other.jpegBytes.contentEquals(jpegBytes) &&
            other.previewPixels sameColorsAs previewPixels

    override fun hashCode(): Int =
        (key.hashCode() * 31 + jpegBytes.contentHashCode()) * 31 +
            (previewPixels?.contentHashCode() ?: 0)
}

/** The honest facts a seal reports back, all measured by the engine rather than asserted here. */
data class StegoSealReport(
    val latticeLabel: String,
    val chipRate: Int,
    val capacityUsedPercent: Int,
    val secretBytes: Int,
    val perEnvelopeLimit: Int,
    val envelopeCount: Int,
    val peakGreyLevel: Int,
    val carrierDescription: String,
    val grainLabel: String,
    val fileName: String,
    val readOnce: Boolean,
    /** False when a cover came from inside the app, which has no colours of its own to wear. */
    val keepsColor: Boolean = true,
    /**
     * True only when the app really re-sent this carrier the way a chat app does — resized,
     * re-written at send quality, read back — and the message came out of the copy. It is a
     * measurement of this photo, not a property of the format, so the screen quotes it as what
     * happened rather than as a promise.
     */
    val survivesSend: Boolean = false,
    /** True when the send round trip really ran on this phone, whatever it proved. */
    val sendTested: Boolean = false
) {
    /** The capacity line under the result, in room rather than in mark vocabulary. */
    val capacityLine: String
        get() = "$capacityUsedPercent% of the room this picture had"

    /** Bytes in hand against the ceiling this very photo can carry, per passphrase. */
    val fillLine: String
        get() = "Your message is $secretBytes bytes of the roughly $perEnvelopeLimit this " +
            "picture can hold."

    /** What the send test did or did not manage to prove, said the same way on every screen. */
    val sendLine: String
        get() = when {
            survivesSend ->
                "This picture was sent the way a chat app sends one and read back from the copy — the " +
                    "message came out."
            sendTested ->
                "This picture went through the send test on this phone and the message did not come back " +
                    "out of the copy. A bigger, busier photo or a shorter message stands a much better " +
                    "chance."
            else ->
                "This phone could not finish the send test on this picture, so its survival in a chat " +
                    "app is untested rather than promised."
        }
}

/** What one carrier said when it was read, kept so a three-image set can show three verdicts. */
data class StegoFinding(
    val carrierName: String,
    val branch: StegoBranch,
    val detail: String,
    val shareIndex: Int? = null,
    val shareTotal: Int = 1,
    val confidenceLabel: String = ""
)

/** The single verdict the reveal screen ends on. */
data class StegoRevealOutcome(
    val branch: StegoBranch,
    val headline: String,
    val explanation: String,
    val kindLabel: String = "",
    val confidenceLabel: String = "",
    val recovered: Recovered? = null,
    val selection: TwoPasswordChoice? = null,
    val readOnce: Boolean = false
)

/** A share + print hand-over the owner can be told the result of. */
data class StegoHandoff(
    val label: String,
    val outcome: ShareOutcome
)

/**
 * The screen's whole snapshot: which flow is open, what has been picked, what has been sealed or
 * read, and what the read-once ledger owns — restored at start from the private ledger record, so a
 * copy saved in an earlier session is still listed here.
 *
 * Immutable, because a recomposing composable must not be able to lose a ledger entry or half-update
 * a sealed set. The derived properties below are what the buttons read, so a disabled control always
 * has a stated reason behind it.
 */
data class StegoUiState(
    val flow: StegoFlow = StegoFlow.HUB,
    val busy: Boolean = false,
    val message: String? = null,
    val fault: String? = null,
    val carriers: List<StegoCarrier> = emptyList(),
    val secret: String = "",
    val passphrase: String = "",
    val deniable: Boolean = false,
    val decoy: String = "",
    val decoyPassphrase: String = "",
    val shareTotal: Int = 1,
    val readOnce: Boolean = false,
    val artifacts: List<StegoArtifact> = emptyList(),
    val report: StegoSealReport? = null,
    val reads: List<StegoCarrier> = emptyList(),
    val readPassphrase: String = "",
    val readSecondPassphrase: String = "",
    val findings: List<StegoFinding> = emptyList(),
    val heldShares: List<SecretShares.Share> = emptyList(),
    val readShareTotal: Int = 1,
    val outcome: StegoRevealOutcome? = null,
    val ledger: SelfDestructLedger = SelfDestructLedger(),
    val destructOffer: List<OwnedCopy> = emptyList(),
    val destructRefused: List<OwnedCopy> = emptyList(),
    val handoff: StegoHandoff? = null,
    val revealedSecretVisible: Boolean = false
) {

    /** Envelopes this seal will write, which is what halves the capacity the photo can hold. */
    val envelopeCount: Int get() = if (deniable && decoy.isNotBlank()) 2 else 1

    val secretBytes: Int get() = secret.toByteArray(Charsets.UTF_8).size

    /** Passphrase slots filled on the hide side; two typed slots is what makes a carrier deniable. */
    val typedHideSlots: Int get() = passphraseSlots(passphrase, decoyPassphrase)

    val typedReadSlots: Int get() = passphraseSlots(readPassphrase, readSecondPassphrase)

    /** The sentence under the dispersal control: hide-side until a read declares its own set size. */
    val dispersalLine: String
        get() = if (flow == StegoFlow.REVEAL) {
            dispersalStatus(heldShares.map { it.index }, readShareTotal)
        } else {
            dispersalStatus(emptyList(), shareTotal)
        }

    /** A cover to mark, a non-empty secret, and a place to put the passphrase. */
    val canSeal: Boolean
        get() = !busy && carriers.isNotEmpty() && secret.isNotBlank() &&
            SecretShares.isSupported(shareTotal) &&
            (!deniable || decoy.isNotBlank()) &&
            secretBytes <= com.securevault.pro.crypto.stego.StegoLimits.MAX_SECRET_BYTES

    val canRead: Boolean get() = !busy && reads.isNotEmpty()

    /**
     * True once the last share landed. Keyed on [readShareTotal] — the set size the carriers
     * themselves declared — rather than on the hide-side choice, which is a different session's job.
     */
    val holdsCompleteSet: Boolean
        get() = rebuildSecret(heldShares, readShareTotal) != null

    val showsSecret: Boolean get() = outcome != null && stegoBranchShowsSecret(outcome.branch)

    /** The read-once question is only ever asked when the ledger holds copies the owner opened. */
    val awaitingSelfDestruct: Boolean get() = destructOffer.isNotEmpty()

    /** Saved copies this device has seen opened. Bookkeeping only: no file is touched by opening. */
    val openedCopyCount: Int get() = ledger.copies.count { ledger.isDue(it.key) }

    val hasArtifacts: Boolean get() = artifacts.isNotEmpty()
}
