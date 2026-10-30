package com.securevault.pro.presentation.screens.email

import com.securevault.pro.data.email.AuthKind
import com.securevault.pro.data.email.EmailAccount
import com.securevault.pro.data.email.MailDraft
import com.securevault.pro.data.email.MailEndpoint
import com.securevault.pro.data.email.MailFolder
import com.securevault.pro.data.email.MailMessage
import com.securevault.pro.data.email.MailServerConfig
import com.securevault.pro.data.email.ProviderPreset
import com.securevault.pro.data.email.ProviderTemplate
import com.securevault.pro.data.email.SecurityMode
import com.securevault.pro.data.email.repository.MailAccountRecord
import com.securevault.pro.data.email.repository.MailCredentials
import com.securevault.pro.data.email.repository.MailFailureKind
import com.securevault.pro.data.email.repository.MailFolderNames
import com.securevault.pro.data.email.repository.MailFolderRole
import com.securevault.pro.data.email.repository.MailOutcome
import com.securevault.pro.data.email.repository.MailStep

/**
 * The mail section's view state, with no Android and no coroutine in sight.
 *
 * Everything the screens decide — which folder is current, which messages are selected, whether a
 * half-typed setup form is worth a network call, what a failure sounds like to the owner — lives here
 * as plain data and pure functions, because that is the only part of a mail client this project can
 * actually test: the sockets need a device, but a selection machine and an error sentence do not.
 * `EmailSelectionLogicTest` and `EmailSetupDraftTest` assert against this file.
 *
 * The shape is deliberate: every transition returns a **new** value, so a ViewModel can hold one
 * immutable snapshot and Compose recomposes exactly the rows that changed.
 */

/** The section's own navigation — the app graph knows one route for mail, this enum knows the rest. */
enum class EmailView {
    ACCOUNTS,
    ADD_ACCOUNT,
    MAILBOX,
    MESSAGE,
    COMPOSE;

    /** True when the back gesture leaves the section instead of stepping out of one screen. */
    val isRoot: Boolean get() = this == ACCOUNTS
}

/** How far the owner is along the "prove this password" path, so a row can say what it needs. */
enum class AccountNeed {
    READY,
    NEEDS_PASSWORD,
    OAUTH_READY,
    BROKEN_CREDENTIAL;

    fun label(): String = when (this) {
        READY -> "Ready"
        NEEDS_PASSWORD -> "Needs a password"
        OAUTH_READY -> "Signed in with Google"
        BROKEN_CREDENTIAL -> "Stored password unreadable"
    }
}

/**
 * One mailbox as the account list shows it.
 *
 * [unread] and [total] come from the last folder listing this device actually fetched
 * ([countsKnown]); the mail layer keeps no message cache on disk, so inventing a number here would be
 * a lie about a server the owner has not spoken to yet. [lastError] is shown exactly as the store
 * saved it — that sentence is the repository's own wording and re-phrasing it would hide which step
 * failed.
 */
data class AccountRow(
    val record: MailAccountRecord,
    val unread: Int = 0,
    val total: Int = 0,
    val countsKnown: Boolean = false,
    val lastCheckedLabel: String = "Never checked on this device"
) {
    val id: String get() = record.id
    val displayName: String get() = record.displayName
    val address: String get() = record.address
    val autoSync: Boolean get() = record.account.autoSync
    val usesOAuth: Boolean get() = record.usesOAuth
    val lastError: String get() = record.account.lastError
    val credentialSummary: String get() = record.credentialSummary
    val providerLabel: String get() = record.account.provider.label()
    val incomingLabel: String get() = record.account.config.incoming.describe()
    val outgoingLabel: String get() = record.account.config.outgoing.describe()
    val needsPassword: Boolean get() = record.needsPassword
    val unreadLabel: String get() = EmailCopy.unreadLabel(unread, total, countsKnown)
    val state: AccountNeed get() = when {
        record.usesOAuth -> AccountNeed.OAUTH_READY
        record.credentialSealed.isBlank() -> AccountNeed.NEEDS_PASSWORD
        MailCredentials.looksSealed(record.credentialSealed) -> AccountNeed.READY
        else -> AccountNeed.BROKEN_CREDENTIAL
    }

    /** True when there is anything to connect with, i.e. the row's actions should not be greyed out. */
    val canConnect: Boolean get() = state == AccountNeed.READY || state == AccountNeed.OAUTH_READY
}

