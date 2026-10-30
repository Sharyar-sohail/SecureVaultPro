package com.securevault.pro.security.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed-blob round trip, which is the only part of this feature that touches the vault's crypto.
 *
 * Nothing here talks to the Android Keystore — that is [com.securevault.pro.security.CryptoManager]'s
 * job on the device, and it is asserted elsewhere. What this test does is hold the passkey code to the
 * *contract* it has with that class: the container is `base64(iv).base64(ciphertext+tag)` with a
 * 12-byte IV and a 128-bit tag, the envelope is the app's existing `{v:3}` JSON, the plaintext that
 * goes in is the provider's registration response plus the account handle, and nothing that comes out
 * of a stored record is ever plaintext. [AesGcmSealer] below is that contract written against
 * `javax.crypto` with an ordinary 256-bit key, so the same bytes the vault would write are exercised
 * here without a device.
 *
 * What this proves: sealing, unsealing, the refuse-to-store paths, and that a wrong key yields nothing
 * rather than garbage. What it cannot prove is that this device's key exists — see
 * `agent_notes/passkeys.md`.
 */
class PasskeySealedRecordTest {

    /** Exactly `CryptoManager`'s container: AES-256-GCM, 12-byte IV, 128-bit tag, `b64(iv).b64(ct)`. */
    private class AesGcmSealer(key: ByteArray) : PasskeySealer {
        private val spec = SecretKeySpec(key, "AES")
        private val random = SecureRandom()
        var seals = 0
        var opens = 0

