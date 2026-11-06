package com.securevault.pro.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain lineup is configuration, and this is the guard that keeps it honest.
 *
 * The scanner, Settings' network list, the send sheet and the address fields all read the registry
 * now, so a chain that is registered badly shows up as a wrong address or a dead node somewhere a
 * person can lose money to. These assertions are the places that cannot be fixed by a screen.
 */
class ChainRegistryCoversEveryChainTest {

    @Test
    fun everyChainTheEnumNamesHasADescriptorAndNoneIsRegisteredTwice() {
        val registered = ChainRegistry.networks()
        assertEquals(
            "a chain added to the enum without a descriptor makes require() throw mid-scan",
            CryptoNetwork.entries.toSet(), registered.toSet()
        )
        assertEquals(registered.size, registered.distinct().size)

        val withChainId = ChainRegistry.all().mapNotNull { it.chainId }
        assertEquals("two chains cannot share one chain id", withChainId.size, withChainId.distinct().size)
    }

    @Test
    fun everyEvmChainIsReachableAndEveryOtherChainDeclaresNoChainId() {
        for (descriptor in ChainRegistry.all()) {
            if (descriptor.isEvm) {
                assertNotNull("${descriptor.displayName} needs a chain id to sign against", descriptor.chainId)
                assertTrue("${descriptor.displayName} needs a host to call", descriptor.rpcEndpoints.isNotEmpty())
            } else {
                assertNull("${descriptor.displayName} has no chain id", descriptor.chainId)
            }
            for (host in descriptor.rpcEndpoints + descriptor.apiEndpoints) {
                assertTrue("${descriptor.displayName} calls a non-https host: $host", host.startsWith("https://"))
            }
            assertTrue(descriptor.txExplorer.startsWith("https://"))
            assertTrue(descriptor.addressExplorer.startsWith("https://"))
        }
    }

    @Test
    fun theMajorEvmNetworksAreAllInTheLineup() {
        val evm = ChainRegistry.all().filter { it.isEvm }.map { it.network }.toSet()
        assertTrue(
            "the portfolio promised every major EVM chain, found $evm",
            evm.containsAll(
                setOf(
                    CryptoNetwork.ETHEREUM, CryptoNetwork.BSC, CryptoNetwork.POLYGON,
                    CryptoNetwork.ARBITRUM, CryptoNetwork.BASE, CryptoNetwork.OPTIMISM,
                    CryptoNetwork.AVALANCHE, CryptoNetwork.FANTOM
                )
            )
        )
    }

    @Test
    fun oneSeedAddressMeansTheSameWalletOnEveryEvmChain() {
        val ethereum = ChainRegistry.ETHEREUM
        for (descriptor in ChainRegistry.all().filter { it.isEvm }) {
            // Same address family, same derivation path and the same native unit, so the phrase the
            // owner typed once derives one key that the scanner can read on all of them.
            assertEquals(AddressFamily.EVM, descriptor.family)
            assertEquals(descriptor.displayName, ethereum.network.derivationPath, descriptor.network.derivationPath)
            assertEquals(descriptor.displayName, 18, descriptor.decimals)
        }
    }

    @Test
    fun theRegistryAnswersForEveryNetworkIdTheAppStores() {
        for (descriptor in ChainRegistry.all()) {
            val id = descriptor.network.id
            assertEquals(descriptor, ChainRegistry.descriptorOrNull(id))
            assertEquals(descriptor, ChainRegistry.require(ChainRegistry.networkFor(id)!!))
            assertEquals(descriptor.displayName, descriptor.shortName, ChainRegistry.shortName(id))
            assertEquals(descriptor.displayName, descriptor.nativeSymbol, ChainRegistry.feeSymbol(id))
            assertEquals(descriptor.displayName, descriptor.txExplorer, ChainRegistry.explorerTxByNetwork[descriptor.network])
            assertTrue(descriptor.displayName, ChainRegistry.addressHint(descriptor.network).isNotBlank())
            if (descriptor.isEvm) {
                assertEquals(descriptor, ChainRegistry.byChainId(descriptor.chainId!!))
                assertEquals(descriptor.rpcEndpoints, ChainRegistry.evmRpcByNetwork[descriptor.network])
                assertEquals(descriptor.chainId, ChainRegistry.evmChainId(id))
            } else {
                assertNull(descriptor.displayName, ChainRegistry.evmChainId(descriptor.network))
            }
        }
        assertEquals("NotAChain", ChainRegistry.shortName("NotAChain"))
        assertEquals("NotAChain", ChainRegistry.feeSymbol("NotAChain"))
        assertNull(ChainRegistry.descriptorOrNull("NotAChain"))
    }
}
