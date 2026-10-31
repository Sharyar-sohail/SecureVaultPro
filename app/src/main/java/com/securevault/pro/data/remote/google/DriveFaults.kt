package com.securevault.pro.data.remote.google

/**
 * Tells an authentication fault apart from a real Drive failure.
 *
 * A stale token used to surface as "the backup could not be read", the owner tapped the Google
 * tile again, every retry rebuilt the same dead credential, and the screen looked like a loop. A
 * fault named here is one that only a fresh Google consent can clear, so the caller signs out and
 * sends the owner back through the picker instead of repeating the same request.
 */
object DriveFaults {

    private val AUTH_MARKERS = listOf(
        "401",
        "403",
        "unauthorized",
        "unauthenticated",
        "invalid_grant",
        "invalid credentials",
        "needs you to sign in",
        "sign in with google",
        "permission was not granted",
        "scope"
    )

    fun needsFreshSignIn(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val text = message.lowercase()
        return AUTH_MARKERS.any { text.contains(it) }
    }

    /** The single sentence a sign-in fault may show; never a raw status code on its own. */
    const val SIGN_IN_AGAIN =
        "Google Drive needs you to sign in again. Tap Continue with Google and approve access — " +
            "nothing in your vault was changed."
}
