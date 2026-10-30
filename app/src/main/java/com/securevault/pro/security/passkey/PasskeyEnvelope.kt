package com.securevault.pro.security.passkey

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * The one place that decides what "sealed" means for a passkey record.
 *
 * Nothing here is new crypto. [PasskeySealer] is the same two-method shape the Bitget credential
 * vault uses ([com.securevault.pro.data.wallet.BitgetSecretSealer]) so the pattern stays recognisable,
 * and on the device it is bound to [com.securevault.pro.security.CryptoManager] — i.e. AES-256-GCM,
 * 12-byte IV, 128-bit tag, a fresh IV per call, under the single Keystore-wrapped data key
 * `SecureVaultProMasterKey` that already seals every password, note, TOTP secret and wallet seed in
 * the app. Wrapped in the app's existing `{v:3}` envelope, so a stored passkey is the same
 * versioned, self-describing container a backup is, and a future reader can tell which writer made it.
 *
 * Two consequences worth stating plainly:
 *  - the envelope is *not* what protects a passkey from the owner's own backed-up device being read.
 *    It is the same device key the rest of the vault uses, so anything that can open the vault opens
 *    these records, and anything that cannot opens neither.
 *  - [PasskeySealer] exists as a seam, not as a choice. Production has exactly one implementation.
 *    The JVM tests use a second one that writes the identical `base64(iv).base64(ciphertext+tag)`
 *    container with `javax.crypto`, which is what makes the record logic testable at all.
 */
interface PasskeySealer {
    fun seal(plaintext: String): String?
    fun open(ciphertext: String?): String?
}

/**
 * The app's `{v:3}` envelope, read and written the way `CloudSyncManager` writes it: a JSON object
 * with a version, and `data` holding a `base64(iv).base64(ciphertext+tag)` blob.
 *
 * Written *without* `salt`, because a passkey record is sealed by the device key directly, not by a
 * PBKDF2-derived one — a salt field here would advertise a KDF that is not in the path. The reader
 * still accepts `salt` and the legacy `ciphertext`/`payload` aliases, so a record that was once
 * copied out of a cloud envelope parses rather than being read as garbage.
 */
object PasskeyEnvelope {

    const val VERSION = 3

    /** Exactly the two characters [com.securevault.pro.security.CryptoManager] uses in its blob. */
    private const val BLOB_SEPARATOR = '.'

    fun wrap(sealedBlob: String, version: Int = VERSION): String {
        val root = JsonObject()
        root.addProperty("v", version)
        root.addProperty("data", sealedBlob)
        return root.toString()
    }

    /** Null when [text] is not an envelope this writer (or the cloud one) would have produced. */
    fun readSealedBlob(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim().removePrefix("﻿")
        if (!trimmed.startsWith("{")) {
            // Pre-envelope records: the blob itself, stored bare.
            return if (looksLikeBlob(trimmed)) trimmed else null
        }
        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
            ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val data = obj.stringOrNull("data") ?: obj.stringOrNull("ciphertext")
        ?: obj.stringOrNull("payload") ?: return null
        val version = obj.get("v")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: obj.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: VERSION
        if (version > VERSION) return null
        val blob = data.trim()
        // A dotted payload is only accepted when it really is a GCM container. Returning any
        // string with a dot in it would let a plaintext field — `text.something` — pass as
        // "sealed material", which is exactly the claim [readSealedBlob] exists to refuse.
        if (blob.contains(BLOB_SEPARATOR)) return blob.takeIf { looksLikeBlob(it) }
        // `{v, iv, data}` split form, which the cloud reader also folds back together.
        val iv = obj.stringOrNull("iv") ?: return null
        return "$iv$BLOB_SEPARATOR$blob".takeIf { looksLikeBlob(it) }
    }

    /** Version of the stored container, or null when it is not one. */
    fun readVersion(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim().removePrefix("﻿")
        if (!trimmed.startsWith("{")) return if (looksLikeBlob(trimmed)) VERSION else null
        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        return root.asJsonObject.get("v")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: root.asJsonObject.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: VERSION
    }

