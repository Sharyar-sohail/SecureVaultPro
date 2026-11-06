package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.CryptoNetwork

/**
 * Ordered public endpoints. Every host is keyless and returns data for the first
 * endpoint that answers, so one blocked or rate-limited provider cannot take a
 * whole network offline.
 *
 * Chain RPC hosts and explorers are *not* listed here: `ChainRegistry` is the one source for those,
 * so a chain added to the registry is reachable without touching this file.
 */
internal object CryptoEndpoints {

    /** Esplora-compatible Bitcoin instances; they share one JSON shape. */
    val bitcoinApi = listOf("https://mempool.space", "https://mempool.emzy.de")

    /** Different payload shape, used only when every Esplora host is silent. */
    const val BITCOIN_BALANCE_FALLBACK = "https://api.blockcypher.com/v1/btc/main/addrs/"

    val tronApi = listOf("https://api.trongrid.io")

    const val COINGECKO_PRICE = "https://api.coingecko.com/api/v3/simple/price"

    /** Same service, quoting by contract instead of coin id — what a discovered token needs. */
    const val COINGECKO_TOKEN_PRICE = "https://api.coingecko.com/api/v3/simple/token_price"
    const val BINANCE_PRICE = "https://api.binance.com/api/v3/ticker/price"

    /**
     * CoinGecko's per-chain token quote, keyed by the network id this app stores. The path segment is
     * CoinGecko's own platform name for that chain, so it is written out here rather than derived.
     */
    val tokenPricePlatform = mapOf(
        CryptoNetwork.ETHEREUM.id to "ethereum",
        CryptoNetwork.BSC.id to "binance-smart-chain",
        CryptoNetwork.POLYGON.id to "polygon-pos",
        CryptoNetwork.ARBITRUM.id to "arbitrum-one",
        CryptoNetwork.BASE.id to "base",
        CryptoNetwork.OPTIMISM.id to "optimistic-ethereum",
        CryptoNetwork.AVALANCHE.id to "avalanche",
        CryptoNetwork.FANTOM.id to "fantom"
    )

    /**
     * Static, versioned token lists. Public JSON over https, no account and no key, so a token list
     * cannot be a source of leaked credentials.
     *
     * The DEX lists (Uniswap, PancakeSwap) are curated and short; the per-chain CoinGecko lists carry
     * the depth — every token the chain's own maintainers rank, each with the chain id it belongs to.
     * A list is only worth naming when its `chainId` values match [ChainRegistry]'s, because that id
     * is the only thing that maps an entry back to a chain this app can read.
     */
    val tokenLists = listOf(
        "https://tokens.uniswap.org" to mapOf(
            1L to CryptoNetwork.ETHEREUM.id,
            56L to CryptoNetwork.BSC.id,
            137L to CryptoNetwork.POLYGON.id
        ),
        "https://tokens.pancakeswap.finance/pancakeswap-extended.json" to mapOf(
            56L to CryptoNetwork.BSC.id
        ),
        "https://tokens.coingecko.com/arbitrum-one/all.json" to mapOf(
            42161L to CryptoNetwork.ARBITRUM.id
        ),
        "https://tokens.coingecko.com/base/all.json" to mapOf(
            8453L to CryptoNetwork.BASE.id
        ),
        "https://tokens.coingecko.com/optimistic-ethereum/all.json" to mapOf(
            10L to CryptoNetwork.OPTIMISM.id
        ),
        "https://tokens.coingecko.com/avalanche/all.json" to mapOf(
            43114L to CryptoNetwork.AVALANCHE.id
        ),
        "https://tokens.coingecko.com/fantom/all.json" to mapOf(
            250L to CryptoNetwork.FANTOM.id
        )
    )

    /**
     * Blockscout instances that answer "every token this address holds" in one request, keyed by
     * network id. Only chains whose public instance was verified to answer are listed; the others are
     * scanned through the token lists instead, which is slower per token but covers the same ground.
     */
    val tokenHistoryApi = mapOf(
        CryptoNetwork.ETHEREUM.id to "https://eth.blockscout.com/api/v2/addresses/"
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