        override fun seal(plaintext: String): String? = try {
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, iv))
            seals++
            encode(iv) + "." + encode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        } catch (_: Throwable) {
            null
        }

        override fun open(ciphertext: String?): String? {
            if (ciphertext.isNullOrBlank()) return null
            opens++
            return try {
                val parts = ciphertext.trim().split('.')
                if (parts.size != 2) return null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, decode(parts[0])))
                String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }

        private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
        private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

        companion object {
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val IV_BYTES = 12
            private const val TAG_BITS = 128
        }
    }

    private val key = ByteArray(32) { index -> (index * 7 + 3).toByte() }
    private val otherKey = ByteArray(32) { index -> (index * 5 + 1).toByte() }

    /** A registration response shaped like the real thing: public material, base64url fields. */
    private val registrationResponse =
        """{"id":"ZmFrZS1jcmVkZW50aWFs","rawId":"AAECAwQFBgcICQoLDA0ODw","type":"public-key",""" +
            """"response":{"attestationObject":"oZJoaW5mb2…lm0",""" +
            """"clientDataJSON":"eyJjaGFsbGVuZ2UiOiJhQUFCQSIsIm9yaWdpbiI6Imh0dHBzOi8vZXhhbXBsZS5jb20ifQ"}}"""

    /**
     * Quote-free fragments of the response. A `contains` check against the whole JSON string would
     * pass for the wrong reason: re-encoding it escapes its quotes, so the substring could never
     * appear even in a file that leaked the response verbatim. These fragments do.
     */
    private val attestationFragment = "oZJoaW5mb2…lm0"
    private val responseIdFragment = "ZmFrZS1jcmVkZW50aWFs"

    private val credentialId = "AAECAwQFBgcICQoLDA0ODw"

    private fun enrolment(
        domain: String = "example.com",
        userName: String = "owner@example.com",
        response: String = registrationResponse,
        handle: String = "ZmFrZS11c2VyLWhhbmRsZQ"
    ) = PasskeyEnrolment(
        rpDomain = domain,
        rpName = "Example",
        userName = userName,
        credentialId = credentialId,
        registrationResponseJson = response,
        userHandle = handle,
        transport = PasskeyTransports.INTERNAL,
        enrolledAt = 1_700_000_000_000L
    )

    // ---- the round trip ------------------------------------------------------------------

    @Test
    fun `an enrolment seals into a record the same device can open`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        val sealed = vault.recordFor(enrolment())
        assertTrue(sealed.message ?: "sealing failed", sealed.ok)
        assertNotNull(PasskeyEnvelope.readSealedBlob(sealed.record!!.sealedMaterial))
        assertEquals(registrationResponse, vault.openMaterial(sealed.record!!))
    }

    /** Every step of the path a record actually travels, asserted one at a time. */
    @Test
    fun `the sealed response survives every step of the record path`() {
        val sealer = AesGcmSealer(key)
        val vault = PasskeyVault(sealer)
        val enrolment = enrolment()
        val record = vault.recordFor(enrolment).record!!

        // 1. the envelope is the app's {v:3} container and its payload is a real GCM blob
        assertEquals(PasskeyEnvelope.VERSION, PasskeyEnvelope.readVersion(record.sealedMaterial))
        val blob = PasskeyEnvelope.readSealedBlob(record.sealedMaterial)
        assertNotNull(blob)
        assertTrue("not a base64(iv).base64(ct) container: $blob", PasskeyEnvelope.looksLikeBlob(blob!!))
        assertEquals(2, blob.split('.').size)
        assertEquals(12, Base64.getDecoder().decode(blob.substringBefore('.')).size)

        // 2. it opened with this device's key and handed back exactly what went in
        assertEquals(registrationResponse, vault.openMaterial(record))
        assertEquals(enrolment.userHandle, vault.openUserHandle(record))
        assertEquals(1, sealer.seals)

        // 3. the record still round-trips through the stored form
        val restored = PasskeyRecordCodec.decode(PasskeyRecordCodec.encode(record))
        assertEquals(record, restored)
        assertEquals(registrationResponse, vault.openMaterial(restored!!))
    }

    @Test
    fun `every field of the sealed document is one the vault owns`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        val record = vault.recordFor(enrolment()).record!!
        val fields = vault.openFields(record)
        assertNotNull(fields)
        assertEquals(registrationResponse, fields!!.string(PasskeyMaterialKeys.RESPONSE))
        assertEquals("example.com", fields.string(PasskeyMaterialKeys.RP_DOMAIN))
        assertEquals("owner@example.com", fields.string(PasskeyMaterialKeys.USER_NAME))
        assertEquals(1_700_000_000_000L, fields.long(PasskeyMaterialKeys.ENROLLED_AT))
        assertTrue(fields.hasField(PasskeyMaterialKeys.HANDLE))
        assertEquals(
            "the sealed document may not grow fields in secret",
            setOf(
                PasskeyMaterialKeys.RESPONSE, PasskeyMaterialKeys.HANDLE, PasskeyMaterialKeys.USER_NAME,
                PasskeyMaterialKeys.RP_DOMAIN, PasskeyMaterialKeys.ENROLLED_AT
            ),
            PasskeyMaterialKeys.ALL_FIELDS
        )
    }

    /**
     * The whole point of the seal: what is stored never contains what the provider returned.
     *
     * `userName` is deliberately *not* asserted here. It is the account name the relying party already
     * knows, stored in the clear for the same reason `VaultRepository` stores a login's title and
     * website in the clear next to a sealed password — the list has to be drawable without a key.
     */
    @Test
    fun `nothing stored in the clear carries provider material`() {
        val enrolment = enrolment()
        val record = PasskeyVault(AesGcmSealer(key)).recordFor(enrolment).record!!
        val stored = PasskeyRecordCodec.encode(record)
        assertFalse(stored.contains(attestationFragment))
        assertFalse(stored.contains(responseIdFragment))
        assertFalse(stored.contains("attestationObject"))
        assertFalse(stored.contains("clientDataJSON"))
        assertFalse(stored.contains(enrolment.userHandle))
        assertFalse("a stored record looks like it carries a secret field", PasskeyRecordCodec.carriesSecret(stored))

        val sealed = PasskeyEnvelope.readSealedBlob(record.sealedMaterial)!!
        assertTrue(record.sealedMaterial.contains(sealed))

        // …and the descriptive half is what the list screen draws without decrypting anything.
        val description = PasskeyRecordCodec.describe(record)
        assertEquals("example.com", description.rpDomain)
        assertEquals(PasskeyTransports.labelFor(PasskeyTransports.INTERNAL), description.transportLabel)
        assertTrue(description.hasSealedMaterial)
        assertFalse(description.toString().contains(attestationFragment))
    }

    /** The sealed document itself is what the guard is for: it always looks like secret material. */
    @Test
    fun `the guard recognises a leaked document when it sees one`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        val record = vault.recordFor(enrolment()).record!!
        val plaintext = vault.openFields(record)!!
        val leaked = """{"${PasskeyMaterialKeys.RESPONSE}":"$attestationFragment",""" +
            """"${PasskeyMaterialKeys.HANDLE}":"${enrolment().userHandle}"}"""
        assertTrue(PasskeyRecordCodec.carriesSecret(leaked))
        assertTrue(plaintext.string(PasskeyMaterialKeys.RESPONSE)?.contains(attestationFragment) == true)
        assertTrue(PasskeyMaterialKeys.SECRET_WORDS.contains("attestationobject"))
        // The container field name is not itself a secret, or every correct record would fail the guard.
        assertFalse(PasskeyMaterialKeys.SECRET_WORDS.contains("sealedmaterial"))
    }

    @Test
    fun `a different device key opens nothing`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        val record = vault.recordFor(enrolment()).record!!
        val stolen = PasskeyVault(AesGcmSealer(otherKey))
        assertNull(stolen.openMaterial(record))
        assertNull(stolen.openFields(record))
        assertNull(stolen.openUserHandle(record))
    }

    @Test
    fun `a tampered blob fails the tag and yields nothing`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        val record = vault.recordFor(enrolment()).record!!
        val blob = PasskeyEnvelope.readSealedBlob(record.sealedMaterial)!!
        val parts = blob.split('.')
        val body = Base64.getDecoder().decode(parts[1])
        body[0] = (body[0].toInt() xor 0x41).toByte()
        val tampered = PasskeyEnvelope.wrap(parts[0] + "." + Base64.getEncoder().encodeToString(body))
        assertNull(vault.openMaterial(record.copy(sealedMaterial = tampered)))
    }

    // ---- refusal paths -------------------------------------------------------------------

    @Test
    fun `a sealer that will not answer stores nothing`() {
        val vault = PasskeyVault(object : PasskeySealer {
            override fun seal(plaintext: String): String? = null
            override fun open(ciphertext: String?): String? = null
        })
        val sealed = vault.recordFor(enrolment())
        assertFalse(sealed.ok)
        assertNull(sealed.record)
        assertNotNull(sealed.message)
        assertTrue(sealed.message!!.contains("could not"))
    }

    @Test
    fun `a domain that is not registrable stores nothing`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        listOf("", "localhost", "co.uk", "user@example.com").forEach { bad ->
            val sealed = vault.recordFor(enrolment(domain = bad))
            assertFalse("`$bad` was accepted", sealed.ok)
            assertNull(sealed.record)
        }
    }

    @Test
    fun `an empty response from the provider stores nothing`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        val sealed = vault.recordFor(enrolment(response = "   "))
        assertFalse(sealed.ok)
        assertNull(sealed.record)
        // …and a response with no credential id anywhere in it cannot be matched to a site later.
        val idless = vault.recordFor(
            enrolment(response = """{"type":"public-key"}""").copy(credentialId = "")
        )
        assertFalse(idless.ok)
        assertTrue(idless.message!!.contains("credential id"))
    }

    @Test
    fun `the credential id is taken from the response when the caller did not read it`() {
        val vault = PasskeyVault(AesGcmSealer(key))
        val record = vault.recordFor(enrolment().copy(credentialId = "  ")).record!!
        assertEquals(credentialId, record.credentialId)
    }

    // ---- the envelope itself ---------------------------------------------------------------

    @Test
    fun `the envelope writes v3 plus one blob and reads its own output back`() {
        val wrapped = PasskeyEnvelope.wrap(TEST_BLOB)
        assertTrue(wrapped.startsWith("{"))
        assertEquals(3, PasskeyEnvelope.readVersion(wrapped))
        assertEquals(TEST_BLOB, PasskeyEnvelope.readSealedBlob(wrapped))
    }

    /** A container this reader was not written for is refused outright, never half-parsed. */
    @Test
    fun `a version or a shape it does not know is refused`() {
        assertNull(PasskeyEnvelope.readSealedBlob("""{"v":4,"data":"$TEST_BLOB"}"""))
        assertNull(PasskeyEnvelope.readSealedBlob("""{"v":3,"data":"not a blob"}"""))
        assertNull(PasskeyEnvelope.readSealedBlob("""{"v":3}"""))
        assertNull(PasskeyEnvelope.readSealedBlob("""{"v":3,"data":""}"""))
        assertNull(PasskeyEnvelope.readSealedBlob("[1,2,3]"))
        assertNull(PasskeyEnvelope.readSealedBlob("not json at all"))
        assertNull(PasskeyEnvelope.readSealedBlob(null))
        assertNull(PasskeyEnvelope.readSealedBlob(""))
        // A dotted plaintext is not a container: before the shape check, `not.a.blob` passed as
        // "sealed material" purely because it contained a dot.
        assertNull(PasskeyEnvelope.readSealedBlob("""{"v":3,"data":"not.a.blob"}"""))
        assertNull(PasskeyEnvelope.readSealedBlob("not.a.blob"))
        // The version read is the container's own, and a newer one is still reported, not swallowed.
        assertEquals(4, PasskeyEnvelope.readVersion("""{"v":4,"data":"$TEST_BLOB"}"""))
    }

    /** The cloud envelope's aliases still parse, so a copied-out record is not read as garbage. */
    @Test
    fun `the reader folds the older container shapes back together`() {
        assertEquals(TEST_BLOB, PasskeyEnvelope.readSealedBlob("""{"v":3,"ciphertext":"$TEST_BLOB"}"""))
        assertEquals(TEST_BLOB, PasskeyEnvelope.readSealedBlob("""{"v":3,"payload":"$TEST_BLOB"}"""))
        assertEquals(TEST_BLOB, PasskeyEnvelope.readSealedBlob("""{"v":3,"salt":"YWJj","data":"$TEST_BLOB"}"""))
        val iv = TEST_BLOB.substringBefore('.')
        val body = TEST_BLOB.substringAfter('.')
        assertEquals(TEST_BLOB, PasskeyEnvelope.readSealedBlob("""{"v":3,"iv":"$iv","data":"$body"}"""))
        // A pre-envelope record: the blob itself, stored bare.
        assertEquals(TEST_BLOB, PasskeyEnvelope.readSealedBlob(TEST_BLOB))
        assertEquals(PasskeyEnvelope.VERSION, PasskeyEnvelope.readVersion(TEST_BLOB))
        assertNull(PasskeyEnvelope.readSealedBlob("""{"v":3,"data":"$body"}"""))
    }

    @Test
    fun `the shape check knows a GCM container and nothing else`() {
        assertTrue(PasskeyEnvelope.looksLikeBlob(TEST_BLOB))
        assertFalse(PasskeyEnvelope.looksLikeBlob("abc"))
        assertFalse(PasskeyEnvelope.looksLikeBlob("abc."))
        assertFalse(PasskeyEnvelope.looksLikeBlob("!!!!.!!!!"))
        assertFalse(PasskeyEnvelope.looksLikeBlob(BASE64_8 + "." + BASE64_20))
        assertFalse(PasskeyEnvelope.looksLikeBlob(TEST_BLOB + "." + TEST_BLOB))
        // The tag has to fit inside the body: exactly-16 bytes is a tag and no plaintext.
        assertFalse(PasskeyEnvelope.looksLikeBlob(BASE64_12 + "." + BASE64_16))
        assertTrue(PasskeyEnvelope.looksLikeBlob(BASE64_12 + "." + BASE64_17))
    }

    // ---- counters, not rewrites -------------------------------------------------------------

    @Test
    fun `a successful sign in moves counters and never rewrites the seal`() {
        val sealer = AesGcmSealer(key)
        val vault = PasskeyVault(sealer)
        val record = vault.recordFor(enrolment()).record!!
        val used = vault.markUsed(vault.markUsed(record, 11L), 22L)

        assertEquals(2, used.useCount)
        assertEquals(22L, used.lastUsedAt)
        assertEquals(record.createdAt, used.createdAt)
        assertEquals(record.sealedMaterial, used.sealedMaterial)
        assertEquals(1, sealer.seals)
        assertNotEquals(record, used)

        // Relabelling touches the label only: still no re-seal, and never blank.
        val renamed = vault.relabel(used, "  Work key  ")
        assertEquals("Work key", renamed.label)
        assertEquals(used.sealedMaterial, renamed.sealedMaterial)
        assertEquals(used.label, vault.relabel(used, "   ").label)
    }

    @Test
    fun `labels say what a key is without inventing an account`() {
        assertEquals("example.com", PasskeyVault.labelFor("", "example.com"))
        assertEquals("example.com", PasskeyVault.labelFor("example.com", "example.com"))
        assertEquals("owner@example.com · example.com", PasskeyVault.labelFor("owner@example.com", "example.com"))
    }

    @Test
    fun `a record whose material is not sealed is dropped, not half read`() {
        val record = PasskeyVault(AesGcmSealer(key)).recordFor(enrolment()).record!!
        val plaintexty = record.copy(sealedMaterial = "the provider said this in the clear")
        assertFalse(PasskeyRecordCodec.isValid(plaintexty))
        assertNull(PasskeyRecordCodec.decode(PasskeyRecordCodec.encode(plaintexty)))
        assertFalse(PasskeyRecordCodec.describe(plaintexty).hasSealedMaterial)
    }

    private companion object {
        val BASE64_8: String = Base64.getEncoder().encodeToString(ByteArray(8))
        val BASE64_12: String = Base64.getEncoder().encodeToString(ByteArray(12))
        val BASE64_16: String = Base64.getEncoder().encodeToString(ByteArray(16))
        val BASE64_17: String = Base64.getEncoder().encodeToString(ByteArray(17))
        val BASE64_20: String = Base64.getEncoder().encodeToString(ByteArray(20))
        val TEST_BLOB: String = BASE64_12 + "." + Base64.getEncoder().encodeToString(ByteArray(30) { 5 })
    }
}
