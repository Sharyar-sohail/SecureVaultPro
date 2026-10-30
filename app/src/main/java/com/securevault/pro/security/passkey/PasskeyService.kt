package com.securevault.pro.security.passkey

import android.app.PendingIntent
import android.content.Context
import com.securevault.pro.security.CryptoManager
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The passkey feature's service: enrol, authenticate, list, and forget on the owner's word.
 *
 * It is deliberately the only class the UI talks to, and it is deliberately thin: the decisions are
 * in the pure parts ([PasskeyDomainRules], [PasskeyAvailabilityJudge], [PasskeyRequestBuilder],
 * [PasskeyVault]) which are unit-tested, and the only Android-shaped work is one call to
 * [PasskeyCredentialTransport] and one write to [PasskeyStore].
 *
 * The honesty the feature depends on: a ceremony here *cannot* complete a login to a real site from
 * this app alone. A relying party issues the challenge and verifies the assertion against its own
 * stored public key; there is no server in this project, so what `authenticate` proves on a real
 * device is that the provider will produce a signed assertion for the enrolled `rp.id`. That is the
 * transport working end to end, and it is what `agent_notes/passkeys.md` says is only checkable on
 * the owner's phone.
 */
@Singleton
class PasskeyService @Inject constructor(
    private val store: PasskeyStore,
    private val transport: PasskeyCredentialTransport,
    private val sessionManager: SessionManager,
    private val crypto: CryptoManager
) {

    /** The verdict for right now: transport, session, and whether this device's key opens at all. */
    fun availability(): PasskeyAvailability = PasskeyAvailabilityJudge.verdict(
        sdkInt = currentSdk(),
        providerRegisteredInManifest = false,
        sessionIsPrimary = isPrimarySession(),
        vaultKeyOpens = runCatching { crypto.healthCheck() }.getOrDefault(true)
    )

    /**
     * A device-only override for [availability]'s manifest question. The app declares no
     * credential-provider service, so on API 33 there is nothing to find; kept as a parameter so a
     * future manifest entry cannot silently make the screen lie.
     */
    fun availabilityFor(sdkInt: Int, providerRegisteredInManifest: Boolean): PasskeyAvailability =
        PasskeyAvailabilityJudge.verdict(
            sdkInt = sdkInt,
            providerRegisteredInManifest = providerRegisteredInManifest,
            sessionIsPrimary = isPrimarySession(),
            vaultKeyOpens = true
        )

    /** Every record this device holds. Empty in a locked or decoy session, like the rest of the vault. */
    suspend fun records(): List<PasskeyRecord> = withContext(Dispatchers.IO) {
        if (!isPrimarySession()) return@withContext emptyList()
        store.records()
    }

    /**
     * Live list for the screen. The session gate is inside the flow, so locking the vault or dropping
     * into the decoy session empties the list without the screen having to remember to.
     */
    fun recordsFlow(): Flow<List<PasskeyRecord>> = combine(store.recordsFlow, sessionManager.mode) { raw, mode ->
        if (mode == SessionMode.REAL && !sessionManager.isDecoy()) raw else emptyList()
    }

    /** Records enrolled against exactly this host. */
    suspend fun recordsFor(domain: String): List<PasskeyRecord> =
        records().filter { it.rpDomain == PasskeyDomainRules.normalize(domain) }

    /** Ranked candidates for a requested host — the autofill and sign-in lookup. */
    suspend fun candidatesFor(domain: String, limit: Int = PasskeyDomainRules.DEFAULT_LIMIT)
    : List<PasskeyCandidate> = withContext(Dispatchers.IO) {
        PasskeyDomainRules.candidatesFor(records(), domain, limit)
    }

    /** Grouped by relying party, for the list screen. */
    suspend fun groupedByDomain(): List<PasskeyDomainGroup> =
        PasskeyDomainRules.groupByDomain(records())

    /**
     * Enrols a passkey for [rpDomain] and stores the sealed record.
     *
     * [context] must be an Activity. [userName] is what the RP will name the account with — the
     * screen passes the account the owner is signing up for, and a blank one becomes the domain,
     * which is what a passkey with no account hint looks like in the list.
     */
    suspend fun enrol(
        context: Context,
        rpDomain: String,
        rpName: String = "",
        userName: String = ""
    ): PasskeyEnrolmentResult {
        val availability = availability()
        if (!availability.canEnrol) {
            return PasskeyEnrolmentResult.Refused(availability)
        }
        val domain = PasskeyDomainRules.normalize(rpDomain)
        if (!PasskeyDomainRules.isRegistrable(domain)) {
            return PasskeyEnrolmentResult.Failed(
                PasskeyFailure(
                    kind = PasskeyFailureKind.INVALID_REQUEST,
                    providerMessage = "not a registrable domain",
                    providerType = null
                )
            )
        }
        val existing = store.records().filter { it.rpDomain == domain }
        val account = PasskeyRequestBuilder.accountFor(userName, domain)
        val requestJson = PasskeyRequestBuilder.registrationJson(
            rpDomain = domain,
            rpName = rpName.ifBlank { account.rpDomain },
            userName = account.userName,
            userDisplayName = account.userName,
            userHandle = PasskeyWire.decodeBase64UrlToBytes(account.userHandle)
                ?: PasskeyRequestBuilder.newChallenge(),
            excludeCredentialIds = existing.map { it.credentialId }
        )
        val outcome = withContext(Dispatchers.Main) {
            withTimeoutOrNull(ENROLMENT_WATCHDOG_MS) { transport.enrol(context, requestJson) }
        } ?: PasskeyRegisterOutcome.Failed(
            PasskeyFailure(PasskeyFailureKind.TIMED_OUT, "the sheet stayed open too long", null)
        )
        return when (outcome) {
            is PasskeyRegisterOutcome.Enrolled -> {
                val enrolment = PasskeyEnrolment(
                    rpDomain = domain,
                    rpName = rpName.ifBlank { domain },
                    userName = account.userName,
                    userDisplayName = account.userName,
                    credentialId = outcome.credentialId,
                    registrationResponseJson = outcome.registrationResponseJson,
                    userHandle = account.userHandle
                )
                val sealed = store.vault.recordFor(enrolment)
                val record = sealed.record
                if (!sealed.ok || record == null) {
                    PasskeyEnrolmentResult.NotStored(sealed.message.orEmpty())
                } else if (store.put(record)) {
                    PasskeyEnrolmentResult.Enrolled(record)
                } else {
                    // The key was created on the device and we cannot keep our copy of its receipt.
                    // Say so, and say what it costs: the passkey still works, the list just misses it.
                    PasskeyEnrolmentResult.StoredElsewhere(record)
                }
            }
            PasskeyRegisterOutcome.Cancelled -> PasskeyEnrolmentResult.Cancelled
            PasskeyRegisterOutcome.NoTransport ->
                PasskeyEnrolmentResult.Refused(availabilityAfterRefusal())
            is PasskeyRegisterOutcome.Failed -> PasskeyEnrolmentResult.Failed(outcome.failure)
            else -> PasskeyEnrolmentResult.Failed(
                PasskeyFailure(PasskeyFailureKind.UNKNOWN, null, null)
            )
        }
    }

    /**
     * Runs a sign-in ceremony for [domain], optionally narrowed to one credential id.
     *
     * On success the record's use count and date move — and nothing else. The assertion itself is
     * returned to the caller and dropped: it is a one-time answer to a one-time challenge, so storing
     * it would be storing something that can never be used again.
     */
    suspend fun authenticate(
        context: Context,
        domain: String,
        credentialId: String? = null
    ): PasskeySignInResult {
        val availability = availability()
        if (!availability.canAuthenticate) return PasskeySignInResult.Refused(availability)
        val normalized = PasskeyDomainRules.normalize(domain)
        val candidates = if (credentialId.isNullOrBlank()) {
            store.records().filter { it.rpDomain == normalized }
        } else {
            store.records().filter { it.credentialId == PasskeyWire.normalizeBase64Url(credentialId) }
        }
        if (candidates.isEmpty()) {
            return PasskeySignInResult.NoPasskeyForDomain(normalized)
        }
        val requestJson = PasskeyRequestBuilder.authenticationJson(
            rpDomain = normalized,
            allowCredentialIds = candidates.map { it.credentialId }
        )
        val outcome = withContext(Dispatchers.Main) {
            withTimeoutOrNull(SIGN_IN_WATCHDOG_MS) { transport.authenticate(context, requestJson) }
        } ?: PasskeyAuthOutcome.Failed(
            PasskeyFailure(PasskeyFailureKind.TIMED_OUT, "the sheet stayed open too long", null)
        )
        return when (outcome) {
            is PasskeyAuthOutcome.Authenticated -> {
                val matched = candidates.firstOrNull {
                    it.credentialId == outcome.credentialId
                } ?: candidates.firstOrNull()
                if (matched == null) {
                    PasskeySignInResult.SignedIn(outcome.assertionResponseJson, null)
                } else {
                    val updated = store.vault.markUsed(matched, System.currentTimeMillis())
                    store.put(updated)
                    PasskeySignInResult.SignedIn(outcome.assertionResponseJson, updated)
                }
            }
            PasskeyAuthOutcome.NoPasskey -> PasskeySignInResult.NoPasskeyOnDevice
            PasskeyAuthOutcome.Cancelled -> PasskeySignInResult.Cancelled
            PasskeyAuthOutcome.NoTransport ->
                PasskeySignInResult.Refused(availabilityAfterRefusal())
            is PasskeyAuthOutcome.Failed -> PasskeySignInResult.Failed(outcome.failure)
            else -> PasskeySignInResult.Failed(
                PasskeyFailure(PasskeyFailureKind.UNKNOWN, null, null)
            )
        }
    }

    /**
     * Removes this app's record of one passkey. Only ever called from a confirmed user action, and
     * the confirmation is a parameter, not a suggestion: without it this returns false and writes
     * nothing. There is no path in this class that removes more than the id it was handed.
     */
    suspend fun forgetRecord(id: String, confirmedByOwner: Boolean): Boolean {
        if (!confirmedByOwner) return false
        if (!isPrimarySession()) return false
        return store.removeRecord(id.trim())
    }

    /** The system's passkey settings, scoped to this app — where an owner deletes an actual key. */
    fun settingsPendingIntent(): PendingIntent? = transport.settingsPendingIntent()

    /** The request document a caller would send for [domain], for a preview or a hand-off. */
    suspend fun authenticationRequestFor(domain: String): String? {
        val normalized = PasskeyDomainRules.normalize(domain)
        val candidates = store.records().filter { it.rpDomain == normalized }
        if (candidates.isEmpty()) return null
        return PasskeyRequestBuilder.authenticationJson(
            rpDomain = normalized,
            allowCredentialIds = candidates.map { it.credentialId }
        )
    }

    /** A refusal from the provider is stronger evidence than our own SDK guess. */
    private fun availabilityAfterRefusal(): PasskeyAvailability = availability().copy(
        transport = PasskeyTransportState.PROVIDER_NOT_REGISTERED
    )

    private fun isPrimarySession(): Boolean =
        sessionManager.mode.value == SessionMode.REAL

    private fun currentSdk(): Int = android.os.Build.VERSION.SDK_INT

    companion object {
        /**
         * Client-side watchdogs. Each is the request's own `timeout` plus a grace period for the
         * provider to dismiss its sheet, so this app never leaves a ceremony open that the site has
         * already given up on — and never hangs a screen waiting for one.
         */
        val ENROLMENT_WATCHDOG_MS: Long = PasskeyRequestBuilder.ENROLMENT_TIMEOUT_MS + 15_000L
        val SIGN_IN_WATCHDOG_MS: Long = PasskeyRequestBuilder.AUTHENTICATION_TIMEOUT_MS + 15_000L
    }
}

