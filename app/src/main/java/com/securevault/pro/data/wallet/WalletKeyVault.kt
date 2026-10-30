package com.securevault.pro.data.wallet

/**
 * Pure half of the device key store: the field names, the masking rule and the seal/open
 * translation. No Android, no DataStore, no network — so it runs in a plain JVM test.
 *
 * The values are sealed through [SecretSealer] (on the device that is `CryptoManager`, i.e. the frozen
 * AES-256-GCM envelope `base64(iv).base64(ciphertext+tag)`, 12-byte IV, 128-bit tag, under the
 * non-exportable Keystore-wrapped data key). Nothing here changes an encryption parameter; it reuses
 * exactly the envelope [WalletRepository] has always used for a vault record.
 *
 * Why a store of its own and not the `crypto_wallets` table: every column of that entity is mirrored
 * to the realtime backend and to the Drive cloud payload (`RealtimeFieldSchema` lists it as one of
 * the seven synced segments). A spend-authorising key must not have a path into a sync payload, so
 * the key material lives only in this file's fields, which are not a Room entity, not in the field
 * schema, and not readable by any DAO. `WalletKeyIsNeverSyncedTest` pins that claim.
 */

/**
 * Abstraction over `CryptoManager.encrypt` / `decrypt`, so the vault runs off-device in a test.
 * Declared here because the wallet key store is its only remaining user: the Bitget credential
 * vault that used to own it is gone, and no product surface asks for an exchange API key any more.
 */
interface SecretSealer {
    fun seal(plaintext: String): String?
    fun open(ciphertext: String?): String?
}

object WalletKeyFields {
    const val SEALED_KEY = "sealedKey"
    const val MASKED_ADDRESS = "maskedAddress"
    const val LABEL = "label"
    const val ADDED_AT = "addedAt"
    const val SOURCE = "source"

    /** Field names as they appear in storage, per wallet: `<field>.<walletId>`. */
    const val SEPARATOR = "."

    val SEALED_FIELDS = setOf(SEALED_KEY)
    val ALL_FIELDS = listOf(SEALED_KEY, MASKED_ADDRESS, LABEL, ADDED_AT, SOURCE)

    fun qualified(field: String, walletId: String): String = field + SEPARATOR + walletId

    /** The wallet id part of a qualified field name, or null when the field is not one of ours. */
    fun split(qualifiedName: String): Pair<String, String>? {
        val index = qualifiedName.indexOf(SEPARATOR)
        if (index <= 0) return null
        val field = qualifiedName.substring(0, index)
        if (field !in ALL_FIELDS) return null
        return field to qualifiedName.substring(index + 1)
    }

    /**
     * Anything a spend-authorising secret would be called if it ever rode into a payload. Used by
     * the guard test and by the store's own write check.
     */
    val SECRET_WORDS = listOf(
        "sealedkey", "privatekey", "private_key", "secretkey", "secret_key",
        "mnemonic", "seedphrase", "seed_phrase", "passphrase", "secret"
    )
}

/** Where a stored key came from, shown to the owner so they can tell an import from a derivation. */
enum class WalletKeySource { IMPORTED_PRIVATE_KEY, DERIVED_FROM_SEED }

/** What the panel may render about a key. Note that it holds no field able to carry a key. */
data class WalletKeyRecord(
    val walletId: String,
    val label: String,
    val maskedAddress: String,
    val addedAt: Long,
    val source: WalletKeySource,
    val sealed: Boolean
)

data class WalletKeyInputProblem(val field: String, val message: String)

class WalletKeyVault(private val sealer: BitgetSecretSealer) {

    /**
     * Fields to write for one wallet. A key that will not seal fails the whole call: there is no
     * path in this app that stores a private key in the clear as a fallback.
     */
    fun fieldsFor(
        walletId: String,
        canonicalKeyHex: String,
        primaryAddress: String,
        label: String,
        source: WalletKeySource,
        nowMillis: Long
    ): SealedResult {
        val problem = inputProblem(walletId, canonicalKeyHex, primaryAddress)
        if (problem != null) return SealedResult.failure(problem.message)
        val sealed = sealer.seal(canonicalKeyHex.trim())
        if (sealed.isNullOrBlank()) {
            return SealedResult.failure(
                "This device could not open its security key, so nothing was stored. " +
                    "Restart the app and try again."
            )
        }
        return SealedResult(
            ok = true,
            message = null,
            fields = mapOf(
                WalletKeyFields.qualified(WalletKeyFields.SEALED_KEY, walletId) to sealed,
                WalletKeyFields.qualified(WalletKeyFields.MASKED_ADDRESS, walletId) to
                    maskAddress(primaryAddress),
                WalletKeyFields.qualified(WalletKeyFields.LABEL, walletId) to
                    label.trim().ifBlank { "Imported key" },
                WalletKeyFields.qualified(WalletKeyFields.ADDED_AT, walletId) to nowMillis.toString(),
                WalletKeyFields.qualified(WalletKeyFields.SOURCE, walletId) to source.name
            )
        )
    }

