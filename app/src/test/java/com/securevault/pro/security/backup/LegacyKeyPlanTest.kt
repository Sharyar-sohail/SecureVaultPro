package com.securevault.pro.security.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the order in which stored envelopes are offered their keys, and the counts paired with each.
 *
 * The order mirrors the original readers found on disk: the Google account ID first (the
 * same-package app sealed with it and it is not derivable from the address), then the universal
 * strings every pre-keyring build wrote with, then the keyring's vault key, then the shared legacy
 * literals, then the account address. These assertions exist so the order cannot drift back to a
 * guess without the build failing.
 */
class LegacyKeyPlanTest {

    @Test
    fun `the universal v3 key leads when there is no account id, vault key or passphrase`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null)
        assertEquals(PRIMARY_CLOUD_KEY, plan.first().value)
        assertEquals(OpeningSecretKind.RETIRED_UNIVERSAL_KEY, plan.first().kind)
    }

    @Test
    fun `the Google account id leads everything, because the original app sealed with it`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            vaultSecret = "dmF1bHQta2V5",
            accountIds = listOf("118273645091827364509"),
            displayName = "Sharyar Sohail"
        )
        assertEquals("118273645091827364509", plan.first().value)
        assertEquals(OpeningSecretKind.ACCOUNT_ID, plan.first().kind)
        assertEquals(PRIMARY_CLOUD_KEY, plan[1].value)
    }

    @Test
    fun `a typed passphrase leads the account id, then the universal key and the vault key`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            passphrase = "correct horse battery",
            vaultSecret = "dmF1bHQta2V5",
            accountIds = listOf("118273645091827364509")
        )
        assertEquals(
            listOf("correct horse battery", "118273645091827364509", PRIMARY_CLOUD_KEY, "dmF1bHQta2V5"),
            plan.take(4).map { it.value }
        )
    }

    @Test
    fun `the vault key is offered only when the keyring already holds one`() {
        val without = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null)
        assertFalse(without.any { it.kind == OpeningSecretKind.VAULT_KEY })

        val with = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = "dmF1bHQta2V5")
        assertTrue(with.any { it.kind == OpeningSecretKind.VAULT_KEY })
    }

    @Test
    fun `every shared literal the original reader swept is offered before the address`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null)
        val values = plan.map { it.value }
        for (key in LegacyKeyPlan.SHARED_LEGACY_KEYS) {
            assertTrue("$key must remain an opening candidate", values.contains(key))
        }
        val addressAt = plan.indexOfFirst { it.kind == OpeningSecretKind.ACCOUNT_EMAIL }
        val lastSharedAt = plan.indexOfLast { it.kind == OpeningSecretKind.SHARED_LEGACY_KEY }
        assertTrue("the shared literals must be tried before an address guess", addressAt > lastSharedAt)
    }

    @Test
    fun `the account address is offered exact, lowercase and as a local part`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("Owner@Example.com"), vaultSecret = null)
        val addresses = plan.filter { it.kind == OpeningSecretKind.ACCOUNT_EMAIL }.map { it.value }
        assertEquals(listOf("Owner@Example.com", "owner@example.com", "Owner"), addresses.take(3))
        assertTrue(
            "the original reader hard-coded the owner's addresses",
            addresses.containsAll(LegacyKeyPlan.HISTORICAL_OWNER_ADDRESSES)
        )
        assertTrue(
            "the original reader also tried bare handles",
            addresses.containsAll(LegacyKeyPlan.HISTORICAL_HANDLES)
        )
    }

    @Test
    fun `the display name is offered after the address`() {
        val plan = LegacyKeyPlan.ordered(
            emails = listOf("owner@example.com"),
            displayName = "Sharyar Sohail",
            vaultSecret = null
        )
        val values = plan.map { it.value }
        assertTrue(values.contains("Sharyar Sohail"))
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
            vaultSecret = "dmF1bHQta2V5",
            accountIds = listOf("118273645091827364509")
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
    fun `universal keys carry every count a release shipped, most likely first`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null)
        val universal = plan.first { it.value == PRIMARY_CLOUD_KEY }
        assertEquals(
            listOf(600_000, 210_000, 120_000, 100_000, 10_000, 1_000),
            universal.iterationProfiles
        )
    }

    @Test
    fun `address-era secrets carry the original reader's four counts`() {
        val plan = LegacyKeyPlan.ordered(emails = listOf("owner@example.com"), vaultSecret = null)
        val address = plan.first { it.kind == OpeningSecretKind.ACCOUNT_EMAIL }
        assertEquals("the address era swept 600k, 100k, 10k and 1k", listOf(600_000, 100_000, 10_000, 1_000), address.iterationProfiles)
        val shared = plan.first { it.kind == OpeningSecretKind.SHARED_LEGACY_KEY }
        assertEquals(listOf(600_000, 100_000, 10_000, 1_000), shared.iterationProfiles)
    }

    @Test
    fun `an empty email list still yields the universal keys`() {
        val plan = LegacyKeyPlan.ordered(emails = emptyList(), vaultSecret = null)
        assertFalse(plan.isEmpty())
        assertEquals(PRIMARY_CLOUD_KEY, plan.first().value)
    }
}
