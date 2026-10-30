package com.securevault.pro.data.wallet

import com.securevault.pro.crypto.AddressRules
import com.securevault.pro.crypto.ChainDescriptor
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.EvmCrypto
import com.securevault.pro.crypto.TrustCheck
import com.securevault.pro.crypto.TrustReport
import java.math.BigDecimal

/**
 * Everything the app checks about a transfer *before* it signs anything, in one report the
 * confirmation screen renders line by line.
 *
 * A failing check is not a warning: [TrustReport.blocking] is what the send path tests, and a send
 * never proceeds past it. The reason is always named — the owner reads which rule stopped them, and
 * so does the log-free crash reporter, which only ever sees the code and the message, never a key.
 */
object TransactionTrust {

    data class Request(
        val network: CryptoNetwork,
        val destination: String,
        val fromAddress: String?,
        val assetSymbol: String,
        val assetContract: String?,
        val isNative: Boolean,
        val amount: BigDecimal,
        val availableBalance: Double,
        val nativeBalance: Double,
        val fee: BigDecimal,
        /** The chain id the node answered with, when the caller already asked it. */
        val nodeChainId: Long? = null,
        /** Contracts the app knows for this chain, so a lookalike token is refused. */
        val knownTokens: List<KnownToken> = emptyList()
    )

    data class KnownToken(val symbol: String, val contract: String)

    fun inspect(request: Request): TrustReport {
        val descriptor = ChainRegistry.descriptor(request.network)
        val checks = ArrayList<TrustCheck>(9)

        checks += check(
            label = "Chain registered",
            passDetail = "${request.network.displayName} is in the chain registry" +
                (descriptor?.chainId?.let { " · chain id $it" } ?: ""),
            failDetail = "This build has no rules for ${request.network.displayName}, so it cannot " +
                "be used to move funds.",
            passed = descriptor != null,
            failureCode = AddressRules.Code.NO_DESCRIPTOR
        )

        val format = descriptor?.let { AddressRules.formatProblem(request.network, request.destination) }
        checks += check(
            label = "Address format",
            passDetail = "Valid ${descriptor?.tokenStandard ?: ""} address shape".trim(),
            failDetail = format?.message ?: "Valid address shape for ${descriptor?.displayName}.",
            passed = format == null,
            failureCode = format?.code
        )

        val checksumLabel = "EIP-55 checksum"
        if (descriptor?.isEvm == true) {
            val mixed = request.destination.any { it in 'A'..'F' } &&
                request.destination.any { it in 'a'..'f' }
            val ok = EvmCrypto.checksumValid(request.destination)
            checks += check(
                label = checksumLabel,
                passDetail = if (mixed) "Mixed-case address matches its checksum"
                else "All-one-case address; it will be sent in checksummed form",
                failDetail = "That address failed its EIP-55 checksum. One character differs — " +
                    "re-copy the whole address.",
                passed = ok,
                failureCode = AddressRules.Code.CHECKSUM_MISMATCH
            )
        } else if (descriptor != null) {
            checks += check(
                label = "Address checksum",
                passDetail = "${descriptor.displayName} address decodes with its own checksum",
                failDetail = "That ${descriptor.displayName} address does not decode.",
                passed = format == null,
                failureCode = format?.code
            )
        }

        val burn = AddressRules.burnProblem(request.network, request.destination)
        checks += check(
            label = "Not a burn address",
            passDetail = "The destination is not a null or published burn address",
            failDetail = burn?.message ?: "Destination is a burn address.",
            passed = burn == null,
            failureCode = burn?.code
        )

        val selfSend = AddressRules.selfSendProblem(request.network, request.fromAddress, request.destination)
        checks += check(
            label = "Not your own address",
            passDetail = "Sending and receiving addresses differ",
            failDetail = selfSend?.message ?: "Self-send.",
            passed = selfSend == null,
            failureCode = selfSend?.code
        )

        val assetProblem = assetProblem(request, descriptor)
        checks += check(
            label = "Chain and asset match",
            passDetail = if (request.isNative) {
                "${request.assetSymbol} is the native coin of ${descriptor?.nativeName}"
            } else {
                "${request.assetSymbol} is a ${descriptor?.tokenStandard} contract known on this chain"
            },
            failDetail = assetProblem ?: "Asset belongs on this chain.",
            passed = assetProblem == null,
            failureCode = AddressRules.Code.ASSET_NOT_ON_CHAIN
        )

        if (descriptor?.isEvm == true && request.nodeChainId != null && descriptor.chainId != null) {
            checks += check(
                label = "Node chain id",
                passDetail = "The node answered on chain id ${descriptor.chainId}",
                failDetail = "The node reported chain id ${request.nodeChainId}, not " +
                    "${descriptor.chainId} for ${descriptor.displayName}. Nothing was signed.",
                passed = request.nodeChainId == descriptor.chainId,
                failureCode = AddressRules.Code.CHAIN_MISMATCH
            )
        }

        checks += check(
            label = "Amount is positive",
            passDetail = "Amount is above zero",
            failDetail = "Enter an amount greater than zero.",
            passed = request.amount.signum() > 0,
            failureCode = AddressRules.Code.ZERO_AMOUNT
        )

        checks += check(
            label = "Within balance",
            passDetail = "Balance covers the amount",
            failDetail = "Only ${trim(request.availableBalance)} ${request.assetSymbol} is available.",
            passed = request.availableBalance + 1e-12 >= request.amount.toDouble(),
            failureCode = AddressRules.Code.AMOUNT_ABOVE_BALANCE
        )

        checks += check(
            label = "Fee covered in ${descriptor?.nativeSymbol ?: "native coin"}",
            passDetail = "Native balance covers the network fee",
            failDetail = "The fee is ${trim(request.fee.toDouble())} " +
                "${descriptor?.nativeSymbol ?: "units"} and only " +
                "${trim(request.nativeBalance)} is left for gas.",
            passed = request.isNative.not() || request.nativeBalance + 1e-12 >= request.fee.toDouble(),
            failureCode = AddressRules.Code.FEE_ABOVE_NATIVE_BALANCE
        )

        return TrustReport(checks)
    }

