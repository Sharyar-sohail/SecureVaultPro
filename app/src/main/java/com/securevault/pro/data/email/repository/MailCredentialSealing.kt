package com.securevault.pro.data.email.repository

import com.securevault.pro.security.CryptoManager
import java.util.Base64
import javax.inject.Inject

/**
 * Sealing a mailbox credential with the vault's own crypto.
 *
 * Nothing new is invented here: the envelope the app already uses for a vault entry's password, a
 * note body or a TOTP secret is `base64(iv).base64(ciphertext+tag)` — AES-256-GCM, a fresh 12-byte
 * IV, a 128-bit tag — under the data key that `CryptoManager` keeps inside the Android Keystore.
 * [MailSecretSealer] is that pair of calls behind an interface, exactly as
 * `BitgetSecretSealer` does for exchange credentials, so the account store is written against the
 * sealing *contract* and the unit test can run the real envelope maths on the JVM
 * ([KeystoreFreeMailSealer] is that test seam's production twin: the same transformation and the
 * same envelope, keyed by the app's own PBKDF2 path in `KeyringCodec`).
 *
 * The plaintext password exists in four places only: the secure keyboard's buffer, the form field
 * in the ViewModel while the owner is typing, the inside of [MailSecretSealer.seal], and the memory
 * of the one connection attempt that needs it. It is never a column, never a preference, never a
 * log line and never part of an exception message.
 */
interface MailSecretSealer {

    /** The envelope for [plaintext], or null when this device cannot seal anything at all. */
    fun seal(plaintext: String): String?

    /** The plaintext, or null when the blob is damaged, foreign or sealed by another device. */
    fun open(ciphertext: String?): String?
}

/** The production sealer: `CryptoManager`, i.e. the vault's own Keystore-wrapped data key. */
class CryptoManagerMailSealer @Inject constructor(
    private val crypto: CryptoManager
) : MailSecretSealer {

    override fun seal(plaintext: String): String? =
        if (plaintext.isEmpty()) null else runCatching { crypto.encrypt(plaintext) }.getOrNull()

    override fun open(ciphertext: String?): String? =
        if (ciphertext.isNullOrBlank()) null else runCatching { crypto.decrypt(ciphertext) }.getOrNull()
}

/**
 * A sealer built on [com.securevault.pro.data.remote.firebase.KeyringCodec]'s AES-256-GCM helpers,
 * which are the same transformation, IV length and envelope text as `CryptoManager` but carry no
 * Android types. Used by the unit tests, and as the fallback the store takes when the device's
 * Keystore key is unavailable so the owner is never told "nothing can be saved" without a reason.
 */
