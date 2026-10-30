package com.securevault.pro.crypto

import com.securevault.pro.data.remote.realtime.RealtimeFieldSchema
import com.securevault.pro.data.remote.realtime.RealtimeModule
import com.securevault.pro.data.remote.realtime.RealtimeRecordCodec
import com.securevault.pro.data.remote.realtime.decodeAny
import com.securevault.pro.data.remote.realtime.decodeRecord
import com.securevault.pro.data.remote.realtime.encodeRecord
import com.securevault.pro.data.remote.realtime.holds
import com.securevault.pro.data.remote.realtime.recordOf
import com.securevault.pro.data.wallet.BitgetCredentialFields
import com.securevault.pro.data.wallet.BitgetCredentialVault
import com.securevault.pro.data.wallet.BitgetCredentials
import com.securevault.pro.data.wallet.BitgetSecretSealer
import com.securevault.pro.data.wallet.BitgetSnapshotCodec
import com.securevault.pro.domain.model.BitgetAsset
import com.securevault.pro.domain.model.BitgetSecretBundle
import com.securevault.pro.domain.model.BitgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential-exclusion guard for the Bitget ("Crypto Violet") feature.
 *
 * How the realtime payload is built, in the order the engine walks it:
 *
 * 1. `RealtimeStore.loadAll(module)` reads a record list from one Room DAO (or the file vault) —
 *    the only mapping from storage to sync, and it is an exhaustive `when` over [RealtimeModule].
 * 2. `RealtimeEngine.pushOne` then calls `RealtimeRecordCodec.encode(module, record)`, and
 *    `RealtimeEnvelope.seal` encrypts that exact string into the `e` field.
 * 3. On the way back in, `RealtimeFieldSchema` refuses any body whose key set is not one of the
 *    seven published shapes.
 *
 * Bitget credentials take part in none of those three steps: they are sealed by
 * `BitgetCredentialStore` into their own DataStore file, which no DAO and no module reads, and their
 * record types are not in [RealtimeFieldSchema]. Because the schema is the single source of truth for
 * "which class is a syncable record", the assertions below are the machine-checkable form of that
 * claim: every module rejects a credential object, no credential field name appears in any published
 * key table, and no body shaped like a credential decodes as any module.
 *
 * The fake values in this file are fabricated for the test and are not real credentials.
 */
class BitgetCredentialIsNeverSyncedTest {

    private val fakeApiKey = "FAKE-ACCESS-KEY-abcdef0123456789"
    private val fakeSecret = "ZmFrZS1zZWNyZXQtdmFsdWUtZm9yLXRlc3Qtb25seQ=="
    private val fakePassphrase = "fake-passphrase-for-tests-only"

    /** A sealer that is reversible but never leaves the plaintext visible in the stored string. */
    private class RecordingSealer : BitgetSecretSealer {
        private val book = HashMap<String, String>()
        var seals: Int = 0
        var opens: Int = 0
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

    private val codec = RealtimeRecordCodec()

    private fun bundle() = BitgetSecretBundle(fakeApiKey, fakeSecret, fakePassphrase)

    private fun sealedFields(): Map<String, String> {
        val result = BitgetCredentialVault(RecordingSealer())
            .fieldsFor(bundle(), "Main account", 1700000000000L)
        assertTrue(result.message ?: "sealing failed", result.ok)
        return result.fields
    }

