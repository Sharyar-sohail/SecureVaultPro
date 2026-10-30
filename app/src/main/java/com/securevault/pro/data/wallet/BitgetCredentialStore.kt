package com.securevault.pro.data.wallet

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.securevault.pro.domain.model.BitgetCredentialState
import com.securevault.pro.domain.model.BitgetSecretBundle
import com.securevault.pro.domain.model.BitgetSnapshot
import com.securevault.pro.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Its own DataStore file, holding one thing: the Bitget read-only credentials and the last snapshot.
 *
 * Why not Room, and why not the `crypto_wallets` table: every column of that entity is mirrored to
 * the realtime backend by `RealtimeRecordCodec` (`cryptoWallets` is one of the seven synced
 * segments), so *anything* written into a crypto row — even a ciphertext in `secret` — is uploaded.
 * This file is not a Room entity, is not listed in `RealtimeFieldSchema`, is not reachable from any
 * DAO, and therefore has no path into a sync payload at all.
 * [com.securevault.pro.crypto.BitgetCredentialIsNeverSyncedTest] pins that.
 *
 * Why not plain SharedPreferences: the three values are sealed individually with the app's existing
 * vault crypto before they are written, through the same AES-256-GCM envelope
 * (`base64(iv).base64(ciphertext+tag)`) under the non-exportable Keystore-wrapped data key that
 * [WalletRepository] uses for a seed phrase. What is on disk is only ciphertext, a label, the last
 * four characters of the API key, and the balance snapshot — which are numbers and symbols, and the
 * only part the owner could ever choose to mirror.
 */
private val Context.bitgetPreferences by preferencesDataStore(name = "svp_bitget_credentials")

@Singleton
class BitgetCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager
) {

    /** `CryptoManager` behind the pure sealer interface the vault is written against. */
    private val sealer = object : BitgetSecretSealer {
        override fun seal(plaintext: String): String? =
            if (plaintext.isBlank()) null else runCatching { crypto.encrypt(plaintext) }.getOrNull()

        override fun open(ciphertext: String?): String? =
            runCatching { crypto.decrypt(ciphertext) }.getOrNull()
    }

    private val vault = BitgetCredentialVault(sealer)

    /** Masked view for the UI. Decrypts nothing. */
    suspend fun state(): BitgetCredentialState? = vault.stateOf(readFields())

    /** The last successfully read balances, or null when nothing has been read yet. */
    suspend fun snapshot(): BitgetSnapshot? =
        BitgetSnapshotCodec.decode(readFields()[BitgetCredentialFields.SNAPSHOT])

    /**
     * The plaintext triple, unsealed for one signed request. Callers must pass it straight to
     * [BitgetClient] and drop it: never store it, log it, show it, or put it in an exception message.
     */
    suspend fun secrets(): BitgetSecretBundle? = vault.secretsOf(readFields())

    /** Seals and persists. On a sealing failure nothing at all is written. */
    suspend fun save(bundle: BitgetSecretBundle, label: String): BitgetCredentialVault.SealedResult {
        val result = vault.fieldsFor(bundle, label, System.currentTimeMillis())
        if (!result.ok) return result
        editSafely { prefs ->
            result.fields.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        }
        return result
    }

    /** Balances only. The snapshot text is checked so a credential can never ride in by accident. */
    suspend fun storeSnapshot(snapshot: BitgetSnapshot) {
        val encoded = BitgetSnapshotCodec.encode(snapshot)
        if (BitgetCredentials.carriesSecret(encoded)) return
        editSafely { prefs ->
            prefs[stringPreferencesKey(BitgetCredentialFields.SNAPSHOT)] = encoded
            prefs[stringPreferencesKey(BitgetCredentialFields.SNAPSHOT_AT)] =
                snapshot.fetchedAt.toString()
        }
    }

    /**
     * Wipes the file: sealed values, masked key, label and snapshot. Suspends until DataStore has
     * applied the change, which is what makes "Remove credentials" take effect immediately.
     */
    suspend fun clear() {
        editSafely { it.clear() }
    }

    suspend fun hasCredentials(): Boolean =
        !readFields()[BitgetCredentialFields.SEALED_API_SECRET].isNullOrBlank()

    private suspend fun readFields(): Map<String, String> {
        val prefs = try {
            context.bitgetPreferences.data
                .catch { emit(emptyPreferences()) }
                .first()
        } catch (_: Throwable) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        BitgetCredentialFields.ALL_FIELDS.forEach { name ->
            val value = prefs[stringPreferencesKey(name)]
            if (!value.isNullOrBlank()) out[name] = value
        }
        return out
    }

    private suspend fun editSafely(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ) {
        try {
            context.bitgetPreferences.edit(block)
        } catch (_: Throwable) {
            // A DataStore that cannot be written is reported by the caller's read-back, not logged:
            // nothing in the catch path may carry a value from the record.
        }
    }
}
