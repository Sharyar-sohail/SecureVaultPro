package com.securevault.pro.security.passkey

/**
 * Whether a passkey can be used *here*, decided from plain values so the answer is testable and the
 * screen can be honest before it tries anything.
 *
 * The thresholds are not guesses. `androidx.credentials` picks its transport in
 * `androidx.credentials.CredentialProviderFactory.getBestAvailableProvider(Context)`, and the shipped
 * bytecode of the pinned `credentials:1.2.0-rc01` artifact says:
 *
 * ```
 *   SDK_INT >= 34  -> androidx.credentials.CredentialProviderFrameworkImpl   (in the core artifact)
 *   SDK_INT == 33  -> tryCreatePreUOemProvider(context)  // a service named by the manifest
 *   SDK_INT <= 32  -> null   ->  Get|CreateCredentialProviderConfigurationException
 * ```
 *
 * which means on this app's dependency set — `androidx.credentials:credentials:1.2.0-rc01` alone, no
 * `credentials-play-services-auth` (its descriptor and Play transitives are not in the offline Gradle
 * cache, so it cannot ship) — a passkey ceremony is reachable only from Android 14 (API 34) upward,
 * through the framework credential store. Below that, this app has registered no credential-provider
 * service in `AndroidManifest.xml` either, so there is genuinely no transport. That is what
 * [PasskeyTransportState.ANDROID_TOO_OLD] means, and the screen says so instead of failing silently.
 */
enum class PasskeyTransportState {
    /** Android 14 or newer: the framework credential store answers. */
    READY,

    /** API 33, and this app declares no `androidx.credentials.CREDENTIAL_PROVIDER_KEY` service. */
    PROVIDER_NOT_REGISTERED,

    /** API 32 or older: no transport exists for this app at all. */
    ANDROID_TOO_OLD,

    /** The check itself could not run; treated as unavailable, never as available. */
    UNKNOWN
}

/**
 * The verdict: transport, plus what the current session permits.
 *
 * Enrolment and use are gated separately because the decoy session is a real vault-shaped UI with a
 * real Keystore: letting an owner enrol a passkey there would write a record that vanishes when the
 * session ends, and letting one authenticate there would hand a site the primary account's key.
 * Listing and reading the device's own availability stays allowed so the screen is never blank.
 */
data class PasskeyAvailability(
    val transport: PasskeyTransportState,
    val sdkInt: Int,
    val sessionIsPrimary: Boolean,
    val vaultKeyOpens: Boolean
) {
    val canRunCeremony: Boolean
        get() = transport == PasskeyTransportState.READY && sessionIsPrimary && vaultKeyOpens

    val canEnrol: Boolean get() = canRunCeremony
    val canAuthenticate: Boolean get() = canRunCeremony

    /** True when the *only* problem is the Android version, which the owner can see for themselves. */
    val isTransportBlockedByPlatform: Boolean
        get() = transport == PasskeyTransportState.ANDROID_TOO_OLD ||
            transport == PasskeyTransportState.PROVIDER_NOT_REGISTERED

    /** A short, non-committal label for a status row. Sentences belong to the screen, not here. */
    fun reasonLabel(): String = when {
        transport == PasskeyTransportState.READY && sessionIsPrimary && vaultKeyOpens -> "ready"
        transport == PasskeyTransportState.READY && !vaultKeyOpens -> "device key"
        transport == PasskeyTransportState.READY -> "session"
        transport == PasskeyTransportState.PROVIDER_NOT_REGISTERED -> "no provider"
        transport == PasskeyTransportState.ANDROID_TOO_OLD -> "android too old"
        else -> "unknown"
    }

    /**
     * The same verdict in the two or three words an owner reads on a status chip. [reasonLabel] stays
     * the machine-ish string the tests pin; this is the one the screen shows, because "ANDROID TOO OLD"
     * on a person's phone is a bug dressed as a label.
     */
    fun ownerBadge(): String = when {
        transport == PasskeyTransportState.READY && sessionIsPrimary && vaultKeyOpens -> "Ready"
        transport == PasskeyTransportState.READY && !vaultKeyOpens -> "Vault key stuck"
        transport == PasskeyTransportState.READY -> "Vault locked"
        transport == PasskeyTransportState.PROVIDER_NOT_REGISTERED -> "No passkey service"
        transport == PasskeyTransportState.ANDROID_TOO_OLD -> "Needs Android 14"
        else -> "Cannot tell"
    }

    /**
     * The Android version this phone reports, in the words the owner sees in their own settings
     * ("Android 12"), not the API number. An unknown level falls back to the number rather than
     * guessing a name, because naming the wrong version is worse than naming none.
     */
    fun androidName(): String = when (sdkInt) {
        28 -> "Android 9"
        29 -> "Android 10"
        30 -> "Android 11"
        31 -> "Android 12"
        32 -> "Android 12L"
        33 -> "Android 13"
        34 -> "Android 14"
        35 -> "Android 15"
        36 -> "Android 16"
        in 0..27 -> "Android $sdkInt"
        else -> "Android 16 or newer"
    }
}

