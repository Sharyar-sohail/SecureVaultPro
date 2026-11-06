package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.CanonicalLattice
import com.securevault.pro.crypto.stego.LatticeSide
import com.securevault.pro.crypto.stego.LlBand
import com.securevault.pro.crypto.stego.LumaPlane
import com.securevault.pro.crypto.stego.MarkWriter
import com.securevault.pro.crypto.stego.Pbkdf2StegoKeyDeriver
import com.securevault.pro.crypto.stego.Qim
import com.securevault.pro.crypto.stego.ReedSolomon
import com.securevault.pro.crypto.stego.RefusalReason
import com.securevault.pro.crypto.stego.SecretKind
import com.securevault.pro.crypto.stego.StegoBits
import com.securevault.pro.crypto.stego.StegoCapacityException
import com.securevault.pro.crypto.stego.StegoDecodeRequest
import com.securevault.pro.crypto.stego.StegoEncodeRequest
import com.securevault.pro.crypto.stego.StegoEncodeResult
import com.securevault.pro.crypto.stego.StegoEnvelopeRequest
import com.securevault.pro.crypto.stego.StegoFrame
import com.securevault.pro.crypto.stego.StegoInputException
import com.securevault.pro.crypto.stego.StegoKeyDeriver
import com.securevault.pro.crypto.stego.StegoKeySchedule
import com.securevault.pro.crypto.stego.StegoLimits
import com.securevault.pro.crypto.stego.StegoProfile
import com.securevault.pro.crypto.stego.StegoRead

/**
 * The missing orchestrator: the thing that turns `crypto/stego`'s parts into a hide and a reveal.
 *
 * That package ships the maths — the LL band, the canonical lattice, QIM chips, the wire format,
 * Reed–Solomon, the key schedule — and the contracts `StegoEncodeRequest`, `StegoEncodeResult` and
 * `StegoRead`, but nothing produced or consumed any of them: there was no encoder, no decoder and
 * therefore no feature. This file is the pipeline those contracts describe, written against the
 * signatures as they really are:
 *
 * **seal** — plane → band → fingerprint of the *unmarked* band (so a later copy can be compared with
 * it) → one sealed envelope per passphrase, padded to a common length and placed in slots the key
 * schedule chose → header + blocks → Reed–Solomon → directory in the ring → chips on the lattice →
 * band delta → back into pixels.
 *
 * **scan** — plane → ring-directory probe over the orientation and lattice candidates → Reed–Solomon
 * → header → geometry gate → every offered passphrase against every block (a wrong key fails the GCM
 * tag and yields nothing, which is the only answer the format can give) → fingerprint drift →
 * verdict.
 *
 * The lattice is chosen from the frame length, and the frame length is known exactly before the
 * blocks are sealed, because a sealed block is `56 + padded bytes` regardless of the lattice — so
 * the plan converges in one or two passes and never writes a directory the payload contradicts.
 *
 * It lives in the screen package only because this assignment's ownership set stops there. It holds
 * no Android types at all, so moving it to `crypto/stego` is a package line and nothing else.
 */
