package com.securevault.pro.data.remote.firebase

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.security.CryptoManager
import com.securevault.pro.security.KeyDerivation
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the keyring can say about itself without revealing anything.
 *
 * [uid] is an identifier and [publishedAt] is a timestamp; both are safe in a log. There is no
 * field for key material and there must never be one.
 */
data class KeyringStatus(
    val uid: String? = null,
    val hasDeviceKey: Boolean = false,
    val publishedAt: Long = 0L,
    val note: String? = null
)

/**
 * The per-user vault key: the one secret both frontends share, and the reason they can share it
 * without a universal key.
 *
 * Two copies exist and they are not the same thing:
 * - the device copy is the raw 256-bit key, wrapped by this handset's non-exportable Keystore key
 *   with [CryptoManager.wrapWithKeystore] and kept in private preferences. It needs no passphrase,
 *   which is what lets a background sync seal a record at any moment.
 * - the cloud copy at `svp1/users/$uid/keyring` is the same key wrapped with
 *   PBKDF2(user passphrase, random 32-byte salt, 600,000) — see [KeyringEnvelope]. Anyone holding
 *   the passphrase (a second phone, the Blogger page) unwraps it; anyone without it learns nothing,
 *   because AES-GCM authenticates before it releases plaintext.
 *
 * Failures are returned, never thrown, and a key that cannot be read is never silently replaced:
 * the cloud is authoritative, so a damaged local copy means "unlock with your passphrase", not
 * "generate a new key and orphan every byte already synced".
 */
