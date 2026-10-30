package com.securevault.pro.data.remote.realtime

import com.google.gson.JsonParser
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import com.securevault.pro.data.local.entity.DigitalCardEntity
import com.securevault.pro.data.local.entity.SecureContactEntity
import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.TotpEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record plaintext `sync_engine/RECORD-FORMAT-v1.md` §3 fixes as the interface between the APK
 * and the browser. Every key the spec lists must be present, an absent optional must be a `null` and
 * not a missing field, and the bytes must round-trip losslessly — the web's second `JSON.parse` of
 * `tagsJson` etc. relies on the exact shape this codec produces.
 */
class RealtimeRecordCodecTest {

    private val codec = RealtimeRecordCodec()

    private val note = SecureNoteEntity(
        id = "note-1", title = "Title", content = "body", isPinned = true,
        color = "CYAN", tagsJson = "[\"a\",\"b\"]", createdAt = 1_000L, updatedAt = 2_000L
    )
    private val entry = VaultEntryEntity(
        id = "entry-1", title = "t", username = "u", email = "e", password = "p",
        website = "w", url = "http://w", notes = "n", category = "APPS",
        customFieldsJson = "[]", tagsJson = "[\"x\"]", isFavorite = true, hasTotp = true,
        totpSecret = "SECRET", createdAt = 10L, updatedAt = 20L, lastAccessedAt = null,
        expiresAt = null, strengthScore = 42, deletedAt = null, isHiddenInTravel = false
    )
    private val card = DigitalCardEntity(
        id = "card-1", type = "DEBIT", nickname = "nick", holderName = "holder",
        number = "4111", expiryMonth = 5, expiryYear = 29, cvv = "123", pin = "0000",
        bankName = "bank", notes = "note", colorGradientStart = "#AAA",
        colorGradientEnd = "#BBB", createdAt = 5L
    )
    private val totp = TotpEntity(
        id = "totp-1", issuer = "GitHub", accountName = "me", secret = "JBSWY3DP",
        algorithm = "SHA1", digits = 6, period = 30, createdAt = 7L
    )
    private val contact = SecureContactEntity(
        id = "contact-1", name = "Ann", phone = "1", email = "a@b", address = "x",
        notes = "n", category = "Work", createdAt = 3L, updatedAt = 9L
    )
    private val wallet = CryptoWalletEntity(
        id = "wallet-1", label = "Main", kind = "BTC", secret = "s",
        addressesJson = "[\"bc1\"]", balancesJson = "[{\"usd\":1}]", totalUsd = 12.5,
        lastSyncedAt = 8L, createdAt = 1L, updatedAt = 4L
    )
    private val file = VaultFile(
        id = "file-1", name = "doc.pdf", size = 2048L, mimeType = "application/pdf",
        category = "Docs", createdAt = 11L, uploadedAt = 12L, noteId = null, driveId = "drv-9"
    )

    @Test
    fun `round-trips every module losslessly`() {
        val cases: List<Pair<RealtimeModule, Any>> = listOf(
            RealtimeModule.NOTES to note,
            RealtimeModule.ENTRIES to entry,
            RealtimeModule.CARDS to card,
            RealtimeModule.TOTPS to totp,
            RealtimeModule.CONTACTS to contact,
            RealtimeModule.CRYPTO_WALLETS to wallet,
            RealtimeModule.FILES to file
        )
        for ((module, record) in cases) {
            val json = codec.encode(module, record)
            val restored = codec.decode(module, json)
            assertNotNull("$module decoded", restored)
            assertEquals("$module round-trip", record, restored)
        }
    }

    @Test
    fun `every key the spec lists is present and ordered`() {
        assertEquals(
            listOf("id", "title", "content", "isPinned", "color", "tagsJson", "createdAt", "updatedAt"),
            keysOf(RealtimeModule.NOTES, note)
        )
        assertEquals(
            listOf("id", "type", "nickname", "holderName", "number", "expiryMonth", "expiryYear", "cvv",
                "pin", "bankName", "notes", "colorGradientStart", "colorGradientEnd", "createdAt"),
            keysOf(RealtimeModule.CARDS, card)
        )
        assertEquals(
            listOf("id", "issuer", "accountName", "secret", "algorithm", "digits", "period", "createdAt"),
            keysOf(RealtimeModule.TOTPS, totp)
        )
        assertEquals(
            listOf("id", "name", "phone", "email", "address", "notes", "category", "createdAt", "updatedAt"),
            keysOf(RealtimeModule.CONTACTS, contact)
        )
        assertEquals(
            listOf("id", "label", "kind", "secret", "addressesJson", "balancesJson", "totalUsd",
                "lastSyncedAt", "createdAt", "updatedAt"),
            keysOf(RealtimeModule.CRYPTO_WALLETS, wallet)
        )
        // files carries metadata + driveId only — never bytes. `folder` names the per-chat
        // container (WhatsApp media lives in chats/<chat id>), null for a flat-root file.
        assertEquals(
            listOf("id", "name", "size", "mimeType", "category", "createdAt", "uploadedAt", "noteId",
                "folder", "driveId"),
            keysOf(RealtimeModule.FILES, file)
        )
        // entries has the widest field set; assert all 21 keys of §3.
        assertEquals(21, keysOf(RealtimeModule.ENTRIES, entry).size)
    }

    @Test
    fun `absent optionals are written as json null and never omitted`() {
        val keys = keysOf(RealtimeModule.ENTRIES, entry)
        assertTrue(keys.containsAll(listOf("lastAccessedAt", "expiresAt", "deletedAt")))
        val obj = JsonParser.parseString(codec.encode(RealtimeModule.ENTRIES, entry)).asJsonObject
        assertTrue(obj.get("deletedAt").isJsonNull)
        assertTrue(obj.get("lastAccessedAt").isJsonNull)

        // files with no noteId still carries the key as null (the web reads a stable shape).
        val fileObj = JsonParser.parseString(codec.encode(RealtimeModule.FILES, file)).asJsonObject
        assertTrue(fileObj.get("noteId").isJsonNull)
        assertEquals("drv-9", fileObj.get("driveId").asString)
    }

    @Test
    fun `a foreign json blob is rejected rather than half decoded`() {
        assertNull(codec.decode(RealtimeModule.NOTES, null))
        assertNull(codec.decode(RealtimeModule.NOTES, "   "))
        assertNull(codec.decode(RealtimeModule.NOTES, "not json at all ["))
    }

    @Test
    fun `clock and tombstone helpers follow the spec`() {
        assertEquals(2_000L, codec.clockOf(RealtimeModule.NOTES, note))       // updatedAt
        assertEquals(9L, codec.clockOf(RealtimeModule.CONTACTS, contact))     // updatedAt
        assertEquals(12L, codec.clockOf(RealtimeModule.FILES, file))          // max(uploadedAt, createdAt)
        assertEquals(5L, codec.clockOf(RealtimeModule.CARDS, card))           // createdAt (no updatedAt)
        assertEquals(7L, codec.clockOf(RealtimeModule.TOTPS, totp))           // createdAt (no updatedAt)
        assertNull(codec.deletedClockOf(note))
        assertEquals(777L, codec.deletedClockOf(entry.copy(deletedAt = 777L)))
    }

    private fun keysOf(module: RealtimeModule, record: Any): List<String> =
        JsonParser.parseString(codec.encode(module, record)).asJsonObject.keySet().toList()
}
