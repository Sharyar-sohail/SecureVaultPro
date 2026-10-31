package com.securevault.pro.security.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the order in which stored envelopes are offered their keys.
 *
 * This order is the whole fix for the "4 candidates tried" failure: an earlier draft tried the
 * account address first and asked the keyring for a key it did not hold (which *generates* a new
 * one), so the universal v3 key every pre-keyring build sealed with was never reached inside the
 * time budget. These assertions exist so that order cannot drift again unnoticed.
 */
class LegacyKeyPlanTest {

    @Test
    fun `the universal v3 key leads when there is no vault key and no passphrase`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null)
        assertEquals(PRIMARY_CLOUD_KEY, plan.first().value)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, plan.first().kind)
    }

    @Test
    fun `a stored vault key leads everything, because the keyring builds wrote with it`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            vaultSecret = "dmF1bHQta2V5"
        )
        assertEquals("dmF1bHQta2V5", plan.first().value)
        assertEquals(OpeningSecretKind.VAULT_KEY, plan.first().kind)
        assertEquals(PRIMARY_CLOUD_KEY, plan[1].value)
    }

    @Test
    fun `a typed passphrase leads, then the vault key, then the writer's universal key`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            passphrase = "correct horse battery",
            vaultSecret = "dmF1bHQta2V5"
        )
        assertEquals(listOf("correct horse battery", "dmF1bHQta2V5", PRIMARY_CLOUD_KEY), plan.take(3).map { it.value })
    }

    @Test
    fun `the account address follows the universal key, exact case then lowercase`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("Owner@Example.com"), vaultSecret = null)
        val addresses = plan.filter { it.kind == OpeningSecretKind.ACCOUNT_EMAIL }.map { it.value }
        assertEquals(listOf("Owner@Example.com", "owner@example.com"), addresses)
        val addressAt = plan.indexOfFirst { it.kind == OpeningSecretKind.ACCOUNT_EMAIL }
        assertTrue("the writer's key must be tried before an address guess", addressAt > 0)
        assertEquals(PRIMARY_CLOUD_KEY, plan[addressAt - 1].value)
    }

    @Test
    fun `every retired universal string is offered, and nothing is duplicated`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com", "owner@example.com", " "),
            vaultSecret = null
        )
        val values = plan.map { it.value }
        assertEquals(values.distinct(), values)
        for (key in listOf(PRIMARY_CLOUD_KEY, LEGACY_CLOUD_KEY_V2, LEGACY_CLOUD_KEY_V1, LEGACY_CLOUD_KEY)) {
            assertTrue("$key must remain an opening candidate", values.contains(key))
        }
    }

    @Test
    fun `a stated round count narrows every secret to that count alone`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            statedIterations = 210_000,
            vaultSecret = "dmF1bHQta2V5"
        )
        assertTrue(plan.isNotEmpty())
        for (secret in plan) {
            assertEquals(
                "${secret.value} must be derived at the stated count only",
                listOf(210_000),
                secret.iterationProfiles
            )
        }
    }

    @Test
    fun `without a stated count the universal keys carry the full historical profile list`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null)
        val universal = plan.first { it.value == PRIMARY_CLOUD_KEY }
        assertEquals(
            listOf(600_000, 210_000, 120_000),
            universal.iterationProfiles
        )
        val address = plan.first { it.kind == OpeningSecretKind.ACCOUNT_EMAIL }
        assertEquals("the address era only ever wrote 600,000", listOf(600_000), address.iterationProfiles)
    }

    @Test
    fun `an empty email list still yields the universal keys`() {
        val plan = LegacyKeyPlan.ordered(emails = emptyList(), vaultSecret = null)
        assertFalse(plan.isEmpty())
        assertEquals(PRIMARY_CLOUD_KEY, plan.first().value)
    }
}
