package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.AddressRules
import com.securevault.pro.crypto.ChainDescriptor
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.Hash
import com.securevault.pro.crypto.Secp256k1
import com.securevault.pro.crypto.TransactionCodec
import com.securevault.pro.crypto.TrustReport
import com.securevault.pro.crypto.WalletDeriver
import com.securevault.pro.domain.model.CryptoWallet
import com.securevault.pro.domain.model.TransferAsset
import com.securevault.pro.domain.model.TransferQuote
import com.securevault.pro.domain.model.TransferReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quotes, signs and broadcasts native/token transfers. Only public addresses and
 * signed raw transactions leave the device; keys are derived inside
 * [WalletRepository.signingKey] and never stored here.
 */
@Singleton
class CryptoTransferService @Inject constructor(
    private val wallets: WalletRepository
) {

    data class TokenSpec(
        val network: CryptoNetwork,
        val symbol: String,
        val name: String,
        val contract: String,
        val decimals: Int
    )

    /** Chain parameters all come from [ChainRegistry]; nothing is repeated here. */
    private data class EvmChain(
        val descriptor: ChainDescriptor,
        val network: CryptoNetwork,
        val rpcs: List<String>,
        val explorer: String,
        val chainId: Long,
        val fallbackGasPrice: BigInteger
    )

    private val fallbackGasPrices = mapOf(
        CryptoNetwork.ETHEREUM to BigInteger("20000000000"),
        CryptoNetwork.BSC to BigInteger("3000000000"),
        CryptoNetwork.POLYGON to BigInteger("50000000000")
    )

    private val evmChains: Map<CryptoNetwork, EvmChain> =
        ChainRegistry.all().filter { it.isEvm }.associate { descriptor ->
            descriptor.network to EvmChain(
                descriptor = descriptor,
                network = descriptor.network,
                rpcs = descriptor.rpcEndpoints,
                explorer = descriptor.txExplorer,
                chainId = descriptor.chainId ?: error("${descriptor.displayName} has no chain id"),
                fallbackGasPrice = fallbackGasPrices[descriptor.network]
                    ?: BigInteger("20000000000")
            )
        }

    companion object {
        val TOKEN_SPECS = listOf(
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

        private const val TRON_FEE_LIMIT_SUN = 30_000_000L

        /** The one Tron host the registry publishes; never a string assembled from user input. */
        val TRON_API: String = ChainRegistry.require(CryptoNetwork.TRON).rpcEndpoints.first()
    }

    fun tokensFor(network: CryptoNetwork): List<TokenSpec> =
        TOKEN_SPECS.filter { it.network == network }

    /** The contracts this app knows for a chain — the allowed set the trust gate checks against. */
    private fun knownTokens(network: CryptoNetwork): List<TransactionTrust.KnownToken> =
        TOKEN_SPECS.filter { it.network == network }
            .map { TransactionTrust.KnownToken(it.symbol, it.contract) }

    // ------------------------------------------------------------ validation

    /**
     * Null when the address is acceptable for this chain, otherwise the reason to show. The rule
     * itself is the registry's ([AddressRules.formatProblem]), so a chain added to the registry is
     * validated identically here and on the confirmation screen — and a chain without a descriptor
     * is refused rather than guessed at.
     */
    fun validateAddress(network: CryptoNetwork, address: String): String? =
        AddressRules.formatProblem(network, address)?.message

    /** Burn and null sinks are refused separately: a well-formed address can still be a hole. */
    fun burnProblem(network: CryptoNetwork, address: String): String? =
        AddressRules.burnProblem(network, address)?.message

    /** Where a broadcast can be read back, straight from the registry. */
    fun explorerFor(network: CryptoNetwork): String =
        ChainRegistry.descriptor(network)?.txExplorer.orEmpty()

    /** The chain line the confirmation screen prints: name, id, native coin, token standard. */
    private fun chainLabel(descriptor: ChainDescriptor): String = buildString {
        append(descriptor.displayName)
        append(" · ")
        append(descriptor.nativeSymbol)
        descriptor.chainId?.let { append(" · chain id $it") }
        if (descriptor.tokenStandard != "native") append(" · ${descriptor.tokenStandard}")
    }

    /**
     * The pre-signing gate: format, checksum, burn, self-send, chain/asset match, the chain id the
     * node itself reported, amount and fee. The report travels with the quote to the confirmation
     * screen, which shows every line, and back to [send], which refuses to sign an unaccepted one.
     */
    private fun trustGate(
        network: CryptoNetwork,
        asset: TransferAsset,
        from: String,
        to: String,
        amount: BigDecimal,
        available: Double,
        nativeAvailable: Double,
        fee: BigDecimal,
        nodeChainId: Long?
    ): TrustReport = TransactionTrust.inspect(
        TransactionTrust.Request(
            network = network,
            destination = to,
            fromAddress = from,
            assetSymbol = asset.symbol,
            assetContract = asset.contract,
            isNative = asset.isNative,
            amount = amount,
            availableBalance = available,
            nativeBalance = nativeAvailable,
            fee = fee,
            nodeChainId = nodeChainId,
            knownTokens = knownTokens(network)
        )
    )

    // ----------------------------------------------------------------- quote

    suspend fun quote(
        wallet: CryptoWallet,
        asset: TransferAsset,
        recipient: String,
        amountText: String
    ): Result<TransferQuote> = withContext(Dispatchers.IO) {
        runCatching {
            val network = CryptoNetwork.entries.firstOrNull { it.id == asset.networkId }
                ?: error("Unsupported network")
            validateAddress(network, recipient)?.let { error(it) }
            val to = recipient.trim()
            val amount = amountText.trim().toBigDecimalOrNull()
                ?: error("Enter a valid amount.")
            if (amount.signum() <= 0) error("Amount must be greater than zero.")
            val from = wallet.addresses.firstOrNull { it.network == network }?.address
                ?: error("This wallet has no ${network.displayName} address.")
            if (from.equals(to, ignoreCase = network != CryptoNetwork.BITCOIN)) {
                error("The recipient is the same as the sending address.")
            }
            when (network) {
                CryptoNetwork.BITCOIN -> quoteBitcoin(wallet, asset, from, to, amount)
                CryptoNetwork.TRON -> quoteTron(wallet, asset, from, to, amount)
                else -> quoteEvm(network, wallet, asset, from, to, amount)
            }
        }
    }

    private suspend fun quoteEvm(
        network: CryptoNetwork,
        wallet: CryptoWallet,
        asset: TransferAsset,
        from: String,
        to: String,
        amount: BigDecimal
    ): TransferQuote {
        val chain = evmChains[network] ?: error("No RPC configured for ${network.displayName}")
        val held = balanceOf(wallet, asset)
        if (held < amount.toDouble()) error("You only hold ${formatAmount(held)} ${asset.symbol}.")
        val units = unitsOf(amount, asset.decimals)
        val gasPrice = fetchGasPrice(chain)
        val gasLimit = BigInteger.valueOf(if (asset.isNative) 21_000L else 120_000L)
        val nonce = fetchNonce(chain.rpcs, from)
        // Asked of the same node the transaction will be broadcast to, so a host that answers for a
        // different chain id is caught before anything is signed, not after.
        val nodeChainId = fetchChainId(chain)
        val data = if (asset.isNative) ByteArray(0) else {
            TransactionCodec.erc20TransferData(asset.contract ?: error("Missing token contract"), units)
        }
        val value = if (asset.isNative) units else BigInteger.ZERO
        val feeWei = gasPrice.multiply(BigDecimal(gasLimit)).toBigInteger()
        val fee = BigDecimal(feeWei).movePointLeft(network.decimals)
        val nativeHeld = nativeBalance(wallet, network)
        if (nativeHeld.toDouble() < fee.toDouble()) {
            error("Not enough ${network.symbol} for the network fee (${fee.toPlainString()} ${network.symbol}).")
        }
        val nativePrice = nativePrice(wallet, network)
        val feeUsd = fee.toDouble() * nativePrice
        val report = trustGate(
            network = network,
            asset = asset,
            from = from,
            to = to,
            amount = amount,
            available = held,
            nativeAvailable = nativeHeld,
            fee = fee,
            nodeChainId = nodeChainId
        )
        if (!report.accepted) error(report.refusalReason())
        val totalLabel = if (asset.isNative) {
            "${formatAmount(amount.add(fee).toDouble())} ${asset.symbol} incl. fee"
        } else {
            "${formatAmount(amount.toDouble())} ${asset.symbol} + ${formatAmount(fee.toDouble())} ${network.symbol} fee"
        }
        return TransferQuote(
            networkId = network.id,
            networkName = network.displayName,
            symbol = asset.symbol,
            contract = asset.contract,
            isNative = asset.isNative,
            fromAddress = from,
            toAddress = to,
            amount = amount,
            amountUsd = amount.toDouble() * asset.priceUsd,
            fee = fee,
            feeSymbol = network.symbol,
            feeUsd = feeUsd,
            totalLabel = totalLabel,
            feeNote = "Gas ${gasPrice.toBigInteger()} wei × ${gasLimit.toInt()} limit",
            explorerBase = chain.explorer,
            warning = if (asset.priceUsd <= 0.0) "USD price unavailable · showing live on-chain units." else null,
            trust = report,
            chainLabel = chainLabel(chain.descriptor),
            evmChainId = chain.chainId,
            evmNonce = BigInteger.valueOf(nonce),
            evmGasPrice = gasPrice.toBigInteger(),
            evmGasLimit = gasLimit,
            evmValue = value,
            evmData = data
        )
    }

    private suspend fun quoteBitcoin(
        wallet: CryptoWallet,
        asset: TransferAsset,
        from: String,
        to: String,
        amount: BigDecimal
    ): TransferQuote {
        if (!asset.isNative) error("Only Bitcoin can be sent from this wallet.")
        val amountSats = satoshis(amount)
        if (amountSats <= 0) error("Amount is below one satoshi.")
        val toScript = TransactionCodec.bitcoinAddressScript(to)
            ?: error("The recipient address could not be decoded.")
        val changeScript = TransactionCodec.bitcoinAddressScript(from)
            ?: error("This wallet's own address could not be decoded.")
        val feeRate = fetchBitcoinFeeRate()
        val utxos = fetchUtxos(from)
        if (utxos.isEmpty()) error("No spendable Bitcoin found at this address.")
        val legacy = !wallet.isSeedPhrase
        var selected = mutableListOf<TransactionCodec.BtcUtxo>()
        var feeSats = 0L
        for (utxo in utxos.sortedByDescending { it.valueSats }) {
            selected += utxo
            val outputs = if (selected.sumOf { it.valueSats } - amountSats - feeRate *
                    TransactionCodec.bitcoinVsize(selected.size, 2) >= 546L
            ) 2 else 1
            val vsize = if (legacy) {
                TransactionCodec.bitcoinLegacyVsize(selected.size, outputs)
            } else {
                TransactionCodec.bitcoinVsize(selected.size, outputs)
            }
            feeSats = feeRate * vsize
            if (selected.sumOf { it.valueSats } >= amountSats + feeSats) break
        }
        if (selected.sumOf { it.valueSats } < amountSats + feeSats) {
            val available = selected.sumOf { it.valueSats }
            val spendable = (available - feeSats).coerceAtLeast(0)
            error("Not enough Bitcoin after the ${feeSats} sat network fee. Max ≈ ${formatAmount(spendable / 100_000_000.0)} BTC.")
        }
        var change = selected.sumOf { it.valueSats } - amountSats - feeSats
        if (change in 1L..545L) {
            feeSats += change
            change = 0
        }
        val price = asset.priceUsd
        val btcFee = BigDecimal.valueOf(feeSats).movePointLeft(8)
        val report = trustGate(
            network = CryptoNetwork.BITCOIN,
            asset = asset,
            from = from,
            to = to,
            amount = amount,
            available = asset.amount,
            nativeAvailable = asset.amount,
            fee = btcFee,
            nodeChainId = null
        )
        if (!report.accepted) error(report.refusalReason())
        return TransferQuote(
            networkId = CryptoNetwork.BITCOIN.id,
            networkName = CryptoNetwork.BITCOIN.displayName,
            symbol = "BTC",
            contract = null,
            isNative = true,
            fromAddress = from,
            toAddress = to,
            amount = amount,
            amountUsd = amount.toDouble() * price,
            fee = BigDecimal.valueOf(feeSats).movePointLeft(8),
            feeSymbol = "BTC",
            feeUsd = BigDecimal.valueOf(feeSats).movePointLeft(8).toDouble() * price,
            totalLabel = "${formatAmount(amount.add(BigDecimal.valueOf(feeSats).movePointLeft(8)).toDouble())} BTC incl. fee",
            feeNote = "$feeSats sats · ${feeRate} sat/vB · ${selected.size} input${if (selected.size == 1) "" else "s"}",
            explorerBase = ChainRegistry.require(CryptoNetwork.BITCOIN).txExplorer,
            trust = report,
            chainLabel = chainLabel(ChainRegistry.require(CryptoNetwork.BITCOIN)),
            btcUtxos = selected,
            btcToScript = toScript,
            btcChangeScript = changeScript,
            btcAmountSats = amountSats,
            btcFeeSats = feeSats,
            btcFeeRate = feeRate,
            btcLegacy = legacy
        )
    }

    private fun quoteTron(
        wallet: CryptoWallet,
        asset: TransferAsset,
        from: String,
        to: String,
        amount: BigDecimal
    ): TransferQuote {
        val held = balanceOf(wallet, asset)
        if (held < amount.toDouble()) error("You only hold ${formatAmount(held)} ${asset.symbol}.")
        val native = nativeBalance(wallet, CryptoNetwork.TRON)
        val feeTrx = if (asset.isNative) BigDecimal("1.1") else BigDecimal.ZERO
        if (asset.isNative && native < amount.add(feeTrx).toDouble()) {
            error("Not enough TRX for the amount and network fee.")
        }
        if (!asset.isNative && native < 1.0) {
            error("Keep at least 1 TRX for Tron bandwidth and energy.")
        }
        val price = nativePrice(wallet, CryptoNetwork.TRON)
        val report = trustGate(
            network = CryptoNetwork.TRON,
            asset = asset,
            from = from,
            to = to,
            amount = amount,
            available = held,
            nativeAvailable = native,
            fee = feeTrx,
            nodeChainId = null
        )
        if (!report.accepted) error(report.refusalReason())
        return TransferQuote(
            networkId = CryptoNetwork.TRON.id,
            networkName = CryptoNetwork.TRON.displayName,
            symbol = asset.symbol,
            contract = asset.contract,
            isNative = asset.isNative,
            fromAddress = from,
            toAddress = to,
            amount = amount,
            amountUsd = amount.toDouble() * asset.priceUsd,
            fee = feeTrx,
            feeSymbol = "TRX",
            feeUsd = feeTrx.toDouble() * price,
            totalLabel = if (asset.isNative) {
                "${formatAmount(amount.add(feeTrx).toDouble())} TRX incl. fee"
            } else {
                "${formatAmount(amount.toDouble())} ${asset.symbol} + energy"
            },
            feeNote = if (asset.isNative) {
                "Bandwidth / account activation · ≈1.1 TRX"
            } else {
                "Energy cap 30 TRX · unused energy is refunded by the network"
            },
            explorerBase = "https://tronscan.org/#/transaction/",
            warning = if (!asset.isNative) "TRC-20 sends may burn up to 30 TRX for energy." else null
        )
    }

    // ------------------------------------------------------------------ send

    suspend fun send(wallet: CryptoWallet, quote: TransferQuote): Result<TransferReceipt> =
        withContext(Dispatchers.IO) {
            runCatching {
                val network = CryptoNetwork.entries.firstOrNull { it.id == quote.networkId }
                    ?: error("Unsupported network")
                // The gate re-runs on the quote's own inputs, so a stale or hand-edited quote is
                // refused here as well as on the review screen. Nothing is signed past this line.
                if (!quote.trust.accepted) error(quote.trust.refusalReason())
                AddressRules.formatProblem(network, quote.toAddress)?.let { error(it.message) }
                AddressRules.burnProblem(network, quote.toAddress)?.let { error(it.message) }
                ChainRegistry.descriptor(network)?.takeIf { it.isEvm }?.let { descriptor ->
                    if (quote.evmChainId != descriptor.chainId) {
                        error(
                            "This quote was built for chain id ${quote.evmChainId}; " +
                                "${descriptor.displayName} is chain id ${descriptor.chainId}. " +
                                "Review the transfer again."
                        )
                    }
                }
                val key = wallets.signingKey(wallet.id, network)
                    ?: error("This wallet's key is unavailable. Unlock the vault and try again.")
                when (network) {
                    CryptoNetwork.BITCOIN -> sendBitcoin(key, quote)
                    CryptoNetwork.TRON -> sendTron(key, quote)
                    else -> sendEvm(network, key, quote)
                }
            }
        }

    private fun sendEvm(
        network: CryptoNetwork,
        key: BigInteger,
        quote: TransferQuote
    ): TransferReceipt {
        val chain = evmChains[network] ?: error("Unsupported network")
        val raw = TransactionCodec.signEvmLegacy(
            chainId = quote.evmChainId,
            nonce = quote.evmNonce,
            gasPrice = quote.evmGasPrice,
            gasLimit = quote.evmGasLimit,
            to = quote.contract ?: quote.toAddress,
            value = quote.evmValue,
            data = quote.evmData,
            privateKey = key
        )
        val result = rpcCall(chain.rpcs, "eth_sendRawTransaction", JSONArray().put(raw))
            ?: error("The network rejected the transaction.")
        return TransferReceipt(
            hash = result,
            explorerUrl = chain.explorer + result,
            networkName = network.displayName,
            symbol = quote.symbol,
            amount = quote.amount
        )
    }

    private fun sendBitcoin(key: BigInteger, quote: TransferQuote): TransferReceipt {
        val point = Secp256k1.multiply(key)
        val publicKey = Secp256k1.compress(point)
        val publicKeyHash = Hash.hash160(publicKey)
        val raw = if (quote.btcLegacy) {
            TransactionCodec.signP2pkhTransaction(
                utxos = quote.btcUtxos,
                toScript = quote.btcToScript,
                changeScript = quote.btcChangeScript,
                amountSats = quote.btcAmountSats,
                feeSats = quote.btcFeeSats,
                privateKey = key,
                publicKey = publicKey
            )
        } else {
            TransactionCodec.signP2wpkhTransaction(
                utxos = quote.btcUtxos,
                toScript = quote.btcToScript,
                changeScript = quote.btcChangeScript,
                amountSats = quote.btcAmountSats,
                feeSats = quote.btcFeeSats,
                privateKey = key,
                publicKey = publicKey,
                publicKeyHash = publicKeyHash
            )
        }
        val response = bitcoinPost("/api/tx", raw, "text/plain")
        val hash = response.trim()
        if (!Regex("^[0-9a-fA-F]{64}$").matches(hash)) {
            error(response.take(160).ifBlank { "The Bitcoin network rejected the transaction." })
        }
        return TransferReceipt(
            hash = hash,
            explorerUrl = "${CryptoEndpoints.bitcoinApi.first()}/tx/$hash",
            networkName = "Bitcoin",
            symbol = "BTC",
            amount = quote.amount
        )
    }

    private fun sendTron(key: BigInteger, quote: TransferQuote): TransferReceipt {
        val transaction = if (quote.isNative) {
            val body = JSONObject()
                .put("owner_address", quote.fromAddress)
                .put("to_address", quote.toAddress)
                .put("amount", tronSun(quote.amount).toLong())
                .put("visible", true)
            JSONObject(httpPost("$TRON_API/wallet/createtransaction", body.toString(), "application/json"))
        } else {
            val recipient = com.securevault.pro.crypto.Base58.decodeCheck(quote.toAddress)
                ?: error("Invalid Tron recipient")
            val parameter = Hash.hex(recipient.copyOfRange(1, 21)).padStart(64, '0') +
                unitsOf(quote.amount, 6).toString(16).padStart(64, '0')
            val body = JSONObject()
                .put("owner_address", quote.fromAddress)
                .put("contract_address", quote.contract ?: error("Missing TRC-20 contract"))
                .put("function_selector", "transfer(address,uint256)")
                .put("parameter", parameter)
                .put("fee_limit", TRON_FEE_LIMIT_SUN)
                .put("call_value", 0)
                .put("visible", true)
            val root = JSONObject(
                httpPost("$TRON_API/wallet/triggersmartcontract", body.toString(), "application/json")
            )
            val result = root.optJSONObject("result")
            if (result != null && !result.optBoolean("result", true)) {
                error(result.optString("message", "Tron rejected the contract call"))
            }
            root.optJSONObject("transaction") ?: error("Tron returned no transaction")
        }
        val txId = transaction.optString("txID")
        if (txId.isBlank()) error("Tron returned no transaction id")
        val rawBytes = Hash.bytesFromHex(txId) ?: error("Malformed Tron transaction id")
        val signature = TransactionCodec.tronSignature(rawBytes, key)
        val broadcast = JSONObject()
            .put("visible", true)
            .put("txID", txId)
            .put("raw_data", transaction.optJSONObject("raw_data"))
            .put("raw_data_hex", transaction.optString("raw_data_hex"))
            .put("signature", JSONArray().put(signature))
        val response = JSONObject(
            httpPost("$TRON_API/wallet/broadcasttransaction", broadcast.toString(), "application/json")
        )
        if (!response.optBoolean("result", false)) {
            error(response.optString("message", response.optString("code", "Tron rejected the transaction")))
        }
        return TransferReceipt(
            hash = txId,
            explorerUrl = "https://tronscan.org/#/transaction/$txId",
            networkName = "Tron",
            symbol = quote.symbol,
            amount = quote.amount
        )
    }

    // ---------------------------------------------------------------- fetches

    private fun fetchGasPrice(chain: EvmChain): BigDecimal {
        val raw = runCatching { rpcCall(chain.rpcs, "eth_gasPrice", JSONArray()) }.getOrNull()
        val price = raw?.removePrefix("0x")?.toBigIntegerOrNull(16)
        return BigDecimal(price ?: chain.fallbackGasPrice)
    }

    /**
     * `eth_chainId` from the node that will be asked to broadcast. Null when no host answered — the
     * trust gate then reports the chain id it used from the registry rather than inventing a match.
     */
    private fun fetchChainId(chain: EvmChain): Long? =
        runCatching { rpcCall(chain.rpcs, "eth_chainId", JSONArray()) }.getOrNull()
            ?.removePrefix("0x")?.toBigIntegerOrNull(16)?.toLong()

    private fun fetchNonce(rpcs: List<String>, address: String): Long {
        val raw = rpcCall(rpcs, "eth_getTransactionCount", JSONArray().put(address).put("pending"))
            ?: return 0L
        return raw.removePrefix("0x").toLongOrNull(16) ?: 0L
    }

    private fun fetchBitcoinFeeRate(): Long {
        CryptoEndpoints.bitcoinApi.forEach { base ->
            val root = runCatching { JSONObject(httpGet("$base/api/v1/fees/recommended")) }.getOrNull()
            val rate = root?.optLong("halfHourFee", 0L) ?: 0L
            val fallback = root?.optLong("fastestFee", 0L) ?: 0L
            val chosen = listOf(rate, fallback).firstOrNull { it > 0 }
            if (chosen != null) return chosen
        }
        return 12L
    }

    private fun fetchUtxos(address: String): List<TransactionCodec.BtcUtxo> {
        var lastFailure: Throwable? = null
        CryptoEndpoints.bitcoinApi.forEach { base ->
            runCatching {
                val array = JSONArray(httpGet("$base/api/address/$address/utxo"))
                return buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val status = item.optJSONObject("status")
                        if (status != null && !status.optBoolean("confirmed", true)) continue
                        val value = item.optLong("value", 0L)
                        if (value <= 0L) continue
                        add(
                            TransactionCodec.BtcUtxo(
                                txid = item.optString("txid"),
                                vout = item.optInt("vout"),
                                valueSats = value
                            )
                        )
                    }
                }
            }.onFailure { lastFailure = it }
        }
        error(
            lastFailure?.message?.takeIf { it.isNotBlank() }
                ?: "No Bitcoin node answered for this address. Refresh the balance and try again."
        )
    }

    /** Posts raw data to the first Bitcoin endpoint that answers. */
    private fun bitcoinPost(path: String, body: String, contentType: String): String {
        var lastFailure: Throwable? = null
        CryptoEndpoints.bitcoinApi.forEach { base ->
            runCatching { return httpPost("$base$path", body, contentType) }
                .onFailure { lastFailure = it }
        }
        error(
            lastFailure?.message?.takeIf { it.isNotBlank() }
                ?: "No Bitcoin node accepted the transaction. Check your connection and try again."
        )
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
                val root = JSONObject(httpPost(endpoint, body, "application/json"))
                if (root.has("error")) {
                    error(root.optJSONObject("error")?.optString("message") ?: "RPC error")
                }
                val result = root.optString("result")
                result.takeIf { it.isNotBlank() && it != "null" }
            }.onSuccess { return it }
                .onFailure { lastFailure = it }
        }
        error(
            lastFailure?.message?.takeIf { it.isNotBlank() }
                ?: "No public node answered for this network. Refresh the wallet balance and try again."
        )
    }

    private fun httpGet(url: String): String = request(url, "GET", null, "application/json")

    private fun httpPost(url: String, body: String, contentType: String): String =
        request(url, "POST", body, contentType)

    private fun request(url: String, method: String, body: String?, contentType: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SecureWalletPro/3.1")
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code · ${text.take(140)}")
            text
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun balanceOf(wallet: CryptoWallet, asset: TransferAsset): Double =
        wallet.balances.firstOrNull {
            it.networkId == asset.networkId && it.symbol.equals(asset.symbol, true)
        }?.amount ?: 0.0

    private fun nativeBalance(wallet: CryptoWallet, network: CryptoNetwork): Double =
        wallet.balances.firstOrNull {
            it.networkId == network.id && it.symbol == network.symbol
        }?.amount ?: 0.0

    private fun nativePrice(wallet: CryptoWallet, network: CryptoNetwork): Double =
        wallet.balances.firstOrNull {
            it.networkId == network.id && it.symbol == network.symbol
        }?.priceUsd ?: 0.0

    private fun unitsOf(amount: BigDecimal, decimals: Int): BigInteger {
        return try {
            amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Use at most $decimals decimal places.")
        }
    }

    private fun satoshis(amount: BigDecimal): Long {
        val scaled = amount.movePointRight(8)
        if (scaled.stripTrailingZeros().scale() > 0) {
            throw IllegalArgumentException("Bitcoin supports at most 8 decimal places.")
        }
        return scaled.setScale(0, RoundingMode.DOWN).toLong()
    }

    private fun tronSun(amount: BigDecimal): BigInteger = unitsOf(amount, 6)

    private fun formatAmount(value: Double): String {
        if (!value.isFinite()) return "0"
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