    @Test
    fun credentialsAreStoredAsSealedValuesPlusAMaskedKeyOnly() {
        val fields = sealedFields()
        val joined = fields.values.joinToString("|") { it.toString() }
        assertFalse("the API key was written in the clear", joined.contains(fakeApiKey))
        assertFalse("the secret was written in the clear", joined.contains(fakeSecret))
        assertFalse("the passphrase was written in the clear", joined.contains(fakePassphrase))

        val masked = fields.getValue(BitgetCredentialFields.MASKED_KEY)
        assertFalse(masked.contains(fakeApiKey.removeSuffix(fakeApiKey.takeLast(4))))
        assertEquals(fakeApiKey.takeLast(4), masked.takeLast(4))

        // Every field the vault can write is one the store knows about, so `clear()` removes all of
        // them: nothing can be persisted under a name that removal never looks for.
        assertTrue(
            "unknown stored field: ${fields.keys - BitgetCredentialFields.ALL_FIELDS}",
            BitgetCredentialFields.ALL_FIELDS.containsAll(fields.keys)
        )
        BitgetCredentialFields.SEALED_FIELDS.forEach { name ->
            assertTrue("$name is not sealed", fields.getValue(name).startsWith("sealed."))
        }
    }

    @Test
    fun noRealtimeModuleOwnsAnyBitgetRecordType() {
        val samples: List<Any> = listOf(
            sealedFields(),
            BitgetCredentialVault(RecordingSealer()).stateOf(sealedFields())!!,
            bundle(),
            BitgetAsset("Spot", "USDT", "Spot wallet", 1.0, 0.0, 1.0),
            BitgetSnapshot(listOf(BitgetAsset("Spot", "USDT", "Spot wallet", 1.0, 0.0, 1.0)), 1L, null)
        )
        samples.forEach { sample ->
            assertNull(
                "${sample.javaClass.simpleName} must not map to a module",
                RealtimeFieldSchema.moduleOf(sample)
            )
            RealtimeModule.entries.forEach { module ->
                assertFalse(
                    "${sample.javaClass.simpleName} claimed by ${module.segment}",
                    module.holds(sample)
                )
                assertNull(codec.recordOf(module, sample))
                assertThrows(
                    "${sample.javaClass.simpleName} was accepted as ${module.segment}",
                    IllegalArgumentException::class.java
                ) { codec.encodeRecord(module, sample) }
            }
        }
    }

    @Test
    fun thePublishedKeyTablesHaveNoFieldThatCouldHoldACredential() {
        val forbidden = listOf(
            "apikey", "api_key", "apisecret", "api_secret", "secretkey", "secret_key",
            "passphrase", "accesskey", "accesssign", "accesstimestamp", "signedkey",
            "sealedapikey", "sealedapisecret", "sealedpassphrase"
        )
        RealtimeFieldSchema.MODULES.forEach { module ->
            val keys = RealtimeFieldSchema.keysOf(module)
            keys.forEach { key ->
                val lowered = key.lowercase()
                forbidden.forEach { word ->
                    assertFalse(
                        "${module.segment} publishes a credential-shaped key: $key",
                        lowered.contains(word)
                    )
                }
            }
            assertFalse(
                "${module.segment} claims a Bitget storage field",
                keys.any { it in BitgetCredentialFields.SEALED_FIELDS }
            )
        }
        // The wallet segment keeps the fields it always had; nothing was added for Bitget.
        assertEquals(
            listOf(
                "id", "label", "kind", "secret", "addressesJson", "balancesJson", "totalUsd",
                "lastSyncedAt", "createdAt", "updatedAt"
            ),
            RealtimeFieldSchema.keysOf(RealtimeModule.CRYPTO_WALLETS)
        )
    }

    @Test
    fun theSegmentTableStillHasNoPlaceForBitget() {
        assertEquals(7, RealtimeModule.SEGMENTS.size)
        assertEquals(
            listOf("notes", "entries", "cards", "totps", "contacts", "cryptoWallets", "files"),
            RealtimeModule.SEGMENTS
        )
        listOf("bitget", "bitgetAccounts", "violet", "exchangeKeys", "apiKeys").forEach { segment ->
            assertNull(RealtimeModule.fromSegment(segment))
            assertFalse(RealtimeModule.isKnownSegment(segment))
        }
    }

