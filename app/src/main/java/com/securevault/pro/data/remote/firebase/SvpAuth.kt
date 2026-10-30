package com.securevault.pro.data.remote.firebase

import android.content.Context
import com.google.firebase.auth.GoogleAuthProvider
import com.securevault.pro.diagnostics.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in person, as the backend knows them.
 *
 * [uid] is the Firebase uid and the namespace every node lives under — it is an identifier, not a
 * credential, and is safe to put in a log line. [email] is only ever the account address the app
 * already shows in Settings.
 */
data class SvpIdentity(
    val uid: String,
    val email: String,
    val displayName: String
)

/**
 * Turns the Google account the app already uses for Drive into a Firebase Auth session, so the
 * APK and the Blogger page land on the same uid with one sign-in and no second password.
 *
 * The Drive credential is reused, not replaced: [GoogleDriveService] keeps its scopes and its own
 * token path, and Drive stays the home of media and archives. The id token comes from a separate,
 * scope-free sign-in for the same account, so the realtime layer can fail without touching Drive.
 *
 * Contract for callers: every method is a `suspend` function on [Dispatchers.IO], every one of them
 * is bounded by a timeout, and none of them throws at the caller — a stalled Google or Firebase
 * answer comes back as `Result.failure` with a sentence a human can act on, so the screen that
 * called this can always offer a retry or a way out.
 */