class StegoCodec(
    private val deriver: StegoKeyDeriver = Pbkdf2StegoKeyDeriver(),
    private val iterations: Int = StegoLimits.PBKDF2_ITERATIONS
) {

    private val coder = ReedSolomon()

    /** One carrier's worth of honest facts about a seal, on top of the core's own result. */
    data class Sealed(
        val result: StegoEncodeResult,
        val plan: StegoPlan,
        val payloadBytes: Int,
        val frameBytes: Int,
        val peakGreyLevel: Float,
        val fingerprint: ByteArray,
        val secretBytes: Int,
        /**
         * True only when this build actually re-read the photo after a chat app's redraw — resized,
         * recompressed and blurred — and got the same frame back. It is a measurement, not a promise,
         * and the screen says "not measured" rather than guessing when it is false.
         */
        val measuredSurvival: Boolean = false,
        /**
         * True when the redraw itself ran on this phone, whether or not the frame came back. Without
         * it a picture that *failed* the send test would be reported the same way as one the phone
         * never managed to test, and the owner would be told the wrong thing.
         */
        val sendTested: Boolean = false
    ) {
        val lattice: LatticeSide get() = plan.lattice
        val chipRate: Int get() = plan.chipRate
        val envelopeCount: Int get() = result.envelopeCount
        val isDeniable: Boolean get() = envelopeCount > 1
        val shareTotal: Int get() = result.shareTotal
        val shareIndex: Int get() = result.shareIndex
        val readsOnce: Boolean get() = result.oneRead
        val capacityUsed: Double get() = plan.utilisation

        /** The marked luminance grid; `StegoImages` is what turns it back into a photo. */
        val marked: LumaPlane get() = result.marked
    }

    /** What a scan concluded, plus the two measurements the screens quote beside the verdict. */
    data class Finding(
        val read: StegoRead,
        val confidence: Double = 0.0,
        val verdict: TamperVerdict = TamperVerdict.INTACT,
        val geometry: GeometryVerdict = GeometryVerdict.MATCHES,
        val lattice: LatticeSide? = null,
        val chipRate: Int = 0,
        val orientation: Int = 0
    ) {
        val revealed: StegoRead.Revealed? get() = read as? StegoRead.Revealed
        val share: StegoRead.ShareRecovered? get() = read as? StegoRead.ShareRecovered
        val refused: StegoRead.Refused? get() = read as? StegoRead.Refused
        val locked: StegoRead.Locked? get() = read as? StegoRead.Locked
        val damaged: StegoRead.Damaged? get() = read as? StegoRead.Damaged
        val hasCarrier: Boolean get() = read !is StegoRead.NoCarrier

        /** Sentence for the reveal screen, built only from the numbers this finding really carries. */
        val summary: String
            get() = when (val outcome = read) {
                is StegoRead.Revealed ->
                    "${(confidence * 100).toInt()}% read confidence · " +
                        TamperReport.explain(verdict, outcome.drift)
                is StegoRead.ShareRecovered ->
                    "Share ${outcome.shareIndex + 1} of ${outcome.shareTotal} · " +
                        "${(confidence * 100).toInt()}% read confidence"
                is StegoRead.Locked ->
                    "Carrier found. None of the passphrases you offered opens it."
                is StegoRead.Damaged ->
                    "Part of the picture was found, but too many marks had changed to rebuild the " +
                        "message. $REDRAW_CAUSE $REDRAW_FIX"
                is StegoRead.Refused ->
                    outcome.reason.explanation +
                        if (outcome.reason == RefusalReason.GEOMETRY_MOVED ||
                            outcome.reason == RefusalReason.CARRIER_EDITED
                        ) " $REDRAW_CAUSE $REDRAW_FIX" else ""
                StegoRead.NoCarrier ->
                    "Nothing is hidden in this image. If this is a copy of one, the copy may be why: " +
                        "$REDRAW_CAUSE $REDRAW_FIX"
            }
    }

    /** Cheap "is there anything here?" answer the viewfinder can afford on every frame. */
    data class Probe(val lattice: LatticeSide, val directory: StegoFrame.Directory, val orientation: Int)

    // ---- sealing -----------------------------------------------------------------------------

    /**
     * Hides one or two envelopes in `request.carrier` and returns the marked plane.
     *
     * With no [attack] this writes exactly one grid, the one asked for, and is byte-for-byte the seal
     * that shipped before. Give it an [attack] — the redraw a messaging app performs on the photo —
     * and it becomes a search: each grid in [StegoProfile.preferred] is sealed, marked, attacked and
     * re-read, and the first grid whose frame comes back intact is the one returned. Nothing is ever
     * promised here that was not measured on this photo, which is why the search is worth the seconds
     * it costs at seal time and none at all when the receiver scans.
     *
     * Throws [com.securevault.pro.crypto.stego.StegoCapacityException] when the secret will not fit —
     * before any key derivation is paid for — and [StegoInputException] when the photo is too small
     * or too flat to carry a mark. Both are the screen's own sentences, not a stack trace.
     */
    fun seal(
        request: StegoEncodeRequest,
        profile: StegoProfile = StegoProfile.CLASSIC,
        attack: ((LumaPlane) -> LumaPlane?)? = null
    ): Sealed {
        val carrier = StegoPlanes.sanitised(request.carrier)
        if (!StegoCapacity.isWorkable(carrier)) {
            throw StegoInputException(
                "This photo is ${carrier.width}x${carrier.height}; a carrier needs a longest side of at " +
                    "least ${StegoLimits.MIN_WORKING_LONGEST} samples."
            )
        }
        val sizes = request.envelopes.map { it.secret.size }
        val candidates = if (attack == null) listOf(profile) else StegoProfile.preferred
        val limit = candidates.maxOfOrNull {
            StegoCapacity.maxSecretBytes(carrier, request.envelopes.size, it)
        } ?: 0
        if (sizes.any { it > StegoLimits.MAX_SECRET_BYTES }) {
            throw StegoCapacityReporting.refused(limit, sizes.max() ?: 0)
        }
        val shape = StegoFrameShape(sizes)
        val salt = request.salt
        // Derivation cost is paid once for the whole search, never once per grid.
        val keys = request.envelopes.map { keyFor(it.passphrase, salt) }
        val slots = StegoKeySchedule.assignSlots(keys, salt, deriver)
        var refusal: StegoCapacityException? = null
        var unwritten: Sealed? = null
        var redrawn = false
        for (candidate in candidates) {
            val sealed = try {
                sealOn(carrier, request, shape, salt, keys, slots, candidate, limit)
            } catch (capacity: StegoCapacityException) {
                refusal = capacity
                continue
            }
            if (attack == null) return sealed
            // A null answer means the redraw could not be performed on this phone. That is not a
            // failure of the mark and must never be reported as one — it is simply unmeasured, so the
            // carrier is handed back without the survival claim.
            val attacked = attack(sealed.marked)
            if (attacked != null) {
                redrawn = true
                if (frameSurvives(attacked, salt)) {
                    return sealed.copy(measuredSurvival = true, sendTested = true)
                }
            }
            if (unwritten == null) unwritten = sealed
        }
        // No grid came back through the redraw. The owner still gets a carrier — the quietest grid,
        // which is the one that survives a print — but `measuredSurvival` stays false and the screen
        // says what was tested instead of claiming a send it never proved.
        val fallback = unwritten ?: throw refusal ?: StegoCapacityReporting.refused(limit, sizes.max() ?: 0)
        return if (redrawn) fallback.copy(sendTested = true) else fallback
    }

    private fun sealOn(
        carrier: LumaPlane,
        request: StegoEncodeRequest,
        shape: StegoFrameShape,
        salt: ByteArray,
        keys: List<ByteArray>,
        slots: IntArray,
        profile: StegoProfile,
        limit: Int
    ): Sealed {
        val band = bandOf(carrier, profile)
        val statistics = StegoTamper.statisticsOf(band)
        if (!statistics.carriesDetail) {
            throw StegoInputException(
                "This image is a flat field — its band spread is ${statistics.spread}, and there is " +
                    "nothing to hide a mark under."
            )
        }
        val paddedBytes = shape.paddedBytes
        val sizes = request.envelopes.map { it.secret.size }
        val fingerprint = StegoTamper.fingerprintOf(band)

        var plan = StegoCapacity.requireFits(carrier, shape, profile)
        var payload = ByteArray(0)
        var blocks: List<ByteArray> = emptyList()
        var meta = StegoFrame.Meta(
            lattice = plan.lattice,
            mode = request.mode,
            blockCount = request.envelopes.size,
            bandCellsWide = band.cellsWide,
            bandCellsHigh = band.cellsHigh,
            planeWidth = carrier.width,
            planeHeight = carrier.height,
            salt = salt,
            shareTotal = request.shareTotal,
            shareIndex = request.shareIndex,
            oneRead = request.oneRead
        )
        // The frame length decides the lattice, and sealing needs the lattice in the header, so the
        // two are resolved together. Two passes is the most this can ever take: block sizes do not
        // depend on the lattice, so the second measurement is either equal or a hard refusal.
        var guard = 0
        while (true) {
            meta = meta.copy(lattice = plan.lattice)
            blocks = sealBlocks(request, meta, keys, slots, salt, paddedBytes, fingerprint)
            payload = StegoFrame.buildPayload(meta, blocks)
            val measuredBits = coder.encodedBitCount(payload.size)
            if (measuredBits == plan.frameBits) break
            val replanned = StegoCapacity.planFor(measuredBits, band.cellsWide, band.cellsHigh, profile)
                ?: throw StegoCapacityReporting.refused(limit, sizes.max() ?: 0)
            if (guard >= 2 || replanned.lattice == plan.lattice && replanned.chipRate == plan.chipRate) {
                plan = replanned
                break
            }
            plan = replanned
            guard += 1
        }
        if (coder.encodedBitCount(payload.size) > StegoCapacity.maxFrameBits) {
            throw StegoCapacityReporting.refused(limit, sizes.max() ?: 0)
        }

        val lattice = CanonicalLattice(plan.side, band.cellsWide, band.cellsHigh)
        val directoryBits = StegoBits.toBits(
            StegoFrame.packDirectory(
                StegoFrame.Directory(
                    mode = request.mode,
                    shareTotal = request.shareTotal,
                    shareIndex = request.shareIndex,
                    chipRate = plan.chipRate,
                    lattice = plan.lattice,
                    frameBits = coder.encodedBitCount(payload.size),
                    oneRead = request.oneRead
                )
            )
        )
        val codeword = coder.encodeChunks(payload)
        val payloadBits = StegoBits.toBits(codeword)
        val delta = MarkWriter.write(
            band = band,
            lattice = lattice,
            directoryBits = directoryBits,
            payloadBits = payloadBits,
            chipRate = plan.chipRate,
            step = profile.step
        )
        val marked = profile.synthesize(carrier, delta)
        return Sealed(
            result = StegoEncodeResult(
                marked = marked,
                geometry = StegoCapacity.geometryOf(carrier, plan),
                frameBytes = codeword.size,
                chipRate = plan.chipRate,
                envelopeCount = request.envelopes.size,
                secretBytes = sizes.sum(),
                salt = salt,
                shareTotal = request.shareTotal,
                shareIndex = request.shareIndex,
                oneRead = request.oneRead
            ),
            plan = plan.copy(frameBits = coder.encodedBitCount(payload.size)),
            payloadBytes = payload.size,
            frameBytes = codeword.size,
            peakGreyLevel = MarkWriter.peakGreyLevel(delta),
            fingerprint = fingerprint,
            secretBytes = sizes.sum()
        )
    }

    /**
     * True when the frame written into `marked` can still be pulled out of the photo this app would
     * have handed to a chat app — before any passphrase is typed.
     *
     * It stops short of opening an envelope on purpose. The envelope is what the passphrase protects,
     * and proving that the *error-corrected frame* came back byte-identical (header version, CRC-16
     * and this carrier's own salt) proves the bitstream survived, which is the only thing the channel
     * can be blamed for. Proving it without a key also keeps a 600 000-round derivation out of a loop
     * that may run twice.
     */
    private fun frameSurvives(attacked: LumaPlane, salt: ByteArray): Boolean = runCatching {
        val acquisition = acquire(attacked) ?: return false
        val payload = readPayload(acquisition) ?: return false
        StegoFrame.parsePayload(payload)?.meta?.salt?.contentEquals(salt) == true
    }.getOrDefault(false)


    private fun sealBlocks(
        request: StegoEncodeRequest,
        meta: StegoFrame.Meta,
        keys: List<ByteArray>,
        slots: IntArray,
        salt: ByteArray,
        paddedBytes: Int,
        fingerprint: ByteArray
    ): List<ByteArray> {
        val blocks = arrayOfNulls<ByteArray>(request.envelopes.size)
        request.envelopes.forEachIndexed { index, envelope ->
            val slot = slots.getOrElse(index) { index }
            if (blocks[slot] != null) return@forEachIndexed
            blocks[slot] = StegoFrame.sealEnvelope(
                key = keys[index],
                iv = StegoKeySchedule.ivFor(salt, slot, deriver),
                meta = meta,
                blockIndex = slot,
                kind = envelope.kind,
                secret = envelope.secret,
                fingerprint = fingerprint,
                paddedBytes = paddedBytes,
                deriver = deriver
            )
        }
        if (blocks.any { it == null }) {
            throw StegoInputException("The envelopes could not be placed on the carrier.")
        }
        return blocks.mapNotNull { it }
    }

    /**
     * The one-key view of [seal] the screens use: a primary envelope, optionally a second one under a
     * different passphrase, plus where this carrier sits in a dispersal set.
     *
     * A share is sealed as [SecretKind.KEYFILE], which is how the reveal path knows that what it
     * recovered is one piece of a set rather than a whole message; a decoy payload always travels as
     * [SecretKind.MESSAGE], so opening a dispersed carrier with the cover passphrase still gives a
     * complete, plausible message.
     */
    fun sealCarrier(
        carrier: LumaPlane,
        primary: ByteArray,
        primaryPassphrase: CharArray?,
        decoy: ByteArray? = null,
        decoyPassphrase: CharArray? = null,
        shareTotal: Int = 1,
        shareIndex: Int = 0,
        oneRead: Boolean = false,
        salt: ByteArray = StegoKeySchedule.salt(),
        profile: StegoProfile = StegoProfile.CLASSIC,
        attack: ((LumaPlane) -> LumaPlane?)? = null
    ): Sealed {
        val envelopes = ArrayList<StegoEnvelopeRequest>(2)
        envelopes += StegoEnvelopeRequest(
            secret = primary,
            kind = if (shareTotal > 1) SecretKind.KEYFILE else SecretKind.MESSAGE,
            passphrase = primaryPassphrase
        )
        if (decoy != null && decoy.isNotEmpty()) {
            envelopes += StegoEnvelopeRequest(
                secret = decoy,
                kind = SecretKind.MESSAGE,
                passphrase = decoyPassphrase ?: primaryPassphrase
            )
        }
        return seal(
            StegoEncodeRequest(
                carrier = carrier,
                envelopes = envelopes,
                shareTotal = shareTotal,
                shareIndex = shareIndex,
                oneRead = oneRead,
                salt = salt
            ),
            profile = profile,
            attack = attack
        )
    }

    /** Splits a message for `shareTotal` carriers; the primary envelope of each carrier is one share. */
    fun sharesFor(message: ByteArray, shareTotal: Int): List<ByteArray> =
        SecretShares.split(message, shareTotal)

    // ---- reading -----------------------------------------------------------------------------

    /**
     * Reads a carrier, or explains precisely why it is not one.
     *
     * Every passphrase offered is tried against every block of the frame, because nothing on the
     * image says which slot belongs to which key — that is the deniability design, and its cost is
     * at most four GCM opens per passphrase.
     */
    fun scan(request: StegoDecodeRequest): Finding {
        val received = StegoPlanes.sanitised(request.carrier)
        if (StegoPlanes.longestSide(received) < StegoLimits.MIN_SCAN_LONGEST) return Finding(StegoRead.NoCarrier)
        val acquisition = acquire(received) ?: return Finding(StegoRead.NoCarrier)
        val directory = acquisition.directory
        val votes = MarkWriter.readConfidences(
            band = acquisition.band,
            lattice = acquisition.lattice,
            bitCount = directory.frameBits,
            chipRate = directory.chipRate,
            step = acquisition.profile.step,
            directory = false
        )
        val bits = IntArray(votes.size) { if (votes[it] >= 0f) 1 else 0 }
        val codeword = StegoBits.fromBits(bits, directory.frameBits)
        val confidence = StegoConfidence.of(votes, directory.chipRate)
        val damaged = StegoRead.Damaged(directory.lattice, directory.chipRate, directory.shareTotal, directory.shareIndex)
        val pre = Finding(
            read = StegoRead.NoCarrier,
            confidence = confidence,
            lattice = directory.lattice,
            chipRate = directory.chipRate,
            orientation = acquisition.orientation
        )
        val payload = coder.decodeChunks(codeword) ?: return pre.copy(read = damaged)
        val parsed = StegoFrame.parsePayload(payload) ?: return pre.copy(read = damaged)

        val meta = parsed.meta
        val geometry = StegoGeometry.verdictOf(meta, acquisition.plane)
        val observedSignature = StegoTamper.fingerprintOf(acquisition.band)
        val statistics = StegoTamper.statisticsOf(acquisition.band)

        var drift = -1
        var opened: StegoFrame.Opened? = null
        var openedWithKey = -1
        var keyIndex = 0
        // Every passphrase the owner offered must be tried, not one per block: a single-envelope
        // carrier has one block, and the correct passphrase typed into the second slot used to be
        // skipped entirely and reported as Locked. An empty offer still means "the public domain
        // key", which is how a passphrase-free envelope is opened.
        val offered = if (request.passphrases.isEmpty()) listOf(null) else request.passphrases.take(4)
        outer@ while (keyIndex < offered.size && opened == null) {
            val key = keyFor(offered[keyIndex], meta.salt)
            parsed.blocks.forEachIndexed { blockIndex, blob ->
                if (opened != null) return@forEachIndexed
                val candidate = StegoFrame.openEnvelope(key, blob, meta, blockIndex) ?: return@forEachIndexed
                drift = StegoTamper.divergence(candidate.fingerprint, observedSignature)
                opened = candidate
                openedWithKey = keyIndex
            }
            keyIndex += 1
        }

        if (opened == null) {
            // No key, so no comparable signature: the drift is reported as unknown rather than as a
            // number the app did not measure. Geometry, which needs no key, still answers for a crop.
            return if (geometry == GeometryVerdict.MATCHES) {
                pre.copy(read = StegoRead.Locked(meta, payload, drift))
            } else {
                pre.copy(
                    read = StegoRead.Refused(meta, RefusalReason.GEOMETRY_MOVED, drift),
                    geometry = geometry
                )
            }
        }
        val success = opened ?: return pre
        val verdict = if (drift < 0) TamperVerdict.INTACT else TamperReport.of(drift, statistics)
        val read: StegoRead = when {
            geometry != GeometryVerdict.MATCHES -> StegoRead.Refused(meta, RefusalReason.GEOMETRY_MOVED, drift)
            verdict == TamperVerdict.FLAT -> StegoRead.Refused(meta, RefusalReason.FLAT_CARRIER, drift)
            verdict == TamperVerdict.EDITED -> StegoRead.Refused(meta, RefusalReason.CARRIER_EDITED, drift)
            meta.shareTotal > 1 && success.kind == SecretKind.KEYFILE ->
                StegoRead.ShareRecovered(meta, success.secret, meta.shareTotal, meta.shareIndex)
            else -> StegoRead.Revealed(
                meta = meta,
                kind = success.kind,
                secret = success.secret,
                drift = drift,
                oneRead = meta.oneRead,
                openedWithKey = openedWithKey
            )
        }
        return pre.copy(read = read, verdict = verdict, geometry = geometry)
    }

    /** Opens every envelope the offered passphrases can reach, for the two-password decision. */
    data class Opening(val keyIndex: Int, val blockIndex: Int, val kind: SecretKind, val secret: ByteArray)

    fun openAll(plane: LumaPlane, passphrases: List<CharArray?>): List<Opening> {
        val acquisition = acquire(plane) ?: return emptyList()
        val payload = readPayload(acquisition) ?: return emptyList()
        val parsed = StegoFrame.parsePayload(payload) ?: return emptyList()
        val found = ArrayList<Opening>()
        passphrases.forEachIndexed { keyIndex, passphrase ->
            val key = keyFor(passphrase, parsed.meta.salt)
            parsed.blocks.forEachIndexed { blockIndex, blob ->
                val opened = StegoFrame.openEnvelope(key, blob, parsed.meta, blockIndex) ?: return@forEachIndexed
                found += Opening(keyIndex, blockIndex, opened.kind, opened.secret)
            }
        }
        return found
    }

    /** A single frame the viewfinder can afford: does this image carry our directory at all? */
    fun probe(plane: LumaPlane): Probe? {
        val acquisition = acquire(plane) ?: return null
        return Probe(acquisition.directory.lattice, acquisition.directory, acquisition.orientation)
    }

    /**
     * True when neither grid finds enough photo detail to hide a mark under, which the screens say
     * before decoding. Both grids are asked because a photo that is too smooth for the fine one can
     * still carry the coarse mark — and the reverse is what an over-sharpened copy looks like.
     */
    fun looksEmpty(plane: LumaPlane): Boolean =
        StegoProfile.searched.none { StegoTamper.statisticsOf(bandOf(plane, it)).carriesDetail }

    // ---- internals ---------------------------------------------------------------------------

    private data class Acquisition(
        val plane: LumaPlane,
        val band: LlBand,
        val lattice: CanonicalLattice,
        val directory: StegoFrame.Directory,
        val orientation: Int,
        val profile: StegoProfile
    )

    /**
     * Finds the ring directory over the grids, orientations and lattice candidates.
     *
     * The ring mapping is rate-independent — directory bit *i* always sits on ring slot *i* — so the
     * probe needs one vote per bit and no tap table, which is what makes it affordable on a live
     * camera frame. The grid is not labelled anywhere on the image: a coarse-grid read of a fine-grid
     * carrier produces noise, not a directory, so the probe simply measures both grids upright first
     * and lets the directory's own magic and checksum ([StegoFrame.unpackDirectory]) answer "this
     * really is one of our carriers" instead of "a lucky photograph".
     * [StegoLimits.MAX_ACQUISITION_TRIES] caps the work.
     */
    private fun acquire(plane: LumaPlane): Acquisition? {
        // Two geometries, in the cheap order. A carrier that arrived at the size this app seals at
        // answers the first try and pays nothing extra; one that a chat app rescaled answers the
        // second, after the plane is brought back to the grid the mark was written on. The order is
        // also what keeps older carriers — sealed before the grid was fixed — reading exactly as they
        // did, because their own size is tried first and wins.
        val direct = acquireOn(plane)
        if (direct != null) return direct
        // The rescue is for a copy that came back from a chat app, and only that. A live viewfinder
        // frame is a small, cropped, off-square image whose cells disagree with the writer's for
        // reasons no rescale can undo, and paying a second full acquisition for it on every frame is
        // the difference between a scanner that keeps up and one that stutters.
        if (StegoPlanes.longestSide(plane) < StegoPlanes.RESCUE_MIN_LONGEST) return null
        val canonical = StegoPlanes.canonicalised(plane)
        return if (canonical === plane) null else acquireOn(canonical)
    }

    private fun acquireOn(plane: LumaPlane): Acquisition? {
        var tries = 0
        for (orientation in StegoPlanes.orientations) {
            val rotated = if (orientation == 0) plane else StegoPlanes.rotated(plane, orientation)
            for (profile in StegoProfile.searched) {
                val band = bandOf(rotated, profile)
                // Skip this grid, not the whole orientation. The coarse band is an average of four
                // fine-band averages, so a flat coarse band says nothing about the fine one; only the
                // reverse implication holds, and it is the reason a smooth photo still has to be
                // probed on the quiet grid.
                if (!StegoTamper.statisticsOf(band).carriesDetail) continue
                for (side in LatticeSide.ordered) {
                    if (side.side > 2 * minOf(band.cellsWide, band.cellsHigh)) continue
                    val lattice = runCatching {
                        CanonicalLattice(side.side, band.cellsWide, band.cellsHigh)
                    }.getOrNull() ?: continue
                    tries += 1
                    val directory = probeDirectory(band, lattice, profile)
                    if (directory != null && directory.lattice == side) {
                        return Acquisition(rotated, band, lattice, directory, orientation, profile)
                    }
                    if (tries >= StegoLimits.MAX_ACQUISITION_TRIES) return null
                }
            }
        }
        return null
    }

    private fun probeDirectory(
        band: LlBand,
        lattice: CanonicalLattice,
        profile: StegoProfile = StegoProfile.CLASSIC
    ): StegoFrame.Directory? {
        val bitCount = StegoLimits.DIRECTORY_BITS
        val ring = lattice.ringSlots
        if (ring.size < bitCount) return null
        val mean = band.mean
        val spread = band.spread(mean)
        val bytes = ByteArray((bitCount + 7) / 8)
        for (bit in 0 until bitCount) {
            // [MarkWriter] writes bit `i` to every ring slot congruent to it, `ring.size / 56`
            // times over. Reading a single slot threw that repetition away, so one damaged ring
            // cell was enough to lose the CRC and report "nothing here" for a print that had in
            // fact survived. Sum the repeated chips, which is the soft vote the writer intended.
            var confidence = 0f
            var slot = bit
            while (slot < ring.size) {
                val measured = lattice.measure(band.values, ring[slot])
                confidence += Qim.demod((measured - mean) / spread, profile.step)
                slot += bitCount
            }
            if (confidence >= 0f) {
                bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            }
        }
        return StegoFrame.unpackDirectory(bytes)
    }

    private fun readPayload(acquisition: Acquisition): ByteArray? {
        val votes = MarkWriter.readConfidences(
            band = acquisition.band,
            lattice = acquisition.lattice,
            bitCount = acquisition.directory.frameBits,
            chipRate = acquisition.directory.chipRate,
            step = acquisition.profile.step,
            directory = false
        )
        val bits = IntArray(votes.size) { if (votes[it] >= 0f) 1 else 0 }
        val codeword = StegoBits.fromBits(bits, acquisition.directory.frameBits)
        return coder.decodeChunks(codeword)
    }

    private fun bandOf(plane: LumaPlane, profile: StegoProfile = StegoProfile.CLASSIC): LlBand {
        if (plane.width <= 0 || plane.height <= 0 || plane.samples.isEmpty()) {
            throw StegoInputException("There is no image to read here.")
        }
        return profile.bandOf(plane)
    }

    /**
     * A passphrase is not a key: it becomes one through PBKDF2 with the carrier's own salt, exactly
     * as the core's schedule defines. An envelope sealed *without* a passphrase uses a public domain
     * key derived from that same salt, which the screen describes honestly as obfuscation rather
     * than protection — anything holding this app can open it.
     */
    private fun keyFor(passphrase: CharArray?, salt: ByteArray): ByteArray =
        if (passphrase != null && passphrase.isNotEmpty()) {
            StegoKeySchedule.envelopeKey(deriver, passphrase, salt, iterations)
        } else {
            deriver.sha256(salt + UNSEALED_CONTEXT).copyOf(StegoLimits.KEY_BYTES)
        }

    companion object {
        /** Domain separation for a passphrase-free envelope; public, and deliberately so. */
        val UNSEALED_CONTEXT: ByteArray = "svp.stego.v1.unsealed".toByteArray(Charsets.UTF_8)

        /** The production codec: the vault's own derivation cost, 600 000 PBKDF2 rounds. */
        fun production(): StegoCodec = StegoCodec()
    }
}