/** What an enrolment did. Only [Enrolled] means this app holds a record. */
sealed class PasskeyEnrolmentResult {
    data class Enrolled(val record: PasskeyRecord) : PasskeyEnrolmentResult()

    /**
     * The provider created the key but this app could not persist its copy of the receipt. The
     * passkey works on the device; the list is simply missing it. Named so the screen can say that
     * exactly rather than reporting a failure that did not happen.
     */
    data class StoredElsewhere(val record: PasskeyRecord) : PasskeyEnrolmentResult()

    data class Refused(val availability: PasskeyAvailability) : PasskeyEnrolmentResult()

    data class Failed(val failure: PasskeyFailure) : PasskeyEnrolmentResult()

    /** The owner dismissed the sheet. Nothing was created and nothing was written. */
    object Cancelled : PasskeyEnrolmentResult()

    /** The key exists; our sealed copy could not be written. [message] is the vault's own reason. */
    data class NotStored(val message: String) : PasskeyEnrolmentResult()

    fun ownerSentence(): String = when (this) {
        is Enrolled -> "Passkey added for ${record.rpDomain}."
        is StoredElsewhere ->
            "Your device created the passkey, but this app could not save its copy of it. " +
                "It will still work for signing in."
        is NotStored -> message
        is Failed -> PasskeyFailureClassifier.ownerMessage(failure)
        is Refused -> PasskeyFailureClassifier.ownerMessage(
            PasskeyFailure(
                kind = if (availability.transport == PasskeyTransportState.READY) {
                    PasskeyFailureKind.UNKNOWN
                } else {
                    PasskeyFailureKind.TRANSPORT_UNAVAILABLE
                },
                providerMessage = availability.reasonLabel(),
                providerType = null
            )
        )
        Cancelled -> "Cancelled. Nothing was created."
    }
}

