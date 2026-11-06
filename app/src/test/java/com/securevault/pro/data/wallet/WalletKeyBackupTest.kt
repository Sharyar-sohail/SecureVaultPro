package com.securevault.pro.data.wallet

import com.google.gson.Gson
import com.securevault.pro.data.backup.BackupCryptoWalletDto
import com.securevault.pro.data.backup.BackupPayloadDto
import com.securevault.pro.data.backup.BackupXmlParser
import com.securevault.pro.data.backup.BackupXmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wallet's spendable material only ever leaves the phone inside the Drive backup document, and only
 * behind this account's own vault key. The codec below is that door: it writes the small JSON, hands it
 * to an injected seal/open pair, and reads it back on the phone that receives it.
 *
 * The sealer here is a fake that is reversible but never leaves the plaintext visible in the string it
 * returns, so the whole path — sealing, opening, refusing, and the address rule that decides whether a
 * restored key belongs to the wallet it landed on — is provable off-device. The fabricated key and
 * phrase are invented for this test and are not real wallet material.
 *
 * The last test is the one that matters most: a document that carries a wallet must carry its key
 * sealed. Both copies of every backup (the JSON body and its XML mirror) are built here and searched
 * for the raw hex, and the blob each copy hands back is opened again, so a regression toward writing
 * a key in the clear — or toward losing it between the two copies — fails loudly.
 */
class WalletKeyBackupTest {

    private val fakeKey = "0x4c0d8c8f6f0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071829345"
    private val fakePhrase = "harbor velvet lantern pulse orbit cedar mimic jumble falcon trumpet almond solar"
    private val fakePassphrase = "second line for the test phrase"
    private val address = "0x1111222233334444555566667777888899990000"

    /** Reversible like the device's real pair, but the sealed string never shows what went in. */
    private class FakeSealer : WalletKeyBackupSealer {
        private val book = HashMap<String, String>()
        var seals = 0
        var opens = 0

        override fun seal(plaintext: String): String? {
            val cipher = "sealed." + java.util.Base64.getEncoder()
                .encodeToString(plaintext.toByteArray(Charsets.UTF_8)) + ".env"
            book[cipher] = plaintext
            seals++
            return cipher
        }

        override fun open(ciphertext: String?): String? {
            if (ciphertext == null) return null
            opens++
            return book[ciphertext]
        }
    }

    /** The device's worst case: the Keystore refuses, so nothing can be sealed or opened. */
    private class DeadSealer : WalletKeyBackupSealer {
        override fun seal(plaintext: String): String? = null
        override fun open(ciphertext: String?): String? = null
    }

    /** A sealer that hands back what it was given; sealing must be refused, not faked. */
    private class EchoSealer : WalletKeyBackupSealer {
        override fun seal(plaintext: String): String? = plaintext
        override fun open(ciphertext: String?): String? = ciphertext
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun aSealedPrivateKeyOpensBackAsTheSameKey() {
        val sealer = FakeSealer()
        val blob = WalletKeyBackup.seal(sealer, WalletKeyBackupKind.PRIVATE_KEY, fakeKey)
        assertNotNull("the key should have sealed", blob)
        assertEquals(1, sealer.seals)

        val material = WalletKeyBackup.open(sealer, blob)
        assertNotNull("the blob should open back", material)
        assertEquals(WalletKeyBackupKind.PRIVATE_KEY, material!!.kind)
        assertEquals(fakeKey, material.secret)
        assertEquals("", material.passphrase)
        assertEquals(1, sealer.opens)
    }

    @Test
    fun aSealedPhraseAndItsPassphraseComeBackTogether() {
        val sealer = FakeSealer()
        val blob = WalletKeyBackup.seal(sealer, WalletKeyBackupKind.SEED_PHRASE, fakePhrase, fakePassphrase)
        val material = WalletKeyBackup.open(sealer, blob)
        assertNotNull(material)
        assertEquals(WalletKeyBackupKind.SEED_PHRASE, material!!.kind)
        assertEquals(fakePhrase, material.secret)
        assertEquals(fakePassphrase, material.passphrase)
    }