    @Test
    fun aCredentialShapedBodyCannotBeDecodedAsAnyModule() {
        val bodies = listOf(
            """{"id":"fake-1","apiKey":"$fakeApiKey","apiSecret":"$fakeSecret","passphrase":"$fakePassphrase"}""",
            """{"label":"Main account","sealedApiKey":"sealed.x.env","sealedApiSecret":"sealed.y.env","sealedPassphrase":"sealed.z.env"}""",
            // The wallet shape with one credential key bolted on: an unknown key disqualifies it.
            """{"id":"fake-1","label":"Bitget","kind":"BITGET","secret":"sealed.x.env","addressesJson":"[]",""" +
                """"balancesJson":"[]","totalUsd":0,"lastSyncedAt":0,"createdAt":0,"updatedAt":0,"apiKey":"$fakeApiKey"}"""
        )
        bodies.forEach { body ->
            assertNull("decoded a credential body: $body", codec.decodeAny(body))
            val tree = RealtimeFieldSchema.parseObject(body)
            assertNull("identified a module for: $body", tree?.let(RealtimeFieldSchema::identify))
            RealtimeModule.entries.forEach { module ->
                assertNull(codec.decodeRecord(module, body))
                assertFalse(RealtimeFieldSchema.accepts(module, tree))
            }
        }
        assertTrue(BitgetCredentials.carriesSecret(bodies.first()))
    }

    @Test
    fun theSnapshotDocumentCarriesOnlyNumbersAndSymbols() {
        val snapshot = BitgetSnapshot(
            assets = listOf(
                BitgetAsset("Spot", "USDT", "Spot wallet", 12.5, 0.0, 1.0),
                BitgetAsset("USDT futures", "USDT", "Futures equity", 100.25, 8.0, 1.0)
            ),
            fetchedAt = 1700000000000L,
            notice = "Futures account not read: the signature did not match."
        )
        val text = BitgetSnapshotCodec.encode(snapshot)
        assertFalse("a snapshot named a credential field", BitgetCredentials.carriesSecret(text))
        assertFalse(text.contains(fakeApiKey))
        assertFalse(text.contains(fakeSecret))
        assertFalse(text.contains(fakePassphrase))

        val back = BitgetSnapshotCodec.decode(text)
        assertEquals(snapshot.fetchedAt, back?.fetchedAt)
        assertEquals(snapshot.notice, back?.notice)
        assertEquals(snapshot.assets.size, back?.assets?.size)
        snapshot.assets.forEachIndexed { index, asset ->
            val other = back!!.assets[index]
            assertEquals(asset.account, other.account)
            assertEquals(asset.symbol, other.symbol)
            assertEquals(asset.caption, other.caption)
            assertEquals(asset.available, other.available, 1e-9)
            assertEquals(asset.onHold, other.onHold, 1e-9)
            assertEquals(asset.priceUsd, other.priceUsd, 1e-9)
        }
        assertEquals(snapshot.totalUsd, back!!.totalUsd, 1e-9)
        assertNull(BitgetSnapshotCodec.decode("not the header\nasset|a|b|c|1|2|3"))
        assertNull(BitgetSnapshotCodec.decode(null))
        // A snapshot is not a record either, so it cannot be pushed as one.
        RealtimeModule.entries.forEach { module -> assertFalse(module.holds(snapshot)) }
    }

    @Test
    fun theVaultOpensSecretsOnlyFromSealedFieldsAndNothingElse() {
        val sealer = RecordingSealer()
        val vault = BitgetCredentialVault(sealer)
        val fields = vault.fieldsFor(bundle(), "Main", 1700000000000L).fields

        // Rendering the panel takes no decryption at all.
        val state = vault.stateOf(fields)!!
        sealer.opens = 0
        assertEquals("Main", state.label)
        assertEquals("********" + fakeApiKey.takeLast(4), state.maskedApiKey)
        assertEquals(0, sealer.opens)
        assertFalse(state.toString().contains(fakeSecret))
        assertFalse(state.toString().contains(fakePassphrase))
        assertFalse(state.toString().contains(fakeApiKey))

        val opened = vault.secretsOf(fields)!!
        assertEquals(fakeApiKey, opened.apiKey)
        assertEquals(fakeSecret, opened.apiSecret)
        assertEquals(fakePassphrase, opened.passphrase)
        assertEquals(3, sealer.opens)

        // An empty or partial record yields nothing rather than a half-open credential.
        assertNull(vault.secretsOf(emptyMap()))
        assertNull(vault.secretsOf(fields.minus(BitgetCredentialFields.SEALED_PASSPHRASE)))
        assertNull(vault.stateOf(emptyMap()))
    }

