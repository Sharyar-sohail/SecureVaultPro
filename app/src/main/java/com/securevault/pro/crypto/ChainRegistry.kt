package com.securevault.pro.crypto

/**
 * Every chain this wallet can speak to, as configuration.
 *
 * A descriptor is the whole of what a chain is: how its addresses look, which hosts may be called,
 * what the native coin is called and where a transaction can be read afterwards. Adding a chain is
 * one entry in [ChainRegistry.ALL] plus one [CryptoNetwork] case — no screen, service or validator
 * holds chain knowledge of its own any more, so the registry and the UI cannot drift apart.
 *
 * What is deliberately *not* here: any chain that could not be resolved against a real public
 * endpoint and a published chain id. `Shiva`, named in the brief, is not in this file because no
 * such chain exists in the registry of any block explorer the team could name — see
 * [ChainRegistry.unknownChainHint]. Guessing a chain id for it would make every address rule,
 * every fee and every broadcast against it wrong, which is worse than saying it is unknown.
 *
 * Endpoints are hosts only, all https, and they are the exact hosts the app is allowed to contact;
 * nothing here is built from user input.
 */
data class ChainDescriptor(
    val network: CryptoNetwork,
    /** CAIP-2 style chain id for EVM chains; null for UTXO and Tron, which have no chain id. */
    val chainId: Long?,
    val family: AddressFamily,
    val nativeSymbol: String,
    val nativeName: String,
    val decimals: Int,
    /** Ticker shown against a token standard, e.g. "BEP-20". */
    val tokenStandard: String,
    val displayName: String,
    /** Short label for chips and rows. */
    val shortName: String,
    /** JSON-RPC hosts (EVM) or REST api roots. Empty when the family has no rpc. */
    val rpcEndpoints: List<String>,
    val txExplorer: String,
    val addressExplorer: String,
    /** Explorer whose balances this wallet actually reads; distinct from the display explorer. */
    val apiEndpoints: List<String> = rpcEndpoints,
) {
    val isEvm: Boolean get() = family == AddressFamily.EVM
}

/** The address grammar of a chain. Each family owns its own validator. */
enum class AddressFamily { EVM, BITCOIN, TRON }

object ChainRegistry {

    // BNB Smart Chain first: it is the chain the owner named. Chain id 56 is the one Binance
    // publishes; 97 is testnet and is deliberately absent.
    val BSC = ChainDescriptor(
        network = CryptoNetwork.BSC,
        chainId = 56L,
        family = AddressFamily.EVM,
        nativeSymbol = "BNB",
        nativeName = "BNB",
        decimals = 18,
        tokenStandard = "BEP-20",
        displayName = "BNB Smart Chain",
        shortName = "BNB",
        rpcEndpoints = listOf(
            "https://bsc-rpc.publicnode.com",
            "https://1rpc.io/bnb",
            "https://bsc-dataseed1.binance.org",
            "https://bsc.rpc.blxrbdn.com"
        ),
        txExplorer = "https://bscscan.com/tx/",
        addressExplorer = "https://bscscan.com/address/"
    )

    val ETHEREUM = ChainDescriptor(
        network = CryptoNetwork.ETHEREUM,
        chainId = 1L,
        family = AddressFamily.EVM,
        nativeSymbol = "ETH",
        nativeName = "Ether",
        decimals = 18,
        tokenStandard = "ERC-20",
        displayName = "Ethereum",
        shortName = "ETH",
        rpcEndpoints = listOf(
            "https://ethereum-rpc.publicnode.com",
            "https://eth.drpc.org",
            "https://1rpc.io/eth",
            "https://rpc.flashbots.net"
        ),
        txExplorer = "https://etherscan.io/tx/",
        addressExplorer = "https://etherscan.io/address/"
    )

    val POLYGON = ChainDescriptor(
        network = CryptoNetwork.POLYGON,
        chainId = 137L,
        family = AddressFamily.EVM,
        nativeSymbol = "POL",
        nativeName = "Polygon",
        decimals = 18,
        tokenStandard = "PoS token",
        displayName = "Polygon",
        shortName = "POL",
        rpcEndpoints = listOf(
            "https://polygon-bor-rpc.publicnode.com",
            "https://polygon.drpc.org",
            "https://1rpc.io/matic"
        ),
        txExplorer = "https://polygonscan.com/tx/",
        addressExplorer = "https://polygonscan.com/address/"
    )

