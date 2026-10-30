package com.securevault.pro.security.passkey

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.SecureRandom

/**
 * The WebAuthn option documents this app hands to `androidx.credentials`.
 *
 * Everything in here is a string-building exercise over a format that is published by the FIDO
 * Alliance, so it is pure JVM and it is tested — which matters because on this dependency set
 * [PasskeyCredentialTransport] is a thin caller of two library methods and nothing else: the JSON
 * below *is* the behaviour we control. What the authenticator does with it is the device's business.
 *
 * The shape is what the pinned artifact expects. `CreatePublicKeyCredentialRequest(requestJson)` and
 * `GetPublicKeyCredentialOption(requestJson)` are documented by the library as taking the
 * `PublicKeyCredentialCreationOptions` / `PublicKeyCredentialRequestOptions` object itself, and the
 * framework transport (`CredentialProviderFrameworkImpl`, the only one reachable here) forwards that
 * string to `android.credentials.*` unchanged. [PUBLIC_KEY_WRAPPER_FIELD] exists because the
 * GMSService transport some other builds use wants the same object nested under `publicKey` instead;
 * the owner's device is the only place that can prove which of the two it wants, and it is one flag.
 */
object PasskeyRequestBuilder {

    /** ES256, the algorithm every Android credential store supports. `-7` is its COSE identifier. */
    const val ALG_ES256 = -7

    /** RS256 as a fallback for an authenticator that has no COSE EC2 support. */
    const val ALG_RS256 = -257

    /** Milliseconds, which is the unit the WebAuthn `timeout` field is in. */
    const val ENROLMENT_TIMEOUT_MS = 120_000L

    /** A sign-in sheet should not sit open on somebody's phone. */
    const val AUTHENTICATION_TIMEOUT_MS = 60_000L

    /** Random bytes per ceremony; a challenge is never reused, and never shorter than 16 bytes. */
    const val CHALLENGE_BYTES = 32

    const val RP_TYPE_WEBAUTHN = "webauthn"
    const val CRED_TYPE_PUBLIC_KEY = "public-key"

    /** The `publicKey` nesting some transports expect; off by default, see the class note above. */
    const val PUBLIC_KEY_WRAPPER_FIELD = "publicKey"

    private val random = SecureRandom()

    fun newChallenge(): ByteArray = ByteArray(CHALLENGE_BYTES).also { random.nextBytes(it) }

    /**
     * An enrolment document for one relying party.
     *
     * `residentKey = required` and `requireResidentKey = true` are the passkey part of the request: a
     * resident (discoverable) credential is the only kind the owner can sign in to without the site
     * first naming a key id, which is what makes "list my passkeys and sign in with one" possible.
     * `userVerification = required` is the app's own policy — a passkey this vault offers must be
     * gated by a biometric or the screen lock, not by a touch alone.
     */
    fun registrationJson(
        rpDomain: String,
        rpName: String,
        userName: String,
        userDisplayName: String = userName,
        challenge: ByteArray = newChallenge(),
        userHandle: ByteArray = newChallenge(),
        timeoutMs: Long = ENROLMENT_TIMEOUT_MS,
        excludeCredentialIds: List<String> = emptyList(),
        wrapInPublicKeyObject: Boolean = false
    ): String {
        val domain = PasskeyDomainRules.normalize(rpDomain)
        val options = JsonObject()
        options.addProperty("challenge", PasskeyWire.encodeBase64Url(challenge))
        options.add("rp", JsonObject().apply {
            addProperty("id", domain)
            addProperty("name", rpName.trim().ifBlank { domain })
        })
        options.add("user", JsonObject().apply {
            addProperty("id", PasskeyWire.encodeBase64Url(userHandle))
            addProperty("name", userName.trim().ifBlank { domain })
            addProperty("displayName", userDisplayName.trim().ifBlank { userName.trim() }.ifBlank { domain })
        })
        options.add("pubKeyCredParams", JsonArray().apply {
            add(algorithmParameter(ALG_ES256))
            add(algorithmParameter(ALG_RS256))
        })
        options.addProperty("timeout", timeoutMs)
        options.add("excludeCredentials", JsonArray().apply {
            excludeCredentialIds.filter { it.isNotBlank() }.forEach { add(credentialDescriptor(it)) }
        })
        options.add("authenticatorSelection", JsonObject().apply {
            addProperty("residentKey", "required")
            addProperty("requireResidentKey", true)
            addProperty("userVerification", "required")
            addProperty("authenticatorAttachment", "platform")
        })
        options.addProperty("attestation", "none")
        return if (wrapInPublicKeyObject) wrap(options).toString() else options.toString()
    }