    /** Renderable record. Opens nothing, so drawing the list never decrypts a key. */
    fun recordOf(walletId: String, fields: Map<String, String>): WalletKeyRecord? {
        val sealed = fields[WalletKeyFields.qualified(WalletKeyFields.SEALED_KEY, walletId)]
        val masked = fields[WalletKeyFields.qualified(WalletKeyFields.MASKED_ADDRESS, walletId)]
        if (sealed.isNullOrBlank() && masked.isNullOrBlank()) return null
        val source = runCatching {
            WalletKeySource.valueOf(
                fields[WalletKeyFields.qualified(WalletKeyFields.SOURCE, walletId)]
                    ?: WalletKeySource.IMPORTED_PRIVATE_KEY.name
            )
        }.getOrDefault(WalletKeySource.IMPORTED_PRIVATE_KEY)
        return WalletKeyRecord(
            walletId = walletId,
            label = fields[WalletKeyFields.qualified(WalletKeyFields.LABEL, walletId)]
                .orEmpty().ifBlank { "Imported key" },
            maskedAddress = masked ?: "not shown",
            addedAt = fields[WalletKeyFields.qualified(WalletKeyFields.ADDED_AT, walletId)]
                ?.toLongOrNull() ?: 0L,
            source = source,
            sealed = !sealed.isNullOrBlank()
        )
    }

    /**
     * The key, unsealed for one signature or one export. The caller must pass it straight on and
     * drop it: never store it, log it, put it in an exception message, or hold it in UI state.
     */
    fun keyFor(walletId: String, fields: Map<String, String>): String? {
        val sealed = fields[WalletKeyFields.qualified(WalletKeyFields.SEALED_KEY, walletId)]
            ?: return null
        return sealer.open(sealed)?.takeIf { it.isNotBlank() }
    }

    /**
     * Rejects a value that could not possibly be a key before anything is sealed. The hex rules are
     * the ones in [com.securevault.pro.crypto.EvmCrypto.parsePrivateKeyHex]; this is the last gate.
     */
    fun inputProblem(walletId: String, keyHex: String, primaryAddress: String): WalletKeyInputProblem? {
        if (walletId.isBlank()) return WalletKeyInputProblem("walletId", "No wallet to attach the key to.")
        val clean = keyHex.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length != 64) {
            return WalletKeyInputProblem("key", "A private key is 64 hexadecimal characters.")
        }
        if (!clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return WalletKeyInputProblem("key", "The stored key is not hexadecimal.")
        }
        if (maskAddress(primaryAddress) == "not shown") {
            return WalletKeyInputProblem("address", "No address to attach the key to.")
        }
        return null
    }

    /**
     * Head and tail only. A masked address is not a secret — it is what the owner already sees on
     * screen — but it must never be the whole address, so the middle is always dropped.
     */
    fun maskAddress(address: String): String {
        val value = address.trim()
        if (value.length < 10) return "not shown"
        return value.take(6) + "…" + value.takeLast(4)
    }

    /** True when [text] names a key field or looks like a bare key; used by the guard test. */
    fun carriesSecret(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lowered = text.lowercase()
        if (WalletKeyFields.SECRET_WORDS.any { lowered.contains(it) }) return true
        return Regex("(^|[^0-9a-f])([0-9a-f]{64})([^0-9a-f]|$)").containsMatchIn(lowered)
    }

    data class SealedResult(
        val ok: Boolean,
        val message: String?,
        val fields: Map<String, String>
    ) {
        companion object {
            fun failure(message: String) = SealedResult(ok = false, message = message, fields = emptyMap())
        }
    }
}
