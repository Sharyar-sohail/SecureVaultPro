package com.securevault.pro.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The XML mirror has always been written beside the JSON body, but nothing used to read it:
 * the restore walk skipped every `*.xml.enc` and the parser only understood JSON. These tests
 * hold the reader to the writer's shape so an XML-only backup is a restorable vault.
 */
class BackupXmlParserTest {

    private val payload = BackupPayloadDto(
        version = 3,
        entries = listOf(
            BackupEntryDto(
                id = "e-1",
                title = "Bank & Co <Personal>",
                username = "owner",
                email = "owner@example.com",
                password = "S3cret \"quoted\" & <tagged>",
                website = "https://bank.example",
                url = "https://bank.example/login",
                notes = "line one\nline two",
                category = "FINANCE",
                customFieldsJson = """[{"label":"PIN","value":"42"}]""",
                tagsJson = """["money","work"]""",
                isFavorite = true,
                hasTotp = true,
                totpSecret = "JBSWY3DPEHPK3PXP",
                createdAt = 1_700_000_000_000,
                updatedAt = 1_700_000_100_000
            )
        ),
        notes = listOf(
            BackupNoteDto(id = "n-1", title = "Recovery", content = "body & more", isPinned = true, color = "MINT")
        ),
        cards = listOf(
            BackupCardDto(
                id = "c-1",
                type = "CREDIT",
                nickname = "Travel",
                holderName = "Ada Lovelace",
                number = "4111111111111111",
                expiryMonth = 9,
                expiryYear = 29,
                cvv = "123",
                bankName = "Example Bank"
            )
        ),
        totps = listOf(
            BackupTotpDto(id = "t-1", issuer = "Example", accountName = "ada", secret = "JBSWY3DPEHPK3PXP")
        ),
        contacts = listOf(
            BackupContactDto(id = "k-1", name = "Ada", phone = "+15550100", email = "ada@example.com")
        ),
        files = listOf(
            BackupFileDto(id = "f-1", name = "note.txt", size = 4, data = "aGVsbG8=", mimeType = "text/plain")
        ),
        cryptoWallets = listOf(
            BackupCryptoWalletDto(id = "w-1", label = "Main", kind = "ETH", secret = "0xdeadbeef", totalUsd = 12.5)
        )
    )

    @Test
    fun `the writer's own XML reads back every record`() {
        val xml = BackupXmlSerializer.serialize(payload)
        val parsed = BackupXmlParser.parse(xml)
        assertNotNull("the app's own XML mirror must parse", parsed)
        parsed!!
        assertEquals(1, parsed.entries.size)
        assertEquals(1, parsed.notes.size)
        assertEquals(1, parsed.cards.size)
        assertEquals(1, parsed.totps.size)
        assertEquals(1, parsed.contacts.size)
        assertEquals(1, parsed.files.size)
        assertEquals(1, parsed.cryptoWallets.size)
    }

    @Test
    fun `escaped text comes back exactly as it went in`() {
        val parsed = BackupXmlParser.parse(BackupXmlSerializer.serialize(payload))!!
        val entry = parsed.entries.single()
        assertEquals("Bank & Co <Personal>", entry.title)
        assertEquals("S3cret \"quoted\" & <tagged>", entry.password)
        assertEquals("line one\nline two", entry.notes)
        assertEquals("""["money","work"]""", entry.tagsJson)
        assertTrue(entry.isFavorite == true)
        assertEquals(1_700_000_000_000, entry.createdAt)
        assertEquals("0xdeadbeef", parsed.cryptoWallets.single().secret)
        assertEquals(12.5, parsed.cryptoWallets.single().totalUsd!!, 0.0001)
    }

    @Test
    fun `the shared parser recognises an XML body without being told its format`() {
        val parsed = BackupParser.parse(BackupXmlSerializer.serialize(payload))
        assertEquals(1, parsed.payload.entries.size)
        assertTrue(parsed.payload.entries.single().password == "S3cret \"quoted\" & <tagged>")
        assertTrue(parsed.warnings.any { it.contains("XML", ignoreCase = true) })
    }

    @Test
    fun `a document type declaration is refused instead of expanded`() {
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE secureVault [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <secureVault version="3"><entries><entry title="&xxe;"/></entries></secureVault>
        """.trimIndent()
        assertNull("a backup never carries a DTD, so the parser must not resolve one", BackupXmlParser.parse(hostile))
    }

    @Test
    fun `a foreign XML document is not mistaken for a vault`() {
        assertNull(BackupXmlParser.parse("<html><body>hello</body></html>"))
    }
}
