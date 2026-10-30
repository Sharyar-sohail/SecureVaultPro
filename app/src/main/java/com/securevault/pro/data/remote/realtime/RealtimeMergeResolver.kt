package com.securevault.pro.data.remote.realtime

import java.util.concurrent.TimeUnit

/**
 * The envelope-level merge report.
 *
 * [MergePolicy] holds the conflict rule itself (higher `u`, then tombstone, then higher `r`) and its
 * four `MergeOutcome` names are the vocabulary the web and the acceptance log share. This resolver
 * adds the two things a transport actually needs around that rule and nothing else:
 *
 * - **validation first** — a node that is not a well-formed `{e,u,r,d}` envelope is rejected before
 *   any comparison, so a corrupt or hostile remote write can never win a merge on clock values it
 *   did not legitimately carry;
 * - **the direction of the drop** — every losing remote write is reported with the deciding `u`/`r`
 *   pair, which is exactly what `SPEC-v1.md` §Conflict and `RECORD-FORMAT-v1.md` §4 rule 4 demand
 *   ("dropped and logged, never silently").
 *
 * Pure JVM: no clock reads, no storage, no network, no coroutines.
 */
object RealtimeMergeResolver {

    /** What the caller must now do locally. */
    enum class Action {
        /** Write the remote plaintext over the local record. */
        WRITE_REMOTE,

        /** The record must end up deleted locally. */
        DELETE_LOCAL,

        /** The remote lost; leave the local record untouched. */
        KEEP_LOCAL,

        /** Nothing to do — there was no remote node to apply. */
        NO_OP
    }

    /** The reported decision. [outcome] carries the shared web-facing name alongside it. */
    enum class Decision {

        /** A live remote node won and must be written locally. */
        APPLIED_REMOTE,

        /** A remote tombstone won: newer `u`, or equal `u` against a live local record. */
        APPLIED_TOMBSTONE,

        /** The remote `u` was strictly older, so the write is dropped — and reported. */
        KEPT_LOCAL_OLDER_REMOTE,

        /** Equal `u`, and local won the tie-break: a local tombstone, a local `r` that is not lower, or an identical node. */
        KEPT_LOCAL_TIE,

        /** There was no remote node at all — a cleared record or the first snapshot. */
        NO_REMOTE_NODE,

        /** The remote node is not a valid envelope; ignored and reported, never guessed at. */
        REJECTED_REMOTE_MALFORMED,

        /** The local node is the corrupt side; the remote is adopted to repair it. */
        REJECTED_LOCAL_MALFORMED
    }

    /** The decision, the action, and every value that settled it — the sync log line, ready to append. */
    data class Result(
        val decision: Decision,
        val outcome: MergeOutcome?,
        val action: Action,
        val localU: Long?,
        val localR: Long?,
        val remoteU: Long?,
        val remoteR: Long?,
        val localD: Long?,
        val remoteD: Long?,
        val reason: String
    ) {
        /** True when the local store has to change. */
        val applyRemote: Boolean get() = action == Action.WRITE_REMOTE || action == Action.DELETE_LOCAL

        /** True when a remote write was thrown away — the case that must reach the log. */
        val remoteDropped: Boolean get() =
            action == Action.KEEP_LOCAL || decision == Decision.REJECTED_REMOTE_MALFORMED

        /** Contains clocks and revisions only, never a plaintext or a key. */
        fun describe(): String = buildString {
            append(decision.name)
            append(" outcome=").append(outcome?.name ?: "-")
            append(" action=").append(action.name)
            append(" u=").append(localU).append('/').append(remoteU)
            append(" r=").append(localR).append('/').append(remoteR)
            append(" d=").append(localD).append('/').append(remoteD)
            append(" (").append(reason).append(')')
        }
    }

    /**
     * Same ceiling [RealtimeEnvelope.fromCloudMap] applies to `u` and `d`, restated here so a node
     * that was built in memory is judged by the same rule as one that came off the wire:
     * roughly a thousand years of milliseconds, which no real vault clock can reach.
     */
    private val MAX_CLOCK: Long = 10L * TimeUnit.DAYS.toMillis(36_500L)