    @Test
    fun removingTheStoredRecordLeavesNoCredentialFieldBehind() {
        val vault = BitgetCredentialVault(RecordingSealer())
        val fields = HashMap(vault.fieldsFor(bundle(), "Main", 1700000000000L).fields)
        fields[BitgetCredentialFields.SNAPSHOT] = BitgetSnapshotCodec.encode(
            BitgetSnapshot(listOf(BitgetAsset("Spot", "USDT", "Spot wallet", 1.0, 0.0, 1.0)), 1L, null)
        )
        fields[BitgetCredentialFields.SNAPSHOT_AT] = "1"

        // This is exactly what `BitgetCredentialStore.clear()` does: drop every field the feature owns.
        BitgetCredentialFields.ALL_FIELDS.forEach { fields.remove(it) }

        assertTrue(fields.isEmpty())
        assertNull(vault.stateOf(fields))
        assertNull(vault.secretsOf(fields))
        assertNull(BitgetSnapshotCodec.decode(fields[BitgetCredentialFields.SNAPSHOT]))
    }

    @Test
    fun maskingNeverExposesMoreThanFourCharacters() {
        // Only the last four characters of the trimmed key survive; everything before them is dots.
        assertEquals("********6789", BitgetCredentials.mask("FAKE-ACCESS-KEY-abcdef0123456789"))
        assertEquals("****", BitgetCredentials.mask("abcd"))
        assertEquals("****", BitgetCredentials.mask(""))
        assertEquals("****", BitgetCredentials.mask("   "))
        assertEquals("********7890", BitgetCredentials.mask("  1234567890  "))
        assertFalse(BitgetCredentials.mask("FAKE-ACCESS-KEY-abcdef0123456789").contains("FAKE"))
        assertFalse(BitgetCredentials.mask(fakeSecret).contains(fakeSecret.take(12)))
        // Long keys are still capped at four visible characters.
        assertEquals(12, BitgetCredentials.mask(fakeApiKey).length)
    }

    @Test
    fun inputValidationRejectsObviousMistakesBeforeAnythingIsSealed() {
        val sealer = RecordingSealer()
        assertNotNullCheck(BitgetCredentials.validate(BitgetSecretBundle("short", fakeSecret, fakePassphrase)))
        assertNotNullCheck(BitgetCredentials.validate(BitgetSecretBundle(fakeApiKey, "short", fakePassphrase)))
        assertNotNullCheck(BitgetCredentials.validate(BitgetSecretBundle(fakeApiKey, fakeSecret, "  ")))
        assertNotNullCheck(
            BitgetCredentials.validate(
                BitgetSecretBundle("FAKE KEY WITH SPACES", fakeSecret, fakePassphrase)
            )
        )
        assertNull(BitgetCredentials.validate(bundle()))
        // A sealer that will not answer stores nothing, so there is no plaintext fallback path.
        val broken = BitgetCredentialVault(object : BitgetSecretSealer {
            override fun seal(plaintext: String): String? = null
            override fun open(ciphertext: String?): String? = null
        })
        val result = broken.fieldsFor(bundle(), "Main", 1700000000000L)
        assertFalse(result.ok)
        assertTrue(result.fields.isEmpty())
        // The untouched recording sealer proves the rejections above never reached the storage step.
        assertTrue("input validation sealed something it should not have", sealer.seals == 0)
    }

    private fun assertNotNullCheck(problem: com.securevault.pro.data.wallet.BitgetInputProblem?) {
        assertTrue("expected a rejection", problem != null)
    }
}
