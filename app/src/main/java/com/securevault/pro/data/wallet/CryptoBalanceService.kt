package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.WalletAddress
import com.securevault.pro.domain.model.BalanceScan
import com.securevault.pro.domain.model.TokenBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only balance scan against public indexers/RPCs. No key material ever leaves
 * the device — only public addresses are queried.
 */
@Singleton
class CryptoBalanceService @Inject constructor() {

    private data class TokenSpec(
        val network: CryptoNetwork,
        val symbol: String,
        val name: String,
        val contract: String,
        val decimals: Int
    )

    private val tokenSpecs = listOf(
        TokenSpec(CryptoNetwork.ETHEREUM, "USDT", "Tether USD", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6),
        TokenSpec(CryptoNetwork.ETHEREUM, "SHIB", "Shiba Inu", "0x95aD61b0a150d79219dCF64E1E6Cc01f0B64C4cE", 18),
        TokenSpec(CryptoNetwork.ETHEREUM, "USDC", "USD Coin", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6),
        TokenSpec(CryptoNetwork.BSC, "USDT", "Tether USD", "0x55d398326f99059fF775485246999027B3197955", 18),
        TokenSpec(CryptoNetwork.BSC, "USDC", "USD Coin", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", 18),
        TokenSpec(CryptoNetwork.BSC, "SHIB", "Shiba Inu", "0x2859e4544C4bB03966803b044A93563Bd2D0DD4D", 18),
        TokenSpec(CryptoNetwork.POLYGON, "USDC", "USD Coin", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", 6),
        TokenSpec(CryptoNetwork.POLYGON, "USDT", "Tether USD", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", 6),
        TokenSpec(CryptoNetwork.TRON, "USDT", "Tether USD", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6)
    )

    private val rpcEndpoints = CryptoEndpoints.evmRpc

    private val priceIds = mapOf(
        "BTC" to "bitcoin",
        "ETH" to "ethereum",
        "BNB" to "binancecoin",
        "POL" to "polygon-ecosystem-token",
        "TRX" to "tron",
        "USDT" to "tether",
        "USDC" to "usd-coin",
        "SHIB" to "shiba-inu"
    )

    suspend fun scan(
        addresses: List<WalletAddress>,
        cachedTokens: List<TokenBalance> = emptyList()
    ): BalanceScan = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext BalanceScan(emptyList())
        val prices = fetchPrices(cachedTokens.associate { it.symbol to it.priceUsd })
        val results = coroutineScope {
            addresses.map { address ->
                async { address to runCatching { scanNetwork(address, prices) } }
            }.awaitAll()
        }
        val tokens = mutableListOf<TokenBalance>()
        val errors = LinkedHashMap<String, String>()
        results.forEach { (address, result) ->
            result.onSuccess { tokens += it }
                .onFailure { failure ->
                    val message = failure.message?.takeIf { it.isNotBlank() }
                        ?: "${address.network.displayName} nodes did not answer. " +
                            "Check your connection and refresh in a moment."
                    errors[address.network.id] = message
                }
        }
        BalanceScan(tokens, errors)
    }

    /**
     * USD quotes for arbitrary ticker symbols, from the same keyless sources the balance scan uses.
     * Public so the read-only Bitget panel can price exchange holdings without inventing a second
     * price feed. Symbols this app does not already know are quoted against USDT on Binance; a
     * symbol nothing answers for is simply absent, and the caller shows the amount without a value.
     */
    suspend fun usdPrices(symbols: Collection<String>): Map<String, Double> = withContext(Dispatchers.IO) {
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
            val array = JSONArray(httpGet("${CryptoEndpoints.BINANCE_PRICE}?symbols=[$pairs]"))
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

    private fun scanNetwork(address: WalletAddress, prices: Map<String, Double>): List<TokenBalance> {
        return when (address.network) {
            CryptoNetwork.BITCOIN -> listOf(bitcoinBalance(address, prices))
            CryptoNetwork.TRON -> tronBalances(address, prices)
            CryptoNetwork.ETHEREUM, CryptoNetwork.BSC, CryptoNetwork.POLYGON -> evmBalances(address, prices)
        }
    }

    private fun bitcoinBalance(address: WalletAddress, prices: Map<String, Double>): TokenBalance {
        var lastFailure: Throwable? = null
        CryptoEndpoints.bitcoinApi.forEach { base ->
            runCatching {
                val root = JSONObject(httpGet("$base/api/address/${address.address}"))
                val confirmed = root.optJSONObject("chain_stats")
                val pending = root.optJSONObject("mempool_stats")
                val sats = (confirmed?.optLong("funded_txo_sum", 0L) ?: 0L) -
                    (confirmed?.optLong("spent_txo_sum", 0L) ?: 0L) +
                    (pending?.optLong("funded_txo_sum", 0L) ?: 0L) -
                    (pending?.optLong("spent_txo_sum", 0L) ?: 0L)
                return token("BTC", "Bitcoin", sats.toDouble() / 100_000_000.0, address, prices)
            }.onFailure { lastFailure = it }
        }
        // BlockCypher uses a different payload, so it is only a last resort.
        runCatching {
            val root = JSONObject(
                httpGet("${CryptoEndpoints.BITCOIN_BALANCE_FALLBACK}${address.address}/balance")
            )
            val sats = root.optLong("final_balance", root.optLong("balance", 0L))
            return token("BTC", "Bitcoin", sats.toDouble() / 100_000_000.0, address, prices)
        }.onFailure { lastFailure = it }
        error(describeFailure("Bitcoin", lastFailure))
    }

    private fun token(
        symbol: String,
        name: String,
        amount: Double,
        address: WalletAddress,
        prices: Map<String, Double>
    ): TokenBalance = TokenBalance(
        networkId = address.network.id,
        networkName = address.network.displayName,
        symbol = symbol,
        name = name,
        amount = amount,
        priceUsd = prices[symbol] ?: 0.0
    )

    private fun evmBalances(address: WalletAddress, prices: Map<String, Double>): List<TokenBalance> {
        val network = address.network
        val rpc = rpcEndpoints[network] ?: error("No RPC for ${network.displayName}")
        val nativeRaw = rpcCall(
            rpc,
            "eth_getBalance",
            JSONArray().put(address.address).put("latest")
        ) ?: error(describeFailure(network.displayName, null))
        val nativeAmount = hexToDecimal(nativeRaw, network.decimals)
        val out = mutableListOf(
            TokenBalance(
                networkId = network.id,
                networkName = network.displayName,
                symbol = network.symbol,
                name = network.displayName,
                amount = nativeAmount,
                priceUsd = prices[network.symbol] ?: 0.0
            )
        )
        tokenSpecs.filter { it.network == network }.forEach { spec ->
            runCatching {
                val data = "0x70a08231" + address.address.removePrefix("0x").lowercase().padStart(64, '0')
                val call = JSONObject()
                    .put("to", spec.contract)
                    .put("data", data)
                val raw = rpcCall(rpc, "eth_call", JSONArray().put(call).put("latest"))
                val amount = if (raw.isNullOrBlank()) 0.0 else hexToDecimal(raw, spec.decimals)
                out += TokenBalance(
                    networkId = network.id,
                    networkName = network.displayName,
                    symbol = spec.symbol,
                    name = spec.name,
                    amount = amount,
                    priceUsd = prices[spec.symbol] ?: 0.0
                )
            }
        }
        return out
    }

    private fun tronBalances(address: WalletAddress, prices: Map<String, Double>): List<TokenBalance> {
        var lastFailure: Throwable? = null
        CryptoEndpoints.tronApi.forEach { base ->
            runCatching {
                return parseTronAccount(
                    JSONObject(httpGet("$base/v1/accounts/${address.address}")),
                    address,
                    prices
                )
            }.onFailure { lastFailure = it }
        }
        error(describeFailure("Tron", lastFailure))
    }

    private fun parseTronAccount(
        root: JSONObject,
        address: WalletAddress,
        prices: Map<String, Double>
    ): List<TokenBalance> {
        val data = root.optJSONArray("data")
        val account = data?.optJSONObject(0)
        val sun = account?.optLong("balance", 0L) ?: 0L
        val out = mutableListOf(
            TokenBalance(
                networkId = address.network.id,
                networkName = address.network.displayName,
                symbol = "TRX",
                name = "Tron",
                amount = sun.toDouble() / 1_000_000.0,
                priceUsd = prices["TRX"] ?: 0.0
            )
        )
        val trc20 = account?.optJSONArray("trc20")
        if (trc20 != null) {
            for (index in 0 until trc20.length()) {
                val entry = trc20.optJSONObject(index) ?: continue
                tokenSpecs.filter { it.network == CryptoNetwork.TRON }.forEach { spec ->
                    val raw = entry.optString(spec.contract)
                    if (raw.isNotBlank()) {
                        out += TokenBalance(
                            networkId = address.network.id,
                            networkName = address.network.displayName,
                            symbol = spec.symbol,
                            name = spec.name,
                            amount = raw.toBigDecimalOrNull()
                                ?.divide(BigDecimal.TEN.pow(spec.decimals))?.toDouble() ?: 0.0,
                            priceUsd = prices[spec.symbol] ?: 0.0
                        )
                    }
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
            val root = JSONObject(httpGet(url))
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
            val symbols = listOf("BTC", "ETH", "BNB", "POL", "TRX")
                .joinToString(",") { "%22${it}USDT%22" }
            val array = JSONArray(httpGet("${CryptoEndpoints.BINANCE_PRICE}?symbols=[$symbols]"))
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

    private fun rpcCall(endpoints: List<String>, method: String, params: JSONArray): String? {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()
        var lastFailure: Throwable? = null
        endpoints.forEach { endpoint ->
            runCatching {
                val root = JSONObject(httpPost(endpoint, body))
                if (root.has("error")) {
                    error(root.optJSONObject("error")?.optString("message") ?: "RPC error")
                }
                val result = root.optString("result")
                result.takeIf { it.isNotBlank() && it != "null" }
            }.onSuccess { return it }
                .onFailure { lastFailure = it }
        }
        error(describeFailure("RPC", lastFailure))
    }

    /** Turns a transport failure into something a user can act on. */
    private fun describeFailure(label: String, cause: Throwable?): String {
        val message = cause?.message.orEmpty()
        val host = Regex("//([^/]+)").find(message)?.groupValues?.getOrNull(1)
        return when {
            message.isBlank() -> "$label nodes did not answer. Check your connection and refresh again."
            message.contains("Unable to resolve host", true) ||
                message.contains("ENOTFOUND", true) ||
                host != null -> "$label nodes are unreachable from this network. Refresh in a moment or switch to mobile data."
            message.contains("rate", true) || message.contains("429", true) ->
                "$label public nodes are rate limited right now. Wait a minute and refresh again."
            else -> "$label lookup failed: ${message.take(120)}"
        }
    }

    private fun hexToDecimal(hex: String, decimals: Int): Double {
        val clean = hex.removePrefix("0x").ifBlank { "0" }
        val value = clean.toBigIntegerOrNull(16) ?: return 0.0
        return BigDecimal(value).divide(BigDecimal.TEN.pow(decimals)).toDouble()
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SecureWalletPro/3.0")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SecureWalletPro/3.0")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            text
        } finally {
            connection.disconnect()
        }
    }
}
