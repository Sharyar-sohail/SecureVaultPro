package com.securevault.pro.data.remote.realtime

/**
 * What one side of a merge knows about a record. Deliberately a bare value (no entity, no Firebase):
 * the only facts the rule uses are the LWW clock `u`, the revision `r` and whether it is a tombstone.
 */
data class MergeState(
    val u: Long,
    val r: Long,
    val deleted: Boolean
)

/** The four outcomes the acceptance log and the web both speak. */
enum class MergeOutcome {
    /** The remote record is newer and live; write it over the local copy. */
    APPLIED_REMOTE,

    /** The remote record is a delete that wins; remove the local copy and keep a tombstone. */
    APPLIED_TOMBSTONE,

    /** The local copy already wins (newer, or a local tombstone the remote tried to revive); keep it. */
    KEPT_LOCAL_NEWER,

    /** The remote is strictly older on `u`; drop it. This is what stops a deleted record resurrecting. */
    REJECTED_OLDER
}

/** The decision plus both sides' clocks, so a rejection can be logged with the values that lost (§4). */
data class MergeDecision(
    val outcome: MergeOutcome,
    val incomingU: Long,
    val incomingR: Long,
    val localU: Long?,
    val localR: Long?,
    /** True when the winner of this comparison is a tombstone — the record must end up deleted. */
    val deleteWins: Boolean
) {
    /** Remote work was chosen: the caller should write / delete locally. */
    val applyRemote: Boolean
        get() = outcome == MergeOutcome.APPLIED_REMOTE || outcome == MergeOutcome.APPLIED_TOMBSTONE
}

/**
 * Per-record last-write-wins, exactly `RECORD-FORMAT-v1.md` §4 and identical to the web's rule.
 *
 * 1. higher `u` wins;
 * 2. equal `u` → higher `r` wins;
 * 3. equal `u` and one side is a tombstone → **the tombstone wins** (delete beats save);
 * 4. whatever does not win is dropped and reported with both `u`/`r` values, never silently;
 * 5. an older `u` never overwrites a newer one — the historical delete-resurrection defect dies here.
 *
 * Pure, no Android, no database — a deleted record cannot come back because of a bug in this table,
 * so it is pinned by unit tests instead of by a phone.
 */
object MergePolicy {

    /**
     * Decide what to do with [incoming] against the [local] copy (null when nothing is held yet).
     * The returned outcome names the winning side and the clocks that settled it.
     */
    fun decide(incoming: MergeState, local: MergeState?): MergeDecision {
        if (local == null) {
            return MergeDecision(
                outcome = if (incoming.deleted) MergeOutcome.APPLIED_TOMBSTONE else MergeOutcome.APPLIED_REMOTE,
                incomingU = incoming.u,
                incomingR = incoming.r,
                localU = null,
                localR = null,
                deleteWins = incoming.deleted
            )
        }

        // 1. Higher u wins outright — an older remote is dropped, never applied over a newer local.
        when {
            incoming.u > local.u -> return accept(incoming, local)
            incoming.u < local.u -> return reject(incoming, local)
        }

        // 2 & 3. Equal u: a tombstone on either side beats a live record; delete always wins a tie.
        if (incoming.deleted && !local.deleted) return accept(incoming, local)
        if (local.deleted && !incoming.deleted) {
            return MergeDecision(
                outcome = MergeOutcome.KEPT_LOCAL_NEWER,
                incomingU = incoming.u,
                incomingR = incoming.r,
                localU = local.u,
                localR = local.r,
                deleteWins = true
            )
        }

        // 4. Same clock, same liveness: the higher revision wins, and a tie keeps local (deterministic).
        return if (incoming.r > local.r) accept(incoming, local) else reject(incoming, local)
    }

    private fun accept(incoming: MergeState, local: MergeState): MergeDecision = MergeDecision(
        outcome = if (incoming.deleted) MergeOutcome.APPLIED_TOMBSTONE else MergeOutcome.APPLIED_REMOTE,
        incomingU = incoming.u,
        incomingR = incoming.r,
        localU = local.u,
        localR = local.r,
        deleteWins = incoming.deleted
    )

    private fun reject(incoming: MergeState, local: MergeState): MergeDecision {
        // Losing against a local tombstone still means the record stays deleted.
        val keptIsTombstone = local.deleted
        val outcome = if (incoming.u < local.u) MergeOutcome.REJECTED_OLDER else MergeOutcome.KEPT_LOCAL_NEWER
        return MergeDecision(
            outcome = outcome,
            incomingU = incoming.u,
            incomingR = incoming.r,
            localU = local.u,
            localR = local.r,
            deleteWins = keptIsTombstone
        )
    }
}
