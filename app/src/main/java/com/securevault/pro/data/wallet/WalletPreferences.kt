package com.securevault.pro.data.wallet

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.EvmCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.walletScanPreferences by preferencesDataStore(name = "svp_wallet_scan")

/**
 * A token the owner named themselves, by contract.
 *
 * The symbol and decimals are what the chain answered when the contract was read, not what the owner
 * typed: a pasted ticker is never trusted, because a lookalike token with a famous symbol is exactly
 * the scam the pre-signing gate exists to stop.
 */
data class CustomToken(
    val networkId: String,
    val contract: String,
    val symbol: String,
    val name: String,
    val decimals: Int
)

/**
 * What the owner has told the scanner to do: which chains to walk, and which contracts to read even
 * though no public list publishes them.
 *
 * Both settings default to *everything works with no input*: every registered chain is scanned until
 * the owner switches one off, and the custom-token list starts empty. Neither is a secret — a chain
 * name and a public contract address — so both live in plain preferences and neither is ever synced
 * or backed up.
 */
@Singleton
class WalletPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val DISABLED_NETWORKS = stringPreferencesKey("scan_disabled_networks")
        val CUSTOM_TOKENS = stringPreferencesKey("scan_custom_tokens")
    }

    private val store = context.walletScanPreferences

    private val data: Flow<Preferences> = store.data.catch { emit(emptyPreferences()) }

    /** Chains the scanner may walk. An unknown or blank id is ignored, never guessed at. */
    val enabledNetworkIds: Flow<Set<String>> = data.map { preferences ->
        val disabled = splitIds(preferences[Keys.DISABLED_NETWORKS])
        ChainRegistry.all().map { it.network.id }.filterNot { it in disabled }.toSet()
    }

    val customTokens: Flow<List<CustomToken>> = data.map { preferences ->
        decodeTokens(preferences[Keys.CUSTOM_TOKENS])
    }

    suspend fun enabledIds(): Set<String> = withContext(Dispatchers.IO) { enabledNetworkIds.first() }

    suspend fun customTokensNow(): List<CustomToken> = withContext(Dispatchers.IO) {
        customTokens.first()
    }

    suspend fun setNetworkEnabled(networkId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val id = networkId.trim().uppercase()
        if (ChainRegistry.descriptorOrNull(id) == null) return@withContext
        val current = disabledIds()
        val next = if (enabled) current - id else current + id
        store.edit { preferences -> preferences[Keys.DISABLED_NETWORKS] = next.joinToString(",") }
    }

    private suspend fun disabledIds(): Set<String> =
        splitIds(data.first()[Keys.DISABLED_NETWORKS])

    private fun splitIds(raw: String?): Set<String> =
        raw.orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Adds [token], returning null when it is stored and the reason it was not when it was refused.
     * The refusal text is shown verbatim, so it names the chain and the shape expected.
     */
    suspend fun addCustomToken(token: CustomToken): String? = withContext(Dispatchers.IO) {
        val network = CryptoNetwork.entries.firstOrNull { it.id.equals(token.networkId, true) }
            ?: return@withContext "This app has no rules for that chain."
        val contract = token.contract.trim()
        val problem = when {
            ChainRegistry.descriptor(network)?.isEvm != true && network != CryptoNetwork.TRON ->
                "${network.displayName} holds only its own coin, so no token can be added to it."
            !looksLikeTokenContract(network, contract) ->
                "That is not a valid ${network.displayName} token address. " +
                    "Copy the contract address from the token's own page."
            else -> null
        }
        if (problem != null) return@withContext problem
        val existing = customTokens.first()
        if (existing.any { it.networkId == network.id && it.contract.equals(contract, true) }) {
            return@withContext "That token is already on the list for ${network.displayName}."
        }
        val stored = token.copy(networkId = network.id, contract = canonical(network, contract))
        val next = existing + stored
        store.edit { it[Keys.CUSTOM_TOKENS] = encodeTokens(next) }
        null
    }

    suspend fun removeCustomToken(networkId: String, contract: String) = withContext(Dispatchers.IO) {
        val existing = customTokens.first()
        val next = existing.filterNot {
            it.networkId.equals(networkId, true) && it.contract.equals(contract, true)
        }
        if (next.size != existing.size) store.edit { it[Keys.CUSTOM_TOKENS] = encodeTokens(next) }
    }

    private fun canonical(network: CryptoNetwork, contract: String): String =
        if (ChainRegistry.descriptor(network)?.isEvm == true) {
            EvmCrypto.normalize(contract) ?: contract
        } else {
            contract
        }

    private fun looksLikeTokenContract(network: CryptoNetwork, contract: String): Boolean = when {
        ChainRegistry.descriptor(network)?.isEvm == true -> EvmCrypto.normalize(contract) != null
        network == CryptoNetwork.TRON -> contract.length == 34 && contract.startsWith('T') &&
            contract.all { it.isLetterOrDigit() }
        else -> false
    }

    private fun encodeTokens(tokens: List<CustomToken>): String {
        val array = JSONArray()
        tokens.forEach { token ->
            array.put(
                JSONObject()
                    .put("n", token.networkId)
                    .put("c", token.contract)
                    .put("s", token.symbol)
                    .put("name", token.name)
                    .put("d", token.decimals)
            )
        }
        return array.toString()
    }

    private fun decodeTokens(json: String?): List<CustomToken> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val contract = entry.optString("c")
                    val symbol = entry.optString("s")
                    if (contract.isBlank() || symbol.isBlank()) continue
                    add(
                        CustomToken(
                            networkId = entry.optString("n"),
                            contract = contract,
                            symbol = symbol,
                            name = entry.optString("name").ifBlank { symbol },
                            decimals = entry.optInt("d", 18)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** The rows this scan may not lose: a token the owner added is kept even when it reads as zero. */
internal fun List<CustomToken>.asCandidateRows(network: CryptoNetwork): List<CatalogToken> =
    filter { it.networkId == network.id }
        .map { CatalogToken(network.id, it.contract, it.symbol, it.name, it.decimals) }
