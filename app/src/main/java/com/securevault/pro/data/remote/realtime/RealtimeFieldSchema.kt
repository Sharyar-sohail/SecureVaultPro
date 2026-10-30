package com.securevault.pro.data.remote.realtime

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import com.securevault.pro.data.local.entity.DigitalCardEntity
import com.securevault.pro.data.local.entity.SecureContactEntity
import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.TotpEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import java.io.StringReader

/**
 * The published field table of the record plaintext, one row per JSON key, for all seven modules.
 *
 * `RealtimeRecordCodec` is the writer/reader; this object is the *referee*: it knows which keys a
 * module must have, which may be `null`, and what JSON type each value must be. It is what
 * [decodeRecord] validates against and what `sync_engine/fixtures/<module>/record.json` is checked
 * against in the unit tests, so the fixtures, the phone and the Blogger page cannot drift apart
 * silently.
 *
 * Pure JVM, no Android, no `org.json` (a throwing stub under unit tests), no Firebase.
 */
object RealtimeFieldSchema {

    /** The JSON type a record key must hold. */
    enum class JsonType { STRING, INT, LONG, DOUBLE, BOOLEAN }

    /** One contract row: [name] on the wire, its [type], and whether `null`/absence is legal. */
    data class Field(val name: String, val type: JsonType, val nullable: Boolean = false) {
        /** Human-readable cell for the published table, e.g. `expiresAt LONG?`. */
        fun describe(): String = if (nullable) "$name ${type.name}?" else "$name ${type.name}"
    }

    private val SPECS: Map<RealtimeModule, List<Field>> = linkedMapOf(
        RealtimeModule.NOTES to listOf(
            f("id"), f("title"), f("content"), b("isPinned"), f("color"), f("tagsJson"),
            l("createdAt"), l("updatedAt")
        ),
        RealtimeModule.ENTRIES to listOf(
            f("id"), f("title"), f("username"), f("email"), f("password"), f("website"), f("url"),
            f("notes"), f("category"), f("customFieldsJson"), f("tagsJson"), b("isFavorite"),
            b("hasTotp"), f("totpSecret"), l("createdAt"), l("updatedAt"),
            l("lastAccessedAt", true), l("expiresAt", true), i("strengthScore"),
            l("deletedAt", true), b("isHiddenInTravel")
        ),
        RealtimeModule.CARDS to listOf(
            f("id"), f("type"), f("nickname"), f("holderName"), f("number"), i("expiryMonth"),
            i("expiryYear"), f("cvv"), f("pin"), f("bankName"), f("notes"), f("colorGradientStart"),
            f("colorGradientEnd"), l("createdAt")
        ),
        RealtimeModule.TOTPS to listOf(
            f("id"), f("issuer"), f("accountName"), f("secret"), f("algorithm"), i("digits"),
            i("period"), l("createdAt")
        ),
        RealtimeModule.CONTACTS to listOf(
            f("id"), f("name"), f("phone"), f("email"), f("address"), f("notes"), f("category"),
            l("createdAt"), l("updatedAt")
        ),
        RealtimeModule.CRYPTO_WALLETS to listOf(
            f("id"), f("label"), f("kind"), f("secret"), f("addressesJson"), f("balancesJson"),
            d("totalUsd"), l("lastSyncedAt"), l("createdAt"), l("updatedAt")
        ),
        RealtimeModule.FILES to listOf(
            f("id"), f("name"), l("size"), f("mimeType"), f("category"), l("createdAt"),
            l("uploadedAt"), f("noteId", true), f("folder", true), f("driveId", true)
        )
    )

    /** All seven modules, in `RealtimePaths.MODULES` order. */
    val MODULES: List<RealtimeModule> = SPECS.keys.toList()

    /** The contract rows of [module], in documented order. */
    fun fieldsOf(module: RealtimeModule): List<Field> = SPECS.getValue(module)

    /** The JSON key names of [module], in documented order. */
    fun keysOf(module: RealtimeModule): List<String> = fieldsOf(module).map { it.name }

    /** The keys that may be `null` or absent on the wire — everything else is required. */
    fun optionalKeysOf(module: RealtimeModule): Set<String> =
        fieldsOf(module).filter { it.nullable }.map { it.name }.toSet()