    /** Shape check only, no decryption: two base64 fields, a 12-byte IV, and a body over the tag. */
    fun looksLikeBlob(value: String): Boolean {
        val parts = value.trim().split(BLOB_SEPARATOR)
        if (parts.size != 2) return false
        val iv = decodeStandard(parts[0]) ?: return false
        val body = decodeStandard(parts[1]) ?: return false
        return iv.size == IV_BYTES && body.size > TAG_BYTES
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()
    }

    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16

    private fun decodeStandard(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value.trim().replace("\n", "").replace("\r", ""))
    }.getOrNull()
}

/**
 * What an enrolment produced, before it becomes a record. Everything here came back from the
 * credential provider in one call; the vault's job is to seal [registrationResponseJson] together with
 * the [userHandle] the app generated for it — the handle is *not* echoed back in the response, so a
 * record that did not keep it could never tell the RP which account the key belongs to — and refuse to
 * store either if the seal fails.
 */
data class PasskeyEnrolment(
    val rpDomain: String,
    val rpName: String,
    val userName: String,
    val userDisplayName: String = userName,
    val credentialId: String,
    val registrationResponseJson: String,
    val userHandle: String,
    val transport: String = PasskeyTransports.INTERNAL,
    val enrolledAt: Long = System.currentTimeMillis()
)

/**
 * Pure record logic: build a sealed record from an enrolment, open a record's material, and update a
 * record after a successful assertion. No Android, no Coroutines, no Keystore — [PasskeyVault] takes
 * a [PasskeySealer] and a clock, so everything the service does to the data is testable directly.
 */
class PasskeyVault(private val sealer: PasskeySealer) {

    /** A record, or the reason nothing may be written. Never a record holding plaintext material. */
    fun recordFor(enrolment: PasskeyEnrolment): SealedRecord {
        val domain = PasskeyDomainRules.normalize(enrolment.rpDomain)
        if (!PasskeyDomainRules.isRegistrable(domain)) {
            return SealedRecord.rejected(
                "That is not a domain a passkey can be enrolled against. " +
                    "Use the site's host, like example.com."
            )
        }
        val response = enrolment.registrationResponseJson.trim()
        if (response.isBlank()) {
            return SealedRecord.rejected("The credential provider returned nothing to store.")
        }
        val credentialId = enrolment.credentialId.trim().ifBlank {
            PasskeyWire.credentialIdOf(response).orEmpty()
        }
        if (credentialId.isBlank()) {
            return SealedRecord.rejected(
                "The registration response carried no credential id, so this passkey could not be " +
                    "matched to a site later. Nothing was stored."
            )
        }
        val sealed = runCatching { sealer.seal(materialJsonFor(enrolment, response)) }.getOrNull()
        if (sealed.isNullOrBlank()) {
            return SealedRecord.rejected(
                "This device could not open its security key, so the passkey was not saved. " +
                    "Unlock the vault and try again — the key itself is still on the device."
            )
        }
        return SealedRecord(
            ok = true,
            message = null,
            record = PasskeyRecord(
                id = PasskeyWire.newRecordId(),
                rpDomain = domain,
                rpName = enrolment.rpName.trim().ifBlank { domain },
                credentialId = credentialId,
                userName = enrolment.userName.trim(),
                label = PasskeyVault.labelFor(enrolment.userName, domain),
                transport = enrolment.transport.ifBlank { PasskeyTransports.INTERNAL },
                createdAt = enrolment.enrolledAt,
                lastUsedAt = enrolment.enrolledAt,
                useCount = 0,
                sealedMaterial = PasskeyEnvelope.wrap(sealed)
            )
        )
    }

    /**
     * The provider's registration response, or null when this device cannot open its own key.
     * Callers must treat the result as secret material: pass it to the provider, never to a log,
     * a snackbar or an exception message.
     */
    fun openMaterial(record: PasskeyRecord): String? =
        openFields(record)?.string(PasskeyMaterialKeys.RESPONSE)

    /** The account handle generated at enrolment, which the RP echoed back at assertion time. */
    fun openUserHandle(record: PasskeyRecord): String? =
        openFields(record)?.string(PasskeyMaterialKeys.HANDLE)

