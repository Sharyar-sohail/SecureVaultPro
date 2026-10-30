package com.securevault.pro.data.wallet

import com.securevault.pro.domain.model.BitgetAsset
import com.securevault.pro.domain.model.BitgetCredentialState
import com.securevault.pro.domain.model.BitgetSecretBundle
import com.securevault.pro.domain.model.BitgetSnapshot

/**
 * Everything in this file is pure JVM: no Android, no Room, no network. [BitgetCredentialVault]
 * turns the three secret values into the field map that gets persisted and reads it back;
 * [BitgetCredentialStore] is the thin Android layer that persists that map with DataStore, sealing
 * the three values through [BitgetSecretSealer] — which on the device is `CryptoManager`, i.e. the
 * same AES-256-GCM envelope (`base64(iv).base64(ciphertext+tag)`, 12-byte IV, 128-bit tag, a fresh
 * random IV per call) under the Keystore-wrapped data key that [WalletRepository] uses for a wallet's
 * seed phrase or private key. No second crypto system was invented here.
 *
 * The three values are sealed *individually*, so one stored record never holds two secrets in a single
 * plaintext and a damaged field costs one value, not all three.
 */

/** Abstraction over `CryptoManager.encrypt` / `decrypt`, so the vault is testable off-device. */
interface BitgetSecretSealer {
    fun seal(plaintext: String): String?
    fun open(ciphertext: String?): String?
}

/** Storage field names, in one place so the never-synced guard test can name them. */
object BitgetCredentialFields {
    const val LABEL = "label"
    const val MASKED_KEY = "maskedApiKey"
    const val ADDED_AT = "addedAt"
    const val SEALED_API_KEY = "sealedApiKey"
    const val SEALED_API_SECRET = "sealedApiSecret"
    const val SEALED_PASSPHRASE = "sealedPassphrase"
    const val SNAPSHOT = "lastSnapshot"
    const val SNAPSHOT_AT = "lastSnapshotAt"

    /** The three fields that hold secret material. Nothing else may. */
    val SEALED_FIELDS = setOf(SEALED_API_KEY, SEALED_API_SECRET, SEALED_PASSPHRASE)

    /** Every field name this feature owns, for the exclusion guard test. */
    val ALL_FIELDS = setOf(
        LABEL, MASKED_KEY, ADDED_AT, SEALED_API_KEY, SEALED_API_SECRET, SEALED_PASSPHRASE,
        SNAPSHOT, SNAPSHOT_AT
    )

    /** The names a Bitget credential would carry if one ever reached a payload. */
    val SECRET_WORDS = listOf(
        "apikey", "api_key", "apisecret", "api_secret", "secretkey", "secret_key",
        "passphrase", "access-key", "access-sign", "sealedapikey", "sealedapisecret",
        "sealedpassphrase"
    )
}

data class BitgetInputProblem(val field: String, val message: String)

object BitgetCredentials {

    /**
     * Cheap sanity checks before anything is sealed. Bitget's keys are long opaque strings, so the
     * rules are length and whitespace only — a wrong value still has to survive one signed call.
     */
    fun validate(bundle: BitgetSecretBundle): BitgetInputProblem? {
        val apiKey = bundle.apiKey.trim()
        val secret = bundle.apiSecret.trim()
        val passphrase = bundle.passphrase
        if (apiKey.length < 10) {
            return BitgetInputProblem(
                "apiKey",
                "That API key looks too short. Copy it straight from Bitget."
            )
        }
        if (secret.length < 10) {
            return BitgetInputProblem(
                "secret",
                "That secret key looks too short. Copy it straight from Bitget."
            )
        }
        if (passphrase.isBlank()) {
            return BitgetInputProblem(
                "passphrase",
                "Enter the passphrase you chose when creating the key."
            )
        }
        if (apiKey.any { it.isWhitespace() } || secret.any { it.isWhitespace() }) {
            return BitgetInputProblem(
                "apiKey",
                "An API key or secret contains a space or a line break — paste it again without them."
            )
        }
        return null
    }

    /** Dot leader plus the last four characters: the only ever-visible form of an API key. */
    fun mask(apiKey: String): String {
        val trimmed = apiKey.trim()
        if (trimmed.length <= 4) return "****"
        return "********" + trimmed.takeLast(4)
    }

    /** True when [text] names any credential field; used by the guard test and by the store. */
    fun carriesSecret(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lowered = text.lowercase()
        return BitgetCredentialFields.SECRET_WORDS.any { lowered.contains(it) }
    }
}

/**
 * The pure core of credential storage. It holds nothing itself: it converts a plaintext triple into
 * sealed fields and back, and the caller decides where those fields are written.
 */
class BitgetCredentialVault(private val sealer: BitgetSecretSealer) {

    /**
     * Fields to persist. A value that would not seal fails the whole call, so there is no path that
     * stores a plaintext fallback: either every secret is sealed or nothing is kept.
     */
    fun fieldsFor(bundle: BitgetSecretBundle, label: String, nowMillis: Long): SealedResult {
        val trimmed = bundle.apiKey.trim()
        val sealedKey = sealer.seal(trimmed)
        val sealedSecret = sealer.seal(bundle.apiSecret.trim())
        val sealedPass = sealer.seal(bundle.passphrase)
        if (sealedKey.isNullOrBlank() || sealedSecret.isNullOrBlank() || sealedPass.isNullOrBlank()) {
            return SealedResult.failure(
                "This device could not open its security key, so nothing was stored. " +
                    "Restart the app and try again."
            )
        }
        return SealedResult(
            ok = true,
            message = null,
            fields = mapOf(
                BitgetCredentialFields.LABEL to label.trim().ifBlank { "Bitget account" },
                BitgetCredentialFields.MASKED_KEY to BitgetCredentials.mask(trimmed),
                BitgetCredentialFields.ADDED_AT to nowMillis.toString(),
                BitgetCredentialFields.SEALED_API_KEY to sealedKey,
                BitgetCredentialFields.SEALED_API_SECRET to sealedSecret,
                BitgetCredentialFields.SEALED_PASSPHRASE to sealedPass
            )
        )
    }

