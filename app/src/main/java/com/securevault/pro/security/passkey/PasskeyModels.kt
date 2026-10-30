package com.securevault.pro.security.passkey

/**
 * The passkey record, and everything about it that can be reasoned about without Android in the room.
 *
 * A passkey is not a secret this app holds. The private key is generated and kept inside the
 * credential provider on the device (Android 14's credential store, which is backed by the owner's
 * Google account); [androidx.credentials] hands the app the *registration response* — public
 * material — and the answer to a challenge. So a [PasskeyRecord] is the vault's index of a key that
 * lives elsewhere, plus the sealed registration response the provider returned when it was created.
 * That distinction drives every rule in this file: the app can list it, use it to ask the provider
 * for an assertion, and forget *its own copy* of it, and it can do none of those things to the key.
 *
 * The record deliberately does not sync. It has no Room entity, no `RealtimeModule`, no field in
 * `RealtimeFieldSchema`, and it is written to its own DataStore file — see [PasskeyStore] and
 * [PasskeyIsNeverSyncedTest].
 */
data class PasskeyRecord(
    /** App-generated identifier; never the credential id itself, so the two cannot be confused. */
    val id: String,
    /** The relying-party id the key was enrolled against, normalised to a bare lowercase host. */
    val rpDomain: String,
    /** Human name given to the RP at enrolment (`publicKey.rp.name`). */
    val rpName: String,
    /** `rawId` from the provider, base64url without padding, as the WebAuthn JSON carries it. */
    val credentialId: String,
    /** `publicKey.user.name` — the account the key belongs to, as the RP spelled it. */
    val userName: String,
    /** The owner-facing label, editable-free: derived at enrolment, shown in the list. */
    val label: String,
    /** `publicKey.challenge`'s origin transport, best-effort: `"internal"` on a device-bound key. */
    val transport: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    /** How many assertions this app asked the provider for and got back. */
    val useCount: Int,
    /**
     * The provider's registration response, sealed with the vault's own device key and wrapped in the
     * app's `{v:3}` envelope. Never plaintext: [PasskeyVault] refuses to build a record whose material
     * would not seal, and [PasskeyStore] only ever writes this field, never a raw response.
     */
    val sealedMaterial: String
) {
    /** True when the sealed copy can be opened by this device right now. */
    val hasReadableMaterial: Boolean get() = sealedMaterial.isNotBlank()
}

/**
 * A record that matched a request's domain, with why it matched. [reason] is what the autofill
 * surface and the list screen show, so a person can tell an exact host match from a parent-domain one.
 */
data class PasskeyCandidate(
    val record: PasskeyRecord,
    val score: Int,
    val reason: MatchReason
) {
    enum class MatchReason {
        /** The requested host is exactly the enrolled `rp.id`. */
        EXACT_HOST,

        /** The enrolled `rp.id` is a parent of the requested host, so WebAuthn allows it. */
        PARENT_DOMAIN,

        /** The requested host is a parent of the enrolled `rp.id` — listed only when asked for by id. */
        CHILD_ONLY,

        /** Matched by credential id from an `allowCredentials` list, not by domain. */
        CREDENTIAL_ID
    }
}

/**
 * Domain rules, deliberately stricter than the vault's fuzzy login matcher.
 *
 * `VaultRepository.autofillCandidates` scores a substring overlap as a weak hit, which is right for
 * "which saved login did the owner probably mean" and wrong for FIDO: WebAuthn will not send a
 * credential whose `rp.id` is not the origin's registrable domain or an ancestor of it, and a fuzzy
 * match here would suggest a passkey the authenticator then refuses. So a match is either exact or a
 * true dot-boundary subdomain relationship, and nothing else is a match at all.
 *
 * [PasskeyDomainRules.isRegistrable] is the honest part of the public-suffix problem: without a
 * Public Suffix List in the app, `com` would otherwise match every `*.com` host. A hand-kept set of
 * the common second-level suffixes is checked instead, and a host that resolves to one label above a
 * known suffix is refused. That is not the whole PSL; it is enough that no single generic label can
 * become an rp id, and [PasskeyDomainRulesTest] pins exactly that claim.
 */
object PasskeyDomainRules {

    /** Same normalisation the vault uses for a website field, plus the userinfo/port forms. */
    fun normalize(raw: String?): String = raw.orEmpty().trim().lowercase()
        .removePrefix("web+authn://www.passkeys/")
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore(':')
        .trim('@', '.')

    /**
     * True when [host] is a bare registrable-looking hostname: at least two labels, each non-empty,
     * each a legal hostname label (letters, digits, hyphen, underscore, no leading/trailing hyphen),
     * no scheme, no path, no port, no userinfo, and not a bare generic suffix.
     *
     * The label check is the part that matters for an `rp.id`. `normalize` cannot remove a userinfo
     * without knowing the difference between `user@host.tld` and `host.tld:8443`, so without it
     * `user@example.com` would pass as a two-label host and this app would enrol a passkey the
     * authenticator can never satisfy. Same for a stray `@`, `_` in the wrong place or an IDN that
     * never got punycoded.
     */
    fun isRegistrable(host: String): Boolean {
        val normalized = normalize(host)
        if (normalized.isBlank() || normalized.contains(' ')) return false
        if (normalized.contains('@') || normalized.contains(':') || normalized.contains('/')) return false
        val labels = normalized.split('.')
        if (labels.size < 2) return false
        if (labels.any { !VALID_LABEL.matches(it) }) return false
        if (labels.last().length < 2 || labels.last().length > 24) return false
        if (normalized.length > 253) return false
        // A host that is only a known public suffix ("co.uk") is not somebody's domain.
        if (SECOND_LEVEL_SUFFIXES.contains(normalized)) return false
        return true
    }

