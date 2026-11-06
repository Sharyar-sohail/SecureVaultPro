package com.securevault.pro.security.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the single key a stored copy is opened with.
 *
 * A restore derives one key, chosen from the marker the writer left in the file (`kid`) plus one
 * fact about the device (whether it already holds the account's vault key). There is no candidate
 * list here to test, because there is no candidate list any more — which is the point of these
 * cases: each shape resolves to exactly one secret at exactly one count, and a device that cannot
 * name the key says so instead of hashing alternatives.
 */
class LegacyKeyPlanTest {

    private val vault = OpeningSecret("dmF1bHQta2V5", OpeningSecretKind.VAULT_KEY)

    @Test
    fun `a copy marked as this account's is opened with the vault key the device holds`() {
        val secret = LegacyKeyPlan.exactSecret(
            keyId = LegacyKeyPlan.KEY_ID_VAULT,
            passphrase = "typed",
            vaultSecret = vault.value
        )
        assertEquals(vault.value, secret?.value)
        assertEquals(OpeningSecretKind.VAULT_KEY, secret?.kind)
    }

    @Test
    fun `a copy marked as this account's names no key on a device without the vault key`() {
        assertNull(LegacyKeyPlan.exactSecret(keyId = LegacyKeyPlan.KEY_ID_VAULT, passphrase = "typed"))
    }

    @Test
    fun `an export marked as passphrase-sealed is opened with the passphrase typed for it`() {
        val secret = LegacyKeyPlan.exactSecret(
            keyId = LegacyKeyPlan.KEY_ID_PASSPHRASE,
            passphrase = "typed",
            vaultSecret = vault.value
        )
        assertEquals("typed", secret?.value)
        assertEquals(OpeningSecretKind.PASSPHRASE, secret?.kind)
    }

    @Test
    fun `a copy written before the marker is opened with the account vault key when held`() {
        val secret = LegacyKeyPlan.exactSecret(keyId = null, vaultSecret = vault.value)
        assertEquals(vault.value, secret?.value)
        assertEquals(OpeningSecretKind.VAULT_KEY, secret?.kind)
    }

    @Test
    fun `a copy written before the marker is opened with the retired Drive key on a fresh device`() {
        val secret = LegacyKeyPlan.exactSecret(keyId = null, vaultSecret = null)
        assertEquals(PRIMARY_CLOUD_KEY, secret?.value)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, secret?.kind)
    }

    @Test
    fun `whatever the shape, the key is stretched at the one count every writer used`() {
        for (keyId in listOf(null, "vault", "passphrase", "unknown")) {
            val secret = LegacyKeyPlan.exactSecret(
                keyId = keyId,
                passphrase = "typed",
                vaultSecret = vault.value
            )
            assertEquals(listOf(600_000), secret?.iterationProfiles)
        }
    }

    @Test
    fun `blank secrets are not keys`() {
        assertNull(
            LegacyKeyPlan.exactSecret(
                keyId = LegacyKeyPlan.KEY_ID_VAULT,
                passphrase = "   ",
                vaultSecret = "  "
            )
        )
    }
}
