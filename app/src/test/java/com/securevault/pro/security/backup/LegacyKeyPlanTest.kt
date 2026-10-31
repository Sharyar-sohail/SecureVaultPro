package com.securevault.pro.security.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the keys a stored envelope is offered and, critically, the exact derivation counts.
 *
 * The old code used 600,000 rounds for every v3 candidate and 210,000/120,000 only for envelopes
 * older than v3. There is no count sweep: a count no writer used cannot open a file and only makes
 * the failure slower. The envelope's version also decides which historical keys are plausible.
 */
class LegacyKeyPlanTest {

    @Test
    fun `the universal v3 key leads a v3 envelope, followed by the vault key and the passphrase`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            passphrase = "correct horse battery",
            vaultSecret = "dmF1bHQta2V5",
            accountIds = listOf("118273645091827364509"),
            version = 3
        )
        assertEquals(
            listOf(PRIMARY_CLOUD_KEY, "dmF1bHQta2V5", "correct horse battery", "118273645091827364509"),
            plan.take(4).map { it.value }
        )
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, plan.first().kind)
    }

    @Test
    fun `a v3 file is derived at exactly 600000 rounds, never a sweep`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            passphrase = "typed",
            vaultSecret = "dmF1bHQta2V5",
            accountIds = listOf("118273645091827364509"),
            version = 3
        )
        assertTrue(plan.isNotEmpty())
        for (secret in plan) {
            assertEquals(
                "${secret.value} must carry the old v3 count and nothing else",
                listOf(600_000),
                secret.iterationProfiles
            )
        }
    }

    @Test
    fun `an older envelope carries the three counts the old reader used for it`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            vaultSecret = null,
            version = 2
        )
        for (secret in plan) {
            assertEquals(listOf(600_000, 210_000, 120_000), secret.iterationProfiles)
        }
    }

    @Test
    fun `a v3 file is not offered the older universal strings or invented addresses`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            vaultSecret = null,
            version = 3
        )
        val values = plan.map { it.value }
        for (key in listOf(LEGACY_CLOUD_KEY_V2, LEGACY_CLOUD_KEY_V1, LEGACY_CLOUD_KEY)) {
            assertFalse("$key belongs to the pre-v3 reader", values.contains(key))
        }
        for (key in LegacyKeyPlan.SHARED_LEGACY_KEYS) {
            assertFalse("$key belongs to the pre-v3 reader", values.contains(key))
        }
        assertFalse(values.any { LegacyKeyPlan.HISTORICAL_OWNER_ADDRESSES.contains(it) })
    }

    @Test
    fun `an older envelope still carries every retired universal string and shared literal`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null, version = 2)
        val values = plan.map { it.value }
        for (key in listOf(PRIMARY_CLOUD_KEY, LEGACY_CLOUD_KEY_V2, LEGACY_CLOUD_KEY_V1, LEGACY_CLOUD_KEY)) {
            assertTrue("$key must remain an opening candidate", values.contains(key))
        }
        for (key in LegacyKeyPlan.SHARED_LEGACY_KEYS) {
            assertTrue("$key must remain an opening candidate", values.contains(key))
        }
    }

    @Test
    fun `the vault key is offered only when the keyring already holds one`() {
        val without = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null, version = 3)
        assertFalse(without.any { it.kind == OpeningSecretKind.VAULT_KEY })

        val with = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = "dmF1bHQta2V5", version = 3)
        assertTrue(with.any { it.kind == OpeningSecretKind.VAULT_KEY })
    }

    @Test
    fun `the account address is offered exact, lowercase and as a local part`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("Owner@Example.com"), vaultSecret = null, version = 3)
        val addresses = plan.filter { it.kind == OpeningSecretKind.ACCOUNT_EMAIL }.map { it.value }
        assertEquals(listOf("Owner@Example.com", "owner@example.com", "Owner"), addresses.take(3))
    }

    @Test
    fun `the display name is offered as a legacy address candidate`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            displayName = "Sharyar Sohail",
            vaultSecret = null,
            version = 3
        )
        assertTrue(plan.map { it.value }.contains("Sharyar Sohail"))
    }

    @Test
    fun `nothing is duplicated`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com", "owner@example.com", " "),
            vaultSecret = null,
            version = 3
        )
        val values = plan.map { it.value }
        assertEquals(values.distinct(), values)
    }

    @Test
    fun `an empty email list still yields the universal key`() {
        val plan = LegacyKeyPlan.ordered(emails = emptyList(), vaultSecret = null, version = 3)
        assertFalse(plan.isEmpty())
        assertEquals(PRIMARY_CLOUD_KEY, plan.first().value)
    }

    @Test
    fun `the salt-less blob is treated as older than v3`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null, version = null)
        val values = plan.map { it.value }
        assertTrue(values.contains(LEGACY_CLOUD_KEY))
        assertEquals(listOf(600_000), plan.first().iterationProfiles)
    }
}
