package com.securevault.pro.crypto

import java.math.BigInteger

enum class CryptoNetwork(
    val id: String,
    val displayName: String,
    val symbol: String,
    val derivationPath: String,
    val decimals: Int,
    val addressLabel: String
) {
    BITCOIN("BITCOIN", "Bitcoin", "BTC", "m/84'/0'/0'/0/0", 8, "Native SegWit"),
    ETHEREUM("ETHEREUM", "Ethereum", "ETH", "m/44'/60'/0'/0/0", 18, "ERC-20"),
    BSC("BSC", "BNB Smart Chain", "BNB", "m/44'/60'/0'/0/0", 18, "BEP-20"),
    POLYGON("POLYGON", "Polygon", "POL", "m/44'/60'/0'/0/0", 18, "Polygon"),
    TRON("TRON", "Tron", "TRX", "m/44'/195'/0'/0/0", 6, "TRC-20")
}

data class WalletAddress(
    val network: CryptoNetwork,
    val address: String,
    val path: String
)

enum class WalletImportKind { SEED_PHRASE, PRIVATE_KEY }

object WalletDeriver {

    fun fromSeedPhrase(mnemonic: String, passphrase: String = ""): List<WalletAddress> {
        val seed = Bip39.toSeed(mnemonic, passphrase)
        return derive(seed)
    }

    fun derive(seed: ByteArray): List<WalletAddress> = CryptoNetwork.entries.map { network ->
        val child = Bip32.derivePath(seed, network.derivationPath)
        val address = when (network) {
            CryptoNetwork.BITCOIN -> btcSegwit(child.publicPoint)
            CryptoNetwork.ETHEREUM, CryptoNetwork.BSC, CryptoNetwork.POLYGON -> evm(child.publicPoint)
            CryptoNetwork.TRON -> tron(child.publicPoint)
        }
        WalletAddress(network, address, network.derivationPath)
    }

    fun fromPrivateKey(privateKey: BigInteger): List<WalletAddress> = CryptoNetwork.entries.map { network ->
        val point = Secp256k1.multiply(privateKey)
        val address = when (network) {
            CryptoNetwork.BITCOIN -> btcLegacy(point)
            CryptoNetwork.ETHEREUM, CryptoNetwork.BSC, CryptoNetwork.POLYGON -> evm(point)
            CryptoNetwork.TRON -> tron(point)
        }
        WalletAddress(network, address, "imported key")
    }

    fun btcSegwit(point: Secp256k1.Point): String {
        val payload = Hash.hash160(Secp256k1.compress(point))
        return Bech32.segwitV0("bc", payload)
    }

    fun btcLegacy(point: Secp256k1.Point): String {
        val payload = byteArrayOf(0x00) + Hash.hash160(Secp256k1.compress(point))
        return Base58.encodeCheck(payload)
    }

    fun evm(point: Secp256k1.Point): String {
        val uncompressed = Secp256k1.uncompress(point)
        val digest = Keccak256.hash(uncompressed.copyOfRange(1, uncompressed.size))
        val raw = Hash.hex(digest.copyOfRange(12, 32))
        return checksumEvm(raw)
    }

    fun tron(point: Secp256k1.Point): String {
        val uncompressed = Secp256k1.uncompress(point)
        val digest = Keccak256.hash(uncompressed.copyOfRange(1, uncompressed.size))
        val payload = byteArrayOf(0x41) + digest.copyOfRange(12, 32)
        return Base58.encodeCheck(payload)
    }

    /** EIP-55 mixed-case checksum. */
    fun checksumEvm(lowerHex: String): String {
        val lower = lowerHex.lowercase().removePrefix("0x")
        val digest = Keccak256.hash(lower.toByteArray(Charsets.US_ASCII))
        val builder = StringBuilder("0x")
        for (index in lower.indices) {
            val char = lower[index]
            if (char in '0'..'9') {
                builder.append(char)
                continue
            }
            val nibble = (digest[index / 2].toInt() ushr (if (index % 2 == 0) 4 else 0)) and 0x0F
            builder.append(if (nibble >= 8) char.uppercaseChar() else char)
        }
        return builder.toString()
    }
}
