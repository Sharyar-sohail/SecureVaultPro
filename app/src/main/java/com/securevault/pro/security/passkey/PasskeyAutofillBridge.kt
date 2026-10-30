package com.securevault.pro.security.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one surface [com.securevault.pro.autofill.VaultAutofillService] is meant to touch.
 *
 * Small on purpose: two reads, one hand-off, and one ceremony runner. Everything else in this package
 * stays private to the feature, so wiring passkeys into autofill is a few lines in a file owned by the
 * manager rather than a redesign of it.
 *
 * What this can honestly offer an autofill session, and what it cannot:
 *
 *  - **Can**: [passkeyCandidatesFor] — which relying parties this device holds a passkey for, from
 *    the app's own sealed records. That is a listing, and it needs no credential provider.
 *  - **Can**: [beginAuthentication] — an `IntentSender` that opens Secure Wallet Pro, where the owner
 *    can run the ceremony. A passkey sheet needs an `Activity`
 *    ([PasskeyCredentialTransport.enrol] documents why), and an `AutofillService` has none, so the
 *    only honest hand-off is into the app. This is the same mechanism the service already uses for a
 *    locked vault in `VaultAutofillService.buildUnlockResponse`.
 *  - **Cannot**: put a passkey *inside* a `Dataset`, which is what "the browser fills your passkey
 *    from Secure Wallet Pro" would require. That is an
 *    `androidx.credentials.provider.PublicKeyCredentialEntry` plus a `PendingIntent` per entry, and it
 *    is the contract of an
 *    `androidx.credentials.provider.CredentialProviderService` — a service this app does not declare,
 *    and cannot declare from inside my ownership set (`AndroidManifest.xml` is off limits). Adding the
 *    manifest entry is the manager's call; see the handoff note. Until then this class does not
 *    pretend, and it does not build a `Dataset` that the framework would ignore.
 */
@Singleton
class PasskeyAutofillBridge @Inject constructor(
    private val service: PasskeyService
) {

    /** Which passkeys this device could offer for a host, best match first. */
    suspend fun passkeyCandidatesFor(domain: String): List<PasskeyCandidate> =
        service.candidatesFor(domain)

    /**
     * Candidates for an autofill request, trying each host the request named.
     *
     * `VaultAutofillService` already collects `hints` (web domain, package, app label); those are
     * tried in order and the first host with a match wins, so a `com.example.app` package with a
     * `example.com` web domain finds the credential enrolled against either.
     */
    suspend fun passkeyCandidatesFor(
        webDomain: String?,
        packageName: String? = null,
        hints: List<String> = emptyList()
    ): List<PasskeyCandidate> {
        val hosts = buildList {
            webDomain?.takeIf { it.isNotBlank() }?.let { add(it) }
            hints.filter { it.isNotBlank() }.forEach { add(it) }
            packageName?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.map { PasskeyDomainRules.normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
        for (host in hosts) {
            val found = service.candidatesFor(host)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * An `IntentSender` that takes the owner into Secure Wallet Pro to complete a passkey sign-in for
     * [domain]. Null when the app cannot build one (no Activity to open, or a broken package state) —
     * a caller must treat null as "offer nothing", not as "offer a password".
     *
     * [EXTRA_PASSKEY_DOMAIN] is set on the intent. Nothing reads it yet: the app's own navigation is
     * in `presentation/navigation/`, which this feature does not edit, so today it lands on the normal
     * start screen. The exact line that would honour it is in `agent_notes/passkeys.md`.
     */
    fun beginAuthentication(context: Context, domain: String): android.content.IntentSender? {
        val normalized = PasskeyDomainRules.normalize(domain)
        if (normalized.isBlank()) return null
        return runCatching {
            val intent = Intent(context, com.securevault.pro.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_PASSKEY_DOMAIN, normalized)
                putExtra(EXTRA_PASSKEY_REQUEST, PASSKEY_REQUEST_SIGN_IN)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            PendingIntent.getActivity(context, REQUEST_CODE, intent, flags).intentSender
        }.getOrNull()
    }

    /**
     * Runs the ceremony where it can actually run — an `Activity`. This is what the passkey screen and
     * any future in-app caller use; an autofill service reaches it through [beginAuthentication].
     */
    suspend fun completeAuthentication(
        activity: android.app.Activity,
        domain: String,
        credentialId: String? = null
    ): PasskeySignInResult = service.authenticate(activity, domain, credentialId)

    /** Enrols from an Activity, for a caller that has one and wants the whole flow. */
    suspend fun completeEnrolment(
        activity: android.app.Activity,
        rpDomain: String,
        rpName: String = "",
        userName: String = ""
    ): PasskeyEnrolmentResult = service.enrol(activity, rpDomain, rpName, userName)

    /** Whether a passkey could work here at all, so a caller can skip the whole idea quietly. */
    fun passkeyAvailability(): PasskeyAvailability = service.availability()

    /** True only when a ceremony is both possible on this Android version and allowed by the session. */
    fun canOfferPasskeys(): Boolean = service.availability().canRunCeremony

    /** The system's "passkeys for this app" screen, for a dataset that says "manage these". */
    fun managePasskeysIntentSender(context: Context): android.content.IntentSender? =
        runCatching { service.settingsPendingIntent()?.intentSender }.getOrNull()

    /**
     * A record's age for a sort or a "used 3 days ago" line. Public so a caller does not re-derive it
     * from `System.currentTimeMillis()` in three places.
     */
    fun daysSince(millis: Long, now: Long = System.currentTimeMillis()): Long =
        TimeUnit.MILLISECONDS.toDays((now - millis).coerceAtLeast(0L))

    companion object {
        /** The only extra this bridge writes; a future deep link may read it. */
        const val EXTRA_PASSKEY_DOMAIN = "com.securevault.pro.extra.PASSKEY_DOMAIN"
        const val EXTRA_PASSKEY_REQUEST = "com.securevault.pro.extra.PASSKEY_REQUEST"
        const val PASSKEY_REQUEST_SIGN_IN = "sign_in"

        private const val REQUEST_CODE = 7312
    }
}
