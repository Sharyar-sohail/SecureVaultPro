package com.securevault.pro.data.remote.realtime

import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record plaintext contract (A2a): every module round-trips losslessly, the key set is exactly
 * the published table, and a body that is not that shape is refused instead of half-decoded.
 */
class A2aRecordCodecTest {

    private val codec = RealtimeRecordCodec()

    @Test
    fun everyModuleRoundTripsItsSampleEntity() {
        for ((module, entity) in A2aRealtimeSamples.all()) {
            val json = codec.encodeRecord(module, entity)
            val decoded = codec.decodeRecord(module, json)
            assertNotNull("$module failed to decode: $json", decoded)
            assertEquals("$module lost a field", entity, decoded!!.entity)
            assertEquals(module, decoded.module)
            assertEquals(entityId(entity), decoded.id)
            assertEquals(A2aRealtimeSamples.clockOf(module), decoded.clock)
            assertNull("$module must not invent a tombstone", decoded.tombstone)
        }
    }

    @Test
    fun reEncodedPlaintextIsStable() {
        for ((module, entity) in A2aRealtimeSamples.all()) {
            val first = codec.encodeRecord(module, entity)
            val second = codec.encode(module, codec.decodeRecord(module, first)!!.entity)
            assertEquals("$module is not byte-stable across a round trip", first, second)
        }
    }

    @Test
    fun keyTablesAreThePublishedContract() {
        val expected: Map<RealtimeModule, List<String>> = mapOf(
            RealtimeModule.NOTES to listOf(
                "id", "title", "content", "isPinned", "color", "tagsJson", "createdAt", "updatedAt"
            ),
            RealtimeModule.ENTRIES to listOf(
                "id", "title", "username", "email", "password", "website", "url", "notes", "category",
                "customFieldsJson", "tagsJson", "isFavorite", "hasTotp", "totpSecret", "createdAt",
                "updatedAt", "lastAccessedAt", "expiresAt", "strengthScore", "deletedAt",
                "isHiddenInTravel"
            ),
            RealtimeModule.CARDS to listOf(
                "id", "type", "nickname", "holderName", "number", "expiryMonth", "expiryYear", "cvv",
                "pin", "bankName", "notes", "colorGradientStart", "colorGradientEnd", "createdAt"
            ),
            RealtimeModule.TOTPS to listOf(
                "id", "issuer", "accountName", "secret", "algorithm", "digits", "period", "createdAt"
            ),
            RealtimeModule.CONTACTS to listOf(
                "id", "name", "phone", "email", "address", "notes", "category", "createdAt", "updatedAt"
            ),
            RealtimeModule.CRYPTO_WALLETS to listOf(
                "id", "label", "kind", "secret", "addressesJson", "balancesJson", "totalUsd",
                "lastSyncedAt", "createdAt", "updatedAt"
            ),
            RealtimeModule.FILES to listOf(
                "id", "name", "size", "mimeType", "category", "createdAt", "uploadedAt", "noteId",
                "folder", "driveId"
            )
        )
        assertEquals(7, RealtimeFieldSchema.MODULES.size)
        assertEquals(RealtimeModule.values().toList(), RealtimeFieldSchema.MODULES)
        for ((module, keys) in expected) {
            assertEquals("${module.segment} key table drifted", keys, RealtimeFieldSchema.keysOf(module))
        }
    }

    @Test
    fun absentOptionalsAreExplicitNulls() {
        val entry = codec.encode(RealtimeModule.ENTRIES, A2aRealtimeSamples.ENTRY)
        assertTrue(entry, entry.contains("\"lastAccessedAt\":null"))
        assertTrue(entry, entry.contains("\"expiresAt\":null"))
        assertTrue(entry, entry.contains("\"deletedAt\":null"))
        val file = codec.encode(RealtimeModule.FILES, A2aRealtimeSamples.FILE)
        assertTrue(file, file.contains("\"noteId\":null"))
    }

    @Test
    fun plaintextKeepsUrlsAndQuotesReadable() {
        val entry = codec.encode(RealtimeModule.ENTRIES, A2aRealtimeSamples.ENTRY)
        assertTrue(entry, entry.contains("https://example.com"))
        assertFalse("HTML escaping would break byte-stable fixtures", entry.contains("\\u003d"))
        val note = codec.encode(RealtimeModule.NOTES, A2aRealtimeSamples.NOTE)
        assertTrue(note, note.contains("""[\"sample\"]"""))
    }

    @Test
    fun softDeletedEntryReportsItsTombstone() {
        val deleted = A2aRealtimeSamples.ENTRY.copy(deletedAt = A2aRealtimeSamples.BASE + 2 * A2aRealtimeSamples.MINUTE)
        val decoded = codec.decodeRecord(RealtimeModule.ENTRIES, codec.encode(RealtimeModule.ENTRIES, deleted))
        assertEquals(deleted, decoded!!.entity)
        assertEquals(deleted.deletedAt, decoded.tombstone)
        assertEquals(deleted.updatedAt, decoded.clock)
    }

