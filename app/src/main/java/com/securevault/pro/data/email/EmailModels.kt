package com.securevault.pro.data.email

/**
 * The mail module's value types.
 *
 * Everything here is transport-independent: the protocol classes produce these, the Room store
 * persists them and the screens render them. No Android type appears in this file so the whole
 * model stays testable on the JVM.
 */

/**
 * How a connection to one server is protected.
 *
 * There is deliberately no plaintext option. `SSL_TLS` wraps the socket from the first byte
 * (port 993 / 465). `STARTTLS` opens the socket bare, issues the protocol's upgrade command and
 * *refuses to authenticate until the TLS handshake has completed* (port 143 / 587). A server that
 * does not offer the upgrade is reported as a failure, never silently downgraded.
 */
enum class SecurityMode {
    SSL_TLS,
    STARTTLS;

    companion object {
        fun fromStorage(raw: String?): SecurityMode? = when (raw?.trim()?.uppercase()) {
            "SSL_TLS", "SSL", "TLS", "IMAPS_SMIME" -> SSL_TLS
            "STARTTLS", "TLS_STARTTLS" -> STARTTLS
            else -> null
        }
    }

    val storageKey: String get() = name
}

/** How the account proves who it is. */
enum class AuthKind {
    /** Password typed by the owner: IMAP LOGIN, SMTP AUTH PLAIN (or LOGIN when PLAIN is absent). */
    PASSWORD,

    /** Google's XOAUTH2 SASL mechanism over an OAuth access token that carries the mail scope. */
    OAUTH_XOAUTH2;

    companion object {
        fun fromStorage(raw: String?): AuthKind? = when (raw?.trim()?.uppercase()) {
            "PASSWORD", "PASSWORD_LOGIN", "LOGIN" -> PASSWORD
            "OAUTH_XOAUTH2", "XOAUTH2", "OAUTH" -> OAUTH_XOAUTH2
            else -> null
        }
    }

    val storageKey: String get() = name
}

/** The manual-configuration presets. [GENERIC] and [CUSTOM] start blank. */
enum class ProviderPreset {
    GMAIL,
    OUTLOOK,
    YAHOO,
    TITAN,
    ICLOUD,
    GENERIC,
    CUSTOM;

    companion object {
        fun fromStorage(raw: String?): ProviderPreset =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: CUSTOM
    }

    val storageKey: String get() = name

    /** The brand an account row shows. [CUSTOM] is spelled for a person, not for the database. */
    fun label(): String = when (this) {
        GMAIL -> "Gmail"
        OUTLOOK -> "Outlook"
        YAHOO -> "Yahoo"
        TITAN -> "Titan"
        ICLOUD -> "iCloud"
        GENERIC -> "Email"
        CUSTOM -> "Custom server"
    }
}

/** One server endpoint: host, port and the protection mode used to reach it. */
data class MailEndpoint(
    val host: String,
    val port: Int,
    val security: SecurityMode
) {
    /** `imap.gmail.com:993 (SSL/TLS)` — a label safe for a screen, it carries no credential. */
    fun describe(): String = "$host:$port (${if (security == SecurityMode.SSL_TLS) "SSL/TLS" else "STARTTLS"})"
}

/** The whole server half of an account. */
data class MailServerConfig(
    val incoming: MailEndpoint,
    val outgoing: MailEndpoint
) {
    fun validate(): List<String> = buildList {
        if (incoming.host.isBlank()) add("Incoming server is empty.")
        if (outgoing.host.isBlank()) add("Outgoing server is empty.")
        if (!incoming.port.isUsableMailPort()) add("Incoming port must be between 1 and 65535.")
        if (!outgoing.port.isUsableMailPort()) add("Outgoing port must be between 1 and 65535.")
    }
}

/** Ports a mail client may legitimately be pointed at; anything else is a typo worth flagging. */
fun Int.isUsableMailPort(): Boolean = this in 1..65535

