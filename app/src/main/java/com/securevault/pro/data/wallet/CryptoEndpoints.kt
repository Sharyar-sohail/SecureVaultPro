package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.CryptoNetwork

/**
 * Ordered public endpoints. Every host is keyless and returns data for the first
 * endpoint that answers, so one blocked or rate-limited provider cannot take a
 * whole network offline.
 */
internal object CryptoEndpoints {

    val evmRpc: Map<CryptoNetwork, List<String>> = mapOf(
        CryptoNetwork.ETHEREUM to listOf(
            "https://ethereum-rpc.publicnode.com",
            "https://eth.drpc.org",
            "https://1rpc.io/eth",
            "https://rpc.flashbots.net"
        ),
        CryptoNetwork.BSC to listOf(
            "https://bsc-rpc.publicnode.com",
            "https://1rpc.io/bnb",
            "https://bsc-dataseed1.binance.org",
            "https://bsc.rpc.blxrbdn.com"
        ),
        CryptoNetwork.POLYGON to listOf(
            "https://polygon-bor-rpc.publicnode.com",
            "https://polygon.drpc.org",
            "https://1rpc.io/matic"
        )
    )

    /** Esplora-compatible Bitcoin instances; they share one JSON shape. */
    val bitcoinApi = listOf("https://mempool.space", "https://mempool.emzy.de")

    /** Different payload shape, used only when every Esplora host is silent. */
    const val BITCOIN_BALANCE_FALLBACK = "https://api.blockcypher.com/v1/btc/main/addrs/"

    val tronApi = listOf("https://api.trongrid.io")

    const val COINGECKO_PRICE = "https://api.coingecko.com/api/v3/simple/price"
    const val BINANCE_PRICE = "https://api.binance.com/api/v3/ticker/price"

    val explorerTx = mapOf(
        CryptoNetwork.ETHEREUM to "https://etherscan.io/tx/",
        CryptoNetwork.BSC to "https://bscscan.com/tx/",
        CryptoNetwork.POLYGON to "https://polygonscan.com/tx/",
        CryptoNetwork.BITCOIN to "https://mempool.space/tx/",
        CryptoNetwork.TRON to "https://tronscan.org/#/transaction/"
    )

    // ---- AUTHENTICATED SECTION ---------------------------------------------------------------
    //
    // Everything above this comment is keyless: those hosts are queried with public addresses only.
    // The hosts below are the ONLY ones this app ever calls with a credential header, and only for
    // the read-only "Crypto Violet" Bitget panel.
    //
    // This stays a host registry. No API key, secret or passphrase appears here or in any other
    // source file — they are sealed on the device by `BitgetCredentialStore` and are never synced,
    // backed up or uploaded. If you are about to paste a key into this file: do not.
    val bitgetApi = listOf("https://api.bitget.com")

    /** Documented v2 paths, spelled exactly as `https://www.bitget.com/api-doc` numbers them. */
    const val BITGET_SPOT_ASSETS_PATH = "/api/v2/spot/account/assets"
    const val BITGET_MIX_ACCOUNTS_PATH = "/api/v2/mix/account/accounts"

    /**
     * Bitget's v2 `productType` values are written in lower case by its own documentation
     * (`GET /docs/classic/uta-api-upgrade-guide.md`: "`productType` (e.g. `usdt-futures`) — same
     * concept, renamed and uppercased in v3"). Only the futures margin account is read.
     */
    const val BITGET_MIX_PRODUCT_TYPE = "usdt-futures"
}