    /**
     * A sign-in document for one relying party. With no [allowCredentialIds] the authenticator is
     * asked for any resident key it holds for that `rpId` — the passkey UX the owner sees in Chrome.
     * With ids, it is a targeted request for the keys this vault has records of.
     */
    fun authenticationJson(
        rpDomain: String,
        challenge: ByteArray = newChallenge(),
        allowCredentialIds: List<String> = emptyList(),
        timeoutMs: Long = AUTHENTICATION_TIMEOUT_MS,
        userVerification: String = "required",
        wrapInPublicKeyObject: Boolean = false
    ): String {
        val domain = PasskeyDomainRules.normalize(rpDomain)
        val options = JsonObject()
        options.addProperty("challenge", PasskeyWire.encodeBase64Url(challenge))
        options.addProperty("timeout", timeoutMs)
        options.addProperty("rpId", domain)
        options.addProperty("userVerification", userVerification)
        options.add("allowCredentials", JsonArray().apply {
            allowCredentialIds.filter { it.isNotBlank() }.forEach { add(credentialDescriptor(it)) }
        })
        return if (wrapInPublicKeyObject) wrap(options).toString() else options.toString()
    }

    /**
     * The account fields for a fresh enrolment, kept together so the caller cannot accidentally reuse
     * a handle from a different site: `userHandle` is the credential the RP will get back at every
     * assertion, so reusing one across two domains is an account-linking bug, not a convenience.
     */
    fun accountFor(userName: String, rpDomain: String): PasskeyAccount {
        val domain = PasskeyDomainRules.normalize(rpDomain)
        val handle = ByteArray(CHALLENGE_BYTES / 2).also { random.nextBytes(it) }
        return PasskeyAccount(
            rpDomain = domain,
            // Relying parties compare the account name case-insensitively, so the stored form is
            // the canonical lower-case one rather than whatever the owner typed.
            userName = userName.trim().lowercase().ifBlank { domain },
            userHandle = PasskeyWire.encodeBase64Url(handle)
        )
    }

    /** True when a request document names [domain] as its relying party, for a pre-flight check. */
    fun requestsDomain(requestJson: String?, domain: String): Boolean {
        if (requestJson.isNullOrBlank()) return false
        val root = runCatching { com.google.gson.JsonParser.parseString(requestJson) }
            .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val options = root.getAsJsonObject(PUBLIC_KEY_WRAPPER_FIELD) ?: root
        val expected = PasskeyDomainRules.normalize(domain)
        val rpId = options.get("rpId")?.takeIf { it.isJsonPrimitive }?.asString
            ?: options.getAsJsonObject("rp")?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return false
        return PasskeyDomainRules.normalize(rpId) == expected
    }

    private fun algorithmParameter(alg: Int): JsonObject = JsonObject().apply {
        addProperty("type", CRED_TYPE_PUBLIC_KEY)
        addProperty("alg", alg)
    }

    private fun credentialDescriptor(credentialId: String): JsonObject = JsonObject().apply {
        addProperty("type", CRED_TYPE_PUBLIC_KEY)
        addProperty("id", PasskeyWire.normalizeBase64Url(credentialId))
    }

    private fun wrap(options: JsonObject): JsonObject =
        JsonObject().apply { add(PUBLIC_KEY_WRAPPER_FIELD, options) }
}

/** The three account fields one enrolment needs, generated as a set so they cannot drift apart. */
data class PasskeyAccount(
    val rpDomain: String,
    val userName: String,
    val userHandle: String
)