    val BITCOIN = ChainDescriptor(
        network = CryptoNetwork.BITCOIN,
        chainId = null,
        family = AddressFamily.BITCOIN,
        nativeSymbol = "BTC",
        nativeName = "Bitcoin",
        decimals = 8,
        tokenStandard = "native",
        displayName = "Bitcoin",
        shortName = "BTC",
        // Esplora-compatible API roots; a transaction is posted to <root>/api/tx.
        rpcEndpoints = listOf("https://mempool.space", "https://mempool.emzy.de"),
        txExplorer = "https://mempool.space/tx/",
        addressExplorer = "https://mempool.space/address/"
    )

    val TRON = ChainDescriptor(
        network = CryptoNetwork.TRON,
        chainId = null,
        family = AddressFamily.TRON,
        nativeSymbol = "TRX",
        nativeName = "Tron",
        decimals = 6,
        tokenStandard = "TRC-20",
        displayName = "Tron",
        shortName = "TRX",
        rpcEndpoints = listOf("https://api.trongrid.io"),
        txExplorer = "https://tronscan.org/#/transaction/",
        addressExplorer = "https://tronscan.org/#/address/"
    )

    val ALL: List<ChainDescriptor> = listOf(BSC, ETHEREUM, POLYGON, BITCOIN, TRON)

    private val byNetwork: Map<CryptoNetwork, ChainDescriptor> = ALL.associateBy { it.network }

    private val byChainId: Map<Long, ChainDescriptor> =
        ALL.filterNotNullChainIds().associateBy { it.chainId!! }

    /** Every chain the app can derive for, in the order the portfolio shows them. */
    fun all(): List<ChainDescriptor> = ALL

    fun descriptor(network: CryptoNetwork): ChainDescriptor? = byNetwork[network]

    /** Lookup by the network id string that is stored on a wallet row or a quote. */
    fun networkFor(networkId: String): CryptoNetwork? =
        CryptoNetwork.entries.firstOrNull { it.id.equals(networkId, ignoreCase = true) }

    fun descriptorOrNull(networkId: String): ChainDescriptor? =
        networkFor(networkId)?.let(::descriptor)

    /** The chip-sized name for a chain, from the registry rather than a per-screen `when`. */
    fun shortName(networkId: String): String = descriptorOrNull(networkId)?.shortName ?: networkId

    /** The coin a network's fee is paid in — the registry's answer, never a guess. */
    fun feeSymbol(networkId: String): String =
        descriptorOrNull(networkId)?.nativeSymbol ?: networkId

    /** Never null for a registered chain; throws on a chain added to the enum but not here. */
    fun require(network: CryptoNetwork): ChainDescriptor =
        byNetwork[network] ?: error("Chain $network has no descriptor in ChainRegistry")

    fun byChainId(chainId: Long): ChainDescriptor? = byChainId[chainId]

    fun networks(): List<CryptoNetwork> = ALL.map { it.network }

    /** EVM chain id for a network, or null for a non-EVM chain. Replaces the old hardcoded table. */
    fun evmChainId(network: CryptoNetwork): Long? = descriptor(network)?.chainId

    /** Chain id for a network id string, defaulting to 1 exactly as the previous codec did. */
    fun evmChainId(networkId: String): Long =
        CryptoNetwork.entries.firstOrNull { it.id.equals(networkId, ignoreCase = true) }
            ?.let(::evmChainId) ?: 1L

    val evmRpcByNetwork: Map<CryptoNetwork, List<String>> =
        ALL.filter { it.isEvm }.associate { it.network to it.rpcEndpoints }

    val explorerTxByNetwork: Map<CryptoNetwork, String> =
        ALL.associate { it.network to it.txExplorer }

    /** A placeholder the address field can offer, derived from the family, never typed per screen. */
    fun addressHint(network: CryptoNetwork): String = when (descriptor(network)?.family) {
        AddressFamily.EVM -> "0x… 42 characters"
        AddressFamily.BITCOIN -> "bc1…, bc1p…, 1… or 3…"
        AddressFamily.TRON -> "T… 34 characters"
        null -> "Address"
    }

    /**
     * The named chains that are *not* in this registry, and why. A test pins that this list is not
     * silently growing.
     */
    val unknownChainHint = "'Shiva' is not a chain this app can register: no published chain id, " +
        "RPC host or address format exists under that name. If it means Shibarium, say so and it " +
        "goes here as one more descriptor."

    private fun List<ChainDescriptor>.filterNotNullChainIds(): List<ChainDescriptor> =
        filter { it.chainId != null }
}
