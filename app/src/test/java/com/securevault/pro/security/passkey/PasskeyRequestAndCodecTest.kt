package com.securevault.pro.security.passkey

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The WebAuthn documents this app hands to `androidx.credentials`, and the stored form of a record.
 *
 * On the pinned dependency set the transport is two library calls — `createCredential(CreatePublicKey
 * CredentialRequest(json))` and `getCredential(GetCredentialRequest(listOf(GetPublicKeyCredentialOption
 * (json)))) — so the JSON built here *is* the behaviour this app controls. These tests are what stand
 * between "it compiles" and "the authenticator would accept it": an ES256-first parameter list, a
 * discoverable credential (`residentKey = required`), verification the app actually wants
 * (`userVerification = required`), base64url without padding, and a fresh 32-byte challenge per call.
 *
 * The codec half is the other contract: a record survives being written and read, and a record that
 * does not survive is dropped rather than half-offered for a sign-in it cannot complete.
 */
class PasskeyRequestAndCodecTest {

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private val creationRequest by lazy {
        json(
            PasskeyRequestBuilder.registrationJson(
                rpDomain = "https://www.Example.com/login",
                rpName = "Example",
                userName = "owner@example.com",
                challenge = ByteArray(32) { (it + 1).toByte() },
                userHandle = ByteArray(16) { 2 },
                excludeCredentialIds = listOf("AAA", "", "BB+CC/DD==")
            )
        )
    }

    private val requestOptions by lazy {
        json(
            PasskeyRequestBuilder.authenticationJson(
                rpDomain = "example.com",
                challenge = ByteArray(32) { 7 },
                allowCredentialIds = listOf("AAA", "BB+CC/DD==")
            )
        )
    }

    // ---- enrolment document ------------------------------------------------------------------

    @Test
    fun `the rp is the normalised host and the account is spelled for the site`() {
        val rp = creationRequest.getAsJsonObject("rp")
        assertEquals("example.com", rp.string("id"))
        assertEquals("Example", rp.string("name"))
        val user = creationRequest.getAsJsonObject("user")
        assertEquals("owner@example.com", user.string("name"))
        assertEquals("owner@example.com", user.string("displayName"))
        assertEquals(PasskeyWire.encodeBase64Url(ByteArray(16) { 2 }), user.string("id"))
        assertNotEquals(user.string("id"), creationRequest.string("challenge"))
    }

    /** The two fields a browser would refuse on: an rp id carrying a scheme, or a user id that is a handle. */
    @Test
    fun `a pasted URL still enrols against a bare host`() {
        assertEquals("example.com", creationRequest.getAsJsonObject("rp").string("id"))
        assertTrue(PasskeyRequestBuilder.requestsDomain(
            PasskeyRequestBuilder.registrationJson("login.example.co.uk", "", ""), "login.example.co.uk"
        ))
    }

    @Test
    fun `es256 leads the algorithm list and rs256 backs it up`() {
        val params = creationRequest.getAsJsonArray("pubKeyCredParams")
        assertEquals(2, params.size())
        val first = params[0].asJsonObject
        assertEquals("public-key", first.string("type"))
        assertEquals(-7, first.get("alg").asInt)
        assertEquals(-257, params[1].asJsonObject.get("alg").asInt)
        assertEquals(PasskeyRequestBuilder.ALG_ES256, first.get("alg").asInt)
        assertEquals(PasskeyRequestBuilder.ALG_RS256, params[1].asJsonObject.get("alg").asInt)
    }

    /**
     * A resident credential is the passkey part of the request. Without it the site has to name a key
     * id before the authenticator will answer, and "list my passkeys and sign in with one" is not a
     * thing a person can do. `requireResidentKey` is the level-1 spelling kept for older providers.
     */
    @Test
    fun `the credential is discoverable and verified, never touch-only`() {
        val selection = creationRequest.getAsJsonObject("authenticatorSelection")
        assertEquals("required", selection.string("residentKey"))
        assertTrue(selection.get("requireResidentKey").asBoolean)
        assertEquals("required", selection.string("userVerification"))
        assertEquals("platform", selection.string("authenticatorAttachment"))
        assertEquals("none", creationRequest.string("attestation"))
        assertEquals("required", requestOptions.string("userVerification"))
    }

    @Test
    fun `challenges are fresh, long enough, and base64url without padding`() {
        val a = PasskeyRequestBuilder.newChallenge()
        val b = PasskeyRequestBuilder.newChallenge()
        assertEquals(PasskeyRequestBuilder.CHALLENGE_BYTES, a.size)
        assertFalse(a.contentEquals(b))
        val encoded = PasskeyRequestBuilder.registrationJson("example.com", "n", "u")
        val challenge = json(encoded).string("challenge")!!
        assertFalse(challenge.contains('='))
        assertFalse(challenge.contains('+'))
        assertFalse(challenge.contains('/'))
        assertTrue(challenge.length >= 22)
        assertEquals(PasskeyRequestBuilder.CHALLENGE_BYTES, decodeUrl(challenge).size)
    }

    @Test
    fun `the timeouts are the request's own and the app's watchdog is behind them`() {
        assertEquals(120_000L, creationRequest.get("timeout").asLong)
        assertEquals(60_000L, requestOptions.get("timeout").asLong)
        assertEquals(PasskeyRequestBuilder.ENROLMENT_TIMEOUT_MS, creationRequest.get("timeout").asLong)
        assertTrue(com.securevault.pro.security.passkey.PasskeyService.ENROLMENT_WATCHDOG_MS >
            PasskeyRequestBuilder.ENROLMENT_TIMEOUT_MS)
        assertTrue(PasskeyService.SIGN_IN_WATCHDOG_MS > PasskeyRequestBuilder.AUTHENTICATION_TIMEOUT_MS)
    }

    @Test
    fun `keys already enrolled are excluded and blank ids are dropped`() {
        val excluded = creationRequest.getAsJsonArray("excludeCredentials")
        assertEquals(listOf("public-key", "public-key"), excluded.map { it.asJsonObject.string("type") })
        assertEquals(listOf("AAA", "BB-CC_DD"), excluded.map { it.asJsonObject.string("id") })
        val allowed = requestOptions.getAsJsonArray("allowCredentials")
        assertEquals(listOf("AAA", "BB-CC_DD"), allowed.map { it.asJsonObject.string("id") })
        val empty = json(PasskeyRequestBuilder.authenticationJson("example.com"))
        assertEquals(0, empty.getAsJsonArray("allowCredentials").size())
    }

    // ---- sign-in document ----------------------------------------------------------------------

    @Test
    fun `a sign-in names the rp and the challenge and nothing else`() {
        assertEquals("example.com", requestOptions.string("rpId"))
        assertEquals(32, decodeUrl(requestOptions.string("challenge")!!).size)
        assertFalse(requestOptions.has("user"))
        assertFalse(requestOptions.has("pubKeyCredParams"))
    }

    /** The one switch the owner's device may need: the same object nested under `publicKey`. */
    @Test
    fun `the wrapper flag changes the shape and nothing about the content`() {
        val wrapped = json(
            PasskeyRequestBuilder.authenticationJson("example.com", wrapInPublicKeyObject = true)
        )
        val inner = wrapped.getAsJsonObject(PasskeyRequestBuilder.PUBLIC_KEY_WRAPPER_FIELD)
        assertNotNull(inner)
        assertEquals("example.com", inner.string("rpId"))
        assertTrue(PasskeyRequestBuilder.requestsDomain(wrapped.toString(), "example.com"))
        assertTrue(PasskeyRequestBuilder.requestsDomain(
            PasskeyRequestBuilder.authenticationJson("example.com"), "example.com"))
        assertFalse(PasskeyRequestBuilder.requestsDomain(null, "example.com"))
        assertFalse(PasskeyRequestBuilder.requestsDomain("{", "example.com"))
        assertFalse(PasskeyRequestBuilder.requestsDomain(
            PasskeyRequestBuilder.authenticationJson("example.com"), "other.net"))
    }

    /** `userHandle` is the credential the RP gets back at every assertion, so it must not be reused. */
    @Test
    fun `accounts are generated as a set and handles never repeat`() {
        val first = PasskeyRequestBuilder.accountFor("Owner@Example.com ", "example.com")
        val second = PasskeyRequestBuilder.accountFor("", "example.com")
        assertEquals("owner@example.com", first.userName)
        assertEquals("example.com", first.rpDomain)
        assertEquals("example.com", second.userName)
        assertNotEquals(first.userHandle, second.userHandle)
        assertEquals(16, decodeUrl(first.userHandle).size)
        assertFalse(first.userHandle.contains('='))
    }

    // ---- the wire helpers --------------------------------------------------------------------------

    @Test
    fun `base64url is normalised the way WebAuthn spells it`() {
        assertEquals("BB-CC_DD", PasskeyWire.normalizeBase64Url("BB+CC/DD=="))
        assertEquals("", PasskeyWire.normalizeBase64Url(null))
        assertEquals("", PasskeyWire.normalizeBase64Url("   "))
        val bytes = ByteArray(48) { (it * 5).toByte() }
        val encoded = PasskeyWire.encodeBase64Url(bytes)
        assertFalse(encoded.contains('='))
        assertTrue(bytes.contentEquals(PasskeyWire.decodeBase64UrlToBytes(encoded)))
        assertTrue(bytes.contentEquals(PasskeyWire.decodeBase64UrlToBytes(encoded + "==")))
        assertNull(PasskeyWire.decodeBase64UrlToBytes("not base64 url !!"))
        assertNull(PasskeyWire.decodeBase64UrlToBytes(""))
        assertEquals("hello", PasskeyWire.decodeBase64UrlToString(
            PasskeyWire.encodeBase64Url("hello".toByteArray())))
    }

    @Test
    fun `the credential id prefers rawId and falls back to id`() {
        assertEquals("AAA", PasskeyWire.credentialIdOf("""{"rawId":"AAA","id":"BBB"}"""))
        assertEquals("BBB", PasskeyWire.credentialIdOf("""{"id":"BBB"}"""))
        assertEquals("BB-CC_DD", PasskeyWire.credentialIdOf("""{"rawId":"BB+CC/DD=="}"""))
        assertNull(PasskeyWire.credentialIdOf("""{"id":12}"""))
        assertNull(PasskeyWire.credentialIdOf("{}"))
        assertNull(PasskeyWire.credentialIdOf("not json"))
        assertNull(PasskeyWire.credentialIdOf(null))
    }

    /**
     * `clientDataJSON` is public by design — the origin and the challenge are in it — which is why the
     * screen can describe a key without opening the sealed attestation object.
     */
    @Test
    fun `the public half of a response is readable and the attachment is honest`() {
        val clientData = PasskeyWire.encodeBase64Url(
            """{"challenge":"YWJj","origin":"https://example.com","type":"webauthn.get"}"""
                .toByteArray()
        )
        val sameDevice = json(
            """{"id":"AAA","authenticatorAttachment":"platform",""" +
                """"response":{"clientDataJSON":"$clientData"}}"""
        ).toString()
        val scanned = """{"authenticatorAttachment":"cross-platform",""" +
            """"response":{"clientDataJSON":"$clientData"}}"""
        val parsed = PasskeyWire.clientDataOf(scanned)!!
        assertEquals("https://example.com", parsed.origin)
        assertEquals("YWJj", parsed.challenge)
        // `authenticatorAttachment` is the provider's own word for where the key lives: a QR-scanned
        // key is 'cross-platform', a key in this device's secure hardware is 'platform'.
        assertTrue(parsed.isCrossPlatform)
        assertFalse(PasskeyWire.clientDataOf(sameDevice)!!.isCrossPlatform)
        assertFalse(PasskeyWire.clientDataOf("""{"response":{"clientDataJSON":"$clientData"}}""")!!
            .isCrossPlatform)
        assertNull(PasskeyWire.clientDataOf("""{"response":{}}"""))
        assertNull(PasskeyWire.clientDataOf("""{"response":{"clientDataJSON":"###"}}"""))
        assertNull(PasskeyWire.clientDataOf(null))
        assertNull(PasskeyWire.clientDataOf("{}"))
    }

    @Test
    fun `a record id is never a credential id`() {
        val id = PasskeyWire.newRecordId()
        assertTrue(id.startsWith("pk-"))
        assertEquals("pk-".length + 18, id.length)
        assertNotEquals(id, PasskeyWire.newRecordId())
    }

    @Test
    fun `transport labels name what the owner can see`() {
        assertEquals("On this device", PasskeyTransports.labelFor(PasskeyTransports.INTERNAL))
        assertEquals("Scanned with another device", PasskeyTransports.labelFor(PasskeyTransports.HYBRID))
        assertEquals("Security key (USB)", PasskeyTransports.labelFor("USB"))
        assertEquals("ble", PasskeyTransports.labelFor("ble"))
        assertEquals("Unknown transport", PasskeyTransports.labelFor(""))
        assertTrue(PasskeyTransports.KNOWN.containsAll(
            listOf(PasskeyTransports.INTERNAL, PasskeyTransports.HYBRID, PasskeyTransports.USB,
                PasskeyTransports.NFC)))
    }

    // ---- the stored form ---------------------------------------------------------------------------

    private fun record() = PasskeyVault(FailingSealer()).let {
        PasskeyRecord(
            id = "pk-0123456789abcdefghij",
            rpDomain = "example.com",
            rpName = "Example",
            credentialId = "AAECAwQFBgcICQoLDA0ODw",
            userName = "owner@example.com",
            label = "owner@example.com · example.com",
            transport = PasskeyTransports.INTERNAL,
            createdAt = 1_700_000_000_000L,
            lastUsedAt = 1_700_000_999_000L,
            useCount = 4,
            sealedMaterial = PasskeyEnvelope.wrap(TEST_BLOB)
        )
    }

    @Test
    fun `a record survives being written and read, field by field`() {
        val original = record()
        val text = PasskeyRecordCodec.encode(original)
        val back = PasskeyRecordCodec.decode(text)
        assertEquals(original, back)
        val fields = PasskeyRecordCodec.decodeFields(text)!!
        assertEquals(PasskeyRecordCodec.ALL_KEYS, fields.keys)
        assertEquals("4", fields[PasskeyRecordCodec.KEY_USE_COUNT])
        assertEquals("1700000000000", fields[PasskeyRecordCodec.KEY_CREATED_AT])
    }

    @Test
    fun `anything this writer did not produce is dropped`() {
        assertNull(PasskeyRecordCodec.decode(null))
        assertNull(PasskeyRecordCodec.decode(""))
        assertNull(PasskeyRecordCodec.decode("   "))
        assertNull(PasskeyRecordCodec.decode("{not json"))
        assertNull(PasskeyRecordCodec.decode("[1,2]"))
        assertNull(PasskeyRecordCodec.decode("""{"id":"pk-1"}"""))
        // An unknown key means a different writer: the value survives, the record is not ours.
        val foreign = PasskeyRecordCodec.encode(record())
            .replaceFirst("{", """{"writtenBy":"something-else",""", )
        assertNotNull(PasskeyRecordCodec.decodeFields(foreign))
        assertEquals(record(), PasskeyRecordCodec.decode(foreign))
    }

    @Test
    fun `a record missing any of the four things that make it usable is refused`() {
        val base = record()
        listOf(
            base.copy(id = " "),
            base.copy(rpDomain = "not a domain"),
            base.copy(rpDomain = "localhost"),
            base.copy(credentialId = ""),
            base.copy(sealedMaterial = ""),
            base.copy(sealedMaterial = "plaintext, not an envelope"),
            base.copy(sealedMaterial = """{"v":3,"data":"nope"}""")
        ).forEach { broken ->
            assertFalse(PasskeyRecordCodec.isValid(broken))
            assertNull(PasskeyRecordCodec.decode(PasskeyRecordCodec.encode(broken)))
        }
    }

    @Test
    fun `the description a screen draws holds no secret and no full id`() {
        val description = PasskeyRecordCodec.describe(record())
        assertEquals("pk-0123456789abcdefghij", description.id)
        assertEquals("example.com", description.rpDomain)
        assertEquals("Example", description.rpName)
        assertEquals(4, description.useCount)
        assertTrue(description.hasSealedMaterial)
        assertFalse(description.credentialTail.contains("AAECAwQ"))
        assertEquals("…", description.credentialTail.take(1))
        assertEquals(9, description.credentialTail.length)
        // An rpName left blank at enrolment falls back to the domain rather than showing nothing.
        assertEquals("example.com", PasskeyRecordCodec.describe(base().copy(rpName = "", label = ""))
            .rpName)
        assertEquals("owner@example.com · example.com",
            PasskeyRecordCodec.describe(base().copy(label = "")).label)
    }

    @Test
    fun `a short credential id is shown whole and a long one only by its tail`() {
        assertEquals("ABC", PasskeyRecordCodec.tailOf("ABC"))
        assertEquals("12345678", PasskeyRecordCodec.tailOf("12345678"))
        assertEquals("…" + "AAECAwQFBgcICQoLDA0ODw".takeLast(8),
            PasskeyRecordCodec.tailOf("AAECAwQFBgcICQoLDA0ODw"))
        assertEquals("", PasskeyRecordCodec.tailOf("   "))
    }

    @Test
    fun `the codec's key set names exactly one field that can carry material`() {
        assertEquals(setOf(PasskeyRecordCodec.KEY_SEALED_MATERIAL), PasskeyRecordCodec.SEALED_KEYS)
        assertEquals(11, PasskeyRecordCodec.ALL_KEYS.size)
        assertFalse(PasskeyRecordCodec.ALL_KEYS.contains("registrationResponse"))
        assertFalse(PasskeyRecordCodec.ALL_KEYS.contains("attestationObject"))
        assertFalse(PasskeyRecordCodec.ALL_KEYS.contains("clientDataJSON"))
        assertFalse(PasskeyRecordCodec.ALL_KEYS.contains("userHandle"))
        assertTrue(PasskeyRecordCodec.ALL_KEYS.contains(PasskeyRecordCodec.KEY_SEALED_MATERIAL))
    }

    @Test
    fun `a blank transport reads as the device it is on`() {
        val back = PasskeyRecordCodec.decode(
            PasskeyRecordCodec.encode(record()).replace("\"transport\":\"internal\"", "\"transport\":\"\"")
        )!!
        assertEquals(PasskeyTransports.INTERNAL, back.transport)
        assertEquals(PasskeyEnvelope.VERSION, PasskeyEnvelope.readVersion(back.sealedMaterial))
    }

    /** A sealer that is never used by this test; it exists so `record()` builds without crypto. */
    private class FailingSealer : PasskeySealer {
        override fun seal(plaintext: String): String? = null
        override fun open(ciphertext: String?): String? = null
    }

    private fun base() = record()

    private fun decodeUrl(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private companion object {
        val TEST_BLOB: String = Base64.getEncoder().encodeToString(ByteArray(12) { 1 }) + "." +
            Base64.getEncoder().encodeToString(ByteArray(28) { 2 })
    }
}
