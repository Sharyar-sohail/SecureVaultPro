package com.securevault.pro.data.remote.realtime

import com.google.gson.GsonBuilder
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import com.securevault.pro.data.local.entity.DigitalCardEntity
import com.securevault.pro.data.local.entity.SecureContactEntity
import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.TotpEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import javax.inject.Inject

/**
 * Encodes and decodes the per-module record plaintext the realtime node seals in `e`.
 *
 * The wire contract (`sync_engine/RECORD-FORMAT-v1.md` §3) names the JSON keys as the Kotlin/Room
 * property names of each entity, so this codec serializes the entities directly — the property
 * names are the contract, and going through the real class means the phone and the browser cannot
 * drift apart. `serializeNulls` is on so every key the spec lists is always present and an absent
 * optional is written as a JSON `null`, never omitted; the web's `JSON.parse` then finds a stable
 * shape it can render module by module.
 *
 * Gson (not `org.json`) is used deliberately: `org.json` is a throwing stub in JVM unit tests, so a
 * codec built on it could not be round-trip tested. Nothing here touches the cipher or the network —
 * [RealtimeEnvelope] seals the string this class produces.
 */
class RealtimeRecordCodec @Inject constructor() {

    /** Field-preserving Gson; `null` optionals are written, so a decode round-trips losslessly. */
    private val gson = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    /** The entity class Gson reads a module's plaintext back into. Files use the metadata-only [VaultFile]. */
    fun recordClass(module: RealtimeModule): Class<*> = when (module) {
        RealtimeModule.NOTES -> SecureNoteEntity::class.java
        RealtimeModule.ENTRIES -> VaultEntryEntity::class.java
        RealtimeModule.CARDS -> DigitalCardEntity::class.java
        RealtimeModule.TOTPS -> TotpEntity::class.java
        RealtimeModule.CONTACTS -> SecureContactEntity::class.java
        RealtimeModule.CRYPTO_WALLETS -> CryptoWalletEntity::class.java
        RealtimeModule.FILES -> VaultFile::class.java
    }

    /** UTF-8 JSON for any record of [module]; the exact bytes that go into the sealed `e` field. */
    fun encode(module: RealtimeModule, record: Any): String = gson.toJson(record)

    /**
     * The record decoded from [json], or null when the JSON is not this module's shape. Callers must
     * null-check: a foreign or truncated node is dropped and logged by the engine, never half applied.
     */
    fun decode(module: RealtimeModule, json: String?): Any? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, recordClass(module)) as Any? }.getOrNull()
    }

    /** The record's own id (a UUID) — the key the node lives under on every device. */
    fun idOf(module: RealtimeModule, record: Any): String? = when (record) {
        is SecureNoteEntity -> record.id
        is VaultEntryEntity -> record.id
        is DigitalCardEntity -> record.id
        is TotpEntity -> record.id
        is SecureContactEntity -> record.id
        is CryptoWalletEntity -> record.id
        is VaultFile -> record.id
        else -> idOfFallback(record)
    }

    /**
     * The LWW clock `u` for a local record. Where the entity carries `updatedAt` that is the clock;
     * where it does not (cards, TOTP), the realtime layer supplies the write time via [writeClock]
     * and [createdAt] is the floor so a re-read never looks older than it was born.
     */
    fun clockOf(module: RealtimeModule, record: Any): Long = when (record) {
        is SecureNoteEntity -> record.updatedAt
        is VaultEntryEntity -> maxOf(record.updatedAt, record.createdAt)
        is SecureContactEntity -> record.updatedAt
        is CryptoWalletEntity -> record.updatedAt
        is DigitalCardEntity -> record.createdAt
        is TotpEntity -> record.createdAt
        is VaultFile -> maxOf(record.uploadedAt, record.createdAt)
        else -> 0L
    }

    /** True when a record is a delete on its own side (only entries soft-delete in Room). */
    fun deletedClockOf(record: Any): Long? = when (record) {
        is VaultEntryEntity -> record.deletedAt
        else -> null
    }

    /** The content signature used to detect an edit on a module with no `updatedAt` column. */
    fun signature(module: RealtimeModule, record: Any): String = encode(module, record)

    private fun idOfFallback(record: Any): String? = when (record) {
        else -> runCatching {
            record.javaClass.getMethod("getId").invoke(record) as? String
        }.getOrNull()
    }
}
