package com.securevault.pro.crypto

import com.securevault.pro.domain.model.CryptoWallet
import com.securevault.pro.domain.model.TokenBalance
import com.securevault.pro.presentation.screens.crypto.fundedAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FundedAssetsTest {

    private val eth = CryptoNetwork.entries.first { it.symbol.equals("ETH", true) }

    private fun wallet(vararg balances: TokenBalance) = CryptoWallet(
        id = "w1",
        label = "Test",
        kind = "SEED_PHRASE",
        addresses = emptyList(),
        balances = balances.toList()
    )

    private fun balance(symbol: String, amount: Double, networkId: String = eth.id) =
        TokenBalance(
            networkId = networkId,
            networkName = eth.displayName,
            symbol = symbol,
            name = symbol,
            amount = amount,
            priceUsd = 1.0
        )

    @Test
    fun zeroBalanceCoinsAreHidden() {
        val funded = wallet(balance("ETH", 0.0), balance("ETH", 0.5)).fundedAssets()
        assertEquals(listOf(0.5), funded.map { it.amount })
    }

    @Test
    fun unknownNetworkIsNeverListed() {
        assertTrue(wallet(balance("ETH", 1.0, networkId = "not-a-network")).fundedAssets().isEmpty())
    }

    @Test
    fun unknownTokenOnKnownNetworkIsNeverListed() {
        assertTrue(wallet(balance("FAKECOIN", 2.0)).fundedAssets().isEmpty())
    }

    @Test
    fun stateCountsOnlyFundedAssets() {
        val funded = wallet(balance("ETH", 3.0), balance("USDT", 0.0)).fundedAssets()
        assertEquals(1, funded.size)
        assertTrue(funded.single().isNative)
    }
}
