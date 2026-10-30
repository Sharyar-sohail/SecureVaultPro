package com.securevault.pro.data.email.repository

import com.securevault.pro.data.email.ImapFolderListing
import com.securevault.pro.data.email.ImapEngine
import com.securevault.pro.data.email.MailFolder

/**
 * Folder naming, in one pure place.
 *
 * A server's folder set is the least standardised thing in IMAP: Gmail answers `[Gmail]/Sent Mail`,
 * Outlook `Sent Items`, Yahoo `Bin` and `Trash`, a cPanel box `Sent` and `junk`, and a Cyrillic or
 * Urdu folder arrives in modified UTF-7 (`&VCMRPA-`). Screens must not care, and the compose and
 * archive actions must find the *right* folder whatever the provider called it.
 *
 * The order of decisions is the contract, and [MailFolderNames.roleOf] implements it in that order:
 * 1. the server's own flags — `\Sent`, `\Drafts`, `\Trash`, `\Junk`, `\Archive`, plus the XLIST
 *    extensions some hosts still send (`xlistsent`, `XLIST/Sent`);
 * 2. the decoded display name against a table of the names real providers ship;
 * 3. `INBOX`, which RFC 3501 says is always the inbox no matter how it is spelled in the LIST answer;
 * 4. everything else is a folder the owner named, shown exactly as named.
 *
 * Nothing here deletes, hides or rewrites a folder: a name that maps to no role still appears.
 */
enum class MailFolderRole {
    INBOX, SENT, DRAFTS, ARCHIVE, TRASH, JUNK, IMPORTANT, ALL_MAIL, OTHER;

    /** The label a screen shows when the folder itself has no nicer name. */
    fun label(): String = when (this) {
        INBOX -> "Inbox"
        SENT -> "Sent"
        DRAFTS -> "Drafts"
        ARCHIVE -> "Archive"
        TRASH -> "Trash"
        JUNK -> "Junk"
        IMPORTANT -> "Important"
        ALL_MAIL -> "All mail"
        OTHER -> "Folders"
    }

    /** The order the folder strip shows roles in; an unknown folder keeps its own name and sorts last. */
    fun sortRank(): Int = when (this) {
        INBOX -> 0
        SENT -> 1
        DRAFTS -> 2
        ARCHIVE -> 3
        IMPORTANT -> 4
        ALL_MAIL -> 5
        JUNK -> 6
        TRASH -> 7
        OTHER -> 8
    }
}

object MailFolderNames {

    /** Names that mean a role, keyed by the role, matched after normalisation. */
    private val BY_NAME: Map<MailFolderRole, List<String>> = mapOf(
        MailFolderRole.SENT to listOf(
            "sent", "sent items", "sent messages", "sent mail", "sentmailbox", "outbox", "enviado"
        ),
        MailFolderRole.DRAFTS to listOf("drafts", "draft mailbox", "drafts folder", "brouillon", "borrador"),
        MailFolderRole.TRASH to listOf(
            "trash", "deleted", "deleted items", "deleted messages", "deleted mailbox",
            "bin", "delete", "corbeille", "poubelle"
        ),
        MailFolderRole.JUNK to listOf(
            "junk", "junk e-mail", "junkmail", "spam", "bulk", "bulk mail", "unsolicited mail",
            "courrier indésirable", "indésirables"
        ),
        MailFolderRole.ARCHIVE to listOf("archive", "all mail", "older", "saved"),
        MailFolderRole.IMPORTANT to listOf("important", "starred", "flagged"),
        MailFolderRole.ALL_MAIL to listOf("all mail", "allmail")
    )

    /** The `\`-flag (or XLIST atom) that names a role outright. */
    private val BY_FLAG: Map<String, MailFolderRole> = mapOf(
        "\\sent" to MailFolderRole.SENT,
        "xlistsent" to MailFolderRole.SENT,
        "\\drafts" to MailFolderRole.DRAFTS,
        "xlistdrafts" to MailFolderRole.DRAFTS,
        "\\trash" to MailFolderRole.TRASH,
        "xlisttrash" to MailFolderRole.TRASH,
        "\\junk" to MailFolderRole.JUNK,
        "xlistjunk" to MailFolderRole.JUNK,
        "\\archive" to MailFolderRole.ARCHIVE,
        "xlistarchive" to MailFolderRole.ARCHIVE,
        "\\important" to MailFolderRole.IMPORTANT,
        "xlistimportant" to MailFolderRole.IMPORTANT,
        "\\all" to MailFolderRole.ALL_MAIL,
        "\\inbox" to MailFolderRole.INBOX
    )

