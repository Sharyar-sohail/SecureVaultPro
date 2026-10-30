package com.securevault.pro.data.wallet

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.securevault.pro.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.walletKeyPreferences by preferencesDataStore(name = "svp_wallet_keys")

/**
 * The device-only home for spend-authorising private keys.
 *
 * Its own DataStore file, one record per wallet, holding exactly one sealed value plus a masked
 * address. It is not a Room entity, not in `RealtimeFieldSchema`, not reachable from any DAO and
 * not part of the Drive backup document, so a key stored here has no path out of the phone
 * (`WalletKeyIsNeverSyncedTest`). The sealing is `CryptoManager` — the existing envelope, unchanged.
 *
 * Reading a key is a deliberate act: [key] returns the plaintext hex to the one caller that signs
 * with it or exports it behind a fresh authorisation. Nothing in this file logs, caches or echoes it.
 */
@Singleton
class WalletKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager
) {

    private val sealer = object : BitgetSecretSealer {
        override fun seal(plaintext: String): String? =
            if (plaintext.isBlank()) null else runCatching { crypto.encrypt(plaintext) }.getOrNull()

        override fun open(ciphertext: String?): String? =
            runCatching { crypto.decrypt(ciphertext) }.getOrNull()
    }

    private val vault = WalletKeyVault(sealer)

    /** Seals and writes. Returns the failure reason rather than a stack, because one is shown. */
    suspend fun store(
        walletId: String,
        canonicalKeyHex: String,
        primaryAddress: String,
        label: String,
        source: WalletKeySource
    ): WalletKeyVault.SealedResult {
        val fields = vault.fieldsFor(
            walletId = walletId,
            canonicalKeyHex = canonicalKeyHex,
            primaryAddress = primaryAddress,
            label = label,
            source = source,
            nowMillis = System.currentTimeMillis()
        )
        if (!fields.ok) return fields
        // A field name that could be mistaken for a payload key is refused before it is written.
        if (fields.fields.keys.any { vault.carriesSecret(it) }) {
            return WalletKeyVault.SealedResult.failure("Internal: unexpected field name.")
        }
        editSafely { prefs ->
            fields.fields.forEach { (name, value) -> prefs[stringPreferencesKey(name)] = value }
        }
        val readBack = readFields()
        return if (vault.keyFor(walletId, readBack) != null) {
            fields
        } else {
            WalletKeyVault.SealedResult.failure(
                "The key was sealed but could not be read back from storage. Nothing was kept."
            )
        }
    }

    /**
     * The plaintext key for one wallet, unsealed in this call only. Callers: the signer, and the
     * export path after a fresh authorisation. Never show the result of this call in a log.
     */
    suspend fun key(walletId: String): String? = vault.keyFor(walletId, readFields())

    suspend fun hasKey(walletId: String): Boolean =
        !readFields()[WalletKeyFields.qualified(WalletKeyFields.SEALED_KEY, walletId)].isNullOrBlank()

    /** Masked records for every wallet that has a key here. Decrypts nothing. */
    suspend fun records(): List<WalletKeyRecord> {
        val fields = readFields()
        val ids = LinkedHashSet<String>()
        fields.keys.forEach { name -> WalletKeyFields.split(name)?.let { ids += it.second } }
        return ids.mapNotNull { vault.recordOf(it, fields) }.sortedByDescending { it.addedAt }
    }

    suspend fun record(walletId: String): WalletKeyRecord? = vault.recordOf(walletId, readFields())

    /**
     * Removes every field of one wallet and confirms by reading the file back. Returns false when
     * something survived, so "Disconnect" can say so instead of pretending.
     */
    suspend fun wipe(walletId: String): Boolean {
        editSafely { prefs ->
            val doomed = prefs.asMap().keys
                .mapNotNull { it.name as? String }
                .filter { name -> WalletKeyFields.split(name)?.second == walletId }
            doomed.forEach { name -> prefs.remove(stringPreferencesKey(name)) }
        }
        val after = readFields()
        return WalletKeyFields.ALL_FIELDS.all { field ->
            after[WalletKeyFields.qualified(field, walletId)].isNullOrBlank()
        } && vault.keyFor(walletId, after) == null
    }

    /** Every wallet id with a key in this store; used by the delete path to clean up. */
    suspend fun walletIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        readFields().keys.forEach { name -> WalletKeyFields.split(name)?.let { ids += it.second } }
        return ids
    }

    private suspend fun readFields(): Map<String, String> {
        val prefs: Preferences = try {
            context.walletKeyPreferences.data.catch { emit(emptyPreferences()) }.first()
        } catch (_: Throwable) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        prefs.asMap().forEach { (key, value) ->
            val name = key.name
            if (WalletKeyFields.split(name) != null && value is String && value.isNotBlank()) {
                out[name] = value
            }
        }
        return out
    }

    private suspend fun editSafely(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ) {
        try {
            context.walletKeyPreferences.edit(block)
        } catch (_: Throwable) {
            // A DataStore that will not write is reported by the caller's read-back. Nothing in a
            // catch path may carry a value from the record.
        }
    }
}