@Singleton
class SvpAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveService: com.securevault.pro.data.remote.google.GoogleDriveService
) {

    companion object {
        /** Long enough for a cold handset on mobile data, short enough that the UI is never stuck. */
        private const val EXCHANGE_TIMEOUT_MS = 25_000L
        private const val IDENTITY_PREFS = "svp_identity_prefs"
        private const val KEY_UID = "firebase_uid"
        private const val KEY_EMAIL = "account_email"
        private const val KEY_NAME = "account_name"
    }

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
    }

    @Volatile
    private var inMemory: SvpIdentity? = null

    /**
     * The last uid this device resolved, with no network and no coroutine. Used by code that has
     * to name a storage path while sign-in is still in flight (or has failed); null until the first
     * successful exchange.
     */
    fun cachedUid(): String? = inMemory?.uid ?: prefs.getString(KEY_UID, null)?.takeIf { it.isNotBlank() }

    fun cachedIdentity(): SvpIdentity? = inMemory ?: run {
        val uid = cachedUid() ?: return null
        SvpIdentity(
            uid = uid,
            email = prefs.getString(KEY_EMAIL, null).orEmpty(),
            displayName = prefs.getString(KEY_NAME, null).orEmpty()
        )
    }

    /**
     * Resolves the Firebase uid for the Google account the app already holds, signing in only when
     * there is no live session. Safe to call from any coroutine; cheap to call again.
     */
    suspend fun identity(refresh: Boolean = false): Result<SvpIdentity> = withContext(Dispatchers.IO) {
        try {
            if (!refresh) {
                existingSession()?.let { return@withContext Result.success(it) }
            }
            exchangeGoogleToken(refresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            Result.failure(
                SvpAuthException("Google sign-in did not answer in time. Retry, or continue without web sync.")
            )
        } catch (e: Throwable) {
            Result.failure(SvpAuthException(readable(e)))
        }
    }

    /** The uid alone, or null. The form most callers want inside a `runCatching`. */
    suspend fun uid(refresh: Boolean = false): String? = identity(refresh).getOrNull()?.uid

    /** Drops the Firebase session. Drive access is deliberately left alone. */
    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // FirebaseAuth.signOut() is local and synchronous in this SDK line; there is no task
            // to await, and the Drive session is intentionally not touched.
            FirebaseBootstrap.auth(context)?.signOut()
            inMemory = null
            prefs.edit().remove(KEY_UID).remove(KEY_EMAIL).remove(KEY_NAME).apply()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            inMemory = null
            Result.failure(SvpAuthException(readable(e)))
        }
    }

    /** A session this device already has, unless the account in it is no longer the Drive account. */
    private fun existingSession(): SvpIdentity? {
        val auth = FirebaseBootstrap.auth(context) ?: return null
        val user = try {
            auth.currentUser
        } catch (error: Throwable) {
            CrashLogger.noteFault(context, "Realtime layer: the auth session could not be read", error)
            return null
        } ?: return null
        val uid = user.uid.takeIf { it.isNotBlank() } ?: return null
        val firebaseEmail = user.email?.trim().orEmpty()
        val driveEmail = runCatching { driveService.lastSignedInAccount()?.email?.trim().orEmpty() }
            .getOrDefault("")
        // A different Google account signed in since would otherwise write into the old namespace.
        if (driveEmail.isNotBlank() && firebaseEmail.isNotBlank() && driveEmail != firebaseEmail) {
            runCatching { auth.signOut() }
            return null
        }
        val identity = SvpIdentity(
            uid = uid,
            email = firebaseEmail.ifBlank { driveEmail },
            displayName = user.displayName?.trim().orEmpty().ifBlank {
                runCatching { driveService.lastSignedInAccount()?.displayName }.getOrNull().orEmpty()
            }
        )
        remember(identity)
        return identity
    }

    private suspend fun exchangeGoogleToken(force: Boolean): Result<SvpIdentity> {
        val auth = FirebaseBootstrap.auth(context)
            ?: return Result.failure(
                SvpAuthException(FirebaseBootstrap.faultReason() ?: "The realtime backend is not available on this device.")
            )
        if (force) {
            runCatching { auth.signOut() }
        }
        val account = driveService.lastSignedInAccount() ?: driveService.silentSignIn()
            ?: return Result.failure(
                SvpAuthException("Sign in to Google to connect this vault to the web companion.")
            )
        // The Drive account itself carries no id token: minting one is the identity path's job, so
        // a web-sync problem can never reach the Drive sign-in. Silent first, no second screen.
        val idToken = driveService.requestIdToken()
        if (idToken.isNullOrBlank()) {
            return Result.failure(
                SvpAuthException(
                    "Web sync needs its own Google authorisation once. Tap Connect Google Drive " +
                        "again and approve the prompt; backups are unaffected either way."
                )
            )
        }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = withTimeout(EXCHANGE_TIMEOUT_MS) { auth.signInWithCredential(credential).await() }
        val user = result.user
            ?: return Result.failure(SvpAuthException("Google sign-in returned no account."))
        val uid = user.uid.takeIf { it.isNotBlank() }
            ?: return Result.failure(SvpAuthException("Google sign-in returned no user id."))
        val identity = SvpIdentity(
            uid = uid,
            email = user.email?.trim().orEmpty().ifBlank { account.email?.trim().orEmpty() },
            displayName = user.displayName?.trim().orEmpty().ifBlank { account.displayName?.trim().orEmpty() }
        )
        remember(identity)
        return Result.success(identity)
    }

    private fun remember(identity: SvpIdentity) {
        inMemory = identity
        runCatching {
            prefs.edit()
                .putString(KEY_UID, identity.uid)
                .putString(KEY_EMAIL, identity.email)
                .putString(KEY_NAME, identity.displayName)
                .apply()
        }
    }

    /** Library exceptions name a network or a rule; the owner needs the sentence, not the class. */
    private fun readable(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return when {
            error is SvpAuthException -> message
            message.isBlank() -> "Google sign-in for the realtime layer failed."
            message.contains("permission_denied", ignoreCase = true) ||
                message.contains("Permission denied", ignoreCase = true) ->
                "The realtime backend refused this account. Check the database rules for the signed-in uid."
            message.contains("network", ignoreCase = true) ->
                "No connection to the realtime backend. Sync will retry."
            message.contains("invalid_credential", ignoreCase = true) ||
                message.contains("credential", ignoreCase = true) ->
                "Google rejected the sign-in credential. Sign in again to fix it."
            else -> message.take(180)
        }
    }
}

/** A sign-in problem that is expected, explainable and recoverable — never a crash. */
class SvpAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