    /** Gmail's technical container, which is a prefix rather than a folder the owner chose. */
    private const val GMAIL_LABEL = "[Gmail]"

    /** Normalised for matching only; the screen always shows [displayOf]'s untouched text. */
    private fun normalise(value: String): String = value
        .lowercase()
        .removePrefix(GMAIL_LABEL.lowercase() + "/")
        .removePrefix(GMAIL_LABEL.lowercase())
        .trim('/', ' ', '-', '_', '"')
        .replace(Regex("\\s+"), " ")

    /**
     * The role of one folder. [wireName] is what the protocol speaks, [display] what the owner reads
     * and [flags] the atoms the LIST answer carried.
     */
    fun roleOf(wireName: String, display: String, flags: List<String>): MailFolderRole {
        if (wireName.equals("INBOX", ignoreCase = true)) return MailFolderRole.INBOX
        for (flag in flags) {
            if (flag.isNotBlank()) BY_FLAG[flag.lowercase()]?.let { return it }
        }
        // A provider that does not send role flags still has to be navigated, so the name is next.
        val candidates = listOf(display, wireName).map(::normalise).filter { it.isNotBlank() }
        // Archive and "all mail" share a name on Gmail; the more specific roles win first.
        val ordered = listOf(
            MailFolderRole.SENT, MailFolderRole.DRAFTS, MailFolderRole.TRASH,
            MailFolderRole.JUNK, MailFolderRole.IMPORTANT, MailFolderRole.ALL_MAIL,
            MailFolderRole.ARCHIVE
        )
        for (role in ordered) {
            val names = BY_NAME[role].orEmpty()
            if (candidates.any { it in names }) return role
        }
        return MailFolderRole.OTHER
    }

    /**
     * The name a screen shows.
     *
     * Modified UTF-7 is decoded first (an Arabic or Urdu folder must read as its own words). Gmail's
     * `[Gmail]/` container is dropped from the label because the folder strip already groups by
     * role — but only from the label: [MailFolder.wireName] keeps the full name, which is the only
     * thing the protocol may be handed.
     */
    fun displayOf(wireName: String, separator: String = "/"): String {
        val decoded = ImapEngine.decodeFolderName(wireName)
        val trimmed = decoded.removePrefix(GMAIL_LABEL + separator).removePrefix(GMAIL_LABEL)
        val tail = trimmed.substringAfterLast(separator, trimmed)
        return tail.ifBlank { decoded }
    }

    /** A role's short label when the folder itself is called nothing usable. */
    fun labelFor(folder: MailFolder): String {
        val role = roleOf(folder.wireName, folder.name, folder.flags)
        return if (folder.name.isBlank()) role.label() else folder.name
    }

    /** True when a folder can hold messages; `\Noselect` containers are shown as group rows only. */
    fun isSelectable(flags: List<String>): Boolean =
        flags.none { it.equals("\\Noselect", true) || it.equals("\\Nonexistent", true) }

    /** Wire names that must never be offered to `SELECT`: they are containers, not mailboxes. */
    fun toMailFolder(
        accountId: String,
        listing: ImapFolderListing
    ): MailFolder {
        val display = displayOf(listing.wireName, listing.separator)
        return MailFolder(
            accountId = accountId,
            wireName = listing.wireName,
            name = display,
            separator = listing.separator,
            flags = listing.flags,
            total = 0,
            unseen = 0
        )
    }

    /** The folder strip's order: role first, then the owner's own names alphabetically. */
    fun comparator(): Comparator<MailFolder> = compareBy<MailFolder> {
        roleOf(it.wireName, it.name, it.flags).sortRank()
    }.thenBy { labelFor(it).lowercase() }

    /** The wire name to write a sent/draft copy to, or null when the server has no such folder. */
    fun wireNameFor(folders: List<MailFolder>, role: MailFolderRole): String? =
        folders.firstOrNull {
            it.isSpecial.not() && roleOf(it.wireName, it.name, it.flags) == role
        }?.wireName

    /**
     * The folder a compose or reply lands in when the server advertises nothing: the guesses every
     * provider in 2026 accepts, tried in order against a real LIST answer first.
     */
    val FALLBACK_SENT = listOf("Sent", "Sent Items", "Sent Messages", "[Gmail]/Sent Mail")
    val FALLBACK_DRAFTS = listOf("Drafts", "Drafts Folder", "[Gmail]/Drafts")

    /** The IMAP quoted-list reference/delimiter pair for a provider's own separator. */
    fun childOf(parent: String, separator: String, child: String): String =
        if (parent.isBlank() || separator.isBlank()) child else "$parent$separator$child"
}
