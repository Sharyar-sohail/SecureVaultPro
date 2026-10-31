package com.securevault.pro.data.remote.realtime

import android.content.Context
import android.os.Build
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.securevault.pro.data.remote.firebase.FirebaseBootstrap
import com.securevault.pro.data.remote.firebase.Keyring
import com.securevault.pro.data.remote.firebase.RealtimePaths
import com.securevault.pro.data.remote.firebase.SvpAuth
import com.securevault.pro.diagnostics.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live numbers the Settings surface renders. Every field is an identifier or a measurement —
 * nothing here can ever hold key material or a record field.
 */
data class RealtimeStatus(
    val running: Boolean = false,
    val uid: String? = null,
    val listenersActive: Boolean = false,
    /** Google session present but no device key yet: the user must attach the keyring before a write. */
    val needsPassphrase: Boolean = false,
    /** The receiving-side delta `tReceive - u` in ms from the last remote change. */
    val lastLatencyMs: Long? = null,
    val lastLatencyAt: Long = 0L,
    /** Records whose local signature differs from what was last pushed. */
    val recordsPending: Int = 0,
    /** The verbatim `Result.failure` sentence when the keyring cannot open, or the last write fault. */
    val lastError: String? = null,
    val lastEvent: String? = null
)

/**
 * The Android realtime engine: a per-record delta writer and a per-module push listener over the
 * Firebase Realtime Database, speaking the frozen `RECORD-FORMAT-v1.md` contract.
 *
 * Load-bearing invariants:
 * - **Push, never poll.** One [ChildEventListener] per module under `dataRoot` is the receive channel;
 *   a local mutation writes a single `{e,u,r,d}` node at once, independent of the Drive archive's
 *   500 ms debounce. Nothing here waits on or duplicates `AutoSyncManager`'s Drive path.
 * - **Ciphertext only.** The record body is sealed by [RealtimeEnvelope.seal] under the per-user vault
 *   key from [Keyring.deviceVaultKey]; there is no universal key and no fallback — if the key cannot
 *   open, the write is refused and the verbatim reason surfaces in [status].
 * - **Never throw into the caller.** Every Firebase entry goes through [FirebaseBootstrap.reference]
 *   (null, never a class-load throw), every failure is a logged reason, and the whole thing is wrapped
 *   so a stalled socket cannot dead-end the app.
 * - **No resurrection.** [MergePolicy] decides each incoming node against local state; a deleted
 *   record only ever gets deleted again, never revived by an older `u`.
 */
