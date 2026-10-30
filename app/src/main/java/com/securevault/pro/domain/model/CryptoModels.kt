package com.securevault.pro.domain.model

import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.TransactionCodec
import com.securevault.pro.crypto.TrustReport
import com.securevault.pro.crypto.WalletAddress
import java.math.BigDecimal

data class TokenBalance(
    val networkId: String,
    val networkName: String,
    val symbol: String,
    val name: String,
    val amount: Double,
    val priceUsd: Double = 0.0,
    val available: Boolean = true,
    val error: String? = null
) {
    val usd: Double get() = amount * priceUsd
}

data class CryptoWallet(
    val id: String,
    val label: String,
    val kind: String,
    val addresses: List<WalletAddress>,
    val balances: List<TokenBalance> = emptyList(),
    val totalUsd: Double = 0.0,
    val lastSyncedAt: Long = 0L,
    val createdAt: Long = 0L
) {
    val isSeedPhrase: Boolean get() = kind == "SEED_PHRASE"
    val shortLabel: String get() = label.ifBlank { if (isSeedPhrase) "Seed wallet" else "Imported key" }
}

data class BalanceScan(
    val tokens: List<TokenBalance>,
    val errors: Map<String, String> = emptyMap()
)

/** A single coin or token that can be sent from a discovered address. */
data class TransferAsset(
    val networkId: String,
    val networkName: String,
    val symbol: String,
    val name: String,
    val contract: String?,
    val decimals: Int,
    val isNative: Boolean,
    val amount: Double,
    val priceUsd: Double
) {
    val usd: Double get() = amount * priceUsd
    val key: String get() = "$networkId:$symbol"
}

data class TransferQuote(
    val networkId: String,
    val networkName: String,
    val symbol: String,
    val contract: String?,
    val isNative: Boolean,
    val fromAddress: String,
    val toAddress: String,
    val amount: BigDecimal,
    val amountUsd: Double,
    val fee: BigDecimal,
    val feeSymbol: String,
    val feeUsd: Double,
    val totalLabel: String,
    val feeNote: String,
    val explorerBase: String,
    val warning: String? = null,
    // Serialized signing inputs captured at review time.
    val evmChainId: Long = 0L,
    val evmNonce: java.math.BigInteger = java.math.BigInteger.ZERO,
    val evmGasPrice: java.math.BigInteger = java.math.BigInteger.ZERO,
    val evmGasLimit: java.math.BigInteger = java.math.BigInteger.ZERO,
    val evmValue: java.math.BigInteger = java.math.BigInteger.ZERO,
    val evmData: ByteArray = ByteArray(0),
    val btcUtxos: List<TransactionCodec.BtcUtxo> = emptyList(),
    val btcToScript: ByteArray = ByteArray(0),
    val btcChangeScript: ByteArray = ByteArray(0),
    val btcAmountSats: Long = 0L,
    val btcFeeSats: Long = 0L,
    val btcFeeRate: Long = 0L,
    val btcLegacy: Boolean = false,
    /**
     * The per-chain checks that were run when this quote was built, and where the destination is
     * asserted to live. The confirmation screen renders these line by line and the send path refuses
     * to sign unless every one of them passed.
     */
    val trust: TrustReport = TrustReport.EMPTY,
    /** Chain name plus the id the transaction will be signed for, spelled from the registry. */
    val chainLabel: String = ""
) {
    val requiresEvm: Boolean get() = ChainRegistry.descriptorOrNull(networkId)?.isEvm == true
}

data class TransferReceipt(
    val hash: String,
    val explorerUrl: String,
    val networkName: String,
    val symbol: String,
    val amount: BigDecimal
)

// --------------------------------------------------------------------------- Bitget (Crypto Violet)
//
// Read-only account views. Nothing in this file carries key material: the plaintext triple
// below is transient by contract (see BitgetSecretBundle) and the persisted state
// ([BitgetCredentialState]) only ever holds a masked label.

/**
 * The three values the owner pastes in once. Every field is a `String`, so Kotlin cannot zero it
 * the way a `CharArray` could be cleared — the whole app therefore treats this object as
 * copy-once-use-once: it is handed to [com.securevault.pro.data.wallet.BitgetCredentialStore] to be
 * sealed, or to the client to sign one request, and never stored, logged, returned in an error or
 * placed in a UI state.
 */
data class BitgetSecretBundle(
    val apiKey: String,
    val apiSecret: String,
    val passphrase: String
)

/** What the interface is allowed to render: the API key reduced to its last four characters. */
data class BitgetCredentialState(
    val label: String,
    val maskedApiKey: String,
    val addedAt: Long,
    val canReadSecret: Boolean,
    val canReadPassphrase: Boolean
)

/** One coin or contract balance returned by Bitget, priced in USD for display. */
data class BitgetAsset(
    val account: String,
    val symbol: String,
    val caption: String,
    val available: Double,
    val onHold: Double,
    val priceUsd: Double
) {
    val total: Double get() = available + onHold
    val usd: Double get() = total * priceUsd
}

/** The last successful read of an account. Numbers and symbols only — never a credential. */
data class BitgetSnapshot(
    val assets: List<BitgetAsset> = emptyList(),
    val fetchedAt: Long = 0L,
    val notice: String? = null
) {
    val spotAssets: List<BitgetAsset> get() = assets.filter { it.account == ACCOUNT_SPOT }
    val mixAssets: List<BitgetAsset> get() = assets.filter { it.account != ACCOUNT_SPOT }
    val spotUsd: Double get() = spotAssets.sumOf { it.usd }
    val mixUsd: Double get() = mixAssets.sumOf { it.usd }
    val totalUsd: Double get() = spotUsd + mixUsd

    companion object {
        const val ACCOUNT_SPOT = "Spot"
        const val ACCOUNT_MIX = "USDT futures"
    }
}

/** How a Bitget rejection should be explained. The code itself is never shown as the answer. */
enum class BitgetFailureKind {
    INVALID_KEY,
    SIGNATURE,
    PASSPHRASE,
    CLOCK_SKEW,
    RATE_LIMITED,
    NETWORK,
    READ_ONLY_DENIED,
    OTHER
}

/**
 * An owner-facing failure. [message] is a sentence with the fix in it; it is built from a table of
 * canned strings, so no server echo, request header or key fragment can ever ride into it.
 */
data class BitgetFailure(
    val kind: BitgetFailureKind,
    val message: String,
    val code: String = ""
)

/** The result of one read: a snapshot, or a failure the panel can show verbatim. */
sealed class BitgetRead {
    data class Loaded(val snapshot: BitgetSnapshot) : BitgetRead()
    data class Failed(val failure: BitgetFailure) : BitgetRead()
}

