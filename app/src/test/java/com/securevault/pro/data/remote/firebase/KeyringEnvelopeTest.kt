package com.securevault.pro.data.remote.firebase

import com.securevault.pro.security.KeyDerivation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The node body the browser reads, in the shape `sync_engine/SPEC-v1.md` promises:
 * `svp1/users/$uid/keyring = {wrap, salt, alg, iters, updatedAt}`.
 *
 * The field names are the interface between the APK and the Blogger theme, so they are pinned here
 * as written, as parsed, and as the database hands them back.
 */
class KeyringEnvelopeTest {

    private val derivation = KeyDerivation()

    private fun fixturePassphrase(label: String) = "$label-${System.nanoTime()}-fixture"

    @Test
    fun `the node body is exactly the five fields the spec names, in order`() {
        val envelope = KeyringEnvelope(
            wrap = "aWsegV4cGlhjYXBlcmNpcHRlcnRleHQ=",
            saltBase64 = "c2FsdHNhbHRzYWx0c2FsdHNhbHRzYWx0c2ExMg==",
            algorithm = KeyringCodec.ALGORITHM,
            iterations = KeyDerivation.PBKDF2_ITERATIONS,
            updatedAt = 1_765_000_000_000L
        )

        val keys = envelope.toCloudMap().keys.toList()
        assertEquals(listOf("wrap", "salt", "alg", "iters", "updatedAt"), keys)
        assertEquals(KeyringEnvelope.FIELDS, keys)
    }

    @Test
    fun `a written envelope reads back from a map the database would hand over`() {
        val passphrase = fixturePassphrase("protocol")
        val key = KeyringCodec.generateVaultKey()
        val (envelope, _) = KeyringProtocol.seal(key, passphrase, derivation)

        val restored = KeyringEnvelope.fromCloudMap(envelope.toCloudMap())
        assertNotNull(restored)
        assertEquals(envelope.wrap, restored!!.wrap)
        assertEquals(envelope.algorithm, restored.algorithm)
        assertEquals(envelope.iterations, restored.iterations)
        assertEquals(envelope.updatedAt, restored.updatedAt)
        assertArrayEquals(key, KeyringProtocol.open(restored, passphrase, derivation))
    }

    /**
     * Realtime Database has no integer type: the same `iters` can arrive as Long, Double or a
     * String depending on which client wrote it. The browser and the Node probe will both post
     * numbers, so the APK must not be picky about which one it gets.
     */
    @Test
    fun `numeric fields are accepted in every shape the database can return`() {
        val wrap = "aWse.gV4cGlhjYXBlcmNpcHRlcnRleHQ="
        val salt = "c2FsdHNhbHRzYWx0c2FsdHNhbHRzYWx0c2ExMg=="
        for (iterations in listOf<Any>(600_000, 600_000L, 600_000.0, "600000")) {
            for (updatedAt in listOf<Any>(1_765_000_000_000L, 1.765E12, "1765000000000")) {
                val parsed = KeyringEnvelope.fromCloudMap(
                    mapOf<String, Any?>("wrap" to wrap, "salt" to salt, "alg" to "AES-256-GCM", "iters" to iterations, "updatedAt" to updatedAt)
                )
                assertNotNull("$iterations / $updatedAt must parse", parsed)
            }
        }
        assertEquals(600_000, KeyringEnvelope.fromCloudMap(
            mapOf("wrap" to wrap, "salt" to salt, "alg" to "AES-256-GCM", "iters" to 600000.0, "updatedAt" to "1765000000000")
        )?.iterations)
    }

    /**
     * Anything that is not a complete, honest wrap is rejected outright. A half-read node must not
     * reach the unwrapper, and a foreign algorithm must not be treated as ours.
     */
    @Test
    fun `a malformed node is rejected rather than half trusted`() {
        val wrap = "aWse.gV4cGlhjYXBlcmNpcHRlcnRleHQ="
        val salt = "c2FsdHNhbHRzYWx0c2FsdHNhbHRzYWx0c2ExMg=="
        val good = mapOf<String, Any?>(
            "wrap" to wrap,
            "salt" to salt,
            "alg" to "AES-256-GCM",
            "iters" to 600_000,
            "updatedAt" to 1_765_000_000_000L
        )
        assertNotNull(KeyringEnvelope.fromCloudMap(good))

        val cutKeys = listOf("wrap", "salt", "alg", "iters", "updatedAt")
        for (missing in cutKeys) {
            assertNull("$missing cannot be optional", KeyringEnvelope.fromCloudMap(good.filterKeys { it != missing }))
        }
        assertNull(KeyringEnvelope.fromCloudMap(null))
        assertNull(KeyringEnvelope.fromCloudMap(emptyMap()))
        assertNull(
            "a different cipher is a different protocol",
            KeyringEnvelope.fromCloudMap(good + ("alg" to "AES-256-CBC"))
        )
        assertNull(KeyringEnvelope.fromCloudMap(good + ("wrap" to "no-dot-here")))
        assertNull(KeyringEnvelope.fromCloudMap(good + ("salt" to "not base64 !!!")))
        assertNull(KeyringEnvelope.fromCloudMap(good + ("iters" to 0)))
        assertNull(KeyringEnvelope.fromCloudMap(good + ("updatedAt" to -1)))
    }