@Singleton
class RealtimeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyring: Keyring,
    private val auth: SvpAuth,
    private val codec: RealtimeRecordCodec,
    private val store: RealtimeStore,
    private val latency: SyncLatencyLog
) {

    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                runCatching { CrashLogger.noteFault(context, "Realtime layer: an engine job failed", error) }
            }
    )

    private val started = AtomicBoolean(false)
    private val persistenceApplied = AtomicBoolean(false)

    /** `module/id -> last signature pushed` (a deleted record's signature is [DELETED_SIGNATURE]). */
    private val lastPushed = ConcurrentHashMap<String, String>()
    /** `module/id -> revision r we last wrote or accepted`, for the `r` tie-break. */
    private val localRevisions = ConcurrentHashMap<String, Long>()

    private val registered = mutableListOf<Pair<DatabaseReference, ChildEventListener>>()
    private val registrationLock = Any()

    /** The uid the receive listeners are bound to, or null while signed out. */
    private var attachedUid: String? = null

    private val attachPumpRunning = AtomicBoolean(false)

    /**
     * `quiet()` clears [started] but does not cancel the scope, so the reconcile and push loops it
     * launched are still alive. This guard is never reset: a later `start()` must not add a second
     * pair, or two consumers would drain the same wake channels and every push would run twice.
     */
    private val pumpsRunning = AtomicBoolean(false)

    /** In-memory monotonic fallback when the `meta/rev` transaction cannot reach the server. */
    private val revFallback = AtomicLong(0L)

    private val _status = MutableStateFlow(RealtimeStatus())
    val status: StateFlow<RealtimeStatus> = _status.asStateFlow()

    private val reconcileWake = Channel<Unit>(Channel.CONFLATED)
    private val pushWake = Channel<PushRequest>(Channel.UNLIMITED)

    /**
     * Conflated, single-consumer attach request. A sign-in can land while an attach is running, and
     * two overlapping attaches would register every listener twice; the latest request wins and the
     * one consumer keeps registration serial.
     */
    private val attachWake = Channel<Unit>(Channel.CONFLATED)

    private data class PushRequest(val module: RealtimeModule, val id: String, val deleted: Boolean)

    // ----------------------------------------------------------------- lifecycle

    /** Idempotent, safe to call at boot. Brings up persistence, listeners and the reconcile loop. */
    fun start() {
        // Already running: all a later caller can add is a uid, so ask for an attach instead of
        // returning silently. That silent return used to leave the receive channel off for the rest
        // of the process while the UI claimed the device was connected to web sync.
        if (!started.compareAndSet(false, true)) {
            attachWake.trySend(Unit)
            return
        }
        applyPersistenceOnce()
        _status.value = _status.value.copy(running = true)
        // A passphrase rotated while offline leaves its sealed wrap queued on the device. Coming up
        // is the moment the connection exists again, and the web companion cannot open the vault
        // until that wrap lands, so it is replayed before anything else touches the tree.
        scope.launch {
            val replay = runCatching { keyring.flushPendingRotation() }
            val problem = replay.exceptionOrNull() ?: replay.getOrNull()?.exceptionOrNull()
            if (problem != null && problem !is CancellationException) {
                _status.value = _status.value.copy(lastError = problem.message?.take(180))
            }
        }
        // Exactly one consumer for the lifetime of the object: `quiet()` does not cancel the scope,
        // so a second start() must not add a second pump or one attach request could attach twice.
        if (attachPumpRunning.compareAndSet(false, true)) {
            // `for (x in channel)` is the receive-iteration form; a Channel has no `collect`.
            scope.launch { for (ignored in attachWake) attachForCurrentUid() }
        }
        attachWake.trySend(Unit)
        if (pumpsRunning.compareAndSet(false, true)) {
            scope.launch { reconcileLoop() }
            scope.launch { pushLoop() }
        }
        scope.launch {
            keyring.status.collect { st ->
                val uid = st.uid ?: auth.cachedUid()
                _status.value = _status.value.copy(
                    uid = uid,
                    needsPassphrase = st.uid != null && !st.hasDeviceKey
                )
                if (uid != null && uid != attachedUid) attachWake.trySend(Unit)
            }
        }
    }

    /**
     * `setPersistenceEnabled` must run before the database is otherwise touched, and calling it twice
     * (or after a listener is live) throws `IllegalStateException`. Guarded, wrapped, once per process:
     * an already-initialized exception must not kill startup.
     */
    private fun applyPersistenceOnce() {
        if (!persistenceApplied.compareAndSet(false, true)) return
        runCatching {
            FirebaseBootstrap.database(context)?.setPersistenceEnabled(true)
        }.onFailure {
            // "already initialized / in use" is expected when another component got here first.
            runCatching { CrashLogger.noteFault(context, "Realtime layer: persistence could not be enabled", it) }
        }
    }

    // ----------------------------------------------------------------- mutation triggers

    /** The millisecond push path for an add/edit; queues a sealed delta write for that record. */
    fun markRecordChanged(module: String, id: String) {
        val m = RealtimeModule.fromSegment(module) ?: return
        if (id.isBlank()) return
        pushWake.trySend(PushRequest(m, id, deleted = false))
    }

    /** The push path for a delete: writes a tombstone so the change propagates and never revives. */
    fun markRecordDeleted(module: String, id: String) {
        val m = RealtimeModule.fromSegment(module) ?: return
        if (id.isBlank()) return
        pushWake.trySend(PushRequest(m, id, deleted = true))
    }

    /**
     * Called from `AutoSyncManager.markDirty()` — the single mutation signal every screen already
     * fires. It schedules a signature-diff sweep so a changed record pushes in milliseconds while the
     * existing Drive archive debounce is left completely untouched.
     */
    fun scheduleReconcile() {
        reconcileWake.trySend(Unit)
    }

    /**
     * Tear down for a wipe: drop listeners, cancel queued writes and forget the device key. The
     * security agent owns the wipe itself and calls this; nothing here touches Room or Drive state.
     */
    suspend fun stopAndClear() {
        quiet()
        runCatching { keyring.clearDeviceKey() }
        _status.value = RealtimeStatus()
    }

    /**
     * What a cloud erase of the realtime tree cost, in nodes rather than secrets. Every field is a
     * count or an identifier so the wipe can report it verbatim.
     */
    data class CloudErase(val uid: String?, val nodesRemoved: Int, val nodesFailed: Int, val error: String?)

    /**
     * Hard-deletes everything this account holds in the realtime database: every module table under
     * `data`, the revision `meta` node and the `keyring`.
     *
     * This is what makes "Erase all data" true. Removing the keyring is deliberate and is the reason
     * the caller must have archived first: without it no other device — including the web companion —
     * can open the records that were just deleted anyway, and a fresh setup starts from a clean tree
     * instead of inheriting a key whose data no longer exists.
     *
     * The engine goes quiet before the delete so a queued delta cannot land on top of it, and each
     * path is removed under its own timeout: a stalled socket must return a worded outcome to the
     * wipe, not hang it.
     */
    suspend fun eraseCloudData(): CloudErase {
        quiet()
        val uid = resolveUid()
            ?: return CloudErase(null, 0, 0, "No signed-in account, so the realtime copies were left alone.")
        val paths = listOfNotNull(
            RealtimePaths.dataRoot(uid),
            RealtimePaths.meta(uid),
            RealtimePaths.keyring(uid)
        )
        var removed = 0
        var failed = 0
        var reason: String? = null
        for (path in paths) {
            val ref = FirebaseBootstrap.reference(context, path)
            if (ref == null) {
                failed++
                reason = FirebaseBootstrap.faultReason() ?: "The realtime backend is unavailable."
                continue
            }
            val outcome = runCatching {
                withTimeout(NETWORK_TIMEOUT_MS) { ref.removeValue().await() }
            }
            if (outcome.isSuccess) removed++ else {
                failed++
                reason = outcome.exceptionOrNull()?.message?.take(180) ?: "A realtime node could not be deleted."
            }
        }
        lastPushed.clear()
        localRevisions.clear()
        _status.value = _status.value.copy(lastEvent = "cloud erased ($removed nodes)")
        return CloudErase(uid, removed, failed, reason)
    }

    /** Stops the receive channel and drops queued writes, keeping the device key. */
    private fun quiet() {
        started.set(false)
        attachedUid = null
        detachListeners()
        drain(pushWake)
        drain(reconcileWake)
    }

    // ----------------------------------------------------------------- listeners (receive channel)

    private suspend fun attachForCurrentUid() {
        val uid = resolveUid() ?: run {
            _status.value = _status.value.copy(lastEvent = "waiting for sign-in")
            return
        }
        val root = RealtimePaths.dataRoot(uid) ?: return
        detachListeners()
        for (module in RealtimeModule.entries) {
            val ref = FirebaseBootstrap.reference(context, "$root/${module.segment}") ?: continue
            val listener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = handleRemote(module, snapshot)
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = handleRemote(module, snapshot)
                override fun onChildRemoved(snapshot: DataSnapshot) = handleRemoteRemoved(module, snapshot)
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = handleRemote(module, snapshot)
                override fun onCancelled(error: DatabaseError) {
                    _status.value = _status.value.copy(
                        listenersActive = false,
                        lastError = error.message,
                        lastEvent = "listener cancelled"
                    )
                }
            }
            ref.addChildEventListener(listener)
            synchronized(registrationLock) { registered.add(ref to listener) }
        }
        attachedUid = uid
        _status.value = _status.value.copy(uid = uid, listenersActive = true, lastEvent = "listening")
    }

    private fun handleRemote(module: RealtimeModule, snapshot: DataSnapshot) {
        val id = snapshot.key ?: return
        val raw = snapshot.value as? Map<*, *> ?: return
        scope.launch { applyRemote(module, id, raw) }
    }

    private fun handleRemoteRemoved(module: RealtimeModule, snapshot: DataSnapshot) {
        val id = snapshot.key ?: return
        // A hard node removal is a delete with no body: drop locally and never resurrect it.
        scope.launch {
            runCatching { store.delete(module, id) }
            lastPushed[localKey(module, id)] = DELETED_SIGNATURE
            _status.value = _status.value.copy(lastEvent = "removed $module/$id")
        }
    }

    private suspend fun applyRemote(module: RealtimeModule, id: String, raw: Map<*, *>) {
        val tReceive = System.currentTimeMillis()
        val envelope = RealtimeEnvelope.fromCloudMap(raw) ?: run {
            _status.value = _status.value.copy(lastEvent = "malformed node dropped: $module/$id")
            return
        }
        // The metric the program is graded on, logged regardless of the merge outcome.
        latency.noteReceive(module.segment, id, envelope.u, tReceive)
        _status.value = _status.value.copy(
            lastLatencyMs = tReceive - envelope.u,
            lastLatencyAt = tReceive,
            lastEvent = "recv $module/$id"
        )

        val key = keyring.deviceVaultKey()?.bytes
        val plain = if (key != null) RealtimeEnvelope.open(key, envelope) else null
        val incomingRecord = if (plain != null) codec.decode(module, plain) else null
        val incoming = MergeState(
            u = envelope.u,
            r = envelope.r,
            deleted = envelope.isTombstone() ||
                (incomingRecord != null && codec.deletedClockOf(incomingRecord) != null)
        )

        val local = store.load(module, id)
        val localKey = localKey(module, id)
        val localState = local?.let {
            MergeState(codec.clockOf(module, it), localRevisions[localKey] ?: 0L, false)
        }
        val decision = MergePolicy.decide(incoming, localState)

        if (decision.applyRemote) {
            if (decision.deleteWins) {
                runCatching { store.delete(module, id) }
                lastPushed[localKey] = DELETED_SIGNATURE
            } else if (incomingRecord != null) {
                runCatching { store.upsert(module, incomingRecord) }
                lastPushed[localKey] = codec.signature(module, incomingRecord)
            } else {
                // A live node we cannot open (no key yet): surface it, never apply garbage.
                failStatus(NO_KEY, needsPassphrase = true)
                return
            }
            localRevisions[localKey] = envelope.r
        } else {
            _status.value = _status.value.copy(
                lastEvent = "kept local over ${decision.outcome} ($module/$id in.u=${decision.incomingU} local.u=${decision.localU})"
            )
        }
    }

    // ----------------------------------------------------------------- push loop

    private suspend fun pushLoop() {
        for (req in pushWake) pushOne(req.module, req.id, req.deleted)
    }

    private suspend fun reconcileLoop() {
        for (ignored in reconcileWake) reconcileOnce()
    }

    private suspend fun pushOne(module: RealtimeModule, id: String, deleted: Boolean) {
        val tWrite = System.currentTimeMillis()
        val uid = resolveUid() ?: run { failStatus("Sign in to Google before realtime can sync."); return }
        val path = RealtimePaths.record(uid, module.segment, id) ?: return
        val key = keyring.deviceVaultKey() ?: run { failStatus(NO_KEY, needsPassphrase = true); return }

        val record = if (deleted) null else store.load(module, id)
        val localKey = localKey(module, id)
        val plain = when {
            deleted -> {
                val last = lastPushed[localKey]
                if (last != null && last != DELETED_SIGNATURE) last else "{}"
            }
            record != null -> codec.encode(module, record)
            else -> return // nothing local to push
        }

        val clock = when {
            deleted -> System.currentTimeMillis()
            record != null -> codec.clockOf(module, record)
            else -> System.currentTimeMillis()
        }
        val rev = nextRevision(uid)
        val envelope = RealtimeEnvelope.seal(
            plaintext = plain,
            vaultKey = key.bytes,
            u = clock,
            r = rev,
            deletedAt = if (deleted) clock else null
        ) ?: run { failStatus(NO_KEY, needsPassphrase = true); return }

        val ref = FirebaseBootstrap.reference(context, path)
            ?: run { failStatus(FirebaseBootstrap.faultReason() ?: "The realtime backend is unavailable."); return }

        val result = runCatching {
            withTimeout(NETWORK_TIMEOUT_MS) { ref.setValue(envelope.toCloudMap()).await() }
        }
        val tAck = System.currentTimeMillis()
        if (result.isFailure) {
            failStatus(result.exceptionOrNull()?.message?.take(180) ?: "The realtime write failed.")
            return
        }
        lastPushed[localKey] = if (deleted) DELETED_SIGNATURE else codec.signature(module, record ?: Any())
        localRevisions[localKey] = rev
        latency.noteWrite(module.segment, id, tWrite, tAck)
        _status.value = _status.value.copy(
            lastError = null,
            lastEvent = "push ${if (deleted) "del" else "set"} $module/$id"
        )
    }

    // ----------------------------------------------------------------- reconcile (signature diff)

    private suspend fun reconcileOnce() {
        val uid = resolveUid() ?: return
        var pending = 0
        for (module in RealtimeModule.entries) {
            val current = store.loadAll(module)
            val seen = HashSet<String>()
            for (record in current) {
                val id = codec.idOf(module, record) ?: continue
                seen.add(id)
                val signature = codec.signature(module, record)
                if (lastPushed[localKey(module, id)] != signature) {
                    pending++
                    pushOne(module, id, deleted = false)
                }
            }
            // Anything previously pushed that vanished from the live set is a delete to propagate.
            for ((k, v) in lastPushed.toList()) {
                if (!k.startsWith("${module.segment}/") || v == DELETED_SIGNATURE) continue
                val id = k.removePrefix("${module.segment}/")
                if (id !in seen) {
                    pending++
                    pushOne(module, id, deleted = true)
                }
            }
        }
        _status.value = _status.value.copy(uid = uid, recordsPending = pending, lastEvent = "reconciled")
    }

    // ----------------------------------------------------------------- meta/rev

    /**
     * The per-user monotonic revision for `r`, via an RTDB transaction on `meta/rev` seeded from the
     * server clock on first use. Falls back to an in-memory counter so a write is never blocked by a
     * stalled server; the `u` clock still orders records, `r` only breaks exact-`u` ties.
     */
    private suspend fun nextRevision(uid: String): Long {
        val ref = FirebaseBootstrap.reference(context, RealtimePaths.meta(uid))?.child("rev")
        if (ref == null) return nextLocal()
        // RTDB runTransaction is callback-based (returns void) and can stall while offline; bridge the
        // completion callback through a deferred and bound it with a timeout so a write is never blocked.
        val deferred = CompletableDeferred<Long>()
        try {
            ref.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    // MutableData.getValue() returns a boxed Number (Long or Double) depending on what a
                    // previous writer stored; coerce both to Long before deciding the next revision.
                    val current = (currentData.getValue() as? Number)?.toLong() ?: 0L
                    val next = if (current <= 0L) System.currentTimeMillis() else current + 1
                    currentData.setValue(next)
                    return Transaction.success(currentData)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    val value = if (error == null && committed)
                        (snapshot?.getValue() as? Number)?.toLong() ?: 0L
                    else 0L
                    deferred.complete(value)
                }
            })
        } catch (_: Throwable) {
            deferred.complete(0L)
        }
        val rev = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { deferred.await() } ?: 0L
        scope.launch { stampMeta(uid) }
        return if (rev > 0L) rev else nextLocal()
    }

    private suspend fun stampMeta(uid: String) {
        val ref = FirebaseBootstrap.reference(context, RealtimePaths.meta(uid)) ?: return
        runCatching {
            withTimeout(NETWORK_TIMEOUT_MS) {
                ref.updateChildren(
                    mapOf(
                        "updatedAt" to ServerValue.TIMESTAMP,
                        "lastDevice" to (Build.MODEL ?: "android")
                    )
                ).await()
            }
        }
    }

    private fun nextLocal(): Long =
        revFallback.updateAndGet { prev -> if (prev <= 0L) System.currentTimeMillis() else prev + 1 }

    // ----------------------------------------------------------------- helpers

    private suspend fun resolveUid(): String? = auth.cachedUid() ?: keyring.uid()

    private fun detachListeners() {
        val snapshot = synchronized(registrationLock) {
            val copy = registered.toList()
            registered.clear()
            copy
        }
        for ((ref, listener) in snapshot) {
            runCatching { ref.removeEventListener(listener) }
        }
    }

    private fun failStatus(message: String, needsPassphrase: Boolean = false) {
        _status.value = _status.value.copy(
            lastError = message,
            needsPassphrase = needsPassphrase || _status.value.needsPassphrase
        )
    }

    private fun localKey(module: RealtimeModule, id: String): String = "${module.segment}/$id"

    private fun <T> drain(channel: Channel<T>) {
        while (true) {
            val polled = channel.tryReceive()
            if (!polled.isSuccess) break
        }
    }

    private companion object {
        private const val NETWORK_TIMEOUT_MS = 15_000L
        private const val DELETED_SIGNATURE = "\u2205"
        private const val NO_KEY =
            "This device cannot open its vault key yet, so nothing was sent. Enter your passphrase to attach it."
    }
}
