package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.Base58
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.WalletAddress
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.domain.model.BalanceScan
import com.securevault.pro.domain.model.TokenBalance
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only balance scan against public indexers and RPC nodes. No key material ever leaves the
 * device — only public addresses are queried.
 *
 * The scan is the answer to "my wallet only shows the gas coin". It walks every chain the owner has
 * left switched on, and on each one it reads the native coin *and* sweeps a curated token list for
 * balances in batched `eth_call`s, so a BNB Chain wallet full of BEP-20s and an Ethereum wallet full
 * of ERC-20s come back with those tokens instead of nothing. Ethereum adds one more source on top:
 * Blockscout reports every token an address holds, which catches contracts no public list publishes.
 * Anything the sweep cannot find, the owner can name by contract in Settings and it is read first.
 *
 * Every host is keyless. A chain that does not answer is reported in the owner's words, and its last
 * known rows keep their values rather than resetting to zero.
 */
@Singleton
class CryptoBalanceService @Inject constructor(
    private val catalog: TokenCatalog,
    private val probe: EvmTokenProbe,
    private val preferences: WalletPreferences
) {

    /**
     * Ticker to CoinGecko coin id, for the coins this app quotes natively. ETH covers Ethereum and
     * the three L2s that carry it as their own coin, because the price is the same price.
     */
    private val priceIds = mapOf(
        "BTC" to "bitcoin",
        "ETH" to "ethereum",
        "BNB" to "binancecoin",
        "POL" to "polygon-ecosystem-token",
        "TRX" to "tron",
        "AVAX" to "avalanche-2",
        "FTM" to "fantom",
        "ARB" to "arbitrum",
        "OP" to "optimism",
        "USDT" to "tether",
        "USDC" to "usd-coin",
        "SHIB" to "shiba-inu",
        "DAI" to "dai",
        "WBTC" to "wrapped-bitcoin",
        "WETH" to "weth"
    )

    suspend fun scan(
        addresses: List<WalletAddress>,
        cachedTokens: List<TokenBalance> = emptyList()
    ): BalanceScan = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext BalanceScan(emptyList())
        CrashLogger.noteEvent("portfolio scan started")
        val enabled = preferences.enabledIds()
        val custom = preferences.customTokensNow()
        val targets = addresses.filter { it.network.id in enabled }
        if (targets.isEmpty()) {
            return@withContext BalanceScan(
                emptyList(),
                emptyMap(),
                emptySet(),
                "Every chain is switched off in Settings. Turn one on to see this wallet's assets."
            )
        }
        val majors = fetchPrices(cachedTokens.associate { it.symbol to it.priceUsd })
        val results = coroutineScope {
            targets.map { address ->
                async { address to runCatching { scanNetwork(address, majors, cachedTokens, custom) } }
            }
        }.awaitAll()

        val rows = mutableListOf<TokenBalance>()
        val errors = LinkedHashMap<String, String>()
        results.forEach { (address, result) ->
            result.onSuccess { rows += it }
                .onFailure { failure ->
                    val message = failure.message?.takeIf { it.isNotBlank() }
                        ?: "${address.network.displayName} nodes did not answer. " +
                            "Check your connection and refresh in a moment."
                    errors[address.network.id] = message
                }
        }
        val priced = withTokenPrices(rows)
        CrashLogger.noteEvent("portfolio scan finished (${rows.size} assets, ${errors.size} chains silent)")
        BalanceScan(
            priced,
            errors,
            results.filter { it.second.isSuccess }.mapTo(LinkedHashSet()) { it.first.network.id }
        )
    }

    /**
     * USD quotes for arbitrary ticker symbols, from the same keyless sources the balance scan uses.
     * Public so the read-only Bitget panel can price exchange holdings without inventing a second
     * price feed. Symbols this app does not already know are quoted against USDT on Binance; a
     * symbol nothing answers for is simply absent, and the caller shows the amount without a value.
     */
    suspend fun usdPrices(symbols: Collection<String>): Map<String, Double> =
        withContext(Dispatchers.IO) {
            val wanted = symbols.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            val known = fetchPrices(emptyMap())
            val out = LinkedHashMap<String, Double>()
            val missing = ArrayList<String>()
            wanted.forEach { symbol ->
                val price = known[symbol]
                if (price != null && price > 0.0) out[symbol] = price else missing += symbol
            }
            if (missing.isNotEmpty()) out.putAll(binanceUsdtQuotes(missing))
            out
        }

    private fun binanceUsdtQuotes(symbols: List<String>): Map<String, Double> {
        if (symbols.isEmpty()) return emptyMap()
        val pairs = symbols.joinToString(",") { "%22${it}USDT%22" }
        return runCatching {
            val array = JSONArray(WalletHttp.get("${CryptoEndpoints.BINANCE_PRICE}?symbols=[$pairs]"))
            buildMap<String, Double> {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    val symbol = entry.optString("symbol").removeSuffix("USDT")
                    val price = entry.optString("price").toDoubleOrNull() ?: 0.0
                    if (symbol.isNotEmpty() && price > 0.0) put(symbol, price)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private suspend fun scanNetwork(
        address: WalletAddress,
        prices: Map<String, Double>,
        cachedTokens: List<TokenBalance>,
        custom: List<CustomToken>
    ): List<TokenBalance> = when (address.network) {
        CryptoNetwork.BITCOIN -> listOf(bitcoinBalance(address, prices))
        CryptoNetwork.TRON -> tronBalances(address, prices, custom)
        else -> evmBalances(address, prices, cachedTokens, custom)
    }

    private fun bitcoinBalance(address: WalletAddress, prices: Map<String, Double>): TokenBalance {
        var lastFailure: Throwable? = null
        CryptoEndpoints.bitcoinApi.forEach { base ->
            runCatching {
                val root = JSONObject(WalletHttp.get("$base/api/address/${address.address}"))
                val confirmed = root.optJSONObject("chain_stats")
                val pending = root.optJSONObject("mempool_stats")
                val sats = (confirmed?.optLong("funded_txo_sum", 0L) ?: 0L) -
                    (confirmed?.optLong("spent_txo_sum", 0L) ?: 0L) +
                    (pending?.optLong("funded_txo_sum", 0L) ?: 0L) -
                    (pending?.optLong("spent_txo_sum", 0L) ?: 0L)
                return nativeToken(address, sats.toDouble() / 100_000_000.0, prices)
            }.onFailure { lastFailure = it }
        }
        // BlockCypher uses a different payload, so it is only a last resort.
        runCatching {
            val root = JSONObject(
                WalletHttp.get("${CryptoEndpoints.BITCOIN_BALANCE_FALLBACK}${address.address}/balance")
            )
            val sats = root.optLong("final_balance", root.optLong("balance", 0L))
            return nativeToken(address, sats.toDouble() / 100_000_000.0, prices)
        }.onFailure { lastFailure = it }
        error(WalletHttp.describeFailure("Bitcoin", lastFailure))
    }

    private fun nativeToken(
        address: WalletAddress,
        amount: Double,
        prices: Map<String, Double>
    ): TokenBalance = TokenBalance(
        networkId = address.network.id,
        networkName = address.network.displayName,
        symbol = address.network.symbol,
        name = address.network.displayName,
        amount = amount,
        priceUsd = prices[address.network.symbol] ?: 0.0
    )

    /**
     * One chain, read completely: the gas coin, then every curated token this address holds.
     *
     * The native coin still comes from `eth_getBalance` exactly as before, so a wallet that only ever
     * showed BNB or POL keeps showing it. What changed is the second half — the sweep — and the rows
     * it produces carry the contract and decimals they were read from, which is what lets the owner
     * send a discovered token instead of only seeing it.
     */
    private suspend fun evmBalances(
        address: WalletAddress,
        prices: Map<String, Double>,
        cachedTokens: List<TokenBalance>,
        custom: List<CustomToken>
    ): List<TokenBalance> {
        val network = address.network
        val descriptor = ChainRegistry.require(network)
        val rpcs = descriptor.rpcEndpoints
        val nativeRaw = WalletHttp.call(
            rpcs,
            "eth_getBalance",
            JSONArray().put(address.address).put("latest")
        )
        val out = mutableListOf(
            nativeToken(address, WalletHttp.hexToBigInteger(nativeRaw), descriptor.decimals, prices)
        )
        val candidates = TokenScanPlanner.candidates(
            networkId = network.id,
            custom = custom,
            cached = cachedTokens,
            catalog = catalog.tokens(network)
        )
        val discovered = probe.fundedTokens(network, rpcs, address.address, candidates)
        out += discovered
        val explorer = runCatching { explorerTokens(network, address.address) }.getOrDefault(emptyList())
        out += TokenScanPlanner.extraFromExplorer(network, explorer, discovered)
        return out.distinctBy { row -> row.contract?.lowercase() ?: row.symbol }
    }

    private fun nativeToken(
        address: WalletAddress,
        raw: java.math.BigInteger,
        decimals: Int,
        prices: Map<String, Double>
    ): TokenBalance = nativeToken(
        address,
        BigDecimal(raw).divide(BigDecimal.TEN.pow(decimals), java.math.MathContext(12)).toDouble(),
        prices
    )

    /**
     * Every token an address is recorded as holding, from the chain explorer that answers for this
     * chain. Ethereum only: the public Blockscout instance was verified, and a chain without one is
     * still scanned through its token list.
     */
    private fun explorerTokens(network: CryptoNetwork, address: String): List<TokenBalance> {
        val base = CryptoEndpoints.tokenHistoryApi[network.id] ?: return emptyList()
        val root = JSONObject(WalletHttp.get("$base$address/tokens?type=ERC-20"))
        val items = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val token = item.optJSONObject("token") ?: continue
                val contract = token.optString("address_hash")
                val symbol = token.optString("symbol")
                if (contract.isBlank() || symbol.isBlank()) continue
                if (token.optString("type").isNotBlank() && token.optString("type") != "ERC-20") continue
                val decimals = token.optInt("decimals", -1).takeIf { it in 0..36 } ?: continue
                val amount = item.optString("value").toBigDecimalOrNull()?.let {
                    it.divide(BigDecimal.TEN.pow(decimals), java.math.MathContext(12)).toDouble()
                } ?: 0.0
                if (amount <= 0.0) continue
                add(
                    TokenBalance(
                        networkId = network.id,
                        networkName = network.displayName,
                        symbol = symbol.uppercase(),
                        name = token.optString("name").ifBlank { symbol },
                        amount = amount,
                        contract = contract,
                        decimals = decimals
                    )
                )
            }
        }
    }

    private fun tronBalances(
        address: WalletAddress,
        prices: Map<String, Double>,
        custom: List<CustomToken>
    ): List<TokenBalance> {
        var lastFailure: Throwable? = null
        CryptoEndpoints.tronApi.forEach { base ->
            runCatching {
                return parseTronAccount(
                    JSONObject(WalletHttp.get("$base/v1/accounts/${address.address}")),
                    address,
                    prices,
                    custom
                )
            }.onFailure { lastFailure = it }
        }
        error(WalletHttp.describeFailure("Tron", lastFailure))
    }

    /**
     * The TRC-20 contracts this app can name without being told: the stablecoins Circle and Tether
     * publish on Tron, which between them are almost every Tron token holding, plus every contract
     * the owner added in Settings. A contract outside that list is left unread rather than guessed
     * at, because a balance printed with the wrong decimals is worse than a missing row.
     */
    private data class TronTokenSpec(val contract: String, val symbol: String, val name: String, val decimals: Int)

    private fun tronCatalog(custom: List<CustomToken>): List<TronTokenSpec> =
        CryptoTransferService.TOKEN_SPECS
            .filter { it.network == CryptoNetwork.TRON }
            .map { TronTokenSpec(it.contract, it.symbol, it.name, it.decimals) } +
            custom.filter { it.networkId == CryptoNetwork.TRON.id }
                .map { TronTokenSpec(it.contract, it.symbol.uppercase(), it.name, it.decimals) }

    /**
     * The 20-byte hex a TRC-20 contract is written as in an account record. TronGrid spells the
     * contract either Base58 (`T…`) or raw hex (`41…`/bare 40 hex) depending on the endpoint, and the
     * owner's list stores it as `T…`, so both are compared as hex and nothing else matches.
     */
    private fun tronContractHex(contract: String): String? {
        val value = contract.trim().removePrefix("0x")
        if (value.length == 40 && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            return value.lowercase()
        }
        if (value.length == 42 && value.startsWith("41")) return value.substring(2).lowercase()
        val decoded = Base58.decodeCheck(contract.trim()) ?: return null
        if (decoded.size != 21 || decoded[0].toInt() != 0x41) return null
        return decoded.copyOfRange(1, 21).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun parseTronAccount(
        root: JSONObject,
        address: WalletAddress,
        prices: Map<String, Double>,
        custom: List<CustomToken>
    ): List<TokenBalance> {
        val data = root.optJSONArray("data")
        val account = data?.optJSONObject(0)
        val sun = account?.optLong("balance", 0L) ?: 0L
        val out = mutableListOf(
            nativeToken(address, sun.toDouble() / 1_000_000.0, prices)
        )
        val trc20 = account?.optJSONArray("trc20") ?: return out
        val known = tronCatalog(custom).mapNotNull { spec ->
            tronContractHex(spec.contract)?.let { hex -> hex to spec }
        }.toMap()
        for (index in 0 until trc20.length()) {
            val entry = trc20.optJSONObject(index) ?: continue
            entry.keys().forEach { key ->
                // One holding is written "<contract>:<raw amount>" with a filler value of "0"; older
                // records wrote "<contract>": "<raw amount>". The amount is taken from the key when
                // the key carries it, which is why reading only the value used to print every
                // TRC-20 balance as nothing.
                val contract = key.substringBefore(':')
                val raw = key.substringAfter(':', "").ifBlank { entry.optString(key) }
                val spec = known[tronContractHex(contract) ?: return@forEach] ?: return@forEach
                val amount = raw.toBigDecimalOrNull()
                    ?.divide(BigDecimal.TEN.pow(spec.decimals), java.math.MathContext(12))?.toDouble() ?: 0.0
                if (amount <= 0.0) return@forEach
                out += TokenBalance(
                    networkId = address.network.id,
                    networkName = address.network.displayName,
                    symbol = spec.symbol.uppercase(),
                    name = spec.name.ifBlank { spec.symbol },
                    amount = amount,
                    priceUsd = prices[spec.symbol] ?: 0.0,
                    contract = spec.contract,
                    decimals = spec.decimals
                )
            }
        }
        return out
    }

    /**
     * USD quotes for the contracts the sweep found, priced by address rather than by ticker.
     *
     * A ticker is not an identity: a thousand tokens call themselves "GOLD". CoinGecko's per-chain
     * token endpoint takes contracts, which is the only way a newly discovered token can be given a
     * value that belongs to it. A token nothing quotes simply shows its amount without a dollar
     * figure, and a row never loses its balance because a price server was busy.
     */
    private fun withTokenPrices(rows: List<TokenBalance>): List<TokenBalance> {
        val toPrice = rows.filter { !it.contract.isNullOrBlank() && it.priceUsd <= 0.0 }
        if (toPrice.isEmpty()) return rows
        val quotes = toPrice.groupBy { it.networkId }
            .filter { (networkId, _) -> CryptoEndpoints.tokenPricePlatform.containsKey(networkId) }
            .flatMap { (networkId, list) ->
                val platform = CryptoEndpoints.tokenPricePlatform[networkId] ?: return@flatMap emptyList()
                fetchTokenPrices(platform, list.mapNotNull { it.contract }.distinct()).entries
            }
            .associate { it.key to it.value }
        return rows.map { row ->
            val price = row.contract?.lowercase()?.let { quotes[it] } ?: 0.0
            if (price > 0.0) row.copy(priceUsd = price) else row
        }
    }

    private fun fetchTokenPrices(platform: String, contracts: List<String>): Map<String, Double> {
        if (contracts.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        contracts.take(MAX_PRICED_TOKENS).chunked(PRICE_CHUNK).forEach { chunk ->
            runCatching {
                val url = "${CryptoEndpoints.COINGECKO_TOKEN_PRICE}/$platform" +
                    "?contract_addresses=${chunk.joinToString(",") { it.lowercase() }}&vs_currencies=usd"
                val root = JSONObject(WalletHttp.get(url))
                chunk.forEach { contract ->
                    val price = root.optJSONObject(contract.lowercase())?.optDouble("usd", 0.0) ?: 0.0
                    if (price > 0.0) out[contract.lowercase()] = price
                }
            }
        }
        return out
    }

    private fun fetchPrices(fallback: Map<String, Double>): Map<String, Double> {
        val result = HashMap<String, Double>()
        fallback.forEach { (symbol, price) -> if (price > 0.0) result[symbol] = price }
        val ids = priceIds.values.distinct().joinToString(",")
        val url = "${CryptoEndpoints.COINGECKO_PRICE}?ids=$ids&vs_currencies=usd"
        runCatching {
            val root = JSONObject(WalletHttp.get(url))
            priceIds.forEach { (symbol, id) ->
                val price = root.optJSONObject(id)?.optDouble("usd", 0.0) ?: 0.0
                if (price > 0.0) result[symbol] = price
            }
            // CoinGecko may list Polygon under the legacy id.
            if (result["POL"] == null || result["POL"] == 0.0) {
                val legacy = root.optJSONObject("matic-network")?.optDouble("usd", 0.0) ?: 0.0
                if (legacy > 0.0) result["POL"] = legacy
            }
        }
        // Prices are cosmetic: if CoinGecko is blocked, quote the majors from Binance.
        if (result.isEmpty()) runCatching {
            val symbols = listOf("BTC", "ETH", "BNB", "POL", "TRX").joinToString(",") { "%22${it}USDT%22" }
            val array = JSONArray(WalletHttp.get("${CryptoEndpoints.BINANCE_PRICE}?symbols=[$symbols]"))
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val symbol = entry.optString("symbol").removeSuffix("USDT")
                val price = entry.optString("price").toDoubleOrNull() ?: 0.0
                if (price > 0.0) result[symbol] = price
            }
        }
        result.putIfAbsent("USDT", 1.0)
        result.putIfAbsent("USDC", 1.0)
        return result
    }

    private companion object {
        /** CoinGecko's free tier answers for a hundred contracts at a time. */
        const val PRICE_CHUNK = 100

        /** A ceiling on priced tokens, so a deep sweep never turns into a dozen price requests. */
        const val MAX_PRICED_TOKENS = 200
    }
}
