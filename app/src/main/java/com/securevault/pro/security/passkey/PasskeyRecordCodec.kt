package com.securevault.pro.security.passkey

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The stored form of a [PasskeyRecord]: flat JSON, five descriptive fields, two timestamps, two
 * counters, and exactly one field that can hold secret material — [PasskeyRecord.sealedMaterial].
 *
 * Hand-rolled rather than reflection-based, for the same reasons `BitgetSnapshotCodec` and
 * `RealtimeRecordCodec` are: it round-trips in a plain JVM test, it survives a field rename without a
 * Room migration, and the key set is checkable, so the guard test can assert that nothing but
 * `sealedMaterial` is ever capable of carrying a passkey's bytes.
 *
 * What is in the clear is what the app needs in order to *list* without decrypting — the relying-party
 * host, the label, the credential id, the dates. That is the same line `VaultRepository` draws (a
 * login's title and website stored plainly, its password sealed), and it is why this screen still
 * shows the owner their passkeys when the data key has a bad day. What is never in the clear is
 * anything the credential provider returned.
 */
object PasskeyRecordCodec {

    const val KEY_ID = "id"
    const val KEY_RP_DOMAIN = "rpDomain"
    const val KEY_RP_NAME = "rpName"
    const val KEY_CREDENTIAL_ID = "credentialId"
    const val KEY_USER_NAME = "userName"
    const val KEY_LABEL = "label"
    const val KEY_TRANSPORT = "transport"
    const val KEY_CREATED_AT = "createdAt"
    const val KEY_LAST_USED_AT = "lastUsedAt"
    const val KEY_USE_COUNT = "useCount"
    const val KEY_SEALED_MATERIAL = "sealedMaterial"

    /** Every key this writer produces. A record with a key outside it was not written by us. */
    val ALL_KEYS = setOf(
        KEY_ID, KEY_RP_DOMAIN, KEY_RP_NAME, KEY_CREDENTIAL_ID, KEY_USER_NAME, KEY_LABEL,
        KEY_TRANSPORT, KEY_CREATED_AT, KEY_LAST_USED_AT, KEY_USE_COUNT, KEY_SEALED_MATERIAL
    )

    /** The one key that holds provider material. Nothing else may. */
    val SEALED_KEYS = setOf(KEY_SEALED_MATERIAL)

    fun encode(record: PasskeyRecord): String {
        val root = JsonObject()
        root.addProperty(KEY_ID, record.id)
        root.addProperty(KEY_RP_DOMAIN, record.rpDomain)
        root.addProperty(KEY_RP_NAME, record.rpName)
        root.addProperty(KEY_CREDENTIAL_ID, record.credentialId)
        root.addProperty(KEY_USER_NAME, record.userName)
        root.addProperty(KEY_LABEL, record.label)
        root.addProperty(KEY_TRANSPORT, record.transport)
        root.addProperty(KEY_CREATED_AT, record.createdAt)
        root.addProperty(KEY_LAST_USED_AT, record.lastUsedAt)
        root.addProperty(KEY_USE_COUNT, record.useCount)
        root.addProperty(KEY_SEALED_MATERIAL, record.sealedMaterial)
        return root.toString()
    }

    /**
     * Null for anything this writer did not produce: not JSON, missing an id or a domain, or carrying
     * a `sealedMaterial` that is not a `{v:3}` container. A damaged record is dropped by the caller
     * rather than half-read, because a half-read passkey would be offered for a sign-in it cannot
     * complete.
     */
    fun decode(text: String?): PasskeyRecord? {
        val fields = decodeFields(text) ?: return null
        return PasskeyRecord(
            id = fields[KEY_ID].orEmpty(),
            rpDomain = fields[KEY_RP_DOMAIN].orEmpty(),
            rpName = fields[KEY_RP_NAME].orEmpty(),
            credentialId = fields[KEY_CREDENTIAL_ID].orEmpty(),
            userName = fields[KEY_USER_NAME].orEmpty(),
            label = fields[KEY_LABEL].orEmpty(),
            transport = fields[KEY_TRANSPORT].orEmpty().ifBlank { PasskeyTransports.INTERNAL },
            createdAt = fields[KEY_CREATED_AT]?.toLongOrNull() ?: 0L,
            lastUsedAt = fields[KEY_LAST_USED_AT]?.toLongOrNull() ?: 0L,
            useCount = fields[KEY_USE_COUNT]?.toIntOrNull() ?: 0,
            sealedMaterial = fields[KEY_SEALED_MATERIAL].orEmpty()
        ).takeIf { isValid(it) }
    }

    /** Round trip through [encode] minus the ordering assumptions: every field survives, none drifts. */
    fun decodeFields(text: String?): Map<String, String>? {
        if (text.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val out = LinkedHashMap<String, String>()
        ALL_KEYS.forEach { key ->
            val element = root.get(key) ?: return@forEach
            if (!element.isJsonPrimitive) return@forEach
            out[key] = runCatching { element.asString }.getOrNull() ?: return@forEach
        }
        return out
    }

    /** A record worth showing: a real domain, a credential id to ask for, and sealed material. */
    fun isValid(record: PasskeyRecord): Boolean =
        record.id.isNotBlank() &&
            PasskeyDomainRules.isRegistrable(record.rpDomain) &&
            record.credentialId.isNotBlank() &&
            record.sealedMaterial.isNotBlank() &&
            PasskeyEnvelope.readSealedBlob(record.sealedMaterial) != null

    /** Only the descriptive fields, for a UI that must not decrypt anything to draw a row. */
    fun describe(record: PasskeyRecord): PasskeyDescription = PasskeyDescription(
        id = record.id,
        rpDomain = record.rpDomain,
        rpName = record.rpName.ifBlank { record.rpDomain },
        label = record.label.ifBlank { PasskeyVault.labelFor(record.userName, record.rpDomain) },
        credentialTail = tailOf(record.credentialId),
        transportLabel = PasskeyTransports.labelFor(record.transport),
        createdAt = record.createdAt,
        lastUsedAt = record.lastUsedAt,
        useCount = record.useCount,
        hasSealedMaterial = PasskeyEnvelope.readSealedBlob(record.sealedMaterial) != null
    )

    /** Last eight characters of a credential id — enough to tell two keys apart, useless alone. */
    fun tailOf(credentialId: String): String {
        val trimmed = credentialId.trim()
        if (trimmed.length <= 8) return trimmed
        return "…" + trimmed.takeLast(8)
    }

    /**
     * True when [text] carries a field name that would only ever mean passkey secret material. Used
     * by the never-synced guard, and by [PasskeyStore] before it writes a snapshot-like blob.
     */
    fun carriesSecret(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lowered = text.lowercase()
        return PasskeyMaterialKeys.SECRET_WORDS.any { lowered.contains(it) }
    }
}

/** A renderable, decryption-free view of one passkey record. */
data class PasskeyDescription(
    val id: String,
    val rpDomain: String,
    val rpName: String,
    val label: String,
    val credentialTail: String,
    val transportLabel: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
    val hasSealedMaterial: Boolean
)