/** Pure decision function. Everything it needs is a number or a boolean the caller observed. */
object PasskeyAvailabilityJudge {

    /**
     * First API level where `CredentialProviderFactory` returns a transport without the app
     * registering one. Read out of the pinned artifact, not from a blog post.
     */
    const val MIN_SDK_FOR_FRAMEWORK_CREDENTIAL_STORE = 34

    /** The one API level where an OEM provider *could* be registered by manifest meta-data. */
    const val PRE_FRAMEWORK_OEM_PROVIDER_SDK = 33

    fun verdict(
        sdkInt: Int,
        providerRegisteredInManifest: Boolean = false,
        sessionIsPrimary: Boolean = true,
        vaultKeyOpens: Boolean = true
    ): PasskeyAvailability {
        val transport = when {
            sdkInt >= MIN_SDK_FOR_FRAMEWORK_CREDENTIAL_STORE -> PasskeyTransportState.READY
            sdkInt == PRE_FRAMEWORK_OEM_PROVIDER_SDK && providerRegisteredInManifest ->
                PasskeyTransportState.READY
            sdkInt == PRE_FRAMEWORK_OEM_PROVIDER_SDK -> PasskeyTransportState.PROVIDER_NOT_REGISTERED
            sdkInt <= 0 -> PasskeyTransportState.UNKNOWN
            else -> PasskeyTransportState.ANDROID_TOO_OLD
        }
        return PasskeyAvailability(
            transport = transport,
            sdkInt = sdkInt,
            sessionIsPrimary = sessionIsPrimary,
            vaultKeyOpens = vaultKeyOpens
        )
    }

    /** Whether a failure the provider reported is really "there is no transport on this device". */
    fun isTransportMissing(failure: PasskeyFailureKind): Boolean = when (failure) {
        PasskeyFailureKind.TRANSPORT_UNAVAILABLE -> true
        else -> false
    }
}

/** What went wrong, in the app's own vocabulary. */
enum class PasskeyFailureKind {
    /** The owner pressed back / dismissed the sheet. Not an error; nothing to fix. */
    CANCELLED,

    /** The ceremony ran out its own clock. Nothing was stored. */
    TIMED_OUT,

    /** No passkey for this site on this device — the honest empty answer to a sign-in. */
    NO_PASSKEY,

    /** No credential provider to talk to (Android version or missing transport dependency). */
    TRANSPORT_UNAVAILABLE,

    /** The provider does not support public-key credentials at all on this build. */
    UNSUPPORTED,

    /** The relying-party JSON was rejected — bad domain, bad challenge, bad options. */
    INVALID_REQUEST,

    /**
     * The provider answered, but what it handed back did not match what this app asked for: a
     * different challenge than the one it sent, a response from the other ceremony, or no credential
     * id to match it to a site. Checked by [PasskeyResponseChecks] before anything is stored, because
     * sealing a response that belongs to somebody else's request would index a passkey this vault
     * cannot use and would say so for years.
     */
    RESPONSE_MISMATCH,

    /** Anything else, with the provider's own words kept for the message. */
    UNKNOWN
}

/** A provider failure translated once, in one place, so no screen re-implements the mapping. */
data class PasskeyFailure(
    val kind: PasskeyFailureKind,
    val providerMessage: String?,
    val providerType: String?
) {
    val isCancelled: Boolean get() = kind == PasskeyFailureKind.CANCELLED
}

/**
 * Maps a thrown credential exception to a [PasskeyFailureKind].
 *
 * Deliberately keyed on the *type name string* rather than `catch (e: GetCredentialCancellationException)`:
 * `androidx.credentials` exception classes are Android artifacts of the same pinned AAR, and matching
 * on a string keeps this classifier — and therefore the whole failure story — a plain JVM unit test.
 * The Android layer ([PasskeyCredentialTransport]) feeds it `e.javaClass.name` plus `e.type`, which is
 * exactly what the WebAuthn-level `DomError` subclasses put in `type`.
 *
 * The names and `type` constants below were read out of the pinned artifact
 * (`androidx.credentials/credentials/1.2.0-rc01`, `classes.jar` →
 * `androidx/credentials/exceptions/`), not from documentation:
 * ```
 *   Get|CreateCredentialCancellationException      type "AbortError"
 *   GetPublicKeyCredentialDomException             type "NotAllowedError" | "ConstraintError" | …
 *   NoCredentialException                          type "NotFoundError"
 *   Get|CreateCredentialProviderConfigurationException
 *   Get|CreateCredentialUnsupportedException
 *   Get|CreateCredentialInterruptedException
 *   Get|CreateCredentialUnknownException
 *   CreateCredentialNoCreateOptionException
 * ```
 * `GetCredentialException` and `CreateCredentialException` are the abstract bases, both exposing
 * `getType(): String` and `getErrorMessage(): CharSequence`, which is what makes one classifier for
 * both directions possible.
 */
