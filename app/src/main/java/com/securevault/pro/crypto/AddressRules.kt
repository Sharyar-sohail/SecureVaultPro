package com.securevault.pro.crypto

/**
 * The address rules a destination has to satisfy before anything is signed, plus the addresses that
 * are known to be sinks. Validation lives here, next to the [ChainDescriptor] that declares each
 * family, so a chain cannot be added without also stating how its addresses look.
 *
 * Every rejection carries a [Code] so the UI can name the reason instead of showing "invalid".
 */
object AddressRules {

    enum class Code {
        EMPTY,
        BAD_FORMAT,
        CHECKSUM_MISMATCH,
        CHAIN_MISMATCH,
        BURN_ADDRESS,
        SELF_SEND,
        ASSET_NOT_ON_CHAIN,
        ZERO_AMOUNT,
        AMOUNT_ABOVE_BALANCE,
        FEE_ABOVE_NATIVE_BALANCE,
        NO_DESCRIPTOR,
        EXPLORER_UNREACHABLE,
        CONTRACT_MISMATCH
    }

    data class Rejection(val code: Code, val message: String)

    /**
     * Format-only check for a chain. Null means the shape is acceptable; it says nothing about
     * whether the address is spendable by anyone, only that it *is* an address of that chain.
     */
    fun formatProblem(network: CryptoNetwork, raw: String): Rejection? {
        val descriptor = ChainRegistry.descriptor(network) ?: return Rejection(
            Code.NO_DESCRIPTOR,
            "This build has no rules registered for ${network.displayName}."
        )
        val value = raw.trim()
        if (value.isBlank()) return Rejection(Code.EMPTY, "Enter a recipient address.")
        return when (descriptor.family) {
            AddressFamily.EVM -> evmProblem(value)
            AddressFamily.BITCOIN -> bitcoinProblem(value)
            AddressFamily.TRON -> tronProblem(value)
        }
    }

    private fun evmProblem(value: String): Rejection? {
        val shape = Regex("^0x[0-9a-fA-F]{40}$").matches(value)
        if (!shape) {
            return Rejection(
                Code.BAD_FORMAT,
                "An address on an EVM chain is 0x plus 40 hex characters (42 in total)."
            )
        }
        if (!EvmCrypto.checksumValid(value)) {
            return Rejection(
                Code.CHECKSUM_MISMATCH,
                "That address failed its EIP-55 checksum. One character differs — re-copy it in full."
            )
        }
        return null
    }

    private fun bitcoinProblem(value: String): Rejection? {
        if (TransactionCodec.bitcoinAddressScript(value) != null) return null
        return Rejection(
            Code.BAD_FORMAT,
            "Enter a Bitcoin address: bc1… (SegWit), bc1p… (Taproot), 1… or 3… (legacy)."
        )
    }

    private fun tronProblem(value: String): Rejection? {
        val payload = Base58.decodeCheck(value)
        if (payload != null && payload.size == 21 && payload[0].toInt() == 0x41) return null
        return Rejection(
            Code.BAD_FORMAT,
            "A Tron address is a T… string of 34 characters with a valid Base58 checksum."
        )
    }

    /** Canonical (checksummed / as-canonical) form of an accepted address, or null. */
    fun canonical(network: CryptoNetwork, raw: String): String? {
        if (formatProblem(network, raw) != null) return null
        val value = raw.trim()
        return when (ChainRegistry.descriptor(network)?.family) {
            AddressFamily.EVM -> EvmCrypto.toChecksum(value)
            else -> value
        }
    }

    /**
     * Known sinks. Sending here destroys the funds, so the confirmation step refuses them even when
     * the address is perfectly well formed.
     *
     * Two kinds of rule, and nothing else: a *structural* one (the payload is all zeros, which is the
     * null address on every chain), and a short list of published burn addresses. Every entry of that
     * list is asserted by `ChainRegistryTest` to decode as a real address of its own chain, so an
     * invented string cannot live here.
     */
    fun burnProblem(network: CryptoNetwork, raw: String): Rejection? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val lowered = value.lowercase()
        when (ChainRegistry.descriptor(network)?.family) {
            AddressFamily.EVM -> {
                val body = lowered.removePrefix("0x")
                if (body.all { it == '0' }) return Rejection(
                    Code.BURN_ADDRESS,
                    "0x0…0 is the null address: anything sent there is unrecoverable."
                )
                if (body.all { it == '0' || it == 'd' } && body.endsWith("dead")) return Rejection(
                    Code.BURN_ADDRESS,
                    "That is a 0x…dead burn address; the tokens would leave circulation."
                )
            }
            AddressFamily.BITCOIN -> {
                if (lowered in BURN_BITCOIN) return Rejection(
                    Code.BURN_ADDRESS,
                    "That is a published Bitcoin burn address."
                )
                val script = TransactionCodec.bitcoinAddressScript(value)
                if (script != null && script.copyOfRange(2, script.size).all { it.toInt() == 0 }) {
                    return Rejection(
                        Code.BURN_ADDRESS,
                        "That Bitcoin address hashes to all zeros — nobody can ever spend it."
                    )
                }
            }
            AddressFamily.TRON -> {
                if (lowered in BURN_TRON) return Rejection(
                    Code.BURN_ADDRESS,
                    "That is a published Tron burn address."
                )
                val payload = Base58.decodeCheck(value)
                if (payload != null && payload.size == 21 && payload.copyOfRange(1, 21).all { it.toInt() == 0 }) {
                    return Rejection(
                        Code.BURN_ADDRESS,
                        "That Tron address hashes to all zeros."
                    )
                }
            }
            null -> return null
        }
        return null
    }

    /** The address belongs to this device's own wallet: a swap-like self-send that is usually a mistake. */
    fun selfSendProblem(network: CryptoNetwork, from: String?, to: String): Rejection? {
        if (from.isNullOrBlank()) return null
        val a = canonical(network, from) ?: return null
        val b = canonical(network, to) ?: return null
        val same = if (ChainRegistry.descriptor(network)?.family == AddressFamily.EVM) {
            a.equals(b, ignoreCase = true)
        } else {
            a == b
        }
        return if (same) Rejection(
            Code.SELF_SEND,
            "The recipient is the same address as the one you are sending from."
        ) else null
    }

    /**
     * Published burn addresses, one per non-EVM chain. `ChainRegistryTest` asserts each of these
     * decodes as a valid address of its own chain: anything that fails that check is an invented
     * string and must not be here.
     */
    private val BURN_BITCOIN = listOf(
        // The vanity address the community uses for destroying coins.
        "1bitcoineateraddressdontsendf59kue"
    )

    private val BURN_TRON = listOf(
        // The address Tron's own burn transactions send to.
        "t9yd14nj9j7xab4dbgeix9h8unkkhxuwwb"
    )
}