/** The account list in display order: newest write first, so an account just added sits on top. */
fun accountRowsOf(
    records: List<MailAccountRecord>,
    folderCounts: Map<String, List<MailFolder>>,
    timeLabel: (Long) -> String
): List<AccountRow> = records
    .sortedByDescending { it.account.updatedAt }
    .map { record ->
        val folders = folderCounts[record.id].orEmpty()
        AccountRow(
            record = record,
            unread = folders.sumOf { it.unseen.coerceAtLeast(0) },
            total = folders.sumOf { it.total.coerceAtLeast(0) },
            countsKnown = folders.isNotEmpty(),
            lastCheckedLabel = timeLabel(record.account.lastSyncAt)
        )
    }

/**
 * Which folder is open, which messages are listed, which rows are selected and which one is read.
 *
 * The whole reason this is one type instead of four ViewModel fields is the folder switch: a mail
 * client that keeps the previous folder's selected UIDs will move the wrong messages, because UIDs are
 * only unique *inside* a folder. Every transition that changes the folder therefore wipes the
 * selection and the open message in the same step — [selectFolder] cannot be called "halfway".
 */
data class MailboxSelection(
    val accountId: String = "",
    val folders: List<MailFolder> = emptyList(),
    val folderWireName: String = "",
    val messages: List<MailMessage> = emptyList(),
    val selectedUids: Set<Long> = emptySet(),
    val openUid: Long? = null,
    val query: String = "",
    val searching: Boolean = false,
    val limit: Int = MailboxSelection.FIRST_WINDOW
) {
    /** A window is widened, never shifted: the server answers "the newest N", so paging means more. */
    fun loadWider(): MailboxSelection = copy(limit = (limit + MORE_WINDOW).coerceAtMost(MAX_WINDOW))

    /** Points the machine at one account, forgetting the other mailbox's folder and rows outright. */
    fun forAccount(id: String): MailboxSelection =
        if (id == accountId) this else MailboxSelection(accountId = id, limit = limit)

    /**
     * Folds in a fresh folder list and keeps the selection legal.
     *
     * A folder that vanished server-side must not stay "current": the message list would keep asking
     * for a mailbox the server refuses. If the open folder survives we keep it untouched; otherwise we
     * land on INBOX, and if even that is gone we take the first selectable folder and say so upstream.
     */
    fun withFolders(next: List<MailFolder>): MailboxSelection {
        val selectable = next.filter { MailFolderNames.isSelectable(it.flags) }
        val stillThere = selectable.firstOrNull { it.wireName == folderWireName }
        val chosen = stillThere
            ?: selectable.firstOrNull { it.wireName.equals("INBOX", ignoreCase = true) }
            ?: selectable.firstOrNull()
        return if (stillThere != null && selectedUids.isEmpty() && openUid == null) {
            copy(folders = selectable)
        } else {
            copy(
                folders = selectable,
                folderWireName = chosen?.wireName.orEmpty(),
                messages = if (stillThere == null) emptyList() else messages,
                selectedUids = emptySet(),
                openUid = null
            )
        }
    }

    fun selectFolder(wireName: String): MailboxSelection =
        if (wireName == folderWireName) this
        else copy(
            folderWireName = wireName,
            messages = emptyList(),
            selectedUids = emptySet(),
            openUid = null,
            query = "",
            searching = false
        )

    /** A new message window replaces the rows; UIDs the server no longer lists cannot stay selected. */
    fun withMessages(next: List<MailMessage>): MailboxSelection {
        val live = next.mapTo(HashSet()) { it.uid }
        return copy(
            messages = next,
            selectedUids = selectedUids.filterTo(LinkedHashSet()) { it in live },
            openUid = openUid?.takeIf { it in live }
        )
    }

    fun toggle(uid: Long): MailboxSelection =
        copy(
            selectedUids = if (uid in selectedUids) selectedUids - uid else selectedUids + uid
        )

    fun selectAll(): MailboxSelection =
        copy(selectedUids = if (allSelected) emptySet() else messages.map { it.uid }.toSet())

    fun clearSelection(): MailboxSelection = copy(selectedUids = emptySet())

    fun open(uid: Long): MailboxSelection = copy(openUid = uid)

    fun closed(): MailboxSelection = copy(openUid = null)

    fun typedQuery(text: String): MailboxSelection = copy(query = text)

    val currentFolder: MailFolder? get() = folders.firstOrNull { it.wireName == folderWireName }

    /** The role of the open folder, so a Trash listing can be labelled differently from an Inbox. */
    val currentRole: MailFolderRole
        get() = currentFolder?.let { MailFolderNames.roleOf(it.wireName, it.name, it.flags) }
            ?: MailFolderRole.INBOX

    val selectedCount: Int get() = selectedUids.size
    val hasSelection: Boolean get() = selectedUids.isNotEmpty()
    val allSelected: Boolean get() = messages.isNotEmpty() && selectedUids.size == messages.size
    val selectedMessages: List<MailMessage>
        get() = messages.filter { it.uid in selectedUids }.sortedByDescending { it.receivedAt }

    /** The rows a move may target: not the folder they are in, and not a `\Noselect` container. */
    val moveTargets: List<MailFolder> get() = folders.filter { it.wireName != folderWireName }

    val canMove: Boolean get() = hasSelection && moveTargets.isNotEmpty()

    /** A search is only ever a search while a query is in force — it decides which header shows. */
    val isSearching: Boolean get() = searching && query.isNotBlank()

    val unseenCount: Int get() = messages.count { !it.seen }

    companion object {
        /** Rows one pass asks for — [com.securevault.pro.data.email.repository.MailboxService.DEFAULT_LIST_SIZE]'s UI twin. */
        const val FIRST_WINDOW = 50
        const val MORE_WINDOW = 50
        const val MAX_WINDOW = 500
    }
}

