package com.securevault.pro.security.passkey

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.securevault.pro.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for passkey records, in its own DataStore file.
 *
 * The reason for a file of its own is the same one `BitgetCredentialStore` documents: a Room table
 * would be a synced table. `VaultDatabase`'s entities are read by `RealtimeStore.loadAll` and pushed
 * by `RealtimeEngine`, so a passkey row would be uploaded to the realtime backend and to Drive — and
 * a passkey record must not leave the device it belongs to. This file is not a Room entity, is not in
 * `RealtimeFieldSchema`, is not reachable from any DAO, and is asserted that way by
 * [com.securevault.pro.security.passkey.PasskeyIsNeverSyncedTest].
 *
 * What lands on disk per record, under `passkey_record.<id>`:
 * the [PasskeyRecordCodec] JSON of the record, whose only secret-bearing field is `sealedMaterial` —
 * the `{v:3}` envelope of the provider's registration response, sealed by [CryptoManager], i.e. by the
 * single device key `SecureVaultProMasterKey` that already protects every other secret in this app.
 * There is no path through this class that writes an unsealed registration response: sealing happens
 * in [PasskeyVault] before a record exists, and a record whose material would not seal is never
 * built at all.
 *
 * Removal is only ever [removeRecord] for one id, and the only caller of that is the owner confirming
 * a delete in [com.securevault.pro.presentation.screens.passkey.PasskeySection]. There is no bulk
 * expiry, no TTL, no cap on how many records are kept, and no scheduled sweep — the standing
 * retention rule.
 */
private val Context.passkeyPreferences by preferencesDataStore(name = "svp_passkeys")

@Singleton
class PasskeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager
) {

    /** `CryptoManager` behind the pure sealer seam — the same binding the Bitget vault uses. */
    private val sealer = object : PasskeySealer {
        override fun seal(plaintext: String): String? =
            if (plaintext.isBlank()) null else runCatching { crypto.encrypt(plaintext) }.getOrNull()

        override fun open(ciphertext: String?): String? =
            runCatching { crypto.decrypt(ciphertext) }.getOrNull()
    }

    /** The pure record logic, bound to this device's key. The service uses this, never a new one. */
    val vault: PasskeyVault = PasskeyVault(sealer)

    /** Every readable record, newest first. A record that will not decode is skipped, not fatal. */
    suspend fun records(): List<PasskeyRecord> = readAll().sortedWith(
        compareByDescending<PasskeyRecord> { it.lastUsedAt }.thenByDescending { it.createdAt }
    )

    /** Live view for the screen: re-emits when a record is written or removed. */
    val recordsFlow: Flow<List<PasskeyRecord>> = context.passkeyPreferences.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> decodeAll(prefs) }

    suspend fun record(id: String): PasskeyRecord? =
        records().firstOrNull { it.id == id }

    /** Writes one record. False when the value would not encode — nothing partial is ever written. */
    suspend fun put(record: PasskeyRecord): Boolean {
        if (!PasskeyRecordCodec.isValid(record)) return false
        val encoded = PasskeyRecordCodec.encode(record)
        // The never-synced guard, applied at the only write gate: a record whose text names a
        // provider secret field is not a sealed record, whatever built it. A correct record never
        // trips this — `sealedMaterial` is deliberately absent from the word list.
        if (PasskeyRecordCodec.carriesSecret(encoded)) return false
        return editSafely { prefs ->
            prefs[stringPreferencesKey(keyFor(record.id))] = encoded
        }
    }

    /**
     * Forgets this app's record of one passkey, on the owner's explicit word.
     *
     * It does not delete the passkey. The credential lives in the provider's store; the app's only
     * removal API in `credentials:1.2.0-rc01` is `ClearCredentialStateRequest`, which is a blanket
     * reset rather than one key, and this feature never calls it. After this the site can still sign
     * the owner in with the key that stays on the device — which is exactly what the screen says.
     */
    suspend fun removeRecord(id: String): Boolean {
        if (id.isBlank()) return false
        return editSafely { prefs -> prefs.remove(stringPreferencesKey(keyFor(id))) }
    }

    /** True when nothing is stored, so the screen can offer enrolment instead of an empty list. */
    suspend fun isEmpty(): Boolean = readKeys().isEmpty()

    fun settingsKeyFor(record: PasskeyRecord): String = record.id

    /** The sealed material of one record, opened for a caller that must have it. Null if unreadable. */
    suspend fun openMaterial(id: String): String? = record(id)?.let { vault.openMaterial(it) }

    private suspend fun readAll(): List<PasskeyRecord> {
        val prefs = try {
            context.passkeyPreferences.data.catch { emit(emptyPreferences()) }.first()
        } catch (_: Throwable) {
            return emptyList()
        }
        return decodeAll(prefs)
    }

    private suspend fun readKeys(): Set<String> = try {
        context.passkeyPreferences.data.catch { emit(emptyPreferences()) }.first()
            .asMap().keys.map { it.name }.filter { it.startsWith(RECORD_PREFIX) }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun decodeAll(prefs: Preferences): List<PasskeyRecord> = buildList {
        prefs.asMap().forEach { (key, value) ->
            if (!key.name.startsWith(RECORD_PREFIX)) return@forEach
            val text = value as? String ?: return@forEach
            // Only what this writer produced, and only a record that still validates.
            if (key.name.removePrefix(RECORD_PREFIX).isBlank()) return@forEach
            val record = PasskeyRecordCodec.decode(text) ?: return@forEach
            if (!record.id.startsWith(RECORD_ID_PREFIX)) return@forEach
            add(record)
        }
    }

    private fun keyFor(id: String): String = RECORD_PREFIX + id

    private suspend fun editSafely(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ): Boolean = try {
        context.passkeyPreferences.edit(block)
        true
    } catch (_: Throwable) {
        // Nothing in the failure path may carry a value from the record, so no logging here.
        false
    }

    companion object {
        /** The DataStore file name, public so the guard test can name it. */
        const val STORE_FILE = "svp_passkeys"
        const val RECORD_PREFIX = "passkey_record."
        const val RECORD_ID_PREFIX = "pk-"

        /** Every field this store writes, for the never-synced guard. */
        val OWNED_KEYS = setOf(RECORD_PREFIX)
    }
}
