package com.securevault.pro.presentation.screens.stego

/**
 * The read-once ledger: what "self-destruct after reading" is allowed to mean here.
 *
 * The owner asked for a copy that disappears once opened. The standing rule in this app is the
 * opposite of automatic deletion — nothing leaves because a timer, a sync message or a data field
 * decided so. The two are reconciled like this:
 *
 *  1. **Only copies this flow created are on the list.** A carrier is registered when the encode
 *     writes it, at the exact path or vault id the encode chose. A photo the owner already had, a
 *     file from the Files section, or a share staged by a different session is never on the list,
 *     and the ledger refuses to name it.
 *  2. **Opening is not deleting.** Reading the secret marks the copy as *due*; the removal is a
 *     question the owner answers, on screen, with the copies itemised. Decline and everything stays.
 *  3. **A refusal is reported, not swallowed.** If the owner asks to destroy the set and one of the
 *     three carriers was already exported somewhere else, the screen says which one it will not
 *     touch and why.
 *  4. **The list survives the process.** What is persisted is this metadata only — key, label,
 *     saved location, created-at, read flag — in the app's private `stego_ledger` preferences, and
 *     never pixels, payload text or keys. It is capped at [SelfDestructLedger.MAX_ENTRIES] so the
 *     store cannot grow without bound; nothing is dropped before that cap is reached.
 *
 * All of this is pure bookkeeping so the guard can be tested without a device: no `File`, no
 * repository, no Android anywhere in this file. [StegoLedgerCodec] is the wire form, and
 * `StegoLedgerStore` (a different file) is the only Android part.
 */
data class OwnedCopy(
    val key: String,
    val label: String,
    val path: String? = null,
    val vaultFileId: String? = null,
    /** When this app wrote the copy; 0 when an older record did not carry one. */
    val createdAt: Long = 0L
) {
    /** A copy is only real if it has somewhere to remove it from. */
    val removable: Boolean get() = !path.isNullOrBlank() || !vaultFileId.isNullOrBlank()
}

/** What the ledger decided about one requested removal. */
sealed class RemovalDecision {

    /** Registered, created by this flow, and the owner confirmed: safe to remove. */
    data class Cleared(val copies: List<OwnedCopy>) : RemovalDecision()

    /** Nothing was registered by this flow, so there is nothing this screen may delete. */
    object NothingOwned : RemovalDecision()

    /**
     * The request mixed owned and foreign copies. The owned half may proceed only after the owner
     * sees the refused half named in full — a silent "some of these" is exactly how data is lost.
     */
    data class Partial(val cleared: List<OwnedCopy>, val refused: List<OwnedCopy>) : RemovalDecision()
}

/**
 * An immutable ledger; every registration returns a new one so a recomposing composable can never
 * lose or duplicate an entry, and so a reveal session holds its own list.
 */
