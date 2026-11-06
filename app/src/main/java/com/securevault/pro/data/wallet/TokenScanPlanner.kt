package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.domain.model.TokenBalance

/**
 * Which contracts a scan should actually read, and in what order.
 *
 * Pure Kotlin over plain values — no JSON, no network — so the rule that decides what the owner sees
 * is the rule the JVM tests assert against. The order matters more than it looks: a public node will
 * only carry so many calls in one request, so the window has to hold the tokens most likely to be
 * there. The owner's own list always leads, because they asked for it by contract; then the tokens
 * this wallet was seen to hold before, because a token you already own should never disappear between
 * refreshes; then the curated list in its maintainers' order.
 */
object TokenScanPlanner {

    /**
     * How many contracts one chain is read per refresh.
     *
     * This is the whole scan's budget: measured at forty calls per request and about 0.2 s per
     * request, a few hundred entries per chain still land in a couple of seconds. A wallet holding a
     * token outside that window is not lost — the owner adds its contract once in Settings and it is
     * read ahead of everything else from then on.
     */
    const val PROBE_LIMIT = 260

    fun candidates(
        networkId: String,
        custom: List<CustomToken>,
        cached: List<TokenBalance>,
        catalog: List<CatalogToken>,
        limit: Int = PROBE_LIMIT
    ): List<CatalogToken> {
        val out = LinkedHashMap<String, CatalogToken>()
        custom.filter { it.networkId == networkId }.forEach { token ->
            out.putIfAbsent(token.contract.lowercase(), CatalogToken(token.networkId, token.contract, token.symbol, token.name, token.decimals))
        }
        cached.filter { it.networkId == networkId && !it.contract.isNullOrBlank() }.forEach { row ->
            out.putIfAbsent(
                row.contract!!.lowercase(),
                CatalogToken(networkId, row.contract, row.symbol, row.name, row.decimals)
            )
        }
        catalog.filter { it.networkId == networkId }.forEach { token ->
            out.putIfAbsent(token.contract.lowercase(), token)
        }
        return out.values.take(limit)
    }

    /**
     * What the scan is allowed to add on top of the on-chain sweep: the tokens a chain explorer says
     * this address holds that the sweep did not already find.
     *
     * A row is only taken when it names its own contract, decimals and amount, and it never replaces
     * what the node itself answered — the node is the authority on its own chain.
     */
    fun extraFromExplorer(
        network: CryptoNetwork,
        explorer: List<TokenBalance>,
        alreadyFound: List<TokenBalance>
    ): List<TokenBalance> {
        val known = alreadyFound.mapNotNull { it.contract?.lowercase() }.toSet()
        return explorer.filter { row ->
            row.networkId == network.id &&
                !row.contract.isNullOrBlank() &&
                row.contract.lowercase() !in known &&
                row.amount > 0.0
        }
    }

    /** The registry's own RPC hosts for a chain, so no screen or service keeps a second list. */
    fun rpcsFor(network: CryptoNetwork): List<String> =
        ChainRegistry.descriptor(network)?.rpcEndpoints.orEmpty()
}