    @Test
    fun theMaterialNeverPrintsWhatItCarries() {
        val material = WalletKeyMaterial(WalletKeyBackupKind.SEED_PHRASE, fakePhrase, fakePassphrase)
        val text = material.toString()
        assertFalse("a key must not reach log or exception text: $text", text.contains(fakePhrase))
        assertFalse("a passphrase must not reach log or exception text: $text", text.contains(fakePassphrase))
        assertTrue(text.contains("hidden"))
    }

    // ------------------------------------------------------------------ no key, no crash

    @Test
    fun aBlobThatWillNotOpenYieldsNoKeyInsteadOfACrash() {
        val sealer = FakeSealer()
        val blob = WalletKeyBackup.seal(sealer, WalletKeyBackupKind.PRIVATE_KEY, fakeKey)

        // The account's vault key is not on this device, so the blob is unreadable.
        assertNull(WalletKeyBackup.open(DeadSealer(), blob))
        // The blob belongs to another account's key, so the real pair gives nothing back.
        assertNull(WalletKeyBackup.open(sealer, "sealed.c29tZXRoaW5nZWxzZS5lbnY=.env"))
        // A document written before wallet keys travelled at all.
        assertNull(WalletKeyBackup.open(sealer, null))
        assertNull(WalletKeyBackup.open(sealer, ""))
        assertNull(WalletKeyBackup.open(sealer, "   "))

        // Nothing seals without a working pair, so there is no plaintext fallback path.
        assertNull(WalletKeyBackup.seal(DeadSealer(), WalletKeyBackupKind.PRIVATE_KEY, fakeKey))
        assertNull(WalletKeyBackup.seal(DeadSealer(), WalletKeyBackupKind.SEED_PHRASE, fakePhrase))
    }

    @Test
    fun aBodyThatIsNotOursCarriesNoKey() {
        val shapes = listOf(
            // A key that is not a key.
            """{"kind":"PRIVATE_KEY","secret":"not hex at all"}""",
            // A phrase with nothing in it.
            """{"kind":"SEED_PHRASE","secret":"   ","passphrase":""}""",
            // The same document with an extra field the writer never emits.
            """{"kind":"PRIVATE_KEY","secret":"$fakeKey","extra":"x"}""",
            // A repeated field.
            """{"kind":"PRIVATE_KEY","secret":"$fakeKey","secret":"$fakeKey"}""",
            // Not a flat object of strings.
            """{"kind":"PRIVATE_KEY","secret":{"nested":"x"}}""",
            """{"kind":"PRIVATE_KEY","secret":42}""",
            // An unterminated string, and something that is not an object at all.
            """{"kind":"PRIVATE_KEY","secret":"$fakeKey""",
            "sealed.without.a.document.env",
            ""
        )
        shapes.forEach { document ->
            assertNull("refused: $document", WalletKeyBackup.parse(document))
        }
        // A blob whose opened body is one of those shapes opens to no key.
        val foreignBody = object : WalletKeyBackupSealer {
            override fun seal(plaintext: String): String? = plaintext
            override fun open(ciphertext: String?): String? = """{"kind":"X","secret":"y"}"""
        }
        assertNull(WalletKeyBackup.open(foreignBody, "whatever"))
    }

    // ------------------------------------------------------------------ the JSON shape

