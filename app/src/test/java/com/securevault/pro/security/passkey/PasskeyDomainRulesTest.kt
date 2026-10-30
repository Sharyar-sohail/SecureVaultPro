package com.securevault.pro.security.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Domain matching — the one rule in this feature that decides whether a passkey is offered at all.
 *
 * WebAuthn's contract is exact: an authenticator sends a credential only when its `rp.id` is the
 * requesting origin's registrable domain or an ancestor of it, separated by a dot. A fuzzy match here
 * would surface a key the authenticator then refuses, so these tests pin the *strict* reading: exact
 * or dot-boundary parent, and nothing else. The substring cases below (`evil-example.com`,
 * `example.com.evil.net`) are the two classic ways a loose matcher becomes a phishing aid.
 *
 * The public-suffix claim is deliberately narrow and pinned as such: this app ships no Public Suffix
 * List, so [PasskeyDomainRules] holds a hand-kept set of common second-level suffixes and refuses a
 * host that is only one of them. That stops `co.uk` from becoming an `rp.id`; it does not claim to
 * know every suffix, and no test here pretends it does.
 *
 * Pure JVM: no Android class is touched, directly or transitively.
 */
class PasskeyDomainRulesTest {

    private fun record(
        rpDomain: String,
        credentialId: String = "cid-" + rpDomain.hashCode(),
        label: String = rpDomain,
        lastUsedAt: Long = 0L,
        createdAt: Long = 0L
    ) = PasskeyRecord(
        id = "pk-" + credentialId,
        rpDomain = rpDomain,
        rpName = rpDomain,
        credentialId = credentialId,
        userName = "owner@" + rpDomain,
        label = label,
        transport = PasskeyTransports.INTERNAL,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        useCount = 0,
        sealedMaterial = PasskeyEnvelope.wrap(TEST_BLOB)
    )

    // ---- normalisation -----------------------------------------------------------------

    @Test
    fun `normalisation strips everything a browser never puts in an rp id`() {
        assertEquals("example.com", PasskeyDomainRules.normalize("HTTPS://www.Example.com/Login?x=1"))
        assertEquals("example.com", PasskeyDomainRules.normalize("http://example.com:8443/path"))
        assertEquals("example.com", PasskeyDomainRules.normalize("example.com."))
        assertEquals("example.com", PasskeyDomainRules.normalize("  Example.COM  "))
        assertEquals("example.com", PasskeyDomainRules.normalize("web+authn://www.passkeys/example.com"))
        assertEquals("example.com", PasskeyDomainRules.normalize("example.com#anchor"))
        assertEquals("", PasskeyDomainRules.normalize(null))
        assertEquals("", PasskeyDomainRules.normalize("   "))
        assertEquals("", PasskeyDomainRules.normalize("https://"))
    }

    /** Port first, path second: both orders have to end up at the same host or the list would split. */
    @Test
    fun `a port and a path are the same site`() {
        val stored = record("example.com")
        val candidates = PasskeyDomainRules.candidatesFor(
            listOf(stored),
            "https://www.example.com:8443/accounts/login"
        )
        assertEquals(1, candidates.size)
        assertEquals(PasskeyCandidate.MatchReason.EXACT_HOST, candidates.first().reason)
    }

    // ---- registrability ----------------------------------------------------------------

    @Test
    fun `a registrable host is at least two legal labels`() {
        assertTrue(PasskeyDomainRules.isRegistrable("example.com"))
        assertTrue(PasskeyDomainRules.isRegistrable("app.example.com"))
        assertTrue(PasskeyDomainRules.isRegistrable("example.co.uk"))
        assertTrue(PasskeyDomainRules.isRegistrable("xn--80ak6aa92e.com"))
        assertTrue(PasskeyDomainRules.isRegistrable("a-b.example.io"))
    }

    @Test
    fun `anything that is not a host is refused`() {
        listOf(
            "", "  ", "localhost", "example", "com", ".com", "example.", "exa mple.com",
            "user@example.com", "192.168.1.1",
            "-example.com", "example-.com", "exa_m,ple.com", "co.uk", "com.au", "www",
            "example..com", "a".repeat(254) + ".com"
        ).forEach { bad ->
            assertFalse("`$bad` must not be an rp id", PasskeyDomainRules.isRegistrable(bad))
        }
    }