/**
 * How sure the reader is, from the soft decisions it actually collected.
 *
 * A QIM chip's confidence is a signed distance to the comb, in steps, bounded by half a step, and
 * the chips of one bit are summed — so `2 * mean(|vote|) / chipRate` is the share of the available
 * margin the received carrier really delivered. 1.0 means every chip landed dead centre in its comb.
 * The second term is the share of bits that were decided at all rather than sitting on the fence; a
 * scratched print can hold a perfect average margin and still lose thirty bits to a coin toss, and
 * that is the number the owner needs to see before trusting a seed phrase.
 */
object StegoConfidence {

    fun of(votes: FloatArray, chipRate: Int): Double {
        if (votes.isEmpty() || chipRate <= 0) return 0.0
        var total = 0.0
        var solid = 0
        for (vote in votes) {
            val magnitude = kotlin.math.abs(vote)
            total += magnitude
            if (magnitude > 0.05f * chipRate) solid += 1
        }
        val margin = (2.0 * total / votes.size / chipRate).coerceIn(0.0, 1.0)
        val agreement = solid.toDouble() / votes.size
        return (margin * 0.6 + agreement * 0.4).coerceIn(0.0, 1.0)
    }

    /** Words for the same number, so three screens cannot describe one reading three ways. */
    fun label(confidence: Double): String = when {
        confidence >= 0.8 -> "Strong"
        confidence >= 0.55 -> "Readable"
        confidence >= 0.3 -> "Marginal"
        else -> "Barely held"
    }
}

