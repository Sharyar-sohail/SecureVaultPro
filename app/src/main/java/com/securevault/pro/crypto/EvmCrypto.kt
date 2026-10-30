package com.securevault.pro.crypto

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger

/**
 * secp256k1 and Keccak-256 through BouncyCastle, used for the paths where a wrong answer would
 * spend funds at the wrong place: private-key import, address derivation and the EIP-55 checksum
 * the trust gate compares against.
 *
 * Only the *lightweight* API is used (`org.bouncycastle.crypto.*`); no `java.security.Provider` is
 * ever registered, so this cannot disturb the platform's own providers on any device.
 *
 * `EvmCryptoVectorTest` pins two things: that these outputs match published vectors (the BIP-44
 * reference address for the public test mnemonic, the official EIP-55 mixed-case addresses) and
 * that they match the in-house codec the transaction signer already uses. Same answer, two ways to
 * get it — not two implementations that can disagree.
 */
object EvmCrypto {

    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)

    /** The order of the secp256k1 group: a scalar must be below it to be a usable key. */
    val CURVE_ORDER: BigInteger = curve.n

    /** Keccak-256 as Ethereum/BSC/Polygon/Tron define it (0x01 pad), not NIST SHA3-256. */
    fun keccak256(input: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(input, 0, input.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /** True for a scalar that can sign: non-zero and below the curve order. */
    fun isValidScalar(value: BigInteger): Boolean = value.signum() > 0 && value < CURVE_ORDER

    /**
     * Parses a hex private key. Returns null with a reason rather than throwing, because the caller
     * is a keyboard and the answer has to be shown to the owner.
     */
    fun parsePrivateKeyHex(raw: String): PrivateKeyParse {
        val cleaned = raw.trim().filterNot { it.isWhitespace() }
            .removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return PrivateKeyParse.Rejected("Paste the private key first.")
        if (cleaned.length != 64) {
            return PrivateKeyParse.Rejected(
                "A private key is 64 hexadecimal characters; this one is ${cleaned.length}."
            )
        }
        if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return PrivateKeyParse.Rejected("That is not hexadecimal — check for a stray character.")
        }
        val bytes = Hash.bytesFromHex(cleaned)
            ?: return PrivateKeyParse.Rejected("That is not hexadecimal — check for a stray character.")
        val value = BigInteger(1, bytes)
        if (value.signum() == 0) {
            return PrivateKeyParse.Rejected("An all-zero key is not a key; nothing can sign with it.")
        }
        if (value >= CURVE_ORDER) {
            return PrivateKeyParse.Rejected("That number is at or above the secp256k1 curve order.")
        }
        return PrivateKeyParse.Accepted(value, "0x" + Hash.hex(Secp256k1.toFixed32(value)))
    }

    /** The uncompressed 65-byte public key (0x04 ‖ x ‖ y) for a scalar. */
    fun uncompressedPublic(privateKey: BigInteger): ByteArray {
        require(isValidScalar(privateKey)) { "Private key outside the curve order" }
        val point = domain.g.multiply(privateKey).normalize()
        return point.getEncoded(false)
    }

    /** Lower-case 0x… address for a scalar: last 20 bytes of Keccak-256(x ‖ y). */
    fun addressOf(privateKey: BigInteger): String {
        val public = uncompressedPublic(privateKey)
        val digest = keccak256(public.copyOfRange(1, public.size))
        return "0x" + Hash.hex(digest.copyOfRange(12, digest.size))
    }

    /** EIP-55 mixed-case checksum form of any 0x… address; input case is ignored. */
    fun toChecksum(address: String): String {
        val lower = normalize(address) ?: error("Not an EVM address")
        val digest = keccak256(lower.toByteArray(Charsets.US_ASCII))
        val builder = StringBuilder("0x")
        lower.forEachIndexed { index, char ->
            if (char in '0'..'9') {
                builder.append(char)
            } else {
                val nibble = (digest[index / 2].toInt() ushr (if (index % 2 == 0) 4 else 0)) and 0x0F
                builder.append(if (nibble >= 8) char.uppercaseChar() else char)
            }
        }
        return builder.toString()
    }

    /** True when a mixed-case address carries the checksum it claims. All-lower/all-upper passes. */
    fun checksumValid(address: String): Boolean {
        val value = normalize(address) ?: return false
        val hasUpper = value.any { it in 'A'..'F' }
        val hasLower = value.any { it in 'a'..'f' }
        if (!hasUpper || !hasLower) return true
        return toChecksum(value) == value
    }

    /** Lower-case 40 hex characters with no 0x, or null when the string is not an address. */
    fun normalize(address: String): String? {
        val trimmed = address.trim().removePrefix("0x").removePrefix("0X")
        if (trimmed.length != 40) return null
        if (!trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return trimmed.lowercase()
    }

    /** Keccak-256 of an ASCII signature, i.e. the first four bytes of an ERC-20 call selector. */
    fun selector(functionSignature: String): ByteArray =
        keccak256(functionSignature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    /** A private key as the app stores it: always 0x + 64 lower-case hex characters. */
    sealed class PrivateKeyParse {
        data class Accepted(val scalar: BigInteger, val canonicalHex: String) : PrivateKeyParse()
        data class Rejected(val reason: String) : PrivateKeyParse()
    }
}