    /**
     * `isRegistrable` is the *stored value* check, so a whole URL still resolves to its host — that
     * is what keeps a record written from a paste-able field readable. `looksLikeAHost` is the field
     * check and is stricter, so the screen asks for the host instead of rewriting it silently.
     */
    @Test
    fun `a URL is registrable once normalised but is not a host-shaped draft`() {
        assertTrue(PasskeyDomainRules.isRegistrable("https://example.com/path"))
        assertTrue(PasskeyDomainRules.isRegistrable("example.com/path"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("https://example.com/path"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("example.com/path"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("example.com:8443"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("user@example.com"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("example com"))
    }

    @Test
    fun `the draft check still accepts a plain host`() {
        assertTrue(PasskeyDomainRules.looksLikeAHost("EXAMPLE.com "))
        assertTrue(PasskeyDomainRules.looksLikeAHost("login.example.co.uk"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("owner@example.com"))
        assertFalse(PasskeyDomainRules.looksLikeAHost(null))
        assertFalse(PasskeyDomainRules.looksLikeAHost("   "))
    }

    /**
     * The IP-literal case is the one worth naming: `192.168.1.1` has four labels and a two-character
     * last one, so only the label shape rule keeps it out. An rp id that is an address is not a
     * relying party, and a passkey enrolled against it could never be matched by a browser.
     */
    @Test
    fun `an address is not a relying party`() {
        assertFalse(PasskeyDomainRules.isRegistrable("192.168.1.1"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("127.0.0.1"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("::1"))
    }

    @Test
    fun `a long label or a long host is refused rather than truncated`() {
        assertFalse(PasskeyDomainRules.isRegistrable("a".repeat(70) + ".com"))
        assertFalse(PasskeyDomainRules.isRegistrable("x".repeat(64) + ".com"))
        assertTrue(PasskeyDomainRules.isRegistrable("x".repeat(63) + ".com"))
        val veryLong = (1..60).joinToString(".") { "label$it" } + ".com"
        assertFalse(PasskeyDomainRules.isRegistrable(veryLong))
    }

    @Test
    fun `the host-shaped draft the screen accepts is exactly the registrable set`() {
        assertTrue(PasskeyDomainRules.looksLikeAHost("EXAMPLE.com "))
        assertFalse(PasskeyDomainRules.looksLikeAHost("owner@example.com"))
        assertFalse(PasskeyDomainRules.looksLikeAHost("example.com/login"))
        assertFalse(PasskeyDomainRules.looksLikeAHost(null))
    }

    // ---- the rp id rule ----------------------------------------------------------------

    @Test
    fun `an rp id is allowed at its own host and at a true subdomain`() {
        assertTrue(PasskeyDomainRules.rpAllowedAt("example.com", "example.com"))
        assertTrue(PasskeyDomainRules.rpAllowedAt("example.com", "login.example.com"))
        assertTrue(PasskeyDomainRules.rpAllowedAt("example.com", "a.b.example.com"))
        assertTrue(PasskeyDomainRules.rpAllowedAt("EXAMPLE.com", "www.example.com"))
    }

    @Test
    fun `a substring is not a match`() {
        assertFalse(PasskeyDomainRules.rpAllowedAt("example.com", "evil-example.com"))
        assertFalse(PasskeyDomainRules.rpAllowedAt("example.com", "example.com.evil.net"))
        assertFalse(PasskeyDomainRules.rpAllowedAt("example.com", "notexample.org"))
        assertFalse(PasskeyDomainRules.rpAllowedAt("amp.example.com", "example.com"))
    }

    @Test
    fun `scores separate an exact host from a parent domain and refuse the rest`() {
        assertEquals(PasskeyDomainRules.SCORE_EXACT, PasskeyDomainRules.score("example.com", "example.com"))
        assertEquals(
            PasskeyDomainRules.SCORE_PARENT,
            PasskeyDomainRules.score("example.com", "login.example.com")
        )
        assertEquals(0, PasskeyDomainRules.score("example.com", "evil-example.com"))
        assertEquals(0, PasskeyDomainRules.score("example.com", "co.uk"))
        assertEquals(0, PasskeyDomainRules.score("localhost", "localhost"))
    }

    /**
     * The other direction matters for the list screen: a key enrolled against `login.example.com`
     * cannot be used at `example.com`, so asking for the parent must offer nothing rather than a key
     * the authenticator will not release.
     */
    @Test
    fun `a child credential is never offered at its parent`() {
        val child = record("login.example.com")
        assertTrue(PasskeyDomainRules.candidatesFor(listOf(child), "example.com").isEmpty())
        assertEquals(1, PasskeyDomainRules.candidatesFor(listOf(child), "login.example.com").size)
    }

    // ---- candidates --------------------------------------------------------------------

    @Test
    fun `candidates are ranked exact first then most recently used`() {
        val exact = record("login.example.com", "cid-exact", lastUsedAt = 100L)
        val parent = record("example.com", "cid-parent", lastUsedAt = 900L)
        val unrelated = record("other.net", "cid-other")
        val found = PasskeyDomainRules.candidatesFor(
            listOf(parent, unrelated, exact),
            "login.example.com"
        )
        assertEquals(listOf("cid-exact", "cid-parent"), found.map { it.record.credentialId })
        assertEquals(PasskeyCandidate.MatchReason.EXACT_HOST, found[0].reason)
        assertEquals(PasskeyCandidate.MatchReason.PARENT_DOMAIN, found[1].reason)
    }

    @Test
    fun `equal scores break on last use then creation`() {
        val stale = record("example.com", "cid-stale", lastUsedAt = 1L, createdAt = 1L)
        val fresh = record("example.com", "cid-fresh", lastUsedAt = 2L, createdAt = 2L)
        val never = record("example.com", "cid-never", lastUsedAt = 0L, createdAt = 3L)
        val found = PasskeyDomainRules.candidatesFor(listOf(stale, never, fresh), "example.com")
        assertEquals(listOf("cid-fresh", "cid-stale", "cid-never"), found.map { it.record.credentialId })
    }

    @Test
    fun `the limit caps the listing but a non positive limit falls back to the default`() {
        val many = (1..20).map { record("example.com", "cid-$it") }
        assertEquals(3, PasskeyDomainRules.candidatesFor(many, "example.com", 3).size)
        assertEquals(12, PasskeyDomainRules.candidatesFor(many, "example.com", 0).size)
        assertEquals(12, PasskeyDomainRules.candidatesFor(many, "example.com", -5).size)
        assertEquals(PasskeyDomainRules.DEFAULT_LIMIT, 12)
        assertEquals(20, PasskeyDomainRules.candidatesFor(many, "example.com", 50).size)
    }

    @Test
    fun `a blank or unparseable request offers nothing`() {
        val some = listOf(record("example.com"))
        assertTrue(PasskeyDomainRules.candidatesFor(some, "").isEmpty())
        assertTrue(PasskeyDomainRules.candidatesFor(some, "   ").isEmpty())
        assertTrue(PasskeyDomainRules.candidatesFor(some, "http://").isEmpty())
        assertTrue(PasskeyDomainRules.candidatesFor(emptyList(), "example.com").isEmpty())
    }

    // ---- grouping for the list screen ---------------------------------------------------

    @Test
    fun `grouping puts one bucket per enrolled domain, normalised`() {
        val groups = PasskeyDomainRules.groupByDomain(
            listOf(
                record("example.com", "cid-a"),
                record("Example.COM", "cid-b"),
                record("https://other.net/x", "cid-c")
            )
        )
        assertEquals(2, groups.size)
        // Same size buckets, so the tie breaks on the name: "example.com" then "other.net".
        assertEquals(listOf("example.com", "other.net"), groups.map { it.domain })
        assertEquals(2, groups.first().count)
        assertEquals(listOf("cid-a", "cid-b"), groups.first().records.map { it.credentialId })
    }

    @Test
    fun `grouping orders by how many keys, then by recency, then by name`() {
        val two = listOf(
            record("beta.com", "cid-b1", lastUsedAt = 1L),
            record("beta.com", "cid-b2", lastUsedAt = 1L)
        )
        val oneOld = listOf(record("alpha.com", "cid-a1", lastUsedAt = 500L))
        val oneNew = listOf(record("gamma.com", "cid-g1", lastUsedAt = 5_000L))
        val groups = PasskeyDomainRules.groupByDomain(two + oneOld + oneNew)
        assertEquals(listOf("beta.com", "gamma.com", "alpha.com"), groups.map { it.domain })
    }

    @Test
    fun `an empty store groups to nothing rather than an empty bucket`() {
        assertTrue(PasskeyDomainRules.groupByDomain(emptyList()).isEmpty())
    }

    private companion object {
        /** A real-shaped `base64(iv).base64(ciphertext+tag)` container, fabricated for the test. */
        val TEST_BLOB = java.util.Base64.getEncoder().encodeToString(ByteArray(12) { 7 }) +
            "." + java.util.Base64.getEncoder().encodeToString(ByteArray(33) { 9 })
    }
}