/** Turns the core's own capacity exception into the sentence the screens show. */
object StegoCapacityReporting {
    fun refused(maxSecretBytes: Int, requestedSecretBytes: Int) =
        com.securevault.pro.crypto.stego.StegoCapacityException(
            "This carrier holds about $maxSecretBytes bytes per envelope; the payload is " +
                "$requestedSecretBytes bytes. Split it across three images, or hide a shorter secret.",
            maxSecretBytes,
            requestedSecretBytes
        )
}

/**
 * The cause a failed read almost always has.
 *
 * Every refusal below stays exactly as strict as it was — this is a message, not a gate. But "nothing
 * is hidden here" sent the owner back to the lens with a fresh copy of the same redrawn file, and the
 * redraw is what destroys a carrier made by an older build: a resize, an auto-enhance, or a picture
 * hidden before the current, tougher mark existed. Naming it is the difference between a bug report
 * and a read secret.
 */
private const val REDRAW_CAUSE =
    "A picture that was resized, edited, or hidden by an older version of this app can lose the " +
        "hidden text when a chat app redraws it on the way to you."

/** The remedy that goes with [REDRAW_CAUSE]: hide it again, then send the new picture as a photo. */
private const val REDRAW_FIX =
    "Hide the text again in this app and send that new picture as an ordinary photo — it is built to " +
        "survive the send."