    /** Every field of the sealed material, or null when the seal will not open. */
    fun openFields(record: PasskeyRecord): PasskeyMaterialFields? {
        val blob = PasskeyEnvelope.readSealedBlob(record.sealedMaterial) ?: return null
        val plaintext = runCatching { sealer.open(blob) }.getOrNull() ?: return null
        return PasskeyMaterialFields.parse(plaintext)
    }

    /**
     * A successful assertion recorded. Only counters and dates move — the sealed material is written
     * once, at enrolment, and never rewritten, so a failed update cannot corrupt the one copy we have.
     */
    fun markUsed(record: PasskeyRecord, now: Long): PasskeyRecord = record.copy(
        lastUsedAt = now,
        useCount = record.useCount + 1
    )

    /** Replaces the label only; the owner's name for a key is the one thing worth re-sealing around. */
    fun relabel(record: PasskeyRecord, label: String): PasskeyRecord = record.copy(
        label = label.trim().take(60).ifBlank { record.label }
    )

    companion object {
        fun labelFor(userName: String, domain: String): String {
            val user = userName.trim()
            return when {
                user.isBlank() -> domain
                user.lowercase() == domain -> domain
                else -> "$user · $domain"
            }
        }
    }

    /**
     * The document that gets sealed. Flat, five fields, and the only writer of the registration
     * response — so there is exactly one place a passkey's secret material is assembled, and a test
     * can assert that no field of it ever leaves the app unsealed.
     */
    private fun materialJsonFor(enrolment: PasskeyEnrolment, response: String): String {
        val root = JsonObject()
        root.addProperty(PasskeyMaterialKeys.RESPONSE, response)
        root.addProperty(PasskeyMaterialKeys.HANDLE, enrolment.userHandle.trim())
        root.addProperty(PasskeyMaterialKeys.USER_NAME, enrolment.userName.trim())
        root.addProperty(PasskeyMaterialKeys.RP_DOMAIN, PasskeyDomainRules.normalize(enrolment.rpDomain))
        root.addProperty(PasskeyMaterialKeys.ENROLLED_AT, enrolment.enrolledAt)
        return root.toString()
    }
}

/**
 * Field names inside the sealed material document, in one object so the never-synced guard test can
 * name them — the same reason `BitgetCredentialFields` exists.
 */
object PasskeyMaterialKeys {
    const val RESPONSE = "registrationResponse"
    const val HANDLE = "userHandle"
    const val USER_NAME = "userName"
    const val RP_DOMAIN = "rpDomain"
    const val ENROLLED_AT = "enrolledAt"

    /** Every field the sealed document can hold; nothing else may be sealed in here. */
    val ALL_FIELDS = setOf(RESPONSE, HANDLE, USER_NAME, RP_DOMAIN, ENROLLED_AT)

    /**
     * The field names that would mean provider secret material if one ever appeared outside the seal.
     * `sealedMaterial` is deliberately absent: it is this app's own *container* name and appears in
     * every stored record, so listing it would make the guard fire on correct data.
     */
    val SECRET_WORDS = listOf(
        "registrationresponse", "userhandle", "attestationobject", "authenticatordata",
        "clientdatajson", "passkeymaterial", "credentialprivatekey", "privatekey"
    )
}

/**
 * The parsed sealed document. Reads only the keys it knows and refuses anything else, so a record
 * written by a different writer is not silently accepted — the same discipline `RealtimeFieldSchema`
 * applies to sync payloads.
 */
class PasskeyMaterialFields private constructor(private val values: Map<String, String>) {

    fun string(key: String): String? = values[key]?.takeIf { it.isNotBlank() }

    fun long(key: String): Long? = values[key]?.toLongOrNull()

    fun hasField(key: String): Boolean = !values[key].isNullOrBlank()

