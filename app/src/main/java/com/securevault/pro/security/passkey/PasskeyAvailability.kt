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

    /** The sentence an owner should see, without pretending a fix happened. */
    fun ownerMessage(failure: PasskeyFailure): String = when (failure.kind) {
        PasskeyFailureKind.CANCELLED ->
            "Cancelled. Nothing was saved and nothing was sent to the site."
        PasskeyFailureKind.TIMED_OUT ->
            "That took too long, so it was stopped. Your passkey is unchanged — start it again."
        PasskeyFailureKind.NO_PASSKEY ->
            "This device has no passkey for that site."
        PasskeyFailureKind.TRANSPORT_UNAVAILABLE ->
            "This Android version has no passkey transport for this app. Passkeys need Android 14 " +
                "or newer on this build."
        PasskeyFailureKind.UNSUPPORTED ->
            "The credential provider on this device does not handle passkeys for this app."
        PasskeyFailureKind.INVALID_REQUEST ->
            "The site's passkey request was not accepted" +
                (failure.providerMessage?.let { ": ${it.take(90)}" } ?: ".")
        PasskeyFailureKind.UNKNOWN ->
            "The credential provider did not complete the request" +
                (failure.providerMessage?.let { ": ${it.take(90)}" } ?: ".")
    }
}
