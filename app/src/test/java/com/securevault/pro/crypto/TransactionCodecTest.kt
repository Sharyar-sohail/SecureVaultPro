package com.securevault.pro.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class TransactionCodecTest {

    private val privateKey = BigInteger(
        "4646464646464646464646464646464646464646464646464646464646464646",
        16
    )

    @Test
    fun `rfc6979 signatures are deterministic`() {
        val hash = Hash.sha256("secure wallet".toByteArray())
        val first = Ecdsa.sign(hash, privateKey)
        val second = Ecdsa.sign(hash, privateKey)
        assertEquals(first.r, second.r)
        assertEquals(first.s, second.s)
        assertEquals(first.recoveryId, second.recoveryId)
    }

    @Test
    fun `ecdsa signatures verify and recover`() {
        val point = Secp256k1.multiply(privateKey)
        repeat(16) { index ->
            val hash = Hash.sha256("payload-$index".toByteArray())
            val signature = Ecdsa.sign(hash, privateKey)
            assertTrue("signature $index must verify", Ecdsa.verify(hash, signature, point))
            val recovered = Ecdsa.recover(hash, signature)
            assertNotNull(recovered)
            assertEquals(point.x, recovered!!.x)
            assertEquals(point.y, recovered.y)
        }
    }

    @Test
    fun `der encoding keeps high bit padding`() {
        repeat(32) { index ->
            val hash = Hash.sha256("der-$index".toByteArray())
            val signature = Ecdsa.sign(hash, privateKey)
            val der = signature.der()
            assertEquals("SEQUENCE tag", 0x30, der[0].toInt() and 0xFF)
            assertTrue("DER length matches", der.size == 6 + integerSize(signature.r) +
                integerSize(signature.s))
            val (r, s) = parseDer(der)
            assertEquals(signature.r, r)
            assertEquals(signature.s, s)
        }
    }

    private fun integerSize(value: BigInteger): Int = value.toByteArray().size

    private fun parseDer(der: ByteArray): Pair<BigInteger, BigInteger> {
        var offset = 2
        assertEquals(0x02, der[offset].toInt() and 0xFF)
        val rLength = der[offset + 1].toInt() and 0xFF
        val r = BigInteger(1, der.copyOfRange(offset + 2, offset + 2 + rLength))
        offset += 2 + rLength
        assertEquals(0x02, der[offset].toInt() and 0xFF)
        val sLength = der[offset + 1].toInt() and 0xFF
        val s = BigInteger(1, der.copyOfRange(offset + 2, offset + 2 + sLength))
        return r to s
    }

    @Test
    fun `eip155 reference transaction matches`() {
        val raw = TransactionCodec.signEvmLegacy(
            chainId = 1L,
            nonce = BigInteger.valueOf(9),
            gasPrice = BigInteger("20000000000"),
            gasLimit = BigInteger.valueOf(21000),
            to = "0x3535353535353535353535353535353535353535",
            value = BigInteger.TEN.pow(18),
            data = ByteArray(0),
            privateKey = privateKey
        )
        assertEquals(
            "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83",
            raw
        )
    }

    @Test
    fun `bip84 and bip44 addresses derive from the reference mnemonic`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon about"
        val addresses = WalletDeriver.fromSeedPhrase(mnemonic)
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            addresses.first { it.network == CryptoNetwork.BITCOIN }.address
        )
        assertEquals(
            "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            addresses.first { it.network == CryptoNetwork.ETHEREUM }.address
        )
    }

    @Test
    fun `bitcoin address scripts decode to expected shapes`() {
        val segwit = TransactionCodec.bitcoinAddressScript(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        )
        assertNotNull(segwit)
        assertEquals(22, segwit!!.size)
        assertEquals(0x00, segwit[0].toInt())
        assertEquals(0x14, segwit[1].toInt())

        val legacy = TransactionCodec.bitcoinAddressScript("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")
        assertNotNull(legacy)
        assertEquals(25, legacy!!.size)
        assertEquals(0x76, legacy[0].toInt() and 0xFF)
        assertEquals(0xA9, legacy[1].toInt() and 0xFF)
    }

    @Test
    fun `taproot bech32m recipient decodes to a v1 witness program`() {
        val taproot = TransactionCodec.bitcoinAddressScript(
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
        )
        assertNotNull(taproot)
        assertEquals(34, taproot!!.size)
        assertEquals(0x51, taproot[0].toInt())
        assertEquals(0x20, taproot[1].toInt())
        val witness = Bech32.decodeWitness(
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
        )
        assertNotNull(witness)
        assertEquals(1, witness!!.first)
        assertEquals(32, witness.second.size)
        assertNull(Bech32.decodeSegwit("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"))
    }

    @Test
    fun `tron signature carries a 27 or 28 recovery byte`() {
        val txId = Hash.sha256("tron transaction".toByteArray())
        val signature = Hash.bytesFromHex(TransactionCodec.tronSignature(txId, privateKey))
        assertNotNull(signature)
        assertEquals(65, signature!!.size)
        val v = signature[64].toInt() and 0xFF
        assertTrue("unexpected recovery byte $v", v == 27 || v == 28)
    }
}