    /**
     * WebAuthn's rp.id rule: [credentialRp] may be used at [requestedHost] when it is that host, or
     * an ancestor of it separated by a dot. Never a substring — `evil-example.com` does not match
     * `example.com`, and `example.com.evil.net` does not either.
     */
    fun rpAllowedAt(credentialRp: String, requestedHost: String): Boolean {
        val rp = normalize(credentialRp)
        val host = normalize(requestedHost)
        if (!isRegistrable(rp) || !isRegistrable(host)) return false
        return rp == host || host.endsWith(".$rp")
    }

    /** Score for a listing: 0 means "not a candidate". [PasskeyCandidate] carries the same tiers. */
    fun score(credentialRp: String, requestedHost: String): Int {
        val rp = normalize(credentialRp)
        val host = normalize(requestedHost)
        if (!rpAllowedAt(rp, host)) return 0
        return if (rp == host) SCORE_EXACT else SCORE_PARENT
    }

    /**
     * The candidates for one requested host, best match first. Ties break on last use, then creation,
     * so a re-enrolled key outranks one untouched since the owner first set the account up.
     */
    fun candidatesFor(
        records: List<PasskeyRecord>,
        requestedHost: String,
        limit: Int = DEFAULT_LIMIT
    ): List<PasskeyCandidate> {
        val host = normalize(requestedHost)
        if (host.isBlank()) return emptyList()
        return records.mapNotNull { record ->
            val scored = score(record.rpDomain, host)
            if (scored <= 0) return@mapNotNull null
            PasskeyCandidate(
                record = record,
                score = scored,
                reason = if (normalize(record.rpDomain) == host) {
                    PasskeyCandidate.MatchReason.EXACT_HOST
                } else {
                    PasskeyCandidate.MatchReason.PARENT_DOMAIN
                }
            )
        }.sortedWith(
            compareByDescending<PasskeyCandidate> { it.score }
                .thenByDescending { it.record.lastUsedAt }
                .thenByDescending { it.record.createdAt }
                .thenBy { it.record.label.lowercase() }
        ).take(if (limit <= 0) DEFAULT_LIMIT else limit)
    }

    /**
     * Grouping for the list screen: one bucket per enrolled rp domain, most-recently-used key first,
     * domains in the order the owner would look for them (most keys, then most recent, then name).
     */
    fun groupByDomain(records: List<PasskeyRecord>): List<PasskeyDomainGroup> {
        if (records.isEmpty()) return emptyList()
        return records
            .groupBy { normalize(it.rpDomain) }
            .map { (domain, group) ->
                PasskeyDomainGroup(
                    domain = domain,
                    records = group.sortedWith(
                        compareByDescending<PasskeyRecord> { it.lastUsedAt }
                            .thenByDescending { record -> record.createdAt }
                    ),
                    mostRecent = group.maxOf { maxOf(it.lastUsedAt, it.createdAt) }
                )
            }
            .sortedWith(
                compareByDescending<PasskeyDomainGroup> { it.records.size }
                    .thenByDescending { it.mostRecent }
                    .thenBy { it.domain }
            )
    }

    /**
     * The draft check the enrolment field uses, and it is deliberately stricter than
     * [isRegistrable]: a person typing a site's address into this field should be told to type the
     * host, not have a pasted `https://site/page` silently rewritten under them. Stored domains still
     * go through [normalize], so a record written by an older build keeps matching.
     */
    fun looksLikeAHost(raw: String?): Boolean {
        val candidate = raw.orEmpty().trim()
        if (candidate.isEmpty()) return false
        if (candidate.any { it.isWhitespace() }) return false
        if (FORBIDDEN_IN_A_HOST.any { marker -> candidate.contains(marker) }) return false
        return isRegistrable(candidate)
    }

    const val SCORE_EXACT = 10
    const val SCORE_PARENT = 8
    const val DEFAULT_LIMIT = 12

    /** One legal hostname label, already lowercased: no empty label, no leading or trailing hyphen. */
    /** A DNS label is at most 63 octets; a longer one is not a host a browser could ever match. */
    private val VALID_LABEL = Regex("^[a-z0-9]([a-z0-9_-]{0,61}[a-z0-9])?$")

    /** Marks of something that is not a bare host: a scheme, a path, userinfo, a port, a fragment. */
    private val FORBIDDEN_IN_A_HOST = listOf('/', '@', ':', '?', '#', ',')

    /**
     * The handful of second-level suffixes that make a two-label host meaningless. Not the Public
     * Suffix List — a floor under "two labels is registrable", nothing more, and documented as such
     * in `agent_notes/passkeys.md`.
     */
    private val SECOND_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk", "ltd.uk", "plc.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "co.in", "net.in", "org.in", "gov.in", "ac.in",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.br", "net.br", "org.br", "gov.br",
        "com.mx", "com.ar", "com.co", "com.cn", "com.hk", "com.sg", "com.my", "com.ph",
        "co.za", "co.kr", "co.id", "com.tr", "com.sa", "com.eg", "com.ng", "com.pk",
        "co.il", "com.ua", "com.ru", "com.tw"
    )
}

/** One relying party and the passkeys this device holds for it. */
data class PasskeyDomainGroup(
    val domain: String,
    val records: List<PasskeyRecord>,
    val mostRecent: Long
) {
    val count: Int get() = records.size
}
