package com.securevault.pro.security.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The salts the original reader fed to PBKDF2, pinned.
 *
 * A file sealed under a short salt only opens when the salt is zero-padded to 32 bytes, which is
 * what `salt.copyOf(32)` does; a salt-less blob only opens with the separate `key.salt` file or the
 * address padded to 32. These tests hold both behaviours so a future opener cannot quietly go back
 * to using the bytes exactly as written.
 */
class LegacySaltPlanTest {

    @Test
    fun `a 32-byte salt is tried once, unchanged`() {
        val salt = ByteArray(32) { it.toByte() }
        val variants = LegacySaltPlan.variants(salt)
        assertEquals(1, variants.size)
        assertArrayEquals(salt, variants.first())
        assertTrue(variants.first() === salt)
    }

    @Test
    fun `a short salt is tried as written, then zero-padded to 32`() {
        val short = ByteArray(16) { (it + 1).toByte() }
        val variants = LegacySaltPlan.variants(short)
        assertEquals(2, variants.size)
        assertArrayEquals(short, variants[0])
        assertEquals(32, variants[1].size)
        assertArrayEquals(short, variants[1].copyOf(16))
        assertArrayEquals(ByteArray(16), variants[1].copyOfRange(16, 32))
    }

    @Test
    fun `a long salt is truncated to 32 for the second derivation`() {
        val long = ByteArray(48) { it.toByte() }
        val variants = LegacySaltPlan.variants(long)
        assertEquals(2, variants.size)
        assertArrayEquals(long, variants[0])
        assertEquals(32, variants[1].size)
        assertArrayEquals(long.copyOf(32), variants[1])
    }

    @Test
    fun `an empty salt still yields a 32-byte derivation source`() {
        val variants = LegacySaltPlan.variants(ByteArray(0))
        assertEquals(1, variants.size)
        assertEquals(32, variants.first().size)
    }

    @Test
    fun `the separate key salt file leads the fallbacks`() {
        val driveSalt = ByteArray(32) { 9 }
        val salts = LegacySaltPlan.fallback(driveSalt, listOf("owner@example.com"))
        assertArrayEquals(driveSalt, salts.first())
        assertEquals("drive salt, zero salt, padded address", 3, salts.size)
    }

    @Test
    fun `without a key salt file the padded address still opens the way the original did`() {
        val salts = LegacySaltPlan.fallback(null, listOf("owner@example.com"))
        assertEquals(2, salts.size)
        assertEquals(32, salts[0].size)
        assertArrayEquals(LegacySaltPlan.padAddress("owner@example.com"), salts[1])
    }

    @Test
    fun `an address pads to exactly 32 bytes and truncates longer input`() {
        val padded = LegacySaltPlan.padAddress("owner@example.com")
        assertEquals(32, padded.size)
        assertEquals("owner@example.com000000000000000", String(padded, Charsets.UTF_8))

        val long = LegacySaltPlan.padAddress("a-very-long-address-that-exceeds-the-width@example.com")
        assertEquals(32, long.size)
        assertEquals("a-very-long-address-that-exceeds", String(long, Charsets.UTF_8))
    }

    @Test
    fun `duplicate and blank addresses do not multiply the sweep`() {
        val salts = LegacySaltPlan.fallback(null, listOf("owner@example.com", " owner@example.com ", " "))
        assertEquals(2, salts.size)
        assertFalse(salts.any { it.contentEquals(LegacySaltPlan.padAddress(" ")) })
    }
}