    companion object {
        /** Null when the plaintext is not a material document this vault wrote. */
        fun parse(plaintext: String?): PasskeyMaterialFields? {
            if (plaintext.isNullOrBlank()) return null
            val root = runCatching { JsonParser.parseString(plaintext) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val response = root.get(PasskeyMaterialKeys.RESPONSE)?.takeIf { it.isJsonPrimitive }
                ?.asString ?: return null
            if (response.isBlank()) return null
            val values = LinkedHashMap<String, String>()
            PasskeyMaterialKeys.ALL_FIELDS.forEach { key ->
                val element = root.get(key) ?: return@forEach
                if (element.isJsonPrimitive) values[key] = element.asString
            }
            return PasskeyMaterialFields(values)
        }
    }
}

/** The outcome of [PasskeyVault.recordFor]: either a record to write, or why nothing was written. */
data class SealedRecord(
    val ok: Boolean,
    val message: String?,
    val record: PasskeyRecord?
) {
    companion object {
        fun rejected(message: String) = SealedRecord(ok = false, message = message, record = null)
    }
}

/**
 * The wire-format helpers a WebAuthn JSON response has to be read with: base64url without padding,
 * and the two fields a credential id can arrive in. Pure JVM, and the only place that knows the
 * provider's JSON shape, so [PasskeyWire] is what the tests exercise rather than the Android layer.
 */
object PasskeyWire {

    /** `rawId` if present, else `id`; base64url-normalised so it compares equal to a stored one. */
    fun credentialIdOf(responseJson: String?): String? {
        if (responseJson.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(responseJson) }.getOrNull()
            ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val raw = obj.stringOrNull("rawId") ?: obj.stringOrNull("id") ?: return null
        return normalizeBase64Url(raw)
    }

    /**
     * The `clientDataJSON` inside a response, decoded. It is public by design — the challenge and the
     * origin are in it — which is what lets the list screen describe a passkey without decrypting the
     * sealed attestation object. [isCrossPlatform] comes from `authenticatorAttachment`, the field the
     * provider sets when the key lives on another device reached by QR.
     */
    fun clientDataOf(responseJson: String?): PasskeyClientData? {
        if (responseJson.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(responseJson) }.getOrNull()
            ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val encoded = obj.getAsJsonObject("response")?.stringOrNull("clientDataJSON") ?: return null
        val decoded = decodeBase64UrlToString(encoded) ?: return null
        val clientData = runCatching { JsonParser.parseString(decoded) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return PasskeyClientData(
            origin = clientData.stringOrNull("origin").orEmpty(),
            challenge = clientData.stringOrNull("challenge").orEmpty(),
            isCrossPlatform = obj.stringOrNull("authenticatorAttachment")
                ?.equals("cross-platform", ignoreCase = true) == true
        )
    }

    fun normalizeBase64Url(value: String?): String {
        val trimmed = value.orEmpty().filterNot { it == '\n' || it == '\r' }.trim()
        if (trimmed.isEmpty()) return ""
        val swapped = trimmed.replace('+', '-').replace('/', '_')
        return swapped.trimEnd('=')
    }

    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Raw bytes of a base64url field, padding-insensitive; null when it is not base64url at all. */
    fun decodeBase64UrlToBytes(value: String?): ByteArray? {
        val normalized = normalizeBase64Url(value)
        if (normalized.isEmpty()) return null
        return runCatching { Base64.getUrlDecoder().decode(normalized) }.getOrNull()
    }

    fun decodeBase64UrlToString(value: String?): String? =
        decodeBase64UrlToBytes(value)?.toString(Charsets.UTF_8)

    /** A record id that is not a credential id, so the two can never be confused in a log. */
    fun newRecordId(): String = "pk-" + java.util.UUID.randomUUID().toString().take(18)

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        // A JSON number would stringify fine, but no WebAuthn field this reads is ever numeric:
        // accepting one would let `{"id":12}` pass as a credential id.
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return runCatching { element.asString }.getOrNull()
    }
}

/** What a response's `clientDataJSON` says publicly. */
data class PasskeyClientData(
    val origin: String,
    val challenge: String,
    val isCrossPlatform: Boolean
)

/** Transport strings the app records; `internal` is a device-bound key, which is what a passkey is. */
object PasskeyTransports {
    const val INTERNAL = "internal"
    const val HYBRID = "hybrid"
    const val USB = "usb"
    const val NFC = "nfc"

    val KNOWN = setOf(INTERNAL, HYBRID, USB, NFC)

    fun labelFor(transport: String): String = when (transport.lowercase()) {
        INTERNAL -> "On this device"
        HYBRID -> "Scanned with another device"
        USB -> "Security key (USB)"
        NFC -> "Security key (NFC)"
        else -> transport.ifBlank { "Unknown transport" }
    }
}