object PasskeyFailureClassifier {

    fun classify(exceptionClassName: String?, typeField: String?, message: String?): PasskeyFailure {
        val name = exceptionClassName.orEmpty()
        val type = typeField.orEmpty().lowercase()
        val text = message.orEmpty().lowercase()
        val signal = "$name $type".lowercase()

        return PasskeyFailure(
            kind = when {
                // Checked before the cancellation branch: `TimeoutCancellationException` contains
                // "cancellation", and a clock running out is not the owner pressing back.
                signal.contains("timeout") || type == "timeouterror" ||
                    text.contains("timed out") || text.contains("timeout") -> PasskeyFailureKind.TIMED_OUT

                // `NotAllowedError` is what a provider reports when the owner refused the sheet; the
                // library has no separate cancellation type for it, so it belongs with Cancelled.
                signal.contains("cancellation") ||
                    type == "aborterror" ||
                    type == "notallowederror" ||
                    signal.contains("notallowed") ||
                    signal.contains("interrupted") ||
                    name.contains("domexception") && type.contains("abort") -> PasskeyFailureKind.CANCELLED

                signal.contains("nocredential") || signal.contains("notfound") ||
                    type == "notfounderror" || type == "optouterror" -> PasskeyFailureKind.NO_PASSKEY

                signal.contains("providerconfiguration") || signal.contains("nosuchprovider") ||
                    signal.contains("nocreateoption") ||
                    signal.contains("illegalstate") && text.contains("provider") ->
                    PasskeyFailureKind.TRANSPORT_UNAVAILABLE

                // `NotSupportedError` is the provider saying "this credential type or algorithm is not
                // handled here", which is a capability answer, not a bad request from us.
                signal.contains("unsupported") || signal.contains("notsupported") ||
                    type == "notsupportederror" -> PasskeyFailureKind.UNSUPPORTED

                signal.contains("constraint") || signal.contains("syntax") ||
                    signal.contains("encoding") || signal.contains("dataerror") ||
                    signal.contains("securityerror") || signal.contains("invalid") ||
                    type.startsWith("dom") && type.isNotEmpty() && type != "aborterror" ->
                    PasskeyFailureKind.INVALID_REQUEST

                else -> PasskeyFailureKind.UNKNOWN
            },
            providerMessage = message?.take(160)?.takeIf { it.isNotBlank() },
            providerType = typeField?.take(80)
        )
    }

    /**
     * The sentence an owner should see, without pretending a fix happened.
     *
     * Written for a person, not a log line: no "assertion", no "attestation", no "relying party", no
     * "transport". Every sentence names the thing that happened, whether anything was saved, and what
     * to do next — and never blames the phone's owner for a limit the phone itself imposes.
     */
    fun ownerMessage(failure: PasskeyFailure): String = when (failure.kind) {
        PasskeyFailureKind.CANCELLED ->
            "Cancelled. Nothing was saved and nothing was sent to the site."
        PasskeyFailureKind.TIMED_OUT ->
            "The passkey screen stayed open too long, so this app stopped waiting. Nothing was " +
                "changed — start it again when you are ready."
        PasskeyFailureKind.NO_PASSKEY ->
            "Your device has no passkey for that site yet. Add one here, or sign in with a password."
        PasskeyFailureKind.TRANSPORT_UNAVAILABLE ->
            "Passkeys need Android 14 or newer. This phone's Android version gives this app no " +
                "passkey service to ask, so nothing was created and nothing was lost. Your saved " +
                "passwords and autofill still work as before."
        PasskeyFailureKind.UNSUPPORTED ->
            "Your device's passkey service will not handle a passkey for this app. " +
                "Nothing was saved."
        PasskeyFailureKind.INVALID_REQUEST ->
            "Your device would not accept this passkey request. Check the site's address is the one " +
                "shown on its own sign-up page, then try again — nothing was saved."
        PasskeyFailureKind.RESPONSE_MISMATCH ->
            "The passkey screen answered with something that did not match what this app asked for, " +
                "so this app kept nothing and sent nothing. A passkey may still have been created on " +
                "your device; you can delete it in the system passkey settings."
        PasskeyFailureKind.UNKNOWN ->
            "Your device did not finish the passkey request" +
                (failure.providerMessage?.let { ": ${it.take(90)}" } ?: ".")
    }
}

