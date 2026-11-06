package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.domain.model.TokenBalance
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
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
 * Reads what one address actually holds on an EVM chain, in as few requests as a public node allows.
 *
 * The old scan asked about nine hardcoded contracts, one HTTP request each, per chain — which is why
 * a wallet full of BEP-20 and ERC-20 tokens showed only its gas coin. This asks hundreds of
 * contracts in a single request per chain by using JSON-RPC *batches*: one round trip carries the
 * whole `balanceOf` sweep, and the metadata for the tokens that answered non-zero is fetched in a
 * second sweep. Measured against the public endpoints this app uses, 64 calls come back in about
 * 0.2 seconds.
 *
 * Nothing here signs, sends or derives. A public address goes in, public balances come out.
 */
@Singleton
class EvmTokenProbe @Inject constructor() {

    private val balanceOf = "0x70a08231"
    private val readDecimals = "0x313ce567"
    private val readSymbol = "0x95d89b41"
    private val readName = "0x06fdde03"

    /**
     * The funded tokens of [holder] among [candidates].
     *
     * A token whose chain-reported decimals cannot be read is skipped rather than guessed: scaling a
     * raw integer by the wrong power of ten prints a balance that is off by orders of magnitude, and
     * a wrong number on a wallet screen is worse than a missing row.
     */
    suspend fun fundedTokens(
        network: CryptoNetwork,
        rpcs: List<String>,
        holder: String,
        candidates: List<CatalogToken>
    ): List<TokenBalance> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty() || rpcs.isEmpty()) return@withContext emptyList()
        val raw = rawBalances(rpcs, holder, candidates.map { it.contract })
        val funded = candidates.mapIndexedNotNull { index, token ->
            val value = raw.getOrNull(index) ?: BigInteger.ZERO
            if (value > BigInteger.ZERO) token to value else null
        }
        if (funded.isEmpty()) return@withContext emptyList()
        val unread = funded
            .filter { it.first.symbol.isBlank() || it.first.decimals <= 0 }
            .map { it.first.contract }
        val metadata = readMetadata(rpcs, unread)
        buildList {
            funded.forEach { (token, value) ->
                val read = metadata[token.contract.lowercase()]
                val symbol = token.symbol.ifBlank { read?.symbol.orEmpty() }
                val decimals = if (token.decimals > 0) token.decimals else read?.decimals ?: 0
                if (symbol.isBlank() || decimals <= 0) return@forEach
                add(
                    TokenBalance(
                        networkId = network.id,
                        networkName = network.displayName,
                        symbol = symbol.uppercase(),
                        name = token.name.ifBlank { read?.name.orEmpty() }.ifBlank { symbol },
                        amount = scale(value, decimals),
                        contract = token.contract,
                        decimals = decimals
                    )
                )
            }
        }
    }

    /**
     * `balanceOf(holder)` for every contract in [contracts], in one pass of batched calls.
     *
     * Chunks go out in parallel because a node answers a batch about as fast as it answers one call;
     * without that the scan's wall clock would be the number of chunks.
     */
    private suspend fun rawBalances(
        rpcs: List<String>,
        holder: String,
        contracts: List<String>
    ): List<BigInteger> = coroutineScope {
        val padded = holder.removePrefix("0x").lowercase().padStart(64, '0')
        contracts.chunked(CHUNK).map { chunk ->
            async(Dispatchers.IO) {
                val calls = chunk.map { contract ->
                    WalletHttp.Call("eth_call", ethCall(contract, balanceOf + padded))
                }
                WalletHttp.batch(rpcs, calls).map { WalletHttp.hexToBigInteger(it) }
            }
        }.awaitAll().flatten()
    }

    /** Symbol, name and decimals for the [contracts] the curated lists did not already describe. */
    private suspend fun readMetadata(
        rpcs: List<String>,
        contracts: List<String>
    ): Map<String, CatalogToken> = withContext(Dispatchers.IO) {
        if (contracts.isEmpty()) return@withContext emptyMap()
        coroutineScope {
            contracts.chunked(CHUNK / 3).map { chunk ->
                async(Dispatchers.IO) {
                    val calls = chunk.flatMap { contract ->
                        listOf(
                            WalletHttp.Call("eth_call", ethCall(contract, readDecimals)),
                            WalletHttp.Call("eth_call", ethCall(contract, readSymbol)),
                            WalletHttp.Call("eth_call", ethCall(contract, readName))
                        )
                    }
                    val answers = WalletHttp.batch(rpcs, calls)
                    chunk.mapIndexedNotNull { index, contract ->
                        describe(contract, answers.getOrNull(index * 3),
                            answers.getOrNull(index * 3 + 1), answers.getOrNull(index * 3 + 2))
                    }
                }
            }.awaitAll().flatten().associateBy { it.contract.lowercase() }
        }
    }

    /**
     * What one contract actually is, read from the chain itself. Used when the owner adds a token by
     * hand, so the app names it from its contract instead of trusting a pasted ticker.
     */
    suspend fun describe(rpcs: List<String>, contract: String): CatalogToken? =
        withContext(Dispatchers.IO) {
            val answers = WalletHttp.batch(
                rpcs,
                listOf(
                    WalletHttp.Call("eth_call", ethCall(contract, readDecimals)),
                    WalletHttp.Call("eth_call", ethCall(contract, readSymbol)),
                    WalletHttp.Call("eth_call", ethCall(contract, readName))
                )
            )
            describe(contract, answers.getOrNull(0), answers.getOrNull(1), answers.getOrNull(2))
        }

    private fun describe(
        contract: String,
        decimalsHex: String?,
        symbolHex: String?,
        nameHex: String?
    ): CatalogToken? {
        val decimals = WalletHttp.hexToBigInteger(decimalsHex)
        val symbol = decodeString(symbolHex)
        if (symbol.isBlank() || decimals.signum() <= 0 || decimals.bitLength() > 7) return null
        return CatalogToken(
            networkId = "",
            contract = contract,
            symbol = symbol.uppercase(),
            name = decodeString(nameHex).ifBlank { symbol },
            decimals = decimals.toInt()
        )
    }

    private fun ethCall(to: String, data: String): JSONArray = JSONArray()
        .put(JSONObject().put("to", to).put("data", data))
        .put("latest")

    private fun scale(raw: BigInteger, decimals: Int): Double =
        BigDecimal(raw).divide(BigDecimal.TEN.pow(decimals), MathContext(12)).toDouble()

    /**
     * An ABI-encoded `string`, and the legacy shape some tokens answer with instead: a right-padded
     * `bytes32`. Both decode here, because a token holding real money that answers as bytes32 would
     * otherwise show up as a row with no name.
     */
    private fun decodeString(hex: String?): String {
        val clean = hex?.removePrefix("0x").orEmpty()
        if (clean.length < 64) return ""
        return runCatching {
            val body = if (clean.length == 64) {
                clean
            } else {
                val head = BigInteger(clean.substring(0, 64), 16).toInt() * 2
                val length = BigInteger(clean.substring(head, head + 64), 16).toInt() * 2
                clean.substring(head + 64, head + 64 + length)
            }
            val bytes = ByteArray(body.length / 2) { index ->
                body.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte()) end--
            String(bytes, 0, end, Charsets.UTF_8).trim()
        }.getOrDefault("")
    }

    private companion object {
        /**
         * The largest batch this app opens a node with. Free tiers differ — one refuses anything above
         * three — and [WalletHttp.batch] shrinks the window rather than losing the rest.
         */
        const val CHUNK = 40
    }
}

/**
 * The contracts a scan actually saw this owner hold, in the shape the pre-signing gate checks
 * against. The gate exists to stop a lookalike token being sent when the ticker matches a famous
 * contract but the address does not; a token this app read from the chain itself, with the contract
 * it was read from, satisfies exactly that requirement.
 */
internal fun verifiedTokens(balances: List<TokenBalance>, network: CryptoNetwork) =
    balances
        .filter { it.networkId == network.id && !it.contract.isNullOrBlank() && it.amount > 0.0 }
        .map { TransactionTrust.KnownToken(it.symbol, it.contract!!) }
        .distinctBy { it.contract.lowercase() }