/** Which of the two servers a manual form is editing, so one row of fields can serve both. */
enum class EndpointSide { INCOMING, OUTGOING }

/** The add-account path the owner chose; the form looks nothing like the other two. */
enum class SetupMode { CHOOSING, GOOGLE, MANUAL }

/**
 * The manual setup form, before anything is stored.
 *
 * [password] is the one field in the whole app that exists only in memory: the form holds it while
 * the owner types, hands it to `validate(...)` as `typedSecret` and to `save(...)` as
 * `plaintextSecret`, and the store seals it into the vault envelope. It is never written into a
 * column, never formatted into a log line and never part of a failure sentence — the seal guard in
 * `MailCredentialSealingTest` pins that a row which contains the typed secret verbatim is refused.
 *
 * A template the address suggested can be overruled by hand: [applyTemplate] fills both endpoints and
 * the owner may then change any half of them, which is exactly the case a cPanel or self-hosted box is.
 */
data class MailSetupDraft(
    val mode: SetupMode = SetupMode.CHOOSING,
    val address: String = "",
    val label: String = "",
    val username: String = "",
    val password: String = "",
    val incoming: MailEndpoint = MailEndpoint("", 993, SecurityMode.SSL_TLS),
    val outgoing: MailEndpoint = MailEndpoint("", 465, SecurityMode.SSL_TLS),
    val preset: ProviderPreset = ProviderPreset.GENERIC,
    val providerNote: String = "",
    val suggestion: String = ""
) {
    val config: MailServerConfig get() = MailServerConfig(incoming, outgoing)
    val loginName: String get() = username.ifBlank { address }

    fun applyTemplate(template: ProviderTemplate, note: String): MailSetupDraft = copy(
        incoming = template.config.incoming,
        outgoing = template.config.outgoing,
        preset = template.preset,
        providerNote = note,
        suggestion = "Filled from ${template.label} — change any field if your host differs."
    )

    /** The endpoint pair the preset says, typed straight in; the port and mode come along too. */
    fun setEndpoint(side: EndpointSide, host: String, port: Int, security: SecurityMode): MailSetupDraft =
        when (side) {
            EndpointSide.INCOMING -> copy(
                incoming = MailEndpoint(host.trim(), port, security),
                suggestion = ""
            )
            EndpointSide.OUTGOING -> copy(
                outgoing = MailEndpoint(host.trim(), port, security),
                suggestion = ""
            )
        }

    fun setSecurity(side: EndpointSide, security: SecurityMode): MailSetupDraft =
        setEndpoint(
            side,
            if (side == EndpointSide.INCOMING) incoming.host else outgoing.host,
            if (side == EndpointSide.INCOMING) incoming.port else outgoing.port,
            security
        )

    /**
     * Why this form cannot be tested yet, as sentences the owner can act on.
     *
     * Deliberately stricter than the repository's own `MailServerConfig.validate()`: an empty host is
     * worth naming *before* a 30-second timeout names it for us, and a password is required here
     * because a test with nothing to authenticate is a guaranteed `AUTH` failure.
     */
    fun problems(): List<String> = buildList {
        if (!MailAddressRules.looksLikeAddress(address)) add("Enter the full email address, like you@mail.com.")
        val configProblems = config.validate()
        if (configProblems.isNotEmpty()) addAll(configProblems)
        if (!incoming.port.isUsablePort()) add("Incoming port must be between 1 and 65535.")
        if (!outgoing.port.isUsablePort()) add("Outgoing port must be between 1 and 65535.")
        MailCredentials.validateSecret(password)?.let { add(it) }
    }

    val canTest: Boolean get() = problems().isEmpty()

    /** The row to store once the probe passes — the id is minted by the store's own helper. */
    fun toAccount(id: String): EmailAccount = EmailAccount(
        id = id,
        address = address.trim(),
        label = label.trim(),
        provider = preset,
        authKind = AuthKind.PASSWORD,
        config = config
    )
}