    /** The class a module's plaintext decodes into. */
    fun classOf(module: RealtimeModule): Class<*> = when (module) {
        RealtimeModule.NOTES -> SecureNoteEntity::class.java
        RealtimeModule.ENTRIES -> VaultEntryEntity::class.java
        RealtimeModule.CARDS -> DigitalCardEntity::class.java
        RealtimeModule.TOTPS -> TotpEntity::class.java
        RealtimeModule.CONTACTS -> SecureContactEntity::class.java
        RealtimeModule.CRYPTO_WALLETS -> CryptoWalletEntity::class.java
        RealtimeModule.FILES -> VaultFile::class.java
    }

    /** The module an entity class belongs to, or null when it is not a vault record. */
    fun moduleOf(entity: Any): RealtimeModule? = when (entity.javaClass) {
        SecureNoteEntity::class.java -> RealtimeModule.NOTES
        VaultEntryEntity::class.java -> RealtimeModule.ENTRIES
        DigitalCardEntity::class.java -> RealtimeModule.CARDS
        TotpEntity::class.java -> RealtimeModule.TOTPS
        SecureContactEntity::class.java -> RealtimeModule.CONTACTS
        CryptoWalletEntity::class.java -> RealtimeModule.CRYPTO_WALLETS
        VaultFile::class.java -> RealtimeModule.FILES
        else -> null
    }