/**
 * What this app can tell about the answer a passkey screen handed back, before it stores or sends it.
 *
 * A WebAuthn response carries its own public receipts: the `clientDataJSON` field repeats the
 * challenge that was asked for and names which ceremony the answer belongs to
 * (`webauthn.create` when a passkey is made, `webauthn.get` when it is used). Nothing here is new
 * crypto and nothing is a substitute for the site checking the signature on its own server — this
 * app has no server, so it can never confirm a login. What these checks do is stop the one failure
 * mode this side can actually cause: sealing an answer that belongs to a different request, then
 * listing it as a passkey for the wrong site forever.
 *
 * Deliberately tolerant about *absent* fields and strict about *wrong* ones. A provider that does not
 * echo a challenge is not proof of a problem, while a provider echoing a different one is.
 */
object PasskeyResponseChecks {

    /** The response is shaped like an answer to this app's own request. */
    enum class Verdict { MATCHES, UNREADABLE, WRONG_CEREMONY, CHALLENGE_DIFFERS, NO_KEY_ID }

    /**
     * The answer to a passkey *creation* request. [requestJson] is the document this app sent, so the
     * challenge it named can be compared with the challenge the answer repeats.
     */
    fun forEnrolment(responseJson: String?, requestJson: String?): Verdict = check(
        responseJson = responseJson,
        requestJson = requestJson,
        expectedType = "webauthn.create",
        requireKeyId = true
    )

    /** The answer to a passkey *sign-in* request. */
    fun forSignIn(responseJson: String?, requestJson: String?): Verdict = check(
        responseJson = responseJson,
        requestJson = requestJson,
        expectedType = "webauthn.get",
        requireKeyId = false
    )

    /** The owner-facing reason a verdict is a refusal; null when the answer is fine. */
    fun failureFor(verdict: Verdict): PasskeyFailure? = when (verdict) {
        Verdict.MATCHES -> null
        else -> PasskeyFailure(
            kind = PasskeyFailureKind.RESPONSE_MISMATCH,
            providerMessage = when (verdict) {
                Verdict.UNREADABLE -> "the answer could not be read"
                Verdict.WRONG_CEREMONY -> "the answer is from the other kind of passkey screen"
                Verdict.CHALLENGE_DIFFERS -> "the answer names a different request"
                Verdict.NO_KEY_ID -> "the answer named no passkey"
                Verdict.MATCHES -> null
            },
            providerType = null
        )
    }

    private fun check(
        responseJson: String?,
        requestJson: String?,
        expectedType: String,
        requireKeyId: Boolean
    ): Verdict {
        val response = parseObject(responseJson) ?: return Verdict.UNREADABLE
        if (requireKeyId && PasskeyWire.credentialIdOf(responseJson).isNullOrBlank()) {
            return Verdict.NO_KEY_ID
        }
        val clientData = response.getAsJsonObject("response")
            ?.takeIf { it.isJsonObject }
            ?.let(::clientDataJsonOf)
        // No readable client data at all: the whole point of the response is missing, so this app
        // would be storing an envelope it cannot describe. Refuse rather than index a mystery.
        if (clientData == null) return Verdict.UNREADABLE
        val type = clientData.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        if (type != null && !type.equals(expectedType, ignoreCase = true)) {
            return Verdict.WRONG_CEREMONY
        }
        val asked = requestJson?.let(::challengeOf)
        val answered = clientData.get("challenge")?.takeIf { it.isJsonPrimitive }?.asString
        if (asked != null && answered != null &&
            PasskeyWire.normalizeBase64Url(asked) != PasskeyWire.normalizeBase64Url(answered)
        ) {
            return Verdict.CHALLENGE_DIFFERS
        }
        return Verdict.MATCHES
    }

    private fun parseObject(text: String?): com.google.gson.JsonObject? =
        text?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { com.google.gson.JsonParser.parseString(raw) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
        }

    private fun clientDataJsonOf(response: com.google.gson.JsonObject): com.google.gson.JsonObject? {
        val encoded = response.get("clientDataJSON")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return null
        val decoded = PasskeyWire.decodeBase64UrlToString(encoded) ?: return null
        return parseObject(decoded)
    }

    /** The challenge this app put into its own request document, wrapped or not. */
    private fun challengeOf(requestJson: String): String? =
        parseObject(requestJson)?.let { root ->
            val options = root.getAsJsonObject(PasskeyRequestBuilder.PUBLIC_KEY_WRAPPER_FIELD) ?: root
            options.get("challenge")?.takeIf { it.isJsonPrimitive }?.asString
        }
}