/** The well-known endpoints, one row per provider, in the preset order the form shows them. */
data class ProviderTemplate(
    val preset: ProviderPreset,
    val label: String,
    val domainHint: String,
    val config: MailServerConfig,
    /** Gmail is the only preset the app can auto-connect with an OAuth token. */
    val autoConnect: Boolean = false,
    /** App-password guidance for providers that refuse a plain account password. */
    val note: String = ""
)

object EmailPresets {
    val ALL: List<ProviderTemplate> = listOf(
        ProviderTemplate(
            preset = ProviderPreset.GMAIL,
            label = "Gmail / Google Workspace",
            domainHint = "gmail.com",
            config = MailServerConfig(
                incoming = MailEndpoint("imap.gmail.com", 993, SecurityMode.SSL_TLS),
                outgoing = MailEndpoint("smtp.gmail.com", 465, SecurityMode.SSL_TLS)
            ),
            autoConnect = true,
            note = "Auto-connect signs in with the Google account already used for Drive backups " +
                "and needs the mail scope approved once."
        ),
        ProviderTemplate(
            preset = ProviderPreset.OUTLOOK,
            label = "Outlook / Hotmail",
            domainHint = "outlook.com",
            config = MailServerConfig(
                incoming = MailEndpoint("outlook.office365.com", 993, SecurityMode.SSL_TLS),
                outgoing = MailEndpoint("smtp.office365.com", 587, SecurityMode.STARTTLS)
            ),
            note = "Microsoft asks personal accounts to use an app password when two-step " +
                "verification is on."
        ),
        ProviderTemplate(
            preset = ProviderPreset.YAHOO,
            label = "Yahoo / AOL",
            domainHint = "yahoo.com",
            config = MailServerConfig(
                incoming = MailEndpoint("imap.mail.yahoo.com", 993, SecurityMode.SSL_TLS),
                outgoing = MailEndpoint("smtp.mail.yahoo.com", 465, SecurityMode.SSL_TLS)
            ),
            note = "Turn on IMAP in Yahoo settings and use an app password, not the account password."
        ),
        ProviderTemplate(
            preset = ProviderPreset.TITAN,
            label = "Titan (business email)",
            domainHint = "yourdomain.com",
            config = MailServerConfig(
                incoming = MailEndpoint("imap.titanemail.com", 993, SecurityMode.SSL_TLS),
                outgoing = MailEndpoint("smtp.titanemail.com", 465, SecurityMode.SSL_TLS)
            ),
            note = "Titan hosts mail for your own domain: the address is you@yourdomain.com, the " +
                "servers below are the same for every tenant."
        ),
        ProviderTemplate(
            preset = ProviderPreset.ICLOUD,
            label = "iCloud",
            domainHint = "icloud.com",
            config = MailServerConfig(
                incoming = MailEndpoint("imap.mail.me.com", 993, SecurityMode.SSL_TLS),
                outgoing = MailEndpoint("smtp.mail.me.com", 587, SecurityMode.STARTTLS)
            ),
            note = "iCloud needs an app-specific password generated at appleid.apple.com."
        ),
        ProviderTemplate(
            preset = ProviderPreset.GENERIC,
            label = "Other IMAP/SMTP host",
            domainHint = "",
            config = MailServerConfig(
                incoming = MailEndpoint("", 993, SecurityMode.SSL_TLS),
                outgoing = MailEndpoint("", 465, SecurityMode.SSL_TLS)
            ),
            note = "Fill in the two servers your provider documents. Both stay on TLS: the app " +
                "never authenticates over a bare connection."
        )
    )