    /**
     * Resolves the two nodes held for one record id; either may be absent.
     * [remote] null means the listener saw the node disappear rather than a tombstoned write.
     */
    fun resolve(local: RealtimeEnvelope?, remote: RealtimeEnvelope?): Result {
        if (remote == null) {
            return of(Decision.NO_REMOTE_NODE, null, Action.NO_OP, local, null, "no remote node")
        }
        if (!remote.isWellFormed()) return rejectRemote(local)
        if (local != null && !local.isWellFormed()) {
            val outcome = if (remote.d != null) MergeOutcome.APPLIED_TOMBSTONE else MergeOutcome.APPLIED_REMOTE
            return of(
                Decision.REJECTED_LOCAL_MALFORMED, outcome,
                if (remote.d != null) Action.DELETE_LOCAL else Action.WRITE_REMOTE,
                local, remote, "local node is corrupt; remote adopted to repair it"
            )
        }

        val incoming = remote.toMergeState()
        val held = local?.toMergeState()
        val decision = MergePolicy.decide(incoming, held)
        val mapped = when {
            decision.applyRemote ->
                if (decision.deleteWins && incoming.deleted) Decision.APPLIED_TOMBSTONE else Decision.APPLIED_REMOTE
            held != null && incoming.u < held.u -> Decision.KEPT_LOCAL_OLDER_REMOTE
            else -> Decision.KEPT_LOCAL_TIE
        }
        val action = when {
            !decision.applyRemote -> Action.KEEP_LOCAL
            incoming.deleted -> Action.DELETE_LOCAL
            else -> Action.WRITE_REMOTE
        }
        val reason = when (mapped) {
            Decision.APPLIED_REMOTE -> when {
                held == null -> "no local node; remote adopted"
                incoming.u == held?.u -> "equal u: tie broken by higher r"
                else -> "remote u is newer"
            }
            Decision.APPLIED_TOMBSTONE ->
                if (incoming.u == held?.u) "equal u: tombstone beats live" else "remote tombstone is newer"
            Decision.KEPT_LOCAL_OLDER_REMOTE -> "remote u is older; write dropped and reported"
            else -> "equal u: local won the tie (tombstone, higher r, or identical node)"
        }
        return of(mapped, decision.outcome, action, local, remote, reason)
    }

    /**
     * Resolves the raw maps a value listener hands back. A map that is present but is not a valid
     * node is a rejected remote write, not an absent one — the difference matters, because an
     * absent node means "nothing happened" while a malformed node means "someone wrote something
     * we must refuse", and only the second one belongs in the log.
     */
    fun resolveNodes(local: Map<*, *>?, remote: Map<*, *>?): Result {
        val held = RealtimeEnvelope.fromCloudMap(local)
        if (remote == null) return resolve(held, null)
        val incoming = RealtimeEnvelope.fromCloudMap(remote)
            ?: return rejectRemote(held)
        return resolve(held, incoming)
    }

    private fun rejectRemote(local: RealtimeEnvelope?) = of(
        Decision.REJECTED_REMOTE_MALFORMED, null,
        if (local != null) Action.KEEP_LOCAL else Action.NO_OP,
        local, null, "remote node is not a valid {e,u,r,d} envelope"
    )

    /** The state the shared rule reads: the clock, the revision, and whether the node is a tombstone. */
    fun RealtimeEnvelope.toMergeState(): MergeState = MergeState(u = u, r = r, deleted = d != null)

    /**
     * Whether an envelope can be trusted at all. The `e` field must look like `iv.ciphertext`
     * (the separator is the only structural promise the database can verify without a key), the
     * clock must be a sane non-negative epoch-ms value and no revision may be negative.
     */
    fun RealtimeEnvelope.isWellFormed(): Boolean {
        val sealed = e.trim()
        if (sealed.isBlank() || !sealed.contains('.')) return false
        if (u < 0L || u > MAX_CLOCK) return false
        if (r < 0L) return false
        val tomb = d ?: return true
        return tomb in 0L..MAX_CLOCK
    }

    private fun of(
        decision: Decision,
        outcome: MergeOutcome?,
        action: Action,
        local: RealtimeEnvelope?,
        remote: RealtimeEnvelope?,
        reason: String
    ) = Result(
        decision = decision,
        outcome = outcome,
        action = action,
        localU = local?.u,
        localR = local?.r,
        remoteU = remote?.u,
        remoteR = remote?.r,
        localD = local?.d,
        remoteD = remote?.d,
        reason = reason
    )
}