    @Test
    fun thePrivateKeyDocumentIsExactlyThisJson() {
        assertEquals(
            """{"kind":"PRIVATE_KEY","secret":"$fakeKey"}""",
            WalletKeyBackup.documentFor(WalletKeyBackupKind.PRIVATE_KEY, fakeKey)
        )
        // The same key typed uppercase, spaced, or without its prefix is written once, canonically.
        val sloppy = " 0X" + fakeKey.substring(2).uppercase() + "  "
        assertEquals(
            """{"kind":"PRIVATE_KEY","secret":"$fakeKey"}""",
            WalletKeyBackup.documentFor(WalletKeyBackupKind.PRIVATE_KEY, sloppy)
        )
        // A value that is not a private key is not a private key document.
        assertNull(WalletKeyBackup.documentFor(WalletKeyBackupKind.PRIVATE_KEY, fakePhrase))
        assertNull(WalletKeyBackup.documentFor(WalletKeyBackupKind.PRIVATE_KEY, "0xdeadbeef"))
    }

    @Test
    fun thePhraseDocumentIsExactlyThisJson() {
        assertEquals(
            """{"kind":"SEED_PHRASE","secret":"$fakePhrase","passphrase":""}""",
            WalletKeyBackup.documentFor(WalletKeyBackupKind.SEED_PHRASE, fakePhrase)
        )
        assertEquals(
            """{"kind":"SEED_PHRASE","secret":"$fakePhrase","passphrase":"$fakePassphrase"}""",
            WalletKeyBackup.documentFor(WalletKeyBackupKind.SEED_PHRASE, fakePhrase, fakePassphrase)
        )
        // Extra spacing between words is the phrase, not a different phrase.
        assertEquals(
            WalletKeyBackup.documentFor(WalletKeyBackupKind.SEED_PHRASE, fakePhrase, fakePassphrase),
            WalletKeyBackup.documentFor(WalletKeyBackupKind.SEED_PHRASE, "  $fakePhrase  ", fakePassphrase)
        )
        assertNull(WalletKeyBackup.documentFor(WalletKeyBackupKind.SEED_PHRASE, "   "))
    }

    @Test
    fun aPhraseAndAKeyTellEachOtherApartTheWayRowsAlwaysHave() {
        assertEquals(WalletKeyBackupKind.PRIVATE_KEY, WalletKeyBackup.kindOf(null, fakeKey))
        assertEquals(WalletKeyBackupKind.SEED_PHRASE, WalletKeyBackup.kindOf(null, fakePhrase))
        assertEquals(WalletKeyBackupKind.SEED_PHRASE, WalletKeyBackup.kindOf("SEED_PHRASE", fakePhrase))
        assertEquals(WalletKeyBackupKind.PRIVATE_KEY, WalletKeyBackup.kindOf("private_key", fakeKey))
        // A value with a space in it reads as a phrase, exactly as a row has always read one; only
        // something that is neither hex nor a spaced phrase is refused, because it is neither.
        assertEquals(WalletKeyBackupKind.SEED_PHRASE, WalletKeyBackup.kindOf(null, "four small words"))
        assertNull(WalletKeyBackup.kindOf(null, "neither-a-key-nor-a-phrase"))
        assertTrue(WalletKeyBackup.isPrivateKeyHex(fakeKey))
        assertFalse(WalletKeyBackup.isPrivateKeyHex(fakePhrase))
    }

    @Test
    fun aPhraseWorthQuotingSurvivesTheRoundTripThroughTheDocument() {
        val secret = "quotes \" backslash \\ and a tab\there once"
        val passphrase = "p \" & \\ and no spaces"
        val document = WalletKeyBackup.documentFor(WalletKeyBackupKind.SEED_PHRASE, secret, passphrase)
        assertNotNull("escaping must still produce a document", document)
        val written = WalletKeyBackup.parse(document)
        assertNotNull("the escaped document must read back", written)
        assertEquals(secret, written!!.secret)
        assertEquals(passphrase, written.passphrase)

        val sealer = FakeSealer()
        val blob = WalletKeyBackup.seal(sealer, written.kind, written.secret, written.passphrase)
        assertNotNull(blob)
        val material = WalletKeyBackup.open(sealer, blob)
        assertNotNull("escaping must survive sealing and reading", material)
        assertEquals(secret, material!!.secret)
        assertEquals(passphrase, material.passphrase)
    }

