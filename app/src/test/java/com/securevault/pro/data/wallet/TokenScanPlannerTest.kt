package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.domain.model.TokenBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide which contracts a refresh reads, and which rows a refresh is allowed to add.
 * All of it is pure Kotlin, so what the owner sees on the portfolio screen is what these tests hold.
 */
class TokenScanPlannerTest {

    private val eth = CryptoNetwork.ETHEREUM.id
    private val bsc = CryptoNetwork.BSC.id

    private fun catalog(networkId: String, contract: String, symbol: String = "T") =
        CatalogToken(networkId, contract, symbol, symbol, 18)

    private fun custom(networkId: String, contract: String, symbol: String = "C") =
        CustomToken(networkId, contract, symbol, symbol, 18)

    private fun row(
        networkId: String,
        symbol: String,
        amount: Double,
        contract: String? = null,
        decimals: Int = 18
    ) = TokenBalance(
        networkId = networkId,
        networkName = networkId,
        symbol = symbol,
        name = symbol,
        amount = amount,
        contract = contract,
        decimals = decimals
    )

    @Test
    fun theOwnersOwnContractsAreReadFirst() {
        val found = TokenScanPlanner.candidates(
            networkId = eth,
            custom = listOf(custom(eth, "0xown")),
            cached = listOf(row(eth, "OLD", 1.0, "0xcached")),
            catalog = listOf(catalog(eth, "0xlist"))
        )
        assertEquals(listOf("0xown", "0xcached", "0xlist"), found.map { it.contract })
    }

    @Test
    fun aTokenHeldBeforeNeverFallsOutOfTheWindow() {
        val found = TokenScanPlanner.candidates(
            networkId = eth,
            custom = emptyList(),
            cached = listOf(row(eth, "HELD", 2.0, "0xheld")),
            catalog = (1..10).map { catalog(eth, "0xlist$it") }
        )
        assertEquals("0xheld", found.first().contract)
    }

    @Test
    fun theSameContractSpelledTwiceIsReadOnce() {
        val found = TokenScanPlanner.candidates(
            networkId = eth,
            custom = listOf(custom(eth, "0xAABB")),
            cached = listOf(row(eth, "X", 1.0, "0xaabb")),
            catalog = listOf(catalog(eth, "0xAaBb", "Y"))
        )
        assertEquals(1, found.size)
        assertEquals("0xAABB", found.single().contract)
        assertEquals("C", found.single().symbol)
    }

    @Test
    fun anotherChainsContractsNeverEnterTheWindow() {
        val found = TokenScanPlanner.candidates(
            networkId = bsc,
            custom = listOf(custom(eth, "0xethOnly")),
            cached = listOf(row(eth, "ETHROW", 1.0, "0xethRow")),
            catalog = listOf(catalog(bsc, "0xbsc"), catalog(eth, "0xethCatalog"))
        )
        assertEquals(listOf("0xbsc"), found.map { it.contract })
    }

    @Test
    fun theBudgetCutsTheCuratedTailAndNeverTheOwnersList() {
        val found = TokenScanPlanner.candidates(
            networkId = eth,
            custom = listOf(custom(eth, "0xown")),
            cached = emptyList(),
            catalog = (1..50).map { catalog(eth, "0xlist$it") },
            limit = 5
        )
        assertEquals(5, found.size)
        assertEquals("0xown", found.first().contract)
    }

    @Test
    fun explorerRowsOnlyAddWhatTheNodeDidNotAnswer() {
        val node = listOf(row(eth, "USDT", 5.0, "0xdac17f"))
        val explorer = listOf(
            row(eth, "USDT", 4.0, "0xDAC17F"),
            row(eth, "NEW", 3.0, "0xnew"),
            row(eth, "ZERO", 0.0, "0xzero"),
            row(eth, "NOCADDR", 1.0, null),
            row(bsc, "OTHERCHAIN", 9.0, "0xother")
        )
        val extra = TokenScanPlanner.extraFromExplorer(CryptoNetwork.ETHEREUM, explorer, node)
        assertEquals(listOf("NEW"), extra.map { it.symbol })
    }

    @Test
    fun onlyFundedContractRowsBecomeSendableKnowledge() {
        val balances = listOf(
            row(eth, "ETH", 1.0),
            row(eth, "USDT", 10.0, "0xdac17f"),
            row(eth, "USDC", 0.0, "0xa0b869"),
            row(bsc, "BSCUSDT", 7.0, "0x55d398"),
            row(eth, "USDT", 2.0, "0xDAC17F")
        )
        val known = verifiedTokens(balances, CryptoNetwork.ETHEREUM)
        assertEquals(listOf("0xdac17f"), known.map { it.contract })
        assertEquals("USDT", known.single().symbol)
    }

    @Test
    fun rpcHostsComeFromTheRegistry() {
        val hosts = TokenScanPlanner.rpcsFor(CryptoNetwork.BSC)
        assertTrue(hosts.isNotEmpty())
        assertTrue(hosts.all { it.startsWith("https://") })
    }
}
