package com.securevault.pro.data.email.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential half of the mail store, exercised on the JVM.
 *
 * [KeystoreFreeMailSealer] is the production envelope maths (AES-256-GCM, 12-byte IV, 128-bit tag,
 * `base64(iv).base64(ct+tag)`) keyed by a plain 32-byte array instead of the Keystore, so the rules
 * the store applies — refuse a blank or impossible secret, refuse a blob that is not the vault's
 * envelope, never let a typed password into a row — are asserted without a device.
 */
class MailCredentialSealingTest {

    private val key = ByteArray(32) { index -> (index * 13 + 7).toByte() }
    private val vault = MailCredentialVault(KeystoreFreeMailSealer(key))

    private val password = "correct horse battery staple"

    @Test
    fun `a typed password seals into the vault envelope and opens again`() {
        val sealed = vault.sealForStorage(password)

        assertTrue(sealed.message, sealed.ok)
        assertTrue("not the vault's envelope: ${sealed.sealed}", MailCredentials.looksSealed(sealed.sealed))
        assertNotEquals(password, sealed.sealed)
        assertFalse(sealed.sealed.contains(password))
        assertEquals(password, vault.openForConnection(sealed.sealed))
    }

    @Test
    fun `an empty secret is a Google account and stores nothing`() {
        val sealed = vault.sealForStorage("")
        assertTrue(sealed.ok)
        assertEquals(MailCredentials.NO_SEALED_CREDENTIAL, sealed.sealed)

        assertTrue(vault.sealForStorage(null).ok)
    }

    @Test
    fun `a secret that cannot be one is refused before it is sealed`() {
        assertTrue(vault.sealForStorage("   ").message.contains("Enter the password"))
        assertTrue(vault.sealForStorage("two\nlines").message.contains("line break"))
        assertTrue(vault.sealForStorage("x".repeat(513)).message.contains("far longer"))
    }

    @Test
    fun `a sealer that cannot seal refuses instead of writing plaintext`() {
        val broken = MailCredentialVault(object : MailSecretSealer {
            override fun seal(plaintext: String): String? = null
            override fun open(ciphertext: String?): String? = null
        })
        val refused = broken.sealForStorage(password)
        assertFalse(refused.ok)
        assertEquals("", refused.sealed)
        assertTrue(refused.message.contains("security key"))
    }

    @Test
    fun `a sealer that hands the plaintext back is refused`() {
        val echoing = MailCredentialVault(object : MailSecretSealer {
            override fun seal(plaintext: String): String = plaintext
            override fun open(ciphertext: String?): String? = ciphertext
        })
        val refused = echoing.sealForStorage(password)
        assertFalse(refused.ok)
        assertEquals("", refused.sealed)
        assertTrue(refused.message.contains("encrypted envelope"))
    }

    @Test
    fun `a different key opens nothing`() {
        val sealed = vault.sealForStorage(password).sealed
        val foreign = MailCredentialVault(KeystoreFreeMailSealer(ByteArray(32) { 1 }))
        assertNull(foreign.openForConnection(sealed))
        assertNull(vault.openForConnection("not an envelope"))
        assertNull(vault.openForConnection(null))
    }

    @Test
    fun `the row guard catches a typed secret anywhere in a stored row`() {
        val sealed = vault.sealForStorage(password).sealed
        val row = "EmailAccount(id=mail-1, address=owner@example.com, credentialSealed=$sealed)"

        assertFalse("a sealed-only row tripped the guard", vault.rowLeaksSecret(row, password))
        assertTrue(vault.rowLeaksSecret("$row\u0000$password", password))
        // A blank secret cannot leak: the guard does not fire on an OAuth row.
        assertFalse(vault.rowLeaksSecret(row, ""))
    }

    @Test
    fun `the same password seals differently each time`() {
        val first = vault.sealForStorage(password).sealed
        val second = vault.sealForStorage(password).sealed
        assertNotEquals("a reused IV would leak equality", first, second)
    }

    @Test
    fun `the credential summary says what the row can do without repeating the value`() {
        val sealed = vault.sealForStorage(password).sealed
        assertEquals("Password saved, encrypted in the vault", MailCredentials.describe(sealed, false))
        assertEquals("No password saved yet", MailCredentials.describe("", false))
        assertEquals("Signed in with Google", MailCredentials.describe("", true))
        assertEquals("The stored password could not be read on this device", MailCredentials.describe("garbage", false))
    }
}