    // ------------------------------------------------------------------ the address rule

    @Test
    fun aKeyThatBelongsToAnotherAddressIsRefused() {
        val other = "0x9999888877776666555544443333222211110000"
        val reason = WalletKeyBackup.addressMismatchReason(other, address)
        assertNotNull("a mismatched address must be refused", reason)
        val sentence = reason!!
        // Plain English for the person reading the restore report, no crypto vocabulary.
        assertTrue("names the claimed address: $sentence", sentence.contains(other))
        assertTrue("says what happened: $sentence", sentence.contains("left out"))
        assertFalse(sentence.hasSecurityJargon())
        assertFalse(sentence.contains("IME"))

        // The claim holds, in either casing, so nothing is refused.
        assertNull(WalletKeyBackup.addressMismatchReason(address, address))
        assertNull(WalletKeyBackup.addressMismatchReason(address.uppercase(), address))
        // Nothing claimed at all: this device has no record of that wallet's address, so no refusal.
        assertNull(WalletKeyBackup.addressMismatchReason(null, address))
        assertNull(WalletKeyBackup.addressMismatchReason("", address))
        assertNull(WalletKeyBackup.addressMismatchReason("   ", address))
        // A wallet with no address to spend from is refused too, in one plain sentence.
        val noAddress = WalletKeyBackup.addressMismatchReason(other, "")
        assertNotNull(noAddress)
        assertFalse(noAddress!!.hasSecurityJargon())
        assertTrue(noAddress.contains("left out"))
    }

    @Test
    fun aMaskedClaimIsCheckedByItsHeadAndTail() {
        // The key store keeps only `head…tail`, and that is all a restore has to go on there.
        val masked = address.take(10) + "…" + address.takeLast(4)
        assertNull(WalletKeyBackup.addressMismatchReason(masked, address))
        val shouting = address.take(10).uppercase() + "…" + address.takeLast(4)
        assertNull(WalletKeyBackup.addressMismatchReason(shouting, address))
        assertNotNull(WalletKeyBackup.addressMismatchReason("0x99998888…0001", address))
        // A half-masked claim is not a claim this rule can honour, and a short one is not a wallet address.
        assertNotNull(WalletKeyBackup.addressMismatchReason("0x1111…", address))
        assertTrue(WalletKeyBackup.addressMatches(masked, address))
        assertFalse(WalletKeyBackup.addressMatches("…", address))
        assertTrue(WalletKeyBackup.sameAddress("0xABC", "0xabc"))
        assertFalse(WalletKeyBackup.sameAddress("0xABC", "0xabd"))
        assertFalse(WalletKeyBackup.sameAddress(null, address))
    }

    // ------------------------------------------------------------------ sealing really happened

    @Test
    fun aSealerThatShowsTheKeyIsRefusedRatherThanWritten() {
        val echo = EchoSealer()
        assertNull("a key that is still visible after sealing must not travel",
            WalletKeyBackup.seal(echo, WalletKeyBackupKind.PRIVATE_KEY, fakeKey))
        assertNull(WalletKeyBackup.seal(echo, WalletKeyBackupKind.SEED_PHRASE, fakePhrase, fakePassphrase))
        assertNull(WalletKeyBackup.seal(FakeSealer(), WalletKeyBackupKind.PRIVATE_KEY, fakePhrase))
        // The leak test itself catches both the prefixed and the bare hex form.
        assertTrue(WalletKeyBackup.leaks(fakeKey, "prefix " + fakeKey.removePrefix("0x") + " suffix"))
        assertTrue(WalletKeyBackup.leaks(fakeKey, fakeKey))
        assertFalse(WalletKeyBackup.leaks(fakeKey, "sealed.c29tZXRoaW5nZWxzZS5lbnY=.env"))
        assertFalse(WalletKeyBackup.leaks(null, "sealed.anything.env"))
        assertFalse(WalletKeyBackup.leaks("  ", "sealed.anything.env"))
    }

