package com.securevault.pro.crypto

import java.math.BigInteger

/**
 * Serializes and signs native transfers. Nothing here touches the network; the
 * transfer service broadcasts the returned raw transaction.
 */
object TransactionCodec {

    data class BtcUtxo(val txid: String, val vout: Int, val valueSats: Long)

    private val CHAIN_IDS = mapOf(
        "ETHEREUM" to 1L,
        "BSC" to 56L,
        "POLYGON" to 137L
    )

    fun evmChainId(networkId: String): Long = CHAIN_IDS[networkId] ?: 1L

    // ------------------------------------------------------------------ EVM

    /**
     * EIP-155 legacy transaction. Returns 0x-prefixed raw hex ready for
     * eth_sendRawTransaction.
     */
    fun signEvmLegacy(
        chainId: Long,
        nonce: BigInteger,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
        to: String,
        value: BigInteger,
        data: ByteArray,
        privateKey: BigInteger
    ): String {
        val toBytes = Hash.bytesFromHex(to.removePrefix("0x")) ?: error("Invalid recipient")
        require(toBytes.size == 20) { "Recipient must be 20 bytes" }
        val unsigned = Rlp.encode(
            listOf(
                nonce, gasPrice, gasLimit, toBytes, value, data, chainId, 0, 0
            )
        )
        val digest = Keccak256.hash(unsigned)
        val signature = Ecdsa.sign(digest, privateKey)
        val v = BigInteger.valueOf(chainId * 2 + 35 + signature.recoveryId)
        val signed = Rlp.encode(
            listOf(
                nonce, gasPrice, gasLimit, toBytes, value, data, v, signature.r, signature.s
            )
        )
        return "0x" + Hash.hex(signed)
    }

    fun erc20TransferData(to: String, units: BigInteger): ByteArray {
        val recipient = Hash.bytesFromHex(to.removePrefix("0x")) ?: error("Invalid recipient")
        require(recipient.size == 20) { "Recipient must be 20 bytes" }
        val selector = Hash.bytesFromHex("a9059cbb")!!
        val padded = ByteArray(32)
        System.arraycopy(recipient, 0, padded, 12, 20)
        val amount = Secp256k1.toFixed32(units)
        return selector + padded + amount
    }

    // -------------------------------------------------------------- Bitcoin

    fun p2pkhScript(hash160: ByteArray): ByteArray {
        require(hash160.size == 20)
        return byteArrayOf(0x76, 0xA9.toByte(), 0x14) + hash160 + byteArrayOf(0x88.toByte(), 0xAC.toByte())
    }

    fun p2shScript(hash160: ByteArray): ByteArray {
        require(hash160.size == 20)
        return byteArrayOf(0xA9.toByte(), 0x14) + hash160 + byteArrayOf(0x87.toByte())
    }

    fun p2wpkhScript(hash160: ByteArray): ByteArray {
        require(hash160.size == 20)
        return byteArrayOf(0x00, 0x14) + hash160
    }

    /** ScriptPubKey for a mainnet Bitcoin address (P2PKH, P2SH, P2WPKH, P2WSH, Taproot). */
    fun bitcoinAddressScript(address: String): ByteArray? {
        Bech32.decodeWitness(address)?.let { (version, program) ->
            val versionOpcode = if (version == 0) 0x00 else 0x50 + version
            return byteArrayOf(versionOpcode.toByte(), program.size.toByte()) + program
        }
        val payload = Base58.decodeCheck(address) ?: return null
        if (payload.size != 21) return null
        val hash = payload.copyOfRange(1, 21)
        return when (payload[0].toInt() and 0xFF) {
            0x00 -> p2pkhScript(hash)
            0x05 -> p2shScript(hash)
            else -> null
        }
    }

    fun signP2wpkhTransaction(
        utxos: List<BtcUtxo>,
        toScript: ByteArray,
        changeScript: ByteArray,
        amountSats: Long,
        feeSats: Long,
        privateKey: BigInteger,
        publicKey: ByteArray,
        publicKeyHash: ByteArray
    ): String {
        require(utxos.isNotEmpty()) { "No spendable inputs" }
        require(amountSats > 0) { "Amount must be positive" }
        val totalIn = utxos.sumOf { it.valueSats }
        val change = totalIn - amountSats - feeSats
        require(change >= 0) { "Insufficient funds for amount and fee" }

        val sequence = ByteArray(4) { 0xFF.toByte() }
        val version = le32(2)
        val locktime = le32(0)
        val outpoints = ByteArray(0).let { acc ->
            utxos.fold(ByteArray(0)) { buffer, utxo ->
                buffer + txidBytes(utxo.txid) + le32(utxo.vout)
            }
        }
        val hashPrevouts = Hash.doubleSha256(outpoints)
        val hashSequence = Hash.doubleSha256(sequence.repeatArray(utxos.size))

        val outputs = ArrayList<Pair<Long, ByteArray>>()
        outputs += amountSats to toScript
        if (change >= 546L) outputs += change to changeScript
        val serializedOutputs = outputs.fold(ByteArray(0)) { acc, (value, script) ->
            acc + le64(value) + varint(script.size) + script
        }
        val hashOutputs = Hash.doubleSha256(serializedOutputs)

        val signatures = ArrayList<Pair<ByteArray, ByteArray>>()
        val scriptCode = byteArrayOf(0x19, 0x76, 0xA9.toByte(), 0x14) +
            publicKeyHash + byteArrayOf(0x88.toByte(), 0xAC.toByte())
        utxos.forEach { utxo ->
            val preimage = le32(2) + hashPrevouts + hashSequence +
                txidBytes(utxo.txid) + le32(utxo.vout) + scriptCode +
                le64(utxo.valueSats) + sequence + hashOutputs + locktime + le32(1)
            val digest = Hash.doubleSha256(preimage)
            val signature = Ecdsa.sign(digest, privateKey).der() + byteArrayOf(0x01)
            signatures += signature to publicKey
        }

        var tx = version + byteArrayOf(0x00, 0x01) + varint(utxos.size)
        utxos.forEach { utxo ->
            tx += txidBytes(utxo.txid) + le32(utxo.vout) + byteArrayOf(0x00) + sequence
        }
        tx += varint(outputs.size) + serializedOutputs
        signatures.forEach { (signature, pubKey) ->
            tx += byteArrayOf(0x02) + varint(signature.size) + signature +
                varint(pubKey.size) + pubKey
        }
        tx += locktime
        return Hash.hex(tx)
    }