    /** The template whose domain the address belongs to; null means the owner picks by hand. */
    fun suggestFor(address: String): ProviderTemplate? {
        val domain = address.substringAfterLast('@', "").trim().lowercase()
        if (domain.isBlank() || !address.contains('@')) return null
        return when {
            domain == "gmail.com" || domain.endsWith(".gmail.com") -> ALL.first { it.preset == ProviderPreset.GMAIL }
            domain in OUTLOOK_DOMAINS -> ALL.first { it.preset == ProviderPreset.OUTLOOK }
            domain in YAHOO_DOMAINS -> ALL.first { it.preset == ProviderPreset.YAHOO }
            domain in ICLOUD_DOMAINS -> ALL.first { it.preset == ProviderPreset.ICLOUD }
            else -> null
        }
    }

    /** True when XOAUTH2 is the right auth for this address, i.e. a Google mailbox. */
    fun isGoogleMailbox(address: String): Boolean {
        val domain = address.substringAfterLast('@', "").trim().lowercase()
        return domain == "gmail.com" || domain.endsWith(".gmail.com") ||
            domain.endsWith(".googlemail.com") ||
            // Workspace tenants keep their own domain; the app cannot prove it from the address
            // alone, so the setup form offers "Use Google sign-in" for a custom host on demand.
            domain in KNOWN_WORKSPACE_SUFFIXES
    }

    private val OUTLOOK_DOMAINS = setOf(
        "outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.office365.com",
        "hormail.com", "windowslive.com"
    )
    private val YAHOO_DOMAINS = setOf("yahoo.com", "ymail.com", "rocketmail.com", "aol.com", "yahoo.co.uk")
    private val ICLOUD_DOMAINS = setOf("icloud.com", "me.com", "mac.com")
    private val KNOWN_WORKSPACE_SUFFIXES = setOf("googlemail.com")
}

/** A configured mailbox. [passwordSealed] is ciphertext only; the plaintext never leaves the form. */
data class EmailAccount(
    val id: String,
    val address: String,
    val label: String,
    val provider: ProviderPreset,
    val authKind: AuthKind,
    val config: MailServerConfig,
    val autoSync: Boolean = true,
    val lastSyncAt: Long = 0L,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
) {
    val displayName: String get() = label.ifBlank { address }

    /** True when the secret is a Google access token minted on demand instead of a stored password. */
    val usesOAuth: Boolean get() = authKind == AuthKind.OAUTH_XOAUTH2
}

/** One mailbox folder as the server reported it. [wireName] is what the protocol speaks. */
data class MailFolder(
    val accountId: String,
    val wireName: String,
    val name: String,
    val separator: String,
    val flags: List<String>,
    val total: Int = 0,
    val unseen: Int = 0
) {
    val isSpecial: Boolean get() = flags.any { it.equals("\\Noselect", true) || it.equals("\\Nonexistent", true) }
    val isInbox: Boolean get() = name.equals("INBOX", ignoreCase = true)
}

/** A message as the local store knows it. [bodyTextPath] is a cache file, never a big String. */
data class MailMessage(
    val id: String,
    val accountId: String,
    val folderWireName: String,
    val uid: Long,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val toSummary: String,
    val receivedAt: Long,
    val sizeOctets: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachments: Boolean,
    val attachmentCount: Int,
    val snippet: String,
    val messageId: String = "",
    val inReplyTo: String = "",
    val bodyTextPath: String = "",
    val bodyHtmlPath: String = "",
    val structureJson: String = "",
    val fetchedAt: Long = 0L
) {
    val unread: Boolean get() = !seen
}

/** One attachment's metadata; the bytes live in the app cache and are streamed on demand. */
data class MailAttachment(
    val id: String,
    val messageId: String,
    val partIndex: Int,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val cachePath: String,
    val inline: Boolean
)

/** The outcome of the bounded validation attempt on the setup screen. */
data class MailValidationResult(
    val ok: Boolean,
    val incomingReport: String,
    val outgoingReport: String,
    val folderCount: Int,
    val reason: String = ""
)

/** A compose draft handed to the SMTP path. */
data class MailDraft(
    val fromAddress: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    /** Absolute cache file paths; each is streamed into the DATA phase in bounded chunks. */
    val attachmentPaths: List<String> = emptyList()
)