class SelfDestructLedger private constructor(
    private val entries: List<OwnedCopy>,
    private val markedDue: Set<String>
) {

    constructor() : this(emptyList(), emptySet())

    val copies: List<OwnedCopy> get() = entries

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Registers a copy this flow produced. Re-registering the same key replaces it, never doubles. */
    fun register(copy: OwnedCopy): SelfDestructLedger {
        require(copy.key.isNotBlank()) { "A ledger entry needs a key to refuse by" }
        val kept = entries.filterNot { it.key == copy.key }
        return SelfDestructLedger(kept + copy, markedDue).capped()
    }

    fun registerAll(copies: List<OwnedCopy>): SelfDestructLedger =
        copies.fold(this) { ledger, copy -> ledger.register(copy) }

    /** True when this exact copy came out of this flow. */
    fun owns(key: String): Boolean = entries.any { it.key == key }

    /** Called when the secret was actually shown, which is what "after reading" keys on. */
    fun markRead(key: String): SelfDestructLedger =
        if (entries.any { it.key == key }) SelfDestructLedger(entries, markedDue + key) else this

    /**
     * Records the copies whose file name is in [labels], the identity a saved copy keeps when it
     * comes back through the photo picker. Nothing else is matched: a photo the owner already had is
     * not on the ledger, so its name can never mark a copy as opened.
     *
     * Returns `this` when nothing changed, which is what lets the caller skip a pointless write.
     */
    fun markReadNamed(labels: Collection<String>): SelfDestructLedger {
        if (entries.isEmpty()) return this
        val names = labels.filter { it.isNotBlank() }.toSet()
        if (names.isEmpty()) return this
        val opened = entries.filter { it.label in names }.map { it.key }
        if (opened.isEmpty() || opened.all { it in markedDue }) return this
        return opened.fold(this) { ledger, key -> ledger.markRead(key) }
    }

    fun markAllRead(): SelfDestructLedger =
        SelfDestructLedger(entries, entries.map { it.key }.toSet())

    fun isDue(key: String): Boolean = key in markedDue

    /** Which keys were opened on this device; the read half of the persisted record. */
    internal val readKeys: Set<String> get() = markedDue

    /** Copies that were opened and are now waiting on the owner's answer. */
    val dueCopies: List<OwnedCopy> get() = entries.filter { it.key in markedDue }

    /**
     * The only removal the ledger will authorise. Anything it did not register comes back in
     * `refused`, named, and never in the cleared list.
     */
    fun decide(requestedKeys: List<String>): RemovalDecision {
        val wanted = requestedKeys.toSet()
        val cleared = entries.filter { it.key in wanted && it.removable }
        val refused = entries.filter { it.key in wanted && !it.removable } +
            wanted.filter { key -> entries.none { it.key == key } && cleared.none { it.key == key } }
                .map { key -> OwnedCopy(key, "not created by this session") }
        return when {
            cleared.isEmpty() && refused.isEmpty() -> RemovalDecision.NothingOwned
            cleared.isEmpty() -> RemovalDecision.NothingOwned
            refused.isEmpty() -> RemovalDecision.Cleared(cleared)
            else -> RemovalDecision.Partial(cleared, refused)
        }
    }

    /** Convenience for "destroy everything this flow wrote". */
    fun decideAll(): RemovalDecision = decide(entries.map { it.key })

    /** Wipes the ledger itself after a removal, so a stale entry cannot be replayed later. */
    fun forget(keys: List<String>): SelfDestructLedger {
        val drop = keys.toSet()
        return SelfDestructLedger(entries.filterNot { it.key in drop }, markedDue - drop)
    }

    /** Sentence for the confirmation dialog, listing exactly what will go. */
    fun describeDue(): String {
        val due = dueCopies
        if (due.isEmpty()) return "Nothing in this session is waiting to be removed."
        return due.joinToString(" · ") { it.label }
    }

    /**
     * The cap, applied on every registration and on every restore: the oldest entries fall off the
     * end, and a read flag whose copy is gone goes with it. Fifty is per ledger and metadata only.
     */
    private fun capped(): SelfDestructLedger {
        val withinCap = entries.size <= MAX_ENTRIES
        val readIsOwned = markedDue.all { key -> entries.any { it.key == key } }
        if (withinCap && readIsOwned) return this
        val trimmed = if (withinCap) entries else entries.takeLast(MAX_ENTRIES)
        val present = trimmed.mapTo(HashSet()) { it.key }
        return SelfDestructLedger(trimmed, markedDue.filterTo(HashSet()) { it in present })
    }

    companion object {
        /** How many saved copies one device remembers. Older entries are metadata, not files. */
        const val MAX_ENTRIES = 50

        /**
         * Rebuilds a ledger from persisted metadata. Duplicate keys keep their last record, and the
         * cap is applied again so a hand-edited or truncated file cannot grow the ledger.
         */
        fun restore(entries: List<OwnedCopy>, readKeys: Set<String>): SelfDestructLedger {
            val ordered = ArrayList<OwnedCopy>(minOf(entries.size, MAX_ENTRIES))
            for (entry in entries) {
                if (entry.key.isBlank()) continue
                ordered.removeAll { it.key == entry.key }
                ordered += entry
                // Trim as we go, so an oversized or hand-edited file stays linear to read.
                while (ordered.size > MAX_ENTRIES) ordered.removeAt(0)
            }
            return SelfDestructLedger(ordered, readKeys.filterTo(HashSet()) { it.isNotBlank() }).capped()
        }
    }
}

/**
 * The ledger's wire form: one header line, then one tab-separated record per copy.
 *
 * Fields are key, label, saved location, vault file id, created-at, read flag. `%`, tab and the two
 * line breaks are percent-escaped so a file name can never forge a record, and anything that does not
 * start with the version header decodes as an empty ledger rather than as half-parsed data. Pure
 * Kotlin on purpose: the round trip and the cap are tested on a bare JVM.
 */
internal object StegoLedgerCodec {

    private const val HEADER = "stego-ledger/1"
    private const val FIELD_COUNT = 6

    fun encode(ledger: SelfDestructLedger): String = buildString {
        append(HEADER)
        for (copy in ledger.copies) {
            append('\n')
            append(escape(copy.key)).append('\t')
            append(escape(copy.label)).append('\t')
            append(escape(copy.path.orEmpty())).append('\t')
            append(escape(copy.vaultFileId.orEmpty())).append('\t')
            append(copy.createdAt).append('\t')
            append(if (copy.key in ledger.readKeys) '1' else '0')
        }
    }

    fun decode(raw: String?): SelfDestructLedger {
        if (raw.isNullOrBlank()) return SelfDestructLedger()
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != HEADER) return SelfDestructLedger()
        val copies = ArrayList<OwnedCopy>()
        val read = HashSet<String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < FIELD_COUNT) continue
            val key = unescape(parts[0])
            if (key.isBlank()) continue
            copies += OwnedCopy(
                key = key,
                label = unescape(parts[1]),
                path = unescape(parts[2]).takeIf { it.isNotBlank() },
                vaultFileId = unescape(parts[3]).takeIf { it.isNotBlank() },
                createdAt = parts[4].toLongOrNull() ?: 0L
            )
            if (parts[5].trim() == "1") read += key
        }
        return SelfDestructLedger.restore(copies, read)
    }

    private fun escape(value: String): String {
        if (value.none { it == '%' || it == '\t' || it == '\n' || it == '\r' }) return value
        return buildString(value.length + 8) {
            for (ch in value) when (ch) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(ch)
            }
        }
    }

    private fun unescape(value: String): String {
        if ('%' !in value) return value
        return buildString(value.length) {
            var at = 0
            while (at < value.length) {
                val ch = value[at]
                if (ch == '%' && at + 2 < value.length) {
                    val code = value.substring(at + 1, at + 3).toIntOrNull(16)
                    if (code != null) {
                        append(code.toChar())
                        at += 3
                        continue
                    }
                }
                append(ch)
                at += 1
            }
        }
    }
}