class KeystoreFreeMailSealer(
    private val key: ByteArray,
    private val codec: SealCodec = DefaultSealCodec
) : MailSecretSealer {

    interface SealCodec {
        fun seal(plain: ByteArray, key: ByteArray): String
        fun open(envelope: String, key: ByteArray): ByteArray?
    }

    override fun seal(plaintext: String): String? =
        if (plaintext.isEmpty()) null else runCatching {
            codec.seal(plaintext.toByteArray(Charsets.UTF_8), key)
        }.getOrNull()

    override fun open(ciphertext: String?): String? {
        if (ciphertext.isNullOrBlank()) return null
        return runCatching {
            codec.open(ciphertext, key)?.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        /** The production codec, wired to the app's existing keyring primitives. */
        val DefaultSealCodec = object : SealCodec {
            override fun seal(plain: ByteArray, key: ByteArray): String =
                com.securevault.pro.data.remote.firebase.KeyringCodec.sealBytes(plain, key)

            override fun open(envelope: String, key: ByteArray): ByteArray? =
                com.securevault.pro.data.remote.firebase.KeyringCodec.openBytes(envelope, key)
        }
    }
}

/** Why a credential could not be stored, in one sentence the screen can show unchanged. */
data class MailSealOutcome(val ok: Boolean, val sealed: String, val message: String) {
    companion object {
        fun saved(sealed: String) = MailSealOutcome(true, sealed, "")
        fun refused(message: String) = MailSealOutcome(false, "", message)
    }
}

/**
 * The pure half of credential storage: decide whether a typed secret may be sealed, seal it, and
 * describe the result. Kept separate from the store so every rule here is assertable without
 * Android, Room or a socket.
 */
class MailCredentialVault(private val sealer: MailSecretSealer) {

    /**
     * Seal a typed password for storage. An empty value means "an OAuth account, no secret kept" and
     * is a success with an empty blob; a value that would not seal is refused with a reason rather
     * than stored in the clear.
     */
    fun sealForStorage(plaintext: String?): MailSealOutcome {
        val value = plaintext.orEmpty()
        if (value.isEmpty()) return MailSealOutcome.saved("")
        val problem = MailCredentials.validateSecret(value)
        if (problem != null) return MailSealOutcome.refused(problem)
        val sealed = sealer.seal(value)
        if (sealed.isNullOrBlank()) {
            return MailSealOutcome.refused(
                "This device could not open its security key, so the password was not saved. " +
                    "Restart the app and try again."
            )
        }
        if (!MailCredentials.looksSealed(sealed)) {
            // A blob that is not the vault's envelope would be a plaintext leak the moment it is
            // written, so it is refused instead of trusted.
            return MailSealOutcome.refused(
                "The password could not be sealed into the vault's encrypted envelope, so nothing " +
                    "was saved. Try again in a moment."
            )
        }
        return MailSealOutcome.saved(sealed)
    }

    /** The plaintext for one connection attempt. Null means "this device cannot read that blob". */
    fun openForConnection(sealed: String?): String? =
        if (sealed.isNullOrBlank()) null else sealer.open(sealed)

    /**
     * True when [storedRow] would expose the secret it is supposed to hide. The account store runs
     * this before writing a row and the guard test runs it over every field name the row can carry.
     */
    fun rowLeaksSecret(storedRow: String, typedSecret: String): Boolean =
        typedSecret.isNotBlank() && storedRow.contains(typedSecret)
}

/** Naming, shape and sanity rules for a stored mail credential. */
object MailCredentials {

    /** An OAuth account's column value: no secret is kept, and none is needed. */
    const val NO_SEALED_CREDENTIAL = ""

    /** The prefixes/keys a mail row may never contain in the clear. */
    val SECRET_FIELD_NAMES = listOf(
        "password", "credential", "sealedcredential", "credentialsealed", "apptoken", "accesstoken"
    )

    /** The column that holds the envelope, named once so the guard test can point at it. */
    const val CREDENTIAL_COLUMN = "credentialSealed"

    /**
     * The envelope shape `CryptoManager` writes: `base64(iv).base64(ciphertext+tag)` with a 12-byte
     * IV and a tag at least 16 bytes long. Pure base64 maths, so the JVM test checks the real shape
     * rather than a name.
     */
    fun looksSealed(value: String?): Boolean {
        val parts = value?.trim()?.split('.') ?: return false
        if (parts.size != 2) return false
        val iv = decodeBase64(parts[0]) ?: return false
        val body = decodeBase64(parts[1]) ?: return false
        return iv.size == 12 && body.size > 16
    }

    /** Tolerant base64 (padding optional); null for anything that is not base64 at all. */
    fun decodeBase64(value: String): ByteArray? {
        val cleaned = value.trim().filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: runCatching {
                Base64.getUrlDecoder().decode(padded.replace('+', '-').replace('/', '_'))
            }.getOrNull()
    }

    /**
     * The rules a typed secret must satisfy before it is sealed. Deliberately minimal: a server on
     * a custom domain may accept a short password, so length is never a reason to refuse the owner's
     * own credential — only a value that cannot be one is (blank, a stray line break, or a paste so
     * large it is clearly something else).
     */
    fun validateSecret(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return "Enter the password or app password for this mailbox."
        if (value.any { it == '\r' || it == '\n' }) {
            return "The password contains a line break. Type it again without one."
        }
        if (value.length > 512) {
            return "That is far longer than any mail password. Check you pasted the password, not a key file."
        }
        return null
    }

    /** What the account row should say about its credential. Never shows the value. */
    fun describe(sealed: String?, usesOAuth: Boolean): String = when {
        usesOAuth -> "Signed in with Google"
        sealed.isNullOrBlank() -> "No password saved yet"
        looksSealed(sealed) -> "Password saved, encrypted in the vault"
        else -> "The stored password could not be read on this device"
    }

    /** True when the text names a secret field; the never-synced guard uses it on payloads. */
    fun carriesSecretName(text: String): Boolean {
        val lowered = text.lowercase()
        return SECRET_FIELD_NAMES.any { lowered.contains(it) }
    }
}