private fun Int.isUsablePort(): Boolean = this in 1..65535

/**
 * One attachment in the outbox, already copied out of the picker's `content://` URI.
 *
 * SAF hands back a URI that only this process may read and only until the process dies, so a draft
 * cannot hold one: the bytes are staged into the mail cache first and [path] is what
 * [com.securevault.pro.data.email.MailDraft] is given.
 */
data class StagedAttachment(
    val name: String,
    val path: String,
    val sizeBytes: Long
)

/** The compose form. [to], [cc] and [bcc] are raw text split at send time, so typing never reorders. */
data class MailComposeDraft(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val attachments: List<StagedAttachment> = emptyList(),
    val inReplyTo: String = "",
    val references: String = ""
) {
    val toList: List<String> get() = MailAddressRules.split(to)
    val ccList: List<String> get() = MailAddressRules.split(cc)
    val bccList: List<String> get() = MailAddressRules.split(bcc)

    /**
     * Why this cannot be sent yet.
     *
     * The Bcc line is refused rather than ignored: [com.securevault.pro.data.email.MailDraft] carries
     * no blind-copy field, so a form that accepted one and dropped it would tell the owner a message
     * went to people it never reached. Refusing with the reason is the honest half of the contract
     * until the builder grows the header.
     */
    fun problems(): List<String> = buildList {
        if (toList.isEmpty()) add("Add at least one recipient in the To line.")
        MailAddressRules.rejected(toList + ccList).let { bad ->
            if (bad.isNotEmpty()) add("These are not valid addresses: ${bad.joinToString(", ")}.")
        }
        if (bccList.isNotEmpty()) {
            add(
                "This build cannot send a blind copy: the message builder has no Bcc channel, so " +
                    "move those addresses to Cc or remove the line."
            )
        }
    }

    val canSend: Boolean get() = problems().isEmpty()

    fun toDraft(fromAddress: String): MailDraft = MailDraft(
        fromAddress = fromAddress,
        to = toList,
        cc = ccList,
        subject = subject.trim(),
        bodyText = body,
        attachmentPaths = attachments.map { it.path }
    )
}

/** Address parsing, in one pure place, because three screens and a test all need the same verdict. */
object MailAddressRules {