/** What a sign-in did. [SignedIn] carries the assertion; callers pass it on and drop it. */
sealed class PasskeySignInResult {
    data class SignedIn(val assertionResponseJson: String, val record: PasskeyRecord?) :
        PasskeySignInResult()

    data class Refused(val availability: PasskeyAvailability) : PasskeySignInResult()

    data class Failed(val failure: PasskeyFailure) : PasskeySignInResult()

    /** This app has no record for that site, so it asked for nothing. */
    data class NoPasskeyForDomain(val domain: String) : PasskeySignInResult()

    /** The provider was asked and answered "no key here" — the site may be enrolled elsewhere. */
    object NoPasskeyOnDevice : PasskeySignInResult()

    object Cancelled : PasskeySignInResult()

    fun ownerSentence(): String = when (this) {
        is SignedIn -> record?.let { "Signed in with your passkey for ${it.rpDomain}." }
            ?: "Signed in with a passkey."
        is NoPasskeyForDomain ->
            if (domain.isBlank()) "This app has no passkey records to sign in with."
            else "This app has no passkey for $domain. Add one first, or sign in with a password."
        NoPasskeyOnDevice ->
            "Your device has no passkey for that site."
        Cancelled -> "Cancelled."
        is Failed -> PasskeyFailureClassifier.ownerMessage(failure)
        is Refused -> PasskeyFailureClassifier.ownerMessage(
            PasskeyFailure(
                kind = PasskeyFailureKind.TRANSPORT_UNAVAILABLE,
                providerMessage = availability.reasonLabel(),
                providerType = null
            )
        )
    }
}