    @Test
    fun aBackupDocumentCarriesOnlySealedKeys() {
        val sealer = FakeSealer()
        val sealedKey = WalletKeyBackup.seal(sealer, WalletKeyBackupKind.PRIVATE_KEY, fakeKey)!!
        val sealedPhrase = WalletKeyBackup.seal(sealer, WalletKeyBackupKind.SEED_PHRASE, fakePhrase, fakePassphrase)!!

        // The blob is what travels, and it never shows the material it carries.
        assertFalse("sealing did not hide the key", WalletKeyBackup.leaks(fakeKey, sealedKey))
        assertFalse(WalletKeyBackup.leaks(fakePhrase, sealedPhrase))
        assertFalse(WalletKeyBackup.leaks(fakePassphrase, sealedPhrase))

        // A wallet whose key lives on this device travels as that pointer plus a sealed blob, so the
        // blob is the only place its spending key could appear in a document at all.
        val payload = BackupPayloadDto(
            cryptoWallets = listOf(
                BackupCryptoWalletDto(
                    id = "w-key", label = "Main", kind = "IMPORTED_PRIVATE_KEY",
                    secret = """{"kind":"PRIVATE_KEY","keyRef":"device_key_store","secret":"","passphrase":""}""",
                    keyBackup = sealedKey, balancesJson = "[]", totalUsd = 12.5,
                    createdAt = 1L, updatedAt = 1L
                ),
                // A wallet this device could not seal — an older document, or no account key yet —
                // still travels, and it carries no key at all.
                BackupCryptoWalletDto(
                    id = "w-addresses", label = "Addresses only", kind = "WATCH",
                    secret = """{"address":"$address"}""",
                    balancesJson = "[]", totalUsd = 0.0, createdAt = 1L, updatedAt = 1L
                )
            )
        )
        val json = Gson().toJson(payload)

        for (document in listOf(json, BackupXmlSerializer.serialize(payload))) {
            assertFalse("a backup document must never show a private key", document.contains(fakeKey))
            assertFalse("nor the same key without its prefix",
                document.contains(fakeKey.removePrefix("0x"), ignoreCase = true))
        }

        // Both copies hand the blob back to the restore walk unchanged, and what they hand over is
        // still sealed: every key a copy carries opens, and shows nothing until it does.
        val copies = listOf(
            Gson().fromJson(json, BackupPayloadDto::class.java).cryptoWallets,
            BackupXmlParser.parse(BackupXmlSerializer.serialize(payload))!!.cryptoWallets
        )
        for (rows in copies) {
            val byId = rows.associateBy { it.id }
            val blob = byId["w-key"]!!.keyBackup
            assertNotNull("the blob survived this copy", blob)
            assertFalse("a copy handed over a key in the clear", WalletKeyBackup.leaks(fakeKey, blob))
            assertEquals(fakeKey, WalletKeyBackup.open(sealer, blob)!!.secret)
            assertNull("a wallet this device could not seal carries no key", byId["w-addresses"]!!.keyBackup)
        }

        // A phrase wallet restored on the strength of its blob comes back as a phrase, ready to derive.
        val phraseRow = BackupCryptoWalletDto(id = "w-phrase", label = "Recovery", keyBackup = sealedPhrase)
        val material = WalletKeyBackup.open(sealer, phraseRow.keyBackup)!!
        assertEquals(WalletKeyBackupKind.SEED_PHRASE, material.kind)
        assertEquals(fakePhrase, material.secret)
        assertEquals(fakePassphrase, material.passphrase)
    }

    private fun String.hasSecurityJargon(): Boolean = listOf(
        "aes", "gcm", "pbkdf2", "envelope", "cipher", "keystore", "keyring",
        "encrypt", "decrypt", "salt", "hash", "byte"
    ).any { contains(it, ignoreCase = true) }
}
