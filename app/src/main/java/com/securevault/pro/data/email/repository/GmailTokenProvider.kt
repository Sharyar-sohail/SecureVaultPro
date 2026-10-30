package com.securevault.pro.data.email.repository

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google sign-in for a mailbox, on the same account the vault already uses for Drive backups.
 *
 * Two rules taken from `GoogleDriveService`, which is the working Google credential in this app and
 * the reason this class exists instead of another ad-hoc sign-in:
 *
 * 1. **No `requestIdToken` on this builder.** The Drive service learned the hard way that putting the
 *    web client id on a scope builder turns the picker into an instant "sign-in was cancelled"
 *    (12501) on a handset whose certificate is not registered for that client. Mail only needs the
 *    account, its address and the mail scope, so that is all it asks for.
 * 2. **Only code 12501 may be reported as a cancellation.** Every other `ApiException` is Google
 *    refusing *this app*, and calling that "you cancelled" sends the owner to fix the wrong thing.
 *
 * The access token is minted per connection through `GoogleAccountCredential.getToken()` — the same
 * credential object the Drive client builds — and is **never stored**: not in the `mail_accounts`
 * row, not in a preference, not in a log. A Gmail account's sealed credential column stays empty
 * precisely because a short-lived OAuth token at rest would be a worse secret than a password.
 */
