package com.securevault.pro.security.passkey

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything this app knows about the credential provider, in one file, with four calls in it.
 *
 * Written against the **pinned artifact**, not against a blog post. The names below were read out of
 * `~/.gradle/caches/.../androidx.credentials/credentials/1.2.0-rc01/…/credentials-1.2.0-rc01.aar`
 * with `javap`, and two of the names the wider literature uses for this job —
 * `RegisterPasskeyCredentialOption`, `GetPasskeyCredentialOption`, `UncategorizedRequest` — **do not
 * exist in 1.2.0-rc01**; `CreatePublicKeyCredentialRequest` + `GetPublicKeyCredentialOption` are what
 * this version actually offers. Same for the response side: `PublicKeyCredential
 * .authenticationResponseJson` and `CreatePublicKeyCredentialResponse.registrationResponseJson`.
 *
 * The private key is never here. `createCredential` returns the registration response (public
 * material) and `getCredential` returns an assertion the site verifies; the credential's private key
 * is created and kept inside the provider and does not leave it, which is the whole point of FIDO2.
 */
@Singleton
class PasskeyCredentialTransport @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    private val manager: CredentialManager by lazy { CredentialManager.create(appContext) }

    /**
     * The transport verdict, without asking the user for anything.
     *
     * [PasskeyCredentialTransport] cannot know whether the owner has a Google account signed in or
     * whether "Google Passkeys" is switched on in their settings — those are only observable by
     * starting a ceremony and reading what comes back. So this is the *possible* answer, and
     * [enrol]/[authenticate] turn a real refusal into the same verdict after the fact.
     */
    fun transportState(): PasskeyTransportState = runCatching {
        val sdk = Build.VERSION.SDK_INT
        when {
            sdk >= PasskeyAvailabilityJudge.MIN_SDK_FOR_FRAMEWORK_CREDENTIAL_STORE ->
                PasskeyTransportState.READY
            sdk == PasskeyAvailabilityJudge.PRE_FRAMEWORK_OEM_PROVIDER_SDK -> {
                if (hasRegisteredProviderService()) PasskeyTransportState.READY
                else PasskeyTransportState.PROVIDER_NOT_REGISTERED
            }
            else -> PasskeyTransportState.ANDROID_TOO_OLD
        }
    }.getOrDefault(PasskeyTransportState.UNKNOWN)

    /** True when this app declares a credential-provider service — it does not, and cannot today. */
    private fun hasRegisteredProviderService(): Boolean = runCatching {
        val info = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
        )
        info.services?.any { service ->
            service.metaData?.keySet()?.any { key ->
                key.contains("CREDENTIAL_PROVIDER", ignoreCase = true)
            } == true
        } == true
    }.getOrDefault(false)

    /**
     * Enrols a passkey for [requestJson] and hands back the registration response.
     *
     * [context] must be an `Activity`: the framework transport starts a UI sheet and the call fails
     * with an `IllegalArgumentException` from a non-activity context, which is why this takes a
     * context per call instead of using the injected application context.
     */
    suspend fun enrol(context: Context, requestJson: String): PasskeyRegisterOutcome {
        if (context !is Activity) {
            return PasskeyRegisterOutcome.Failed(
                PasskeyFailure(
                    kind = PasskeyFailureKind.TRANSPORT_UNAVAILABLE,
                    providerMessage = "a passkey sheet needs an Activity context",
                    providerType = null
                )
            )
        }
        val request = CreatePublicKeyCredentialRequest(requestJson)
        return try {
            val response = manager.createCredential(context, request)
            if (response !is CreatePublicKeyCredentialResponse) {
                return PasskeyRegisterOutcome.Failed(
                    PasskeyFailure(PasskeyFailureKind.UNKNOWN, null, response.type)
                )
            }
            val registrationJson = response.registrationResponseJson
            if (registrationJson.isNullOrBlank()) {
                PasskeyRegisterOutcome.Failed(
                    PasskeyFailure(
                        kind = PasskeyFailureKind.NO_PASSKEY,
                        providerMessage = "the provider returned an empty registration response",
                        providerType = response.type
                    )
                )
            } else {
                PasskeyRegisterOutcome.Enrolled(
                    registrationResponseJson = registrationJson,
                    credentialId = PasskeyWire.credentialIdOf(registrationJson).orEmpty()
                )
            }
        } catch (e: CreateCredentialException) {
            PasskeyRegisterOutcome.from(failureOf(e))
        } catch (e: IllegalArgumentException) {
            PasskeyRegisterOutcome.Failed(
                PasskeyFailure(PasskeyFailureKind.INVALID_REQUEST, e.message, null)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PasskeyRegisterOutcome.Failed(
                PasskeyFailure(PasskeyFailureKind.UNKNOWN, e.message, e.javaClass.simpleName)
            )
        }
    }

    /**
     * Asks the provider to sign [requestJson]'s challenge. The assertion comes back; the key does not.
     */
    suspend fun authenticate(context: Context, requestJson: String): PasskeyAuthOutcome {
        if (context !is Activity) {
            return PasskeyAuthOutcome.Failed(
                PasskeyFailure(
                    kind = PasskeyFailureKind.TRANSPORT_UNAVAILABLE,
                    providerMessage = "a passkey sheet needs an Activity context",
                    providerType = null
                )
            )
        }
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        return try {
            val response = manager.getCredential(context, request)
            val credential = response.credential as? PublicKeyCredential
            val assertion = credential?.authenticationResponseJson
            when {
                assertion.isNullOrBlank() -> PasskeyAuthOutcome.Failed(
                    PasskeyFailure(
                        kind = PasskeyFailureKind.NO_PASSKEY,
                        providerMessage = "the provider returned no assertion",
                        providerType = response.credential.type
                    )
                )
                else -> PasskeyAuthOutcome.Authenticated(
                    assertionResponseJson = assertion,
                    credentialId = PasskeyWire.credentialIdOf(assertion).orEmpty()
                )
            }
        } catch (e: GetCredentialException) {
            PasskeyAuthOutcome.from(failureOf(e))
        } catch (e: IllegalArgumentException) {
            PasskeyAuthOutcome.Failed(
                PasskeyFailure(PasskeyFailureKind.INVALID_REQUEST, e.message, null)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PasskeyAuthOutcome.Failed(
                PasskeyFailure(PasskeyFailureKind.UNKNOWN, e.message, e.javaClass.simpleName)
            )
        }
    }

    /**
     * The system's own passkey management screen, scoped to this app.
     *
     * This is the only honest way to delete a passkey *from the device*: the credential lives in the
     * provider's store, not ours, and `androidx.credentials` 1.2.0-rc01 gives a client app no
     * per-credential delete call at all — `ClearCredentialStateRequest` is the only removal API and it
     * is a blanket reset of the provider's state for this app, so nothing in this feature calls it.
     * The owner deletes the key here, and this app forgets its own record separately.
     */
    fun settingsPendingIntent(): PendingIntent? =
        runCatching { manager.createSettingsPendingIntent() }.getOrNull()

    /** A PendingIntent into the same settings screen, for a caller that needs a request code. */
    fun settingsRequestCode(): Int = SETTINGS_REQUEST_CODE

    /** Shared translation so a refusal means the same thing everywhere in the feature. */
    private fun failureOf(error: Exception): PasskeyFailure {
        val type = when (error) {
            is CreateCredentialException -> error.type
            is GetCredentialException -> error.type
            else -> null
        }
        val classified = PasskeyFailureClassifier.classify(
            exceptionClassName = error.javaClass.name,
            typeField = type,
            message = error.message
        )
        Log.d(TAG, "passkey ceremony refused: ${classified.kind}")
        return classified
    }

    private companion object {
        const val TAG = "PasskeyTransport"
        const val SETTINGS_REQUEST_CODE = 7311
    }
}

/** The result of an enrolment ceremony. Nothing is persisted unless it is [Enrolled]. */
sealed class PasskeyRegisterOutcome {
    data class Enrolled(val registrationResponseJson: String, val credentialId: String) :
        PasskeyRegisterOutcome()

    data class Failed(val failure: PasskeyFailure) : PasskeyRegisterOutcome()

    object Cancelled : PasskeyRegisterOutcome()

    object NoTransport : PasskeyRegisterOutcome()

    companion object {
        fun from(failure: PasskeyFailure): PasskeyRegisterOutcome = when (failure.kind) {
            PasskeyFailureKind.CANCELLED -> Cancelled
            PasskeyFailureKind.TRANSPORT_UNAVAILABLE, PasskeyFailureKind.UNSUPPORTED -> NoTransport
            else -> Failed(failure)
        }
    }
}

/** The result of a sign-in ceremony. */
sealed class PasskeyAuthOutcome {
    data class Authenticated(val assertionResponseJson: String, val credentialId: String) :
        PasskeyAuthOutcome()

    data class Failed(val failure: PasskeyFailure) : PasskeyAuthOutcome()

    object NoPasskey : PasskeyAuthOutcome()

    object Cancelled : PasskeyAuthOutcome()

    object NoTransport : PasskeyAuthOutcome()

    companion object {
        fun from(failure: PasskeyFailure): PasskeyAuthOutcome = when (failure.kind) {
            PasskeyFailureKind.CANCELLED -> Cancelled
            PasskeyFailureKind.NO_PASSKEY -> NoPasskey
            PasskeyFailureKind.TRANSPORT_UNAVAILABLE, PasskeyFailureKind.UNSUPPORTED -> NoTransport
            else -> Failed(failure)
        }
    }
}
