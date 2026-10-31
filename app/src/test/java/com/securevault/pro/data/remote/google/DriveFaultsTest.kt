package com.securevault.pro.data.remote.google

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFaultsTest {

    @Test
    fun `consent and token faults ask for a fresh sign-in`() {
        val faults = listOf(
            "401 Unauthorized",
            "403 Forbidden",
            "invalid_grant: Token has been expired or revoked.",
            "Google Drive needs you to sign in again.",
            "Sign in with Google to access your encrypted cloud vault.",
            "Google Drive permission was not granted. Allow app data and file access, then try again.",
            "Access Not Configured. The API is not enabled for your project (insufficient scope)."
        )
        for (fault in faults) {
            assertTrue("should re-open Google: $fault", DriveFaults.needsFreshSignIn(fault))
        }
    }

    @Test
    fun `a real transfer or quota failure does not throw the account away`() {
        val notAuth = listOf(
            null,
            "",
            "Unable to download the encrypted backup.",
            "Backup is larger than the supported 120 MB limit",
            "Drive download failed (404).",
            "The cloud restore timed out after 60 seconds.",
            "Unable to read the encrypted backups in Drive (status 500)."
        )
        for (fault in notAuth) {
            assertFalse("must not sign out for: $fault", DriveFaults.needsFreshSignIn(fault))
        }
    }

    @Test
    fun `the sign-in sentence names the action and promises no data loss`() {
        assertTrue(DriveFaults.SIGN_IN_AGAIN.contains("sign in again"))
        assertTrue(DriveFaults.SIGN_IN_AGAIN.contains("Continue with Google"))
        assertTrue(DriveFaults.SIGN_IN_AGAIN.contains("nothing in your vault was changed"))
    }
}