@Singleton
class Keyring @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager,
    private val derivation: KeyDerivation,
    private val auth: SvpAuth,
    private val sessionManager: SessionManager
) {

    companion object {
        private const val PREFS = "svp_keyring_prefs"
        private const val KEY_VAULT_WRAPPED = "vault_key_wrapped_v1"
        private const val KEY_BOUND_UID = "bound_uid"
        private const val KEY_PUBLISHED_AT = "keyring_published_at"

        /**
         * A rotation whose cloud write did not land, kept as the finished envelope — ciphertext,
         * salt, iteration count and timestamp. No passphrase and no key ever reaches these keys; the
         * wrap is only ever replayed after it has been *opened* and matched against this device's
         * vault key, so a stale or foreign queue entry cannot rewrite anything.
         */
        private const val KEY_PENDING_WRAP = "keyring_pending_wrap"
        private const val KEY_PENDING_SALT = "keyring_pending_salt"
        private const val KEY_PENDING_ITERS = "keyring_pending_iters"
        private const val KEY_PENDING_AT = "keyring_pending_at"
        private const val KEY_PENDING_UID = "keyring_pending_uid"

        /** A cold handset on mobile data needs room; longer than this and the caller moves on. */
        private const val NETWORK_TIMEOUT_MS = 30_000L

        private const val WRONG_PASSPHRASE =
            "That passphrase does not open this account's vault key. Re-enter it, or restore from a backup."

        private const val NO_DEVICE_KEY =
            "This device cannot hold a vault key right now. Unlock with your passphrase to fetch it again."
    }

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val lock = Any()

    @Volatile
    private var cachedKey: VaultKey? = null

    private val _status = MutableStateFlow(KeyringStatus())
    val status: StateFlow<KeyringStatus> = _status.asStateFlow()

    /** Fire-and-forget work: a passphrase arriving on the lock screen must never block it. */
    private val backgroundScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                runCatching {
                    CrashLogger.noteFault(context, "Realtime layer: the vault key could not be published", error)
                }
            }
    )

    // ----------------------------- device copy -----------------------------

    /**
     * The vault key sealed under this device's Keystore key, created on first use.
     *
     * Null means this device cannot hold a vault key (Keystore unavailable, or the stored copy no
     * longer unwraps). Callers must then fail the operation in plain words: inventing a replacement
     * key here would strand every record already synced under the real one.
     */
    fun deviceVaultKey(): VaultKey? {
        cachedKey?.let { return it }
        return synchronized(lock) {
            cachedKey?.let { return it }
            val stored = prefs.getString(KEY_VAULT_WRAPPED, null)
            if (!stored.isNullOrBlank()) {
                val raw = crypto.unwrapWithKeystore(stored)
                if (raw != null && raw.size == KeyringCodec.KEY_BYTES) {
                    return VaultKey(raw).also { cachedKey = it }.also { publishStatus() }
                }
                _status.value = _status.value.copy(hasDeviceKey = false, note = NO_DEVICE_KEY)
                return null
            }
            val fresh = KeyringCodec.generateVaultKey()
            val wrapped = crypto.wrapWithKeystore(fresh)
            if (wrapped.isNullOrBlank()) {
                fresh.fill(0)
                _status.value = _status.value.copy(hasDeviceKey = false, note = NO_DEVICE_KEY)
                return null
            }
            val saved = runCatching {
                prefs.edit().putString(KEY_VAULT_WRAPPED, wrapped).commit()
            }.getOrDefault(false)
            // [fresh] is the key itself: VaultKey takes ownership of the buffer and [destroy]
            // zeroes it, so it is never copied back out of the wrapper here.
            cachedKey = VaultKey(fresh)
            publishStatus()
            if (!saved) {
                _status.value = _status.value.copy(
                    note = "The vault key is in memory but could not be saved; this session will not survive a restart."
                )
            }
            cachedKey
        }
    }

    /**
     * The string the existing envelope format seals a vault body with: the vault key, base64.
     *
     * `CloudSyncManager` and the Blogger page both run `PBKDF2(this, per-file salt, 600000)` to get
     * the file key, so a Drive backup sealed here opens on the web as soon as the browser can
     * unwrap the keyring — and no shared literal is involved.
     */
    fun sealingSecret(): String? = deviceVaultKey()?.let { key ->
        runCatching { KeyringCodec.encodeBase64(key.bytes) }.getOrNull()
    }

    /** True when this device already holds a usable copy, with no Keystore round trip. */
    fun hasDeviceKey(): Boolean = cachedKey != null || !prefs.getString(KEY_VAULT_WRAPPED, null).isNullOrBlank()

    /** Stores [material] as this device's copy and zeroes the caller's buffer. */
    private fun adoptDeviceKey(material: ByteArray): Boolean {
        if (material.size != KeyringCodec.KEY_BYTES) {
            material.fill(0)
            return false
        }
        val wrapped = crypto.wrapWithKeystore(material)
        if (wrapped.isNullOrBlank()) {
            material.fill(0)
            return false
        }
        val saved = runCatching {
            prefs.edit().putString(KEY_VAULT_WRAPPED, wrapped).commit()
        }.getOrDefault(false)
        val previous = cachedKey
        cachedKey = VaultKey(material.copyOf())
        previous?.destroy()
        material.fill(0)
        publishStatus()
        return saved
    }

    // ----------------------------- cloud copy -----------------------------

    /** The uid this vault key belongs to; resolves a Firebase session when none is cached yet. */
    suspend fun uid(forceRefresh: Boolean = false): String? =
        if (forceRefresh) auth.uid(true) else (auth.cachedUid() ?: auth.uid())

    /** Where the cloud copy lives. Shown in diagnostics: a path, never a secret. */
    fun keyringPath(): String? = auth.cachedUid()?.let { RealtimePaths.keyring(it) }

    /** Reads `svp1/users/$uid/keyring`. A missing node is a success carrying null, not an error. */
    suspend fun readKeyring(owner: String? = null): Result<KeyringEnvelope?> {
        val uid = owner ?: run { uid() }
            ?: return Result.failure(SvpAuthException("Sign in to Google before the vault key can be read."))
        val path = RealtimePaths.keyring(uid)
            ?: return Result.failure(SvpAuthException("The account id is not usable as a storage path."))
        val ref = FirebaseBootstrap.reference(context, path)
            ?: return Result.failure(
                SvpAuthException(FirebaseBootstrap.faultReason() ?: "The realtime backend is unavailable.")
            )
        return try {
            val snapshot = withContext(Dispatchers.IO) {
                withTimeout(NETWORK_TIMEOUT_MS) { ref.get().await() }
            }
            Result.success(snapshot.toKeyringEnvelope())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Result.failure(SvpAuthException("Reading the vault key timed out. Retry when you have signal."))
        } catch (e: Throwable) {
            Result.failure(SvpAuthException(readable(e)))
        }
    }

    /**
     * Makes the cloud copy of the vault key for [passphrase], or confirms the existing one.
     *
     * A write only happens when the node is empty. If it already holds a keyring this passphrase
     * opens, the cloud copy wins and this device adopts it, because overwriting a live keyring
     * would lock the owner out of every record already synced. A passphrase that does not open it
     * is a hard, worded failure — never a partial success and never a silent wrong key.
     */
    suspend fun publishKeyring(passphrase: String): Result<Unit> {
        if (passphrase.isBlank()) return Result.failure(SvpAuthException("A passphrase is required."))
        if (sessionManager.isDecoy()) return Result.failure(
            SvpAuthException("The vault key is not shared from a decoy session.")
        )
        val uid = uid()
            ?: return Result.failure(
                SvpAuthException("Sign in to Google to connect this vault to the web companion.")
            )
        val path = RealtimePaths.keyring(uid)
            ?: return Result.failure(SvpAuthException("The account id is not usable as a storage path."))
        val key = deviceVaultKey()
            ?: return Result.failure(SvpAuthException(_status.value.note ?: NO_DEVICE_KEY))

        val existing = readKeyring(uid)
        if (existing.isFailure) {
            val reason = existing.exceptionOrNull()?.message ?: "The vault key could not be checked."
            return Result.failure(SvpAuthException(reason))
        }
        val current = existing.getOrNull()
        if (current != null) {
            val opened = withContext(Dispatchers.Default) {
                KeyringProtocol.open(current, passphrase, derivation)
            }
            if (opened == null) {
                // The node does not open with a passphrase that just verified on this device, which
                // is the signature of a rotation whose write never landed. The queued envelope is
                // only a repair if it opens with this same passphrase and holds this device's key —
                // otherwise this stays a hard "wrong passphrase", never a silent overwrite.
                val queued = pendingRotation()
                if (queued != null && pendingOwner() == uid &&
                    pendingOpensWith(queued, passphrase, key.bytes)
                ) {
                    return writeEnvelope(path, queued).also { written ->
                        if (written.isSuccess) {
                            clearPendingRotation()
                            markPublished(uid, queued.updatedAt)
                            _status.value = _status.value.copy(
                                note = "Repaired the vault key after an offline passphrase change."
                            )
                        }
                    }
                }
                _status.value = _status.value.copy(note = WRONG_PASSPHRASE)
                return Result.failure(SvpAuthException(WRONG_PASSPHRASE))
            }
            val isThisDevice = opened.size == KeyringCodec.KEY_BYTES &&
                derivation.constantTimeEquals(opened, key.bytes)
            if (isThisDevice) {
                opened.fill(0)
                markPublished(uid, current.updatedAt)
                return Result.success(Unit)
            }
            val adopted = withContext(Dispatchers.Default) { adoptDeviceKey(opened) }
            if (!adopted) return Result.failure(SvpAuthException(NO_DEVICE_KEY))
            markPublished(uid, current.updatedAt)
            _status.value = _status.value.copy(
                note = "Adopted the vault key already stored for this account."
            )
            return Result.success(Unit)
        }

        val envelope = try {
            withContext(Dispatchers.Default) {
                KeyringProtocol.seal(key.bytes, passphrase, derivation).first
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(SvpAuthException("The vault key could not be sealed on this device."))
        }
        val ref = FirebaseBootstrap.reference(context, path)
            ?: return Result.failure(
                SvpAuthException(FirebaseBootstrap.faultReason() ?: "The realtime backend is unavailable.")
            )
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(NETWORK_TIMEOUT_MS) { ref.setValue(envelope.toCloudMap()).await() }
            }
            markPublished(uid, envelope.updatedAt)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Result.failure(SvpAuthException("Saving the vault key timed out. Retry when you have signal."))
        } catch (e: Throwable) {
            Result.failure(SvpAuthException(readable(e)))
        }
    }

    /**
     * Second-phone path: opens the cloud copy with [passphrase] and stores the result as this
     * device's copy. A wrong passphrase fails here and writes nothing at all.
     */
    suspend fun attachKeyring(passphrase: String): Result<VaultKey> {
        val envelope = readKeyring().getOrElse { return Result.failure(it) }
            ?: return Result.failure(
                SvpAuthException("This account has no vault key in the cloud yet. Publish one from the phone that created it.")
            )
        val material = withContext(Dispatchers.Default) {
            KeyringProtocol.open(envelope, passphrase, derivation)
        } ?: run {
            _status.value = _status.value.copy(note = WRONG_PASSPHRASE)
            return Result.failure(SvpAuthException(WRONG_PASSPHRASE))
        }
        if (!adoptDeviceKey(material)) {
            return Result.failure(SvpAuthException(NO_DEVICE_KEY))
        }
        auth.cachedUid()?.let { markPublished(it, envelope.updatedAt) }
        val key = deviceVaultKey()
            ?: return Result.failure(SvpAuthException(NO_DEVICE_KEY))
        return Result.success(key)
    }

    /**
     * Called whenever a master passphrase has just been proven correct (setup, passphrase change,
     * passphrase unlock). Returns immediately: the wrap and the write happen off the UI thread,
     * and a failure lands on [status] instead of the crash reporter.
     */
    fun onMasterPassphrase(passphrase: String) {
        if (passphrase.isBlank() || sessionManager.isDecoy()) return
        backgroundScope.launch {
            runCatching { flushPendingRotation() }
            val outcome = runCatching { publishKeyring(passphrase) }
            val problem = outcome.exceptionOrNull() ?: outcome.getOrNull()?.exceptionOrNull()
            if (problem is CancellationException) return@launch
            _status.value = _status.value.copy(
                note = problem?.message?.take(200) ?: "Vault key shared with this account."
            )
        }
    }

    // ----------------------------- rotation queue -----------------------------

    /**
     * A finished keyring envelope whose write did not land, or null when nothing is queued.
     *
     * This exists because an offline passphrase change used to strand the web companion: the phone
     * committed the new master passphrase, the background re-seal failed for want of a connection,
     * and the cloud kept a wrap that only the *old* passphrase could open — a passphrase the owner
     * had just replaced and would not remember. Replaying the stored envelope fixes it, and the
     * envelope proves itself before it is ever written.
     */
    fun pendingRotation(): KeyringEnvelope? {
        val wrap = prefs.getString(KEY_PENDING_WRAP, null).orEmpty()
        if (wrap.isBlank() || !wrap.contains('.')) return null
        return KeyringEnvelope.fromCloudMap(
            mapOf(
                KeyringEnvelope.FIELD_WRAP to wrap,
                KeyringEnvelope.FIELD_SALT to prefs.getString(KEY_PENDING_SALT, null),
                KeyringEnvelope.FIELD_ALG to KeyringCodec.ALGORITHM,
                KeyringEnvelope.FIELD_ITERS to prefs.getInt(KEY_PENDING_ITERS, 0),
                KeyringEnvelope.FIELD_UPDATED to prefs.getLong(KEY_PENDING_AT, 0L)
            )
        )
    }

    fun hasPendingRotation(): Boolean = pendingRotation() != null

    /** The uid the queued wrap was sealed for, so a second account can never inherit it. */
    private fun pendingOwner(): String? = prefs.getString(KEY_PENDING_UID, null)?.takeIf { it.isNotBlank() }

    private fun storePendingRotation(owner: String, envelope: KeyringEnvelope): Boolean =
        runCatching {
            prefs.edit()
                .putString(KEY_PENDING_WRAP, envelope.wrap)
                .putString(KEY_PENDING_SALT, envelope.saltBase64)
                .putInt(KEY_PENDING_ITERS, envelope.iterations)
                .putLong(KEY_PENDING_AT, envelope.updatedAt)
                .putString(KEY_PENDING_UID, owner)
                .commit()
        }.getOrDefault(false)

    private fun clearPendingRotation() {
        runCatching {
            prefs.edit()
                .remove(KEY_PENDING_WRAP)
                .remove(KEY_PENDING_SALT)
                .remove(KEY_PENDING_ITERS)
                .remove(KEY_PENDING_AT)
                .remove(KEY_PENDING_UID)
                .commit()
        }
    }

    /**
     * True when the queued wrap is genuinely this device's keyring under [passphrase]: it must open,
     * and the key inside must be the one this handset already holds. Nothing is written by this check.
     */
    private suspend fun pendingOpensWith(
        envelope: KeyringEnvelope,
        passphrase: String,
        deviceKey: ByteArray
    ): Boolean {
        if (passphrase.isBlank()) return false
        val opened = withContext(Dispatchers.Default) {
            KeyringProtocol.open(envelope, passphrase, derivation)
        } ?: return false
        try {
            return opened.size == KeyringCodec.KEY_BYTES && derivation.constantTimeEquals(opened, deviceKey)
        } finally {
            opened.fill(0)
        }
    }

    /**
     * Replays the queued re-seal to the cloud. Called on the next connection, without needing the
     * passphrase again, because the envelope was already proven against the old one before it was
     * queued. A node that is newer than the queue entry wins and the entry is dropped, so a second
     * device that rotated in the meantime is never rolled back.
     */
    suspend fun flushPendingRotation(owner: String? = null): Result<Boolean> {
        val pending = pendingRotation() ?: return Result.success(false)
        if (sessionManager.isDecoy()) return Result.failure(SvpAuthException("The vault key is not shared from a decoy session."))
        val uid = owner ?: uid()
            ?: return Result.failure(SvpAuthException("Sign in to Google before the vault key can be updated."))
        if (pendingOwner() != uid) {
            clearPendingRotation()
            return Result.success(false)
        }
        val path = RealtimePaths.keyring(uid)
            ?: return Result.failure(SvpAuthException("The account id is not usable as a storage path."))
        val current = readKeyring(uid).getOrElse { return Result.failure(it) }
        if (current != null && current.updatedAt >= pending.updatedAt) {
            clearPendingRotation()
            markPublished(uid, current.updatedAt)
            return Result.success(false)
        }
        return writeEnvelope(path, pending).map {
            clearPendingRotation()
            markPublished(uid, pending.updatedAt)
            _status.value = _status.value.copy(note = "Vault key re-sealed under the new passphrase.")
            true
        }
    }

    // ----------------------------- publish / rotate -----------------------------

    /**
     * The master passphrase has just changed, so the cloud copy must stop opening with the old one.
     * Returns immediately; the rewrite happens in the background and a failure is reported on
     * [status] rather than hanging the settings screen.
     */
    fun onPassphraseRotated(previousPassphrase: String?, newPassphrase: String) {
        if (newPassphrase.isBlank() || sessionManager.isDecoy()) return
        backgroundScope.launch {
            val outcome = runCatching { rotateKeyring(previousPassphrase, newPassphrase) }
            val problem = outcome.exceptionOrNull() ?: outcome.getOrNull()?.exceptionOrNull()
            if (problem is CancellationException) return@launch
            _status.value = _status.value.copy(
                note = problem?.message?.take(200) ?: "Vault key re-sealed under the new passphrase."
            )
        }
    }

    /**
     * Re-seals the cloud copy under [newPassphrase] without touching the vault key itself.
     *
     * Ownership is proven first: the node must open with [previousPassphrase] (or already with the
     * new one, which makes this a no-op). A guess therefore cannot rewrite anything — it fails with
     * a sentence, and the keyring a browser is already using stays intact.
     */
    suspend fun rotateKeyring(previousPassphrase: String?, newPassphrase: String): Result<Unit> {
        if (newPassphrase.isBlank()) return Result.failure(SvpAuthException("A passphrase is required."))
        if (sessionManager.isDecoy()) return Result.failure(
            SvpAuthException("The vault key is not shared from a decoy session.")
        )
        val uid = uid() ?: return Result.failure(
            SvpAuthException("Sign in to Google to connect this vault to the web companion.")
        )
        val path = RealtimePaths.keyring(uid)
            ?: return Result.failure(SvpAuthException("The account id is not usable as a storage path."))
        val key = deviceVaultKey() ?: return Result.failure(SvpAuthException(NO_DEVICE_KEY))
        val node = readKeyring(uid).getOrElse {
            return Result.failure(it)
        } ?: return publishKeyring(newPassphrase)

        val proven = withContext(Dispatchers.Default) {
            val withPrevious = previousPassphrase?.takeIf { it.isNotBlank() }
                ?.let { KeyringProtocol.open(node, it, derivation) }
            withPrevious ?: KeyringProtocol.open(node, newPassphrase, derivation)
        } ?: run {
            _status.value = _status.value.copy(note = WRONG_PASSPHRASE)
            return Result.failure(SvpAuthException(WRONG_PASSPHRASE))
        }
        if (proven.size != KeyringCodec.KEY_BYTES || !derivation.constantTimeEquals(proven, key.bytes)) {
            proven.fill(0)
            return Result.failure(
                SvpAuthException("The vault key stored in the cloud is not this device's key, so nothing was changed.")
            )
        }
        proven.fill(0)

        val alreadyRotated = withContext(Dispatchers.Default) {
            KeyringProtocol.open(node, newPassphrase, derivation)
        }
        if (alreadyRotated != null && derivation.constantTimeEquals(alreadyRotated, key.bytes)) {
            alreadyRotated.fill(0)
            markPublished(uid, node.updatedAt)
            return Result.success(Unit)
        }
        alreadyRotated?.fill(0)

        val envelope = withContext(Dispatchers.Default) {
            KeyringProtocol.seal(key.bytes, newPassphrase, derivation).first
        }
        // Queued before the attempt, not after it fails: the moment this function returns, the new
        // passphrase is the only one the owner knows. A wrap that never landed would otherwise be
        // unrecoverable, because reopening the old node needs the old passphrase.
        val queued = storePendingRotation(uid, envelope)
        val written = writeEnvelope(path, envelope)
        if (written.isSuccess) {
            clearPendingRotation()
            markPublished(uid, envelope.updatedAt)
            return Result.success(Unit)
        }
        return if (queued) {
            Result.failure(
                SvpAuthException(
                    (written.exceptionOrNull()?.message ?: "The vault key could not be updated.") +
                        " The change is saved on this device and will retry on its own."
                )
            )
        } else written
    }

    /** Forgets the local copy only. The cloud keyring is what other devices need, so it stays. */
    fun clearDeviceKey(): Unit = synchronized(lock) {
        cachedKey?.destroy()
        cachedKey = null
        runCatching {
            prefs.edit()
                .remove(KEY_VAULT_WRAPPED)
                .remove(KEY_PENDING_WRAP)
                .remove(KEY_PENDING_SALT)
                .remove(KEY_PENDING_ITERS)
                .remove(KEY_PENDING_AT)
                .remove(KEY_PENDING_UID)
                .commit()
        }
        _status.value = KeyringStatus(uid = auth.cachedUid())
    }

    /** One bounded write of a finished envelope to a keyring path. Never logs the body. */
    private suspend fun writeEnvelope(path: String, envelope: KeyringEnvelope): Result<Unit> {
        val ref = FirebaseBootstrap.reference(context, path)
            ?: return Result.failure(
                SvpAuthException(FirebaseBootstrap.faultReason() ?: "The realtime backend is unavailable.")
            )
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(NETWORK_TIMEOUT_MS) { ref.setValue(envelope.toCloudMap()).await() }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Result.failure(SvpAuthException("Saving the vault key timed out. Retry when you have signal."))
        } catch (e: Throwable) {
            Result.failure(SvpAuthException(readable(e)))
        }
    }

    private fun markPublished(owner: String, at: Long) {
        runCatching {
            prefs.edit()
                .putString(KEY_BOUND_UID, owner)
                .putLong(KEY_PUBLISHED_AT, at)
                .commit()
        }
        _status.value = _status.value.copy(uid = owner, publishedAt = at)
    }

    private fun publishStatus() {
        _status.value = _status.value.copy(
            uid = auth.cachedUid() ?: prefs.getString(KEY_BOUND_UID, null),
            hasDeviceKey = cachedKey != null,
            publishedAt = prefs.getLong(KEY_PUBLISHED_AT, 0L)
        )
    }

    private fun readable(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.contains("permission_denied", ignoreCase = true) ||
                message.contains("Permission denied", ignoreCase = true) ->
                "The realtime backend refused this account. Check that the database rules allow read and write for the signed-in uid."
            message.contains("DATABASE_URL", ignoreCase = true) ->
                "The realtime database address is missing from this build."
            message.isBlank() -> "The vault key could not be reached."
            else -> message.take(180)
        }
    }
}

/** Reads a keyring node straight off a snapshot, tolerating whatever shape the client returned. */
private fun DataSnapshot.toKeyringEnvelope(): KeyringEnvelope? {
    if (!exists()) return null
    val snapshot = runCatching { value as? Map<*, *> }.getOrNull() ?: return null
    val raw = mutableMapOf<String, Any?>()
    for ((key, entry) in snapshot) {
        val name = key as? String ?: continue
        raw[name] = entry
    }
    return KeyringEnvelope.fromCloudMap(raw)
}
