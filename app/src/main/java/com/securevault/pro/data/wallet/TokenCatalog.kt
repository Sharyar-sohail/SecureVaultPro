package com.securevault.pro.data.wallet

import android.content.Context
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One token a curated public list says exists on a chain.
 *
 * [contract] is stored exactly as the list spells it, and compared case-insensitively for EVM; the
 * scan never invents an address that a list did not publish.
 */
data class CatalogToken(
    val networkId: String,
    val contract: String,
    val symbol: String,
    val name: String,
    val decimals: Int
) {
    /** The identity a token has on one chain: its contract, not its ticker. */
    val key: String get() = "$networkId:${contract.lowercase()}"
}

/**
 * The token lists this app trusts, with no account and no API key.
 *
 * Trust Wallet-scale discovery needs a list of what exists on each chain. Two curated DEX lists cover
 * Ethereum, BNB Chain and Polygon, and CoinGecko publishes a per-chain list for every other chain in
 * [ChainRegistry] — Arbitrum, Base, Optimism, Avalanche and Fantom among them. All of them are plain
 * static JSON over https, so there is nothing to sign up for and no key that could leak.
 *
 * A list is fetched at most every [REFRESH_MS], cached on disk, and reused stale if the network
 * refuses — a wallet must still show its tokens offline, just not the newest ones. If even the cache
 * is missing, [SEED] is the fallback: the majors of each chain, read from that chain's own list, so
 * this can only ever widen discovery, never narrow it.
 */
