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
 *
 * All of this is pure bookkeeping so the guard can be tested without a device: no `File`, no
 * repository, no Android anywhere in this file.
 */
data class OwnedCopy(
    val key: String,
    val label: String,
    val path: String? = null,
    val vaultFileId: String? = null
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
        return SelfDestructLedger(kept + copy, markedDue)
    }

    fun registerAll(copies: List<OwnedCopy>): SelfDestructLedger =
        copies.fold(this) { ledger, copy -> ledger.register(copy) }

    /** True when this exact copy came out of this flow. */
    fun owns(key: String): Boolean = entries.any { it.key == key }

    /** Called when the secret was actually shown, which is what "after reading" keys on. */
    fun markRead(key: String): SelfDestructLedger =
        if (entries.any { it.key == key }) SelfDestructLedger(entries, markedDue + key) else this

    fun markAllRead(): SelfDestructLedger =
        SelfDestructLedger(entries, entries.map { it.key }.toSet())

    fun isDue(key: String): Boolean = key in markedDue

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
}