    /**
     * Chain/asset sanity. A BEP-20 USDT and an ERC-20 USDT are different contracts; sending a
     * contract address that the app does not know for this chain, or a native symbol that is not
     * this chain's coin, is refused here rather than at the node.
     */
    private fun assetProblem(request: Request, descriptor: ChainDescriptor?): String? {
        if (descriptor == null) return "No descriptor for ${request.network.displayName}."
        val symbol = request.assetSymbol.trim()
        if (request.isNative) {
            return if (symbol.equals(descriptor.nativeSymbol, ignoreCase = true)) null else
                "${request.network.displayName}'s native coin is ${descriptor.nativeSymbol}, " +
                    "not $symbol."
        }
        val contract = request.assetContract?.trim().orEmpty()
        if (contract.isBlank()) {
            return "$symbol is not ${descriptor.nativeSymbol}: a token transfer needs its " +
                "${descriptor.tokenStandard} contract, and this build has none for $symbol on " +
                "${descriptor.displayName}."
        }
        if (descriptor.isEvm && EvmCrypto.normalize(contract) == null) {
            return "The $symbol contract on ${descriptor.displayName} is not an EVM address."
        }
        if (descriptor.isEvm && AddressRules.formatProblem(request.network, contract) != null) {
            return "The $symbol contract on ${descriptor.displayName} is not a valid address."
        }
        val known = request.knownTokens.firstOrNull {
            it.symbol.equals(symbol, ignoreCase = true) &&
                it.contract.equals(contract, ignoreCase = true)
        }
        if (request.knownTokens.isNotEmpty() && known == null) {
            return "This app knows a different contract for $symbol on ${descriptor.displayName}. " +
                "Nothing was signed against an unverified token address."
        }
        return null
    }

    private fun check(
        label: String,
        passDetail: String,
        failDetail: String,
        passed: Boolean,
        failureCode: AddressRules.Code? = null
    ) = TrustCheck(
        label = label,
        detail = if (passed) passDetail else failDetail,
        passed = passed,
        code = if (passed) null else failureCode
    )

    private fun trim(value: Double): String =
        if (!value.isFinite()) "0" else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