    fun signP2pkhTransaction(
        utxos: List<BtcUtxo>,
        toScript: ByteArray,
        changeScript: ByteArray,
        amountSats: Long,
        feeSats: Long,
        privateKey: BigInteger,
        publicKey: ByteArray
    ): String {
        require(utxos.isNotEmpty()) { "No spendable inputs" }
        val totalIn = utxos.sumOf { it.valueSats }
        val change = totalIn - amountSats - feeSats
        require(change >= 0) { "Insufficient funds for amount and fee" }
        val sequence = ByteArray(4) { 0xFF.toByte() }

        val outputs = ArrayList<Pair<Long, ByteArray>>()
        outputs += amountSats to toScript
        if (change >= 546L) outputs += change to changeScript
        val serializedOutputs = outputs.fold(ByteArray(0)) { acc, (value, script) ->
            acc + le64(value) + varint(script.size) + script
        }

        val signatures = ArrayList<Pair<ByteArray, ByteArray>>()
        utxos.forEachIndexed { signIndex, utxo ->
            var preimage = le32(2) + varint(utxos.size)
            utxos.forEachIndexed { index, input ->
                preimage += txidBytes(input.txid) + le32(input.vout)
                if (index == signIndex) {
                    val scriptCode = p2pkhScript(Hash.hash160(publicKey))
                    preimage += varint(scriptCode.size) + scriptCode
                } else {
                    preimage += byteArrayOf(0x00)
                }
                preimage += sequence
            }
            preimage += varint(outputs.size) + serializedOutputs + le32(0) + le32(1)
            val digest = Hash.doubleSha256(preimage)
            signatures += (Ecdsa.sign(digest, privateKey).der() + byteArrayOf(0x01)) to publicKey
        }

        var tx = le32(2) + varint(utxos.size)
        utxos.forEachIndexed { index, utxo ->
            val (signature, pubKey) = signatures[index]
            val scriptSig = varint(signature.size) + signature + varint(pubKey.size) + pubKey
            tx += txidBytes(utxo.txid) + le32(utxo.vout) + varint(scriptSig.size) + scriptSig + sequence
        }
        tx += varint(outputs.size) + serializedOutputs + le32(0)
        return Hash.hex(tx)
    }

    fun bitcoinVsize(inputs: Int, outputs: Int): Int {
        val base = 10 + inputs * 41 + outputs * 31
        // Witness bytes include the 2-byte marker/flag plus ~108 per P2WPKH input.
        val witness = inputs * 108 + 2
        return (base * 4 + witness + 3) / 4
    }

    fun bitcoinLegacyVsize(inputs: Int, outputs: Int): Int =
        10 + inputs * 148 + outputs * 34

    // ------------------------------------------------------------------ Tron

    /** TronGrid expects r||s||v over the SHA-256 transaction id, with v = 27/28. */
    fun tronSignature(txId: ByteArray, privateKey: BigInteger): String {
        val digest = Hash.sha256(txId)
        val signature = Ecdsa.sign(digest, privateKey)
        val compact = signature.compact()
        compact[64] = (27 + (signature.recoveryId and 1)).toByte()
        return Hash.hex(compact)
    }

    fun tronAddress(payload: ByteArray): String = Base58.encodeCheck(payload)

    // ---------------------------------------------------------------- Helpers

    private fun txidBytes(txid: String): ByteArray {
        val raw = Hash.bytesFromHex(txid) ?: error("Invalid transaction id")
        require(raw.size == 32) { "Invalid transaction id" }
        return raw.reversedArray()
    }

    private fun le32(value: Int): ByteArray = ByteArray(4) { index ->
        ((value ushr (8 * index)) and 0xFF).toByte()
    }

    private fun le32(value: Long): ByteArray = le32(value.toInt())

    private fun le64(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (8 * index)) and 0xFF).toByte()
    }

    private fun varint(value: Int): ByteArray = when {
        value < 0xFD -> byteArrayOf(value.toByte())
        value <= 0xFFFF -> byteArrayOf(0xFD.toByte(), (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())
        else -> byteArrayOf(
            0xFE.toByte(),
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        )
    }

    private fun ByteArray.repeatArray(times: Int): ByteArray {
        val out = ByteArray(size * times)
        for (index in 0 until times) System.arraycopy(this, 0, out, index * size, size)
        return out
    }
}