@Singleton
class TokenCatalog @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val memory = LinkedHashMap<String, List<CatalogToken>>()

    /** Curated tokens for [network], from the freshest source available. */
    suspend fun tokens(network: CryptoNetwork): List<CatalogToken> = withContext(Dispatchers.IO) {
        val descriptor = ChainRegistry.descriptor(network)
        if (descriptor == null || !descriptor.isEvm) return@withContext emptyList()
        memory[network.id]?.let { return@withContext it }
        val loaded = readCache() ?: refresh()
        val list = loaded[network.id].orEmpty()
        memory[network.id] = list
        list
    }

    /** Discovery still works with nothing cached: the majors this build has always known. */
    fun seed(network: CryptoNetwork): List<CatalogToken> =
        SEED.filter { it.networkId == network.id }

    private fun refresh(): Map<String, List<CatalogToken>> {
        val merged = LinkedHashMap<String, MutableList<CatalogToken>>()
        SOURCES.forEach { source ->
            val fetched = runCatching { fetch(source) }.getOrNull() ?: return@forEach
            fetched.forEach { token ->
                val bucket = merged.getOrPut(token.networkId) { ArrayList() }
                if (bucket.none { it.key == token.key } && bucket.size < source.cap) bucket += token
            }
        }
        if (merged.isEmpty()) {
            // Nothing answered. Reuse the stale cache if there is one, otherwise the seed set.
            val stale = readCache(acceptStale = true).orEmpty()
            return stale.ifEmpty { SEED.groupBy { it.networkId } }
        }
        writeCache(merged)
        return merged
    }

    private fun fetch(source: TokenListSource): List<CatalogToken> {
        val root = JSONObject(WalletHttp.get(source.url))
        val array = root.optJSONArray("tokens") ?: return emptyList()
        val out = ArrayList<CatalogToken>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val network = CryptoNetwork.entries.firstOrNull { it.id == source.chainIds[entry.optLong("chainId")] }
                ?: continue
            val contract = entry.optString("address")
            val symbol = entry.optString("symbol")
            if (contract.isBlank() || symbol.isBlank()) continue
            val decimals = entry.optInt("decimals", -1).takeIf { it in 0..18 } ?: continue
            out += CatalogToken(
                networkId = network.id,
                contract = contract,
                symbol = symbol.uppercase(),
                name = entry.optString("name").ifBlank { symbol },
                decimals = decimals
            )
        }
        return out
    }

    private fun readCache(acceptStale: Boolean = false): Map<String, List<CatalogToken>>? {
        val file = cacheFile
        if (!file.exists()) return null
        if (!acceptStale && System.currentTimeMillis() - file.lastModified() > REFRESH_MS) return null
        return runCatching {
            val array = JSONArray(file.readText())
            val out = LinkedHashMap<String, MutableList<CatalogToken>>()
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val token = CatalogToken(
                    networkId = entry.optString("n"),
                    contract = entry.optString("c"),
                    symbol = entry.optString("s"),
                    name = entry.optString("name"),
                    decimals = entry.optInt("d", 18)
                )
                if (token.contract.isBlank() || token.symbol.isBlank()) continue
                out.getOrPut(token.networkId) { ArrayList() } += token
            }
            out
        }.getOrNull()
    }

    private fun writeCache(byNetwork: Map<String, List<CatalogToken>>) {
        val array = JSONArray()
        byNetwork.values.flatten().forEach { token ->
            array.put(
                JSONObject()
                    .put("n", token.networkId)
                    .put("c", token.contract)
                    .put("s", token.symbol)
                    .put("name", token.name)
                    .put("d", token.decimals)
            )
        }
        runCatching { cacheFile.writeText(array.toString()) }
    }

    private val cacheFile: File get() = File(context.filesDir, "svp_token_catalog.json")

    private companion object {
        /** Three days: a token list changes slowly, and a scan should not refetch on every pull. */
        const val REFRESH_MS = 3 * 24 * 60 * 60 * 1000L

        /**
         * A chain's own list order is its maintainers' ranking, so it is kept as read and cut at the
         * cap. The cap exists because every entry costs one on-chain read.
         */
        const val PER_LIST_CAP = 420

        data class TokenListSource(val url: String, val chainIds: Map<Long, String>, val cap: Int)

        val SOURCES = CryptoEndpoints.tokenLists.map { (url, chainIds) ->
            TokenListSource(url, chainIds, PER_LIST_CAP)
        }

        val SEED = listOf(
            CatalogToken("ETHEREUM", "0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", "Tether USD", 6),
            CatalogToken("ETHEREUM", "0x95aD61b0a150d79219dCF64E1E6Cc01f0B64C4cE", "SHIB", "Shiba Inu", 18),
            CatalogToken("ETHEREUM", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", "USD Coin", 6),
            CatalogToken("BSC", "0x55d398326f99059fF775485246999027B3197955", "USDT", "Tether USD", 18),
            CatalogToken("BSC", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "USDC", "USD Coin", 18),
            CatalogToken("BSC", "0x2859e4544C4bB03966803b044A93563Bd2D0DD4D", "SHIB", "Shiba Inu", 18),
            CatalogToken("POLYGON", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", "USDC", "USD Coin", 6),
            CatalogToken("POLYGON", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "USDT", "Tether USD", 6),
            // Every address below is read from that chain's own published list, not written from
            // memory: a seed that names the wrong contract would put a lookalike token in front of
            // the pre-signing gate.
            CatalogToken("ARBITRUM", "0xaf88d065e77c8cC2239320C1EDb3A432268e5831", "USDC", "USDC", 6),
            CatalogToken("ARBITRUM", "0x82aF49447D8a07e3BD95Bd0d56f35241523FbAb1", "WETH", "Arbitrum Bridged WETH", 18),
            CatalogToken("ARBITRUM", "0x912CE59144191C1204E64559FE8253a0e49E6548", "ARB", "Arbitrum", 18),
            CatalogToken("ARBITRUM", "0x2f2a2543B76A4166549F7aB2e75bEF0aEFc5b0F", "WBTC", "Arbitrum Bridged WBTC", 8),
            CatalogToken("ARBITRUM", "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "DAI", "Arbitrum Bridged DAI", 18),
            CatalogToken("BASE", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", "USDC", 6),
            CatalogToken("BASE", "0x4200000000000000000000000000000000000006", "WETH", "L2 Standard Bridged WETH", 18),
            CatalogToken("BASE", "0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2", "USDT", "L2 Standard Bridged USDT", 6),
            CatalogToken("BASE", "0x0555E30da8f98308EdB960aa94C0Db47230d2B9c", "WBTC", "Wrapped Bitcoin", 8),
            CatalogToken("OPTIMISM", "0x0B2C639c533813f4Aa9D7837CAf62653d097Ff85", "USDC", "USDC", 6),
            CatalogToken("OPTIMISM", "0x4200000000000000000000000000000000000006", "WETH", "L2 Standard Bridged WETH", 18),
            CatalogToken("OPTIMISM", "0x4200000000000000000000000000000000000042", "OP", "Optimism", 18),
            CatalogToken("OPTIMISM", "0x94b008aA00579c1307B0EF2c499aD98a8CE58E58", "USDT", "Bridged USDT", 6),
            CatalogToken("AVALANCHE", "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", "USDC", "USDC", 6),
            CatalogToken("AVALANCHE", "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", "USDT", "Tether USD", 6),
            CatalogToken("AVALANCHE", "0xB31f66AA3C1e785363F0875A1B74e27b85FD66c7", "WAVAX", "Wrapped AVAX", 18),
            CatalogToken("AVALANCHE", "0x49D5c2BdFfac6CE2BFdB6640F4F80f226bc10bAB", "WETH", "Avalanche Bridged WETH", 18),
            CatalogToken("FANTOM", "0x04068DA6c83AFcFA0e13bA15a6696662335d5B75", "USDC", "Fantom Bridged USDC", 6),
            CatalogToken("FANTOM", "0x74b23882a30290451A17c44f4F05243b6b58C76d", "WETH", "Fantom Bridged WETH", 18),
            CatalogToken("FANTOM", "0x511D35c52A3C244E7b8BD92c0C297755fBD89212", "WAVAX", "Bridged WAVAX", 18)
        )
    }
}