    @Test
    fun `a keyring sealed at the spec profile opens at the spec profile`() {
        val passphrase = fixturePassphrase("spec")
        val key = KeyringCodec.generateVaultKey()

        val (envelope, _) = KeyringProtocol.seal(key, passphrase, derivation)
        assertEquals(KeyDerivation.PBKDF2_ITERATIONS, envelope.iterations)
        assertEquals(KeyringCodec.ALGORITHM, envelope.algorithm)
        assertEquals(44, envelope.saltBase64.length) // 32 raw bytes, standard base64

        assertArrayEquals(key, KeyringProtocol.open(envelope, passphrase, derivation))
        assertNull(KeyringProtocol.open(envelope, "$passphrase!", derivation))
    }

    /** A wrong passphrase must not be able to write anything back, so the wrap stays intact. */
    @Test
    fun `a wrong passphrase leaves the envelope untouched and yields nothing`() {
        val passphrase = fixturePassphrase("owner")
        val key = KeyringCodec.generateVaultKey()
        val (envelope, _) = KeyringProtocol.seal(key, passphrase, derivation)

        val attackerGuess = "let-me-in-${System.nanoTime()}"
        assertNull(KeyringProtocol.open(envelope, attackerGuess, derivation))
        val reread = KeyringEnvelope.fromCloudMap(envelope.toCloudMap())
        assertArrayEquals(key, KeyringProtocol.open(reread!!, passphrase, derivation))
    }
}

/**
 * The node layout is the contract between two codebases that never compile together, so it is
 * pinned against the text of the spec: `svp1/users/$uid/{keyring,data/$module/$id,meta}`.
 */
class RealtimePathsTest {

    @Test
    fun `the tree matches the layout the spec fixes`() {
        assertEquals("svp1/users/u123", RealtimePaths.user("u123"))
        assertEquals("svp1/users/u123/keyring", RealtimePaths.keyring("u123"))
        assertEquals("svp1/users/u123/data", RealtimePaths.dataRoot("u123"))
        assertEquals("svp1/users/u123/data/notes", RealtimePaths.module("u123", "notes"))
        assertEquals("svp1/users/u123/data/notes/n-1", RealtimePaths.record("u123", "notes", "n-1"))
        assertEquals("svp1/users/u123/meta", RealtimePaths.meta("u123"))
    }

    @Test
    fun `every module the sync engine mirrors has a table`() {
        assertEquals(
            listOf("notes", "entries", "cards", "totps", "contacts", "cryptoWallets", "files"),
            RealtimePaths.MODULES
        )
    }

    /**
     * A Realtime Database key may not contain `. # $ [ ] /` or whitespace. Building a path anyway
     * would silently address a different node, which reads to the owner as an empty vault instead
     * of a bug — so the builders answer null and the caller has to notice.
     */
    @Test
    fun `an unsafe segment is refused instead of quietly mangled`() {
        for (bad in listOf("a.b", "a#b", "a\$b", "a[b", "a]b", "a/b", "a b", "", "   ")) {
            org.junit.Assert.assertNull("uid '$bad' must not build a path", RealtimePaths.user(bad))
            org.junit.Assert.assertNull("module '$bad' must not build a path", RealtimePaths.module("u123", bad))
        }
        org.junit.Assert.assertNull(RealtimePaths.record("u123", "notes", "id?#1"))
        org.junit.Assert.assertNull(RealtimePaths.keyring(null))
        org.junit.Assert.assertNull(RealtimePaths.user(null))
    }

    @Test
    fun `a real firebase uid and record id are accepted`() {
        org.junit.Assert.assertTrue(RealtimePaths.isSafeSegment("abcdefghij0123456789ABCDEFGHIJKL"))
        org.junit.Assert.assertTrue(RealtimePaths.isSafeSegment("note-2026-10-29-0001"))
        org.junit.Assert.assertTrue(RealtimePaths.isSafeSegment("cryptoWallets"))
    }
}