    /** A deliberately small grammar: one `@`, no spaces, a dot in the host. Nothing else is claimed. */
    fun looksLikeAddress(value: String): Boolean {
        val trimmed = value.trim()
        val at = trimmed.indexOf('@')
        if (at <= 0 || at != trimmed.lastIndexOf('@') || at == trimmed.length - 1) return false
        if (trimmed.any { it.isWhitespace() }) return false
        val host = trimmed.substring(at + 1)
        return host.contains('.') && host.length >= 4 && host.none { it == '.' && host.startsWith(".") }
    }

    /** Splits a To/Cc line on commas, semicolons and newlines — every way owners paste a list. */
    fun split(text: String): List<String> = text
        .split(',', ';', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** The tokens that are not addresses, so the form can name them instead of failing silently. */
    fun rejected(values: List<String>): List<String> = values.filterNot { looksLikeAddress(it) }

    /** Display names are allowed on the wire; the engine strips them. Here they only gate the UI. */
    fun isUsableList(text: String): Boolean = rejected(split(text)).isEmpty()
}

/** A failure, already reduced to the three lines a screen shows: what, why, what to do. */
data class EmailProblem(
    val headline: String,
    val sentence: String,
    val advice: String,
    val kind: MailFailureKind
) {
    companion object {
        /** The repository's sentence, unchanged, plus the heading its [MailFailureKind] implies. */
        fun of(problem: MailOutcome.Problem): EmailProblem = EmailProblem(
            headline = EmailCopy.headline(problem.kind),
            sentence = problem.message,
            advice = problem.advice,
            kind = problem.kind
        )
    }
}

/** A network operation the screen is waiting for, with the two things that make it escapable. */
data class BusyWork(
    val key: MailOperation,
    val detail: String,
    val secondsLeft: Int,
    val step: MailStep
) {
    /** Past the budget the repository itself gave this step: the escape hatch stops being advice. */
    val isOverRunning: Boolean get() = secondsLeft <= 0

    val label: String get() = "${step.label} — $secondsLeft s left"

    val escape: String
        get() = if (isOverRunning) {
            "The server is slow to answer. Nothing is lost — use Stop and wait, or leave this screen."
        } else {
            "You can stop this at any time with the button below."
        }
}

/** The tracked network operations. One slot each, so a Cancel always has a target. */
enum class MailOperation(val label: String) {
    ACCOUNTS("Reading your mailboxes"),
    VALIDATE("Testing the mail settings"),
    FOLDERS("Reading the folders"),
    LIST("Reading the message list"),
    SEARCH("Searching this folder"),
    OPEN("Opening the message"),
    DOWNLOAD("Saving the attachment"),
    MARK("Marking the message read"),
    FLAG("Updating the star"),
    MOVE("Moving the message"),
    DELETE("Deleting the message"),
    SEND("Sending the message"),
    STAGE("Copying the attachment in"),
    SIGN_IN("Signing in with Google");

    /** The budget the step the operation runs under, so the countdown is the real one. */
    fun step(): MailStep = when (this) {
        VALIDATE -> MailStep.VALIDATE
        FOLDERS -> MailStep.FOLDERS
        LIST -> MailStep.METADATA
        SEARCH -> MailStep.METADATA
        OPEN -> MailStep.OPEN
        DOWNLOAD -> MailStep.DOWNLOAD
        MARK, FLAG, MOVE, DELETE -> MailStep.FLAG
        SEND -> MailStep.SEND
        SIGN_IN -> MailStep.TOKEN
        ACCOUNTS, STAGE -> MailStep.CONNECT
    }
}

/** Every word the mail screens say that is not a server's own sentence. */
object EmailCopy {

    fun headline(kind: MailFailureKind): String = when (kind) {
        MailFailureKind.NETWORK -> "The server could not be reached"
        MailFailureKind.TLS -> "The encrypted connection was refused"
        MailFailureKind.AUTH -> "The server did not accept these credentials"
        MailFailureKind.PROTOCOL -> "The server answered something unexpected"
        MailFailureKind.TIMEOUT -> "The server stopped answering"
        MailFailureKind.CONFIG -> "Something is missing from the setup"
        MailFailureKind.CANCELLED -> "Stopped"
        MailFailureKind.UNKNOWN -> "That mail operation did not finish"
    }