    @Test
    fun decodeAnyReportsTheModuleItCameFrom() {
        for ((module, entity) in A2aRealtimeSamples.all()) {
            val decoded = codec.decodeAny(codec.encode(module, entity))
            assertNotNull("$module not identified", decoded)
            assertEquals(module, decoded!!.module)
            assertEquals(entity, decoded.entity)
        }
    }

    @Test
    fun foreignBodiesAreRejected() {
        val note = codec.encode(RealtimeModule.NOTES, A2aRealtimeSamples.NOTE)
        // An extra plaintext field means the wrong module or a leaked value: refuse it.
        val leaked = note.replace("\"isPinned\":false", "\"isPinned\":false,\"password\":\"leaked\"")
        assertNull(codec.decodeRecord(RealtimeModule.NOTES, leaked))
        assertNull(codec.decodeAny(leaked))
        // A missing required key must not silently become Gson's default zero.
        val truncated = note.replace("\"content\":\"Synthetic sample body. Not a secret.\",", "")
        assertNull(codec.decodeRecord(RealtimeModule.NOTES, truncated))
        assertNull(codec.decodeAny(truncated))
        // A null in a non-nullable column is malformed, not optional.
        assertNull(codec.decodeRecord(RealtimeModule.NOTES, note.replace("\"title\":\"Sample note title\"", "\"title\":null")))
        // Wrong JSON types.
        assertNull(codec.decodeRecord(RealtimeModule.NOTES, note.replace("\"createdAt\":${A2aRealtimeSamples.BASE}", "\"createdAt\":\"${A2aRealtimeSamples.BASE}\"")))
        assertNull(codec.decodeRecord(RealtimeModule.NOTES, note.replace("\"isPinned\":false", "\"isPinned\":0")))
        assertNull(codec.decodeRecord(RealtimeModule.TOTPS, codec.encode(RealtimeModule.TOTPS, A2aRealtimeSamples.TOTP).replace("\"digits\":6", "\"digits\":\"six\"")))
        assertNull(codec.decodeRecord(RealtimeModule.NOTES, note.replace("\"tagsJson\":\"[\\\"sample\\\"]\"", "\"tagsJson\":[]")))
        // Not an object at all, lenient JSON, or trailing content.
        for (bad in listOf("", "   ", "null", "[]", "[1,2]", "\"just a string\"", "{id:1}", "{\"a\":1} \"tail\"")) {
            assertNull("accepted $bad", codec.decodeAny(bad))
        }
    }

    @Test
    fun optionalKeysMayBeAbsentOnTheWire() {
        val file = codec.encode(RealtimeModule.FILES, A2aRealtimeSamples.FILE)
            .replace(",\"noteId\":null", "")
        val decoded = codec.decodeRecord(RealtimeModule.FILES, file)
        assertNotNull("a browser that omits a null optional must still parse", decoded)
        assertEquals(A2aRealtimeSamples.FILE, decoded!!.entity)
        assertNull(decoded.tombstone)
    }

    @Test
    fun wrongEntityClassIsALoudWiringBug() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encodeRecord(RealtimeModule.NOTES, A2aRealtimeSamples.CARD)
        }
        assertNull(codec.recordOf(RealtimeModule.NOTES, A2aRealtimeSamples.CARD))
        assertFalse(RealtimeModule.NOTES.holds(VaultEntryEntity::class.java))
    }

    @Test
    fun schemaHelpersAgreeWithTheCodec() {
        val note = codec.encode(RealtimeModule.NOTES, A2aRealtimeSamples.NOTE)
        val tree = RealtimeFieldSchema.parseObject(note)
        assertNotNull(tree)
        assertEquals(RealtimeModule.NOTES, RealtimeFieldSchema.identify(tree!!))
        assertTrue(RealtimeFieldSchema.accepts(RealtimeModule.NOTES, tree))
        assertFalse(RealtimeFieldSchema.accepts(RealtimeModule.CONTACTS, tree))
        assertEquals(setOf("lastAccessedAt", "expiresAt", "deletedAt"), RealtimeFieldSchema.optionalKeysOf(RealtimeModule.ENTRIES))
        assertEquals(
            setOf("noteId", "folder", "driveId"),
            RealtimeFieldSchema.optionalKeysOf(RealtimeModule.FILES)
        )
        assertTrue(RealtimeFieldSchema.fieldsOf(RealtimeModule.NOTES).first().describe().startsWith("id STRING"))
        assertEquals(SecureNoteEntity::class.java, RealtimeFieldSchema.classOf(RealtimeModule.NOTES))
    }

    private fun entityId(entity: Any): String? = when (entity) {
        is com.securevault.pro.data.local.entity.SecureNoteEntity -> entity.id
        is VaultEntryEntity -> entity.id
        is com.securevault.pro.data.local.entity.DigitalCardEntity -> entity.id
        is com.securevault.pro.data.local.entity.TotpEntity -> entity.id
        is com.securevault.pro.data.local.entity.SecureContactEntity -> entity.id
        is com.securevault.pro.data.local.entity.CryptoWalletEntity -> entity.id
        is com.securevault.pro.data.file.VaultFile -> entity.id
        else -> null
    }
}