    /** The masked, renderable view. Decrypts nothing, so drawing the panel never touches a secret. */
    fun stateOf(fields: Map<String, String>): BitgetCredentialState? {
        val masked = fields[BitgetCredentialFields.MASKED_KEY]?.takeIf { it.isNotBlank() } ?: return null
        return BitgetCredentialState(
            label = fields[BitgetCredentialFields.LABEL].orEmpty().ifBlank { "Bitget account" },
            maskedApiKey = masked,
            addedAt = fields[BitgetCredentialFields.ADDED_AT]?.toLongOrNull() ?: 0L,
            canReadSecret = !fields[BitgetCredentialFields.SEALED_API_SECRET].isNullOrBlank(),
            canReadPassphrase = !fields[BitgetCredentialFields.SEALED_PASSPHRASE].isNullOrBlank()
        )
    }

    /**
     * The plaintext triple, decrypted for one signed request. Callers must not store, log or display
     * it, and must drop the reference as soon as the call returns.
     */
    fun secretsOf(fields: Map<String, String>): BitgetSecretBundle? {
        val apiKey = sealer.open(fields[BitgetCredentialFields.SEALED_API_KEY])?.trim().orEmpty()
        val secret = sealer.open(fields[BitgetCredentialFields.SEALED_API_SECRET]).orEmpty()
        val passphrase = sealer.open(fields[BitgetCredentialFields.SEALED_PASSPHRASE]).orEmpty()
        if (apiKey.isBlank() || secret.isBlank() || passphrase.isBlank()) return null
        return BitgetSecretBundle(apiKey = apiKey, apiSecret = secret, passphrase = passphrase)
    }

    data class SealedResult(
        val ok: Boolean,
        val message: String?,
        val fields: Map<String, String>
    ) {
        companion object {
            fun failure(message: String) =
                SealedResult(ok = false, message = message, fields = emptyMap())
        }
    }
}

/**
 * The last read of the account, kept as plain numbers and symbols. It is deliberately a separate
 * document from the credentials: it is the only Bitget-derived text this app writes outside the vault,
 * and it has no field that can hold a key, a secret or a passphrase.
 *
 * Hand-rolled rather than reflection-based — five flat fields per line, so it survives a field
 * rename, needs no keep rules, and round-trips in a plain JVM test.
 */
object BitgetSnapshotCodec {

    private const val HEADER = "bitget.v1"
    private const val SEP = "|"
    private const val ROW_ASSET = "asset"
    private const val ROW_AT = "at"
    private const val ROW_NOTICE = "notice"

    fun encode(snapshot: BitgetSnapshot): String = buildString {
        append(HEADER)
        append('\n')
        append(ROW_AT).append(SEP).append(snapshot.fetchedAt)
        append('\n')
        snapshot.notice?.let {
            append(ROW_NOTICE).append(SEP).append(escape(it))
            append('\n')
        }
        snapshot.assets.forEach { asset ->
            append(ROW_ASSET).append(SEP)
                .append(escape(asset.account)).append(SEP)
                .append(escape(asset.symbol)).append(SEP)
                .append(escape(asset.caption)).append(SEP)
                .append(asset.available.toString()).append(SEP)
                .append(asset.onHold.toString()).append(SEP)
                .append(asset.priceUsd.toString())
                .append('\n')
        }
    }

    /** Null on anything the writer did not produce; a damaged line is skipped, not fatal. */
    fun decode(text: String?): BitgetSnapshot? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trim() != HEADER) return null
        var fetchedAt = 0L
        var notice: String? = null
        val assets = ArrayList<BitgetAsset>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split(SEP)
            when (parts.firstOrNull()) {
                ROW_AT -> fetchedAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                ROW_NOTICE -> notice = parts.drop(1).joinToString(SEP).let { unescape(it) }
                ROW_ASSET -> {
                    if (parts.size < 7) continue
                    val available = parts[4].toDoubleOrNull() ?: continue
                    val onHold = parts[5].toDoubleOrNull() ?: continue
                    val price = parts[6].toDoubleOrNull() ?: continue
                    if (!available.isFinite() || !onHold.isFinite() || !price.isFinite()) continue
                    assets += BitgetAsset(
                        account = unescape(parts[1]),
                        symbol = unescape(parts[2]),
                        caption = unescape(parts[3]),
                        available = available,
                        onHold = onHold,
                        priceUsd = price
                    )
                }
                else -> Unit
            }
        }
        return BitgetSnapshot(assets = assets, fetchedAt = fetchedAt, notice = notice)
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '|' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                append(char)
                index++
            } else {
                when (val next = value[index + 1]) {
                    '\\' -> append('\\')
                    'b' -> append('|')
                    'n' -> append('\n')
                    else -> append(next)
                }
                index += 2
            }
        }
    }
}