    /** The sentence a result carries — null when it is ready, because there is nothing to say. */
    fun <T> sentenceOf(outcome: MailOutcome<T>): String? =
        (outcome as? MailOutcome.Problem)?.message

    /**
     * Unread/total wording for an account row.
     *
     * "Counts after the first check" instead of `0/0`: a mailbox that has never been listed on this
     * device has no counts, and a zero reads as "no unread mail", which is a different claim.
     */
    fun unreadLabel(unread: Int, total: Int, known: Boolean): String = when {
        !known -> "Counts after the first check"
        unread > 0 -> "$unread unread · $total in folders"
        else -> "No unread · $total in folders"
    }

    /** The timeout sentence the repository produces, spelled here so a test can pin the wording. */
    fun timeoutSentence(step: MailStep): String = MailStepTimeouts.sentence(step)

    fun relativeTime(at: Long, nowMillis: Long): String {
        if (at <= 0L) return "Never"
        val delta = (nowMillis - at).coerceAtLeast(0L)
        return when {
            delta < 60_000L -> "Just now"
            delta < 3_600_000L -> "${delta / 60_000L} min ago"
            delta < 86_400_000L -> "${delta / 3_600_000L} h ago"
            delta < 604_800_000L -> "${delta / 86_400_000L} d ago"
            else -> "on ${MailDate.shortDate(at)}"
        }
    }

    fun accountTitle(row: AccountRow): String = row.displayName

    fun folderLabel(folder: MailFolder): String =
        MailFolderNames.labelFor(folder).ifBlank { folder.role().label() }

    fun providerLabel(preset: ProviderPreset): String = when (preset) {
        ProviderPreset.GMAIL -> "Gmail"
        ProviderPreset.OUTLOOK -> "Outlook"
        ProviderPreset.YAHOO -> "Yahoo"
        ProviderPreset.TITAN -> "Titan"
        ProviderPreset.ICLOUD -> "iCloud"
        ProviderPreset.GENERIC -> "IMAP host"
        ProviderPreset.CUSTOM -> "Custom host"
    }

    fun securityLabel(mode: SecurityMode): String =
        if (mode == SecurityMode.SSL_TLS) "SSL/TLS" else "STARTTLS"

    fun modeLabel(mode: SecurityMode): String = securityLabel(mode)

    fun bodyNotice(truncated: Boolean, wasHtml: Boolean): String = when {
        truncated && wasHtml ->
            "The HTML source was too large to fetch whole; this is the readable part of it."
        truncated -> "This message is longer than the reader's limit; the rest stays on the server."
        wasHtml -> "Read from the message's HTML part, with the markup stripped."
        else -> ""
    }

    /** The one line that tells the owner nothing was destroyed by accident. */
    fun deleteWarning(folder: String): String =
        "This deletes the selected message on the server, in $folder, and this app keeps no copy " +
            "of it. Nothing else is removed and no message is ever deleted by itself."

    fun moveSentence(from: String, to: String, count: Int): String =
        "Moved $count message${if (count == 1) "" else "s"} from $from to $to."

    /** The star caveat the protocol layer imposes, said once on the row rather than hidden. */
    const val STAR_NOTE: String =
        "A star can always be added. Clearing one rewrites the flag set this app knows about, so a " +
            "message whose other flags the app never read may keep one."

    private fun MailFolder.role(): MailFolderRole = MailFolderNames.roleOf(wireName, name, flags)
}

/** The timeout sentence, reached without a socket so a test can assert the exact words. */
private object MailStepTimeouts {
    fun sentence(step: MailStep): String =
        "${step.label} stopped after ${step.seconds} seconds because the server did not answer. " +
            "Nothing was lost; try again, or check the network."
}

/** A date formatter with no Compose and no Android, so [EmailCopy.relativeTime] is testable. */
private object MailDate {
    fun shortDate(millis: Long): String =
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