    /**
     * Strict parse of a record body: exactly one JSON object, no trailing content, no lenient
     * syntax. Null for anything else, so a caller never has to catch a parser.
     */
    fun parseObject(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return try {
            val reader = JsonReader(StringReader(json)).apply { isLenient = false }
            // The declared type is load-bearing: `fromJson(reader, JsonObject::class.java)` on its
            // own lets Kotlin infer the wrong type variable for Gson's overloads and the read throws
            // a ClassCastException at run time, which the strict parser below would report as null.
            val tree: JsonObject? = STRICT_GSON.fromJson(reader, JsonObject::class.java)
            if (tree == null) return null
            if (reader.peek() != JsonToken.END_DOCUMENT) null else tree
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The module a plaintext came from, decided by its key set, or null when nothing matches or
     * more than one shape fits. Unknown keys disqualify every module rather than being ignored:
     * a foreign field means either the wrong module or a leaked plaintext.
     */
    fun identify(tree: JsonObject): RealtimeModule? {
        val keys = tree.keySet()
        if (keys.isEmpty()) return null
        return MODULES.filter { module ->
            val fields = fieldsOf(module)
            keys.none { key -> fields.none { it.name == key } } &&
                fields.filterNot { it.nullable }.all { field -> keys.contains(field.name) }
        }.singleOrNull()
    }

    /** True when [tree] is a complete, well-typed body of [module]. */
    fun accepts(module: RealtimeModule, tree: JsonObject?): Boolean {
        if (tree == null) return false
        val fields = fieldsOf(module)
        if (identify(tree) != module) return false
        for (field in fields) {
            val value = tree.get(field.name) ?: continue
            if (value.isJsonNull) {
                if (!field.nullable) return false
                continue
            }
            if (!matches(value, field)) return false
        }
        return true
    }

    private fun matches(value: JsonElement, field: Field): Boolean {
        if (!value.isJsonPrimitive) return false
        val primitive = value.asJsonPrimitive
        return when (field.type) {
            JsonType.STRING -> primitive.isString
            JsonType.BOOLEAN -> primitive.isBoolean
            JsonType.DOUBLE -> primitive.isNumber
            JsonType.INT -> primitive.isIntegral() && primitive.asLong in Int.MIN_VALUE..Int.MAX_VALUE
            JsonType.LONG -> primitive.isIntegral()
        }
    }

    /** A JSON number with no fractional part. A quoted number is a string, not a number. */
    private fun JsonPrimitive.isIntegral(): Boolean {
        if (!isNumber) return false
        return runCatching {
            val text = asString
            if (text.contains('.') || text.contains('e') || text.contains('E')) asDouble % 1.0 == 0.0 else true
        }.getOrDefault(false)
    }

    private val STRICT_GSON = GsonBuilder().disableHtmlEscaping().create()

    private fun f(name: String, nullable: Boolean = false) = Field(name, JsonType.STRING, nullable)
    private fun i(name: String, nullable: Boolean = false) = Field(name, JsonType.INT, nullable)
    private fun l(name: String, nullable: Boolean = false) = Field(name, JsonType.LONG, nullable)
    private fun d(name: String, nullable: Boolean = false) = Field(name, JsonType.DOUBLE, nullable)
    private fun b(name: String, nullable: Boolean = false) = Field(name, JsonType.BOOLEAN, nullable)
}

/**
 * A record that has been opened, validated against [RealtimeFieldSchema] and typed.
 *
 * [module] is which segment it belongs to, [entity] the value to hand a DAO (cast it, or use
 * [as]), [id] the node key, [clock] the value to stamp into `u` and [tombstone] the delete marker
 * the model itself carries — only `entries` soft-deletes in Room, so every other module reports
 * null and tombstones come from the realtime layer alone.
 */
data class RealtimeRecord(
    val module: RealtimeModule,
    val entity: Any,
    val id: String?,
    val clock: Long,
    val tombstone: Long?
) {
    /** Typed accessor, so callers do not sprinkle casts. */
    inline fun <reified T> asType(): T? = entity as? T

    companion object {
        /** Everything except the plaintext itself, for the log line. */
        fun summary(record: RealtimeRecord): String =
            "${record.module.segment}/${record.id} u=${record.clock} d=${record.tombstone}"
    }
}

// Shape-checked record codec operations.
//
// RealtimeRecordCodec serializes the entities; these extensions add the contract enforcement the
// cross-client wire needs: refuse a record that is not the module's own class, refuse a body with
// an unknown key or a wrong-typed value, and identify a module from a body alone.

/** True when [module] stores entities of [entity]'s class. */
fun RealtimeModule.holds(entity: Any): Boolean = RealtimeFieldSchema.moduleOf(entity) == this

/**
 * Encodes [entity] as [module]'s plaintext. Throws when the value is not the model that module
 * stores — that is a wiring bug in the caller, not bad data, so it must be loud.
 */
fun RealtimeRecordCodec.encodeRecord(module: RealtimeModule, entity: Any): String {
    require(module.holds(entity)) { "${entity.javaClass.simpleName} is not a ${module.segment} record" }
    return encode(module, entity)
}

/**
 * Decodes [json] as [module]'s plaintext, or null when the body is not exactly that shape.
 * Never returns a partially filled entity: Gson would default a missing `Long` to `0` and silently
 * invent a clock, so the key set is validated before any deserialization.
 */
fun RealtimeRecordCodec.decodeRecord(module: RealtimeModule, json: String?): RealtimeRecord? {
    val tree = RealtimeFieldSchema.parseObject(json) ?: return null
    if (!RealtimeFieldSchema.accepts(module, tree)) return null
    return wrap(module, decode(module, tree.toString()))
}

/**
 * Decodes a plaintext without being told the module, and reports which module it came from.
 * Identification is by key set across the seven shapes; ambiguous or foreign bodies yield null.
 */
fun RealtimeRecordCodec.decodeAny(json: String?): RealtimeRecord? {
    val tree = RealtimeFieldSchema.parseObject(json) ?: return null
    val module = RealtimeFieldSchema.identify(tree) ?: return null
    if (!RealtimeFieldSchema.accepts(module, tree)) return null
    return wrap(module, decode(module, tree.toString()))
}

/** Wraps a live entity in its [RealtimeRecord] without going through JSON; null on a wrong class. */
fun RealtimeRecordCodec.recordOf(module: RealtimeModule, entity: Any): RealtimeRecord? =
    if (!module.holds(entity)) null else wrap(module, entity)

/** The plaintext of an already-decoded [record]. */
fun RealtimeRecordCodec.encode(record: RealtimeRecord): String = encode(record.module, record.entity)

private fun RealtimeRecordCodec.wrap(module: RealtimeModule, entity: Any?): RealtimeRecord? {
    val value = entity ?: return null
    if (!module.holds(value)) return null
    return RealtimeRecord(
        module = module,
        entity = value,
        id = idOf(module, value),
        clock = clockOf(module, value),
        tombstone = deletedClockOf(value)
    )
}