@Singleton
class GmailTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** Gmail's IMAP/SMTP scope; XOAUTH2 will not work with the profile scope alone. */
        const val MAIL_SCOPE = "https://mail.google.com/"

        /** Google's own "the user walked away" code — the only one allowed to read as a cancellation. */
        private const val CODE_CANCELED = 12501
    }

    private val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(MAIL_SCOPE))
        .build()

    private fun client() = GoogleSignIn.getClient(context, signInOptions)

    /** The picker intent the add-account screen launches. */
    fun signInIntent(): Intent = client().signInIntent

    /** Null means Play Services on this handset can serve a sign-in; a code means it cannot. */
    fun availabilityFault(): Int? =
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }
            .getOrNull()?.takeIf { it != ConnectionResult.SUCCESS }

    /** The address already signed in on this device, so Gmail one-tap can skip the picker. */
    fun lastSignedInEmail(): String? =
        runCatching { GoogleSignIn.getLastSignedInAccount(context)?.email?.trim() }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** True when the signed-in account already granted the mail scope, i.e. no consent screen. */
    fun hasMailAccess(email: String? = null): Boolean {
        val account = lastSignedInAccount(email) ?: return false
        return runCatching { GoogleSignIn.hasPermissions(account, Scope(MAIL_SCOPE)) }.getOrDefault(false)
    }

    fun lastSignedInAccount(email: String? = null): GoogleSignInAccount? {
        val signedIn = runCatching { GoogleSignIn.getLastSignedInAccount(context) }.getOrNull() ?: return null
        val address = signedIn.email?.trim().orEmpty()
        if (address.isBlank()) return null
        if (email.isNullOrBlank() || email.equals(address, ignoreCase = true)) return signedIn
        return null
    }

    /** A readable sentence for a Play Services code, so "Google is missing" is never "you cancelled". */
    fun availabilityMessage(code: Int): String = when (code) {
        ConnectionResult.SERVICE_MISSING ->
            "This device has no Google Play services, so Gmail cannot sign in. Use the manual setup " +
                "with an app password instead."
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
            "Google Play services needs an update before Gmail can sign in."
        ConnectionResult.SERVICE_DISABLED ->
            "Google Play services is turned off on this device, so Gmail cannot sign in."
        ConnectionResult.SERVICE_INVALID ->
            "Google Play services on this device is not valid, so Gmail cannot sign in."
        else -> "Google sign-in is unavailable on this device right now."
    }

    /**
     * Turn a picker result into a signed-in address, or a sentence.
     *
     * The exact idiom `GoogleDriveService.completeSignIn` uses — the same await on the intent task,
     * the same `hasPermissions` check, the same rule that only code 12501 is a cancellation — so this
     * screen cannot be the place where a working Google sign-in is reimplemented wrong.
     */
    suspend fun completeSignIn(data: Intent?): MailOutcome<String> = withContext(Dispatchers.IO) {
        // A picker the owner backed out of hands back no Intent at all. Reading that as Google's
        // own 12501 cancellation — rather than handing `null` to a Play Services API and reporting
        // whatever it throws — is the only way "cancelled" is guaranteed to be what the screen says.
        if (data == null) {
            return@withContext MailOutcome.problem(
                "Gmail sign-in was cancelled before it finished.",
                MailFailureKind.CANCELLED
            )
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).await()
                ?: return@withContext MailOutcome.problem(
                    "Google returned no account. Try the sign-in again, or use manual setup.",
                    MailFailureKind.AUTH
                )
            if (!GoogleSignIn.hasPermissions(account, Scope(MAIL_SCOPE))) {
                return@withContext MailOutcome.problem(
                    "Gmail access was not granted, so the mailbox cannot be opened. Allow mail " +
                        "access and sign in again.",
                    MailFailureKind.AUTH
                )
            }
            val email = account.email?.trim().orEmpty()
            if (email.isBlank()) {
                MailOutcome.problem(
                    "The Google account did not give an email address, so there is no mailbox to open.",
                    MailFailureKind.AUTH
                )
            } else {
                MailOutcome.ready(email)
            }
        } catch (e: ApiException) {
            if (e.statusCode == CODE_CANCELED) {
                MailOutcome.problem(
                    "Gmail sign-in was cancelled before it finished.",
                    MailFailureKind.CANCELLED
                )
            } else {
                MailOutcome.problem(
                    "Google refused the sign-in${e.message.orEmpty().take(120).let { if (it.isBlank()) "" else ": $it" }}. " +
                        "If this keeps happening, use manual setup with an app password.",
                    MailFailureKind.AUTH
                )
            }
        } catch (e: Throwable) {
            MailOutcome.problem(MailFailures.describe(e, MailStep.TOKEN), MailFailures.kindOf(e))
        }
    }

    /**
     * A fresh XOAUTH2 token for [email], or a sentence explaining why there is none.
     *
     * `getToken()` is a blocking network call wrapped by Google's own AccountManager-backed
     * credential; [MailStep.TOKEN]'s budget bounds it and the caller supplies the IO dispatcher. The
     * token is returned for one connection and dropped afterwards — nothing here caches it, because a
     * stored bearer token for a mailbox is exactly the kind of secret this vault keeps out of files.
     */
    fun accessToken(email: String): MailOutcome<String> {
        val address = email.trim()
        if (address.isBlank()) {
            return MailOutcome.problem(
                "There is no Google address on this account, so it cannot sign in.",
                MailFailureKind.CONFIG
            )
        }
        availabilityFault()?.let { return MailOutcome.problem(availabilityMessage(it), MailFailureKind.NETWORK) }
        return try {
            val credential = GoogleAccountCredential
                .usingOAuth2(context, listOf(MAIL_SCOPE))
                .apply { selectedAccount = Account(address, "com.google") }
            val token = credential.token
            if (token.isNullOrBlank()) {
                MailOutcome.problem(
                    "Google did not give a mail token for $address. Sign out of Gmail in this app " +
                        "and sign in again.",
                    MailFailureKind.AUTH
                )
            } else {
                MailOutcome.ready(token)
            }
        } catch (e: Throwable) {
            // GoogleAuthException (io error, missing permission, unresolved account) all mean the
            // same thing to the owner: this account cannot serve mail right now.
            MailOutcome.problem(
                "Google could not sign this account in for mail: " +
                    MailFailures.describe(e, MailStep.TOKEN).take(200),
                MailFailureKind.AUTH
            )
        }
    }

    /** Signed out of the mail scopes only; the Drive sign-in and the vault's sync are untouched. */
    suspend fun signOut() {
        runCatching { client().signOut().await() }
        runCatching { client().revokeAccess().await() }
    }
}
