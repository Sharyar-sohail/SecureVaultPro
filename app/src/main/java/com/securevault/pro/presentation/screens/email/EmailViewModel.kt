package com.securevault.pro.presentation.screens.email

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.email.MailFolder
import com.securevault.pro.data.email.MailMessage
import com.securevault.pro.data.email.ProviderPreset
import com.securevault.pro.data.email.SecurityMode
import com.securevault.pro.data.email.repository.GmailTokenProvider
import com.securevault.pro.data.email.repository.MailAccounts
import com.securevault.pro.data.email.repository.MailFolderNames
import com.securevault.pro.data.email.repository.MailThreading
import com.securevault.pro.data.email.repository.MailOutcome
import com.securevault.pro.data.email.repository.MailStep
import com.securevault.pro.data.email.repository.MailboxService
import com.securevault.pro.data.email.repository.MailAccountStore
import com.securevault.pro.data.email.repository.MailOpenedMessage
import com.securevault.pro.data.email.repository.MailPartHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.Date
import javax.inject.Inject

/**
 * The mail section's only Android-aware class.
 *
 * Sockets, TLS and the sealed credential all live behind [MailboxService] and [MailAccountStore];
 * this holds the immutable screen snapshot from [EmailLogic] and nothing else. Every network call is
 * wrapped in [track], which owns the countdown the screen shows and guarantees the busy flag is
 * cleared even when a socket dies mid-command.
 */
@HiltViewModel
class EmailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: MailboxService,
    private val store: MailAccountStore,
    private val gmail: GmailTokenProvider
) : ViewModel() {

    data class UiState(
        val view: EmailView = EmailView.ACCOUNTS,
        val rows: List<AccountRow> = emptyList(),
        val selection: MailboxSelection = MailboxSelection(),
        val folderCounts: Map<String, List<MailFolder>> = emptyMap(),
        val draft: MailSetupDraft = MailSetupDraft(),
        val opened: MailOpenedMessage? = null,
        val compose: MailComposeDraft = MailComposeDraft(),
        val busy: BusyWork? = null,
        val problem: EmailProblem? = null,
        val notice: String? = null,
        val saving: Boolean = false
    ) {
        val openAccount: AccountRow? get() = rows.firstOrNull { it.id == selection.accountId }
        val activeRow: AccountRow? get() = rows.firstOrNull { it.id == selection.accountId }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var work: Job? = null

    /**
     * The generation of the operation that currently owns the busy slot.
     *
     * [work] is cancelled, not awaited, so a superseded operation's `finally` can run *after* the
     * next one has started. Without a token it would cancel the new ticker and clear the new busy
     * state, leaving a long call running with no Stop button. Only the run whose token still
     * matches may retire the slot.
     */
    private var workToken = 0

    init {
        viewModelScope.launch {
            store.observeAccounts().collect { records ->
                _state.update { current ->
                    current.copy(rows = accountRowsOf(records, current.folderCounts, ::timeLabel))
                }
            }
        }
        refreshAccounts()
    }

    // ------------------------------------------------------------------ accounts

    fun refreshAccounts() = track(MailOperation.ACCOUNTS, "Reading the accounts on this device") {
        reloadRows()
    }

    /**
     * The body of a reload, without the busy wrapper.
     *
     * [track] owns one slot and cancels whatever held it, so calling [refreshAccounts] from inside a
     * tracked block would cancel the coroutine that is calling it. Nested work goes through here.
     */
    private suspend fun reloadRows() {
        val records = store.accounts()
        _state.update { it.copy(rows = accountRowsOf(records, it.folderCounts, ::timeLabel)) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Opens the setup screen; [mode] chooses between the two ways an account can be added. */
    fun beginAdd(mode: SetupMode) = _state.update {
        it.copy(view = EmailView.ADD_ACCOUNT, draft = MailSetupDraft(mode = mode), problem = null)
    }

    fun leaveAdd() = _state.update {
        if (it.view == EmailView.ADD_ACCOUNT) it.copy(view = EmailView.ACCOUNTS) else it
    }

    fun editAddress(value: String) = _state.update { current ->
        val template = MailAccounts.templateFor(value)
        val next = current.draft.copy(address = value)
        if (template != null) {
            current.copy(
                draft = next.applyTemplate(template, template.note),
                // A suggestion is only a sentence; it never overwrites what the owner typed.
                notice = null
            )
        } else {
            current.copy(draft = next)
        }
    }

    fun editLabel(value: String) = _state.update { it.copy(draft = it.draft.copy(label = value)) }

    fun editUsername(value: String) = _state.update { it.copy(draft = it.draft.copy(username = value)) }

    fun editPassword(value: String) = _state.update { it.copy(draft = it.draft.copy(password = value)) }

    fun editEndpoint(side: EndpointSide, host: String) = _state.update { current ->
        val endpoint = if (side == EndpointSide.INCOMING) current.draft.incoming else current.draft.outgoing
        current.copy(draft = current.draft.setEndpoint(side, host, endpoint.port, endpoint.security))
    }

    fun editPort(side: EndpointSide, port: Int) = _state.update { current ->
        val endpoint = if (side == EndpointSide.INCOMING) current.draft.incoming else current.draft.outgoing
        current.copy(draft = current.draft.setEndpoint(side, endpoint.host, port, endpoint.security))
    }

    fun editSecurity(side: EndpointSide, security: SecurityMode) = _state.update { current ->
        current.copy(draft = current.draft.setSecurity(side, security))
    }

    fun editPreset(preset: ProviderPreset) = _state.update { current ->
        current.copy(draft = current.draft.copy(preset = preset))
    }

    fun useTemplate() = _state.update { current ->
        val template = MailAccounts.templateFor(current.draft.address)
        if (template == null) {
            current.copy(problem = EmailProblem(
                headline = "No template for that address",
                sentence = "This host is not one of the presets, so the servers have to be typed in.",
                advice = "Ask your provider for its IMAP and SMTP hosts, then fill the four fields below.",
                kind = com.securevault.pro.data.email.repository.MailFailureKind.CONFIG
            ))
        } else {
            current.copy(draft = current.draft.applyTemplate(template, template.note))
        }
    }

    /** Test then save. Nothing is written until both servers have answered. */
    fun testAndSave() = track(MailOperation.VALIDATE, "Testing the mail settings") {
        val draft = _state.value.draft
        val problems = draft.problems()
        if (problems.isNotEmpty()) {
            _state.update { it.copy(problem = EmailProblem(
                headline = "Finish the setup first",
                sentence = problems.first(),
                advice = problems.drop(1).joinToString(" ").ifBlank { "Correct the field and test again." },
                kind = com.securevault.pro.data.email.repository.MailFailureKind.CONFIG
            )) }
            return@track
        }
        val probe = store.findByAddress(draft.address.trim())
        // Re-using the existing id is what makes "replace" true: minting a new one would leave two
        // rows for the same address, one of them pointing at stale endpoints.
        val account = draft.toAccount(probe?.id ?: MailAccounts.newId())
        val record = com.securevault.pro.data.email.repository.MailAccountRecord(
            account = account,
            username = draft.loginName,
            credentialSealed = ""
        )
        val outcome = service.validate(record, draft.password)
        when (outcome) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                if (!outcome.value.ok) {
                    _state.update {
                        it.copy(problem = EmailProblem(
                            headline = "One of the servers refused",
                            sentence = listOf(outcome.value.incomingReport, outcome.value.outgoingReport)
                                .filter { report -> report.isNotBlank() }
                                .joinToString(" "),
                            advice = "Check the host, port and password for the half named above, then test again.",
                            kind = com.securevault.pro.data.email.repository.MailFailureKind.AUTH
                        ))
                    }
                    return@track
                }
                val saved = store.save(account, draft.loginName, draft.password)
                when (saved) {
                    is com.securevault.pro.data.email.repository.MailSaveResult.Rejected ->
                        _state.update { it.copy(problem = EmailProblem(
                            headline = "The account was not saved",
                            sentence = saved.message,
                            advice = "Nothing was changed. Try again once the vault is unlocked.",
                            kind = com.securevault.pro.data.email.repository.MailFailureKind.UNKNOWN
                        )) }
                    is com.securevault.pro.data.email.repository.MailSaveResult.Saved -> {
                        val already = probe != null
                        _state.update {
                            it.copy(
                                view = EmailView.ACCOUNTS,
                                draft = MailSetupDraft(),
                                saving = false,
                                notice = if (already) "That address was already set up; this entry replaced it."
                                else "Added ${account.address}. Open it to read the mail."
                            )
                        }
                        reloadRows()
                    }
                }
            }
        }
    }

    /** The picker's result. Only Google's own cancellation code reads as "you walked away". */
    fun completeGoogleSignIn(result: Intent?) = track(MailOperation.SIGN_IN, "Signing in with Google") {
        when (val outcome = gmail.completeSignIn(result)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                val account = MailAccounts.googleAccount(outcome.value)
                when (val saved = store.save(account, account.address, "")) {
                    is com.securevault.pro.data.email.repository.MailSaveResult.Rejected ->
                        _state.update { it.copy(problem = EmailProblem(
                            headline = "The Gmail account was not saved",
                            sentence = saved.message,
                            advice = "Nothing was changed. Try again once the vault is unlocked.",
                            kind = com.securevault.pro.data.email.repository.MailFailureKind.UNKNOWN
                        )) }
                    is com.securevault.pro.data.email.repository.MailSaveResult.Saved -> {
                        _state.update {
                            it.copy(
                                view = EmailView.ACCOUNTS,
                                draft = MailSetupDraft(),
                                notice = "Signed in as ${account.address}."
                            )
                        }
                        reloadRows()
                    }
                }
            }
        }
    }

    fun signInIntent(): Intent = gmail.signInIntent()

    fun googleUnavailable(): String? = gmail.availabilityFault()?.let(gmail::availabilityMessage)

    fun googleLastEmail(): String? = gmail.lastSignedInEmail()

    fun remove(id: String) = track(MailOperation.ACCOUNTS, "Removing the account") {
        store.remove(id)
        _state.update { current ->
            current.copy(
                view = if (current.selection.accountId == id) EmailView.ACCOUNTS else current.view,
                selection = if (current.selection.accountId == id) MailboxSelection() else current.selection,
                notice = "Removed. The sealed password was wiped with it."
            )
        }
        reloadRows()
    }

    fun toggleAutoSync(id: String) = track(MailOperation.ACCOUNTS, "Updating the account") {
        val row = _state.value.rows.firstOrNull { it.id == id } ?: return@track
        store.setAutoSync(id, !row.autoSync)
        reloadRows()
    }

    // ------------------------------------------------------------------ mailbox

    fun openAccount(id: String) = track(MailOperation.FOLDERS, "Reading the folders") {
        _state.update {
            it.copy(
                view = EmailView.MAILBOX,
                selection = it.selection.forAccount(id),
                opened = null,
                problem = null
            )
        }
        loadFolders(id)
        val selection = _state.value.selection
        if (selection.folderWireName.isNotBlank()) loadMessages()
    }

    fun leaveMailbox() = _state.update {
        it.copy(view = EmailView.ACCOUNTS, selection = MailboxSelection(), opened = null, compose = MailComposeDraft())
    }

    fun selectFolder(wireName: String) = track(MailOperation.LIST, "Opening this folder") {
        _state.update { it.copy(selection = it.selection.selectFolder(wireName), opened = null) }
        loadMessages()
    }

    /**
     * Re-reads the folders and then the rows.
     *
     * A search in force is re-run rather than replaced by the whole folder: the header says
     * "Results for …", so showing an unfiltered list under it would be a different claim than the
     * one the screen is making.
     */
    fun refreshFolder() = track(MailOperation.LIST, "Refreshing this folder") {
        loadFolders(_state.value.selection.accountId)
        if (_state.value.selection.isSearching) searchNow() else loadMessages()
    }

    fun loadMore() = track(MailOperation.LIST, "Loading older messages") {
        val current = _state.value.selection
        if (current.limit >= MailboxSelection.MAX_WINDOW) return@track
        _state.update { it.copy(selection = current.loadWider()) }
        // A widened window during a search must widen the search, not swap in the whole folder.
        if (_state.value.selection.isSearching) searchNow() else loadMessages()
    }

    fun toggleSelection(uid: Long) = _state.update { it.copy(selection = it.selection.toggle(uid)) }

    fun selectAll() = _state.update { it.copy(selection = it.selection.selectAll()) }

    fun clearSelection() = _state.update { it.copy(selection = it.selection.clearSelection()) }

    fun search(query: String) = _state.update { it.copy(selection = it.selection.typedQuery(query)) }

    fun runSearch() = track(MailOperation.SEARCH, "Searching this folder") {
        searchNow()
    }

    fun clearSearch() = track(MailOperation.LIST, "Leaving the search") {
        _state.update { it.copy(selection = it.selection.copy(query = "", searching = false)) }
        loadMessages()
    }

    /** The search body, untracked so a tracked caller can re-run it without cancelling itself. */
    private suspend fun searchNow() {
        val selection = _state.value.selection
        if (selection.query.isBlank() || selection.folderWireName.isBlank()) return
        val record = recordFor(selection.accountId) ?: return
        when (val outcome = service.searchMessages(record, selection.folderWireName, selection.query, selection.limit)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> _state.update {
                it.copy(selection = it.selection.copy(searching = true).withMessages(outcome.value))
            }
        }
    }

    fun openMessage(uid: Long) = track(MailOperation.OPEN, "Opening the message") {
        val selection = _state.value.selection
        val record = recordFor(selection.accountId) ?: return@track
        when (val outcome = service.readMessage(record, selection.folderWireName, uid)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                _state.update {
                    it.copy(
                        view = EmailView.MESSAGE,
                        opened = outcome.value,
                        selection = it.selection.open(uid)
                    )
                }
                if (!outcome.value.message.seen) {
                    // `\Seen` is written only when this succeeds; until then the row stays bold,
                    // which is the honest state. A refused flag write is not worth a dialog over a
                    // message the owner is already reading, so the row simply stays unread.
                    val marked = service.markSeen(record, selection.folderWireName, listOf(uid))
                    if (marked is MailOutcome.Ready && marked.value) {
                        _state.update { current ->
                            current.copy(
                                opened = current.opened?.let { open ->
                                    if (open.message.uid == uid) open.copy(message = open.message.copy(seen = true))
                                    else open
                                },
                                selection = current.selection.withMessages(
                                    current.selection.messages.map {
                                        if (it.uid == uid) it.copy(seen = true) else it
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun closeMessage() = _state.update {
        it.copy(view = EmailView.MAILBOX, opened = null, selection = it.selection.closed())
    }

    fun toggleStar(message: MailMessage) = track(MailOperation.FLAG, "Updating the star") {
        val selection = _state.value.selection
        val record = recordFor(selection.accountId) ?: return@track
        val flags = if (message.flagged) listOf("\\Seen", "\\Flagged") else listOf("\\Seen")
        when (val outcome = service.setFlagged(record, selection.folderWireName, message.uid, !message.flagged, flags)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                _state.update { current ->
                    val replaced = current.selection.messages.map {
                        if (it.uid == message.uid) it.copy(flagged = !message.flagged) else it
                    }
                    current.copy(
                        selection = current.selection.withMessages(replaced),
                        opened = current.opened?.let {
                            if (it.message.uid == message.uid) it.copy(message = it.message.copy(flagged = !message.flagged)) else it
                        }
                    )
                }
                _state.update { it.copy(notice = EmailCopy.STAR_NOTE) }
            }
        }
    }

    fun moveSelected(toWireName: String) = track(MailOperation.MOVE, "Moving the messages") {
        val selection = _state.value.selection
        val record = recordFor(selection.accountId) ?: return@track
        val uids = selection.selectedUids.toList()
        if (uids.isEmpty()) return@track
        when (val outcome = service.moveMessages(record, selection.folderWireName, toWireName, uids)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                val from = selection.currentFolder?.let { EmailCopy.folderLabel(it) }.orEmpty()
                val to = selection.folders.firstOrNull { it.wireName == toWireName }?.let { EmailCopy.folderLabel(it) }.orEmpty()
                _state.update {
                    it.copy(
                        selection = it.selection.clearSelection(),
                        notice = EmailCopy.moveSentence(from, to, uids.size)
                    )
                }
                loadFolders(selection.accountId)
                loadMessages()
            }
        }
    }

    fun deleteSelected() = track(MailOperation.DELETE, "Deleting the messages") {
        val selection = _state.value.selection
        val record = recordFor(selection.accountId) ?: return@track
        val uids = selection.selectedUids.toList()
        if (uids.isEmpty()) return@track
        when (val outcome = service.deleteMessages(record, selection.folderWireName, uids)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                _state.update { it.copy(selection = it.selection.clearSelection(), notice = "Deleted on the server.") }
                loadFolders(selection.accountId)
                loadMessages()
            }
        }
    }

    fun downloadAttachment(handle: MailPartHandle) = track(MailOperation.DOWNLOAD, "Saving ${handle.fileName}") {
        when (val outcome = service.downloadPart(handle)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                val open = _state.value.opened ?: return@track
                val parts = open.parts.map {
                    if (it.section == handle.section) it.copy(attachment = outcome.value) else it
                }
                _state.update {
                    it.copy(opened = open.copy(parts = parts), notice = "Saved to the vault's cache.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ compose

    fun composeNew() = _state.update {
        it.copy(
            view = EmailView.COMPOSE,
            compose = MailComposeDraft(),
            problem = null
        )
    }

    fun replyTo(message: MailMessage) = _state.update {
        it.copy(
            view = EmailView.COMPOSE,
            compose = MailComposeDraft(
                to = message.fromAddress,
                subject = if (message.subject.lowercase().startsWith("re:")) message.subject else "Re: ${message.subject}",
                inReplyTo = message.messageId,
                references = message.messageId
            ),
            problem = null
        )
    }

    fun leaveCompose() = _state.update { it.copy(view = EmailView.MAILBOX, compose = MailComposeDraft()) }

    fun editTo(value: String) = _state.update { it.copy(compose = it.compose.copy(to = value)) }

    fun editCc(value: String) = _state.update { it.copy(compose = it.compose.copy(cc = value)) }

    fun editBcc(value: String) = _state.update { it.copy(compose = it.compose.copy(bcc = value)) }

    fun editSubject(value: String) = _state.update { it.copy(compose = it.compose.copy(subject = value)) }

    fun editBody(value: String) = _state.update { it.copy(compose = it.compose.copy(body = value)) }

    fun removeAttachment(path: String) = _state.update { current ->
        current.copy(compose = current.compose.copy(attachments = current.compose.attachments.filterNot { it.path == path }))
    }

    /** Copies a picked document into the mail cache; the SAF URI does not outlive this process. */
    fun stageAttachment(uri: Uri) = track(MailOperation.STAGE, "Copying the attachment in") {
        val staged = withContext(Dispatchers.IO) { copyIntoCache(uri) }
        if (staged == null) {
            _state.update { it.copy(problem = EmailProblem(
                headline = "That file could not be read",
                sentence = "The picker returned a document this app has no permission to open.",
                advice = "Pick the file again; if it keeps failing, copy it out of the other app first.",
                kind = com.securevault.pro.data.email.repository.MailFailureKind.UNKNOWN
            )) }
            return@track
        }
        _state.update { it.copy(compose = it.compose.copy(attachments = it.compose.attachments + staged)) }
    }

    fun send() = track(MailOperation.SEND, "Sending the message") {
        val current = _state.value
        val row = current.openAccount ?: _state.value.rows.firstOrNull()
        val record = row?.let { recordFor(it.id) }
        if (record == null) {
            _state.update { it.copy(problem = EmailProblem(
                headline = "No account to send from",
                sentence = "Open a mailbox before composing.",
                advice = "Go back to the account list and open the account.",
                kind = com.securevault.pro.data.email.repository.MailFailureKind.CONFIG
            )) }
            return@track
        }
        val problems = current.compose.problems()
        if (problems.isNotEmpty()) {
            _state.update { it.copy(problem = EmailProblem(
                headline = "The message is not ready",
                sentence = problems.first(),
                advice = problems.drop(1).joinToString(" ").ifBlank { "Correct the line named above." },
                kind = com.securevault.pro.data.email.repository.MailFailureKind.CONFIG
            )) }
            return@track
        }
        val draft = current.compose.toDraft(record.address)
        // A reply carries the original Message-ID so every client threads it under the message it
        // answers, not as a fresh conversation. `MailDraft` has no field for it, so the threading
        // is passed beside the draft rather than silently dropped.
        val threading = if (current.compose.inReplyTo.isNotBlank()) {
            MailThreading.replyTo(current.compose.inReplyTo, current.compose.references)
        } else {
            MailThreading()
        }
        when (val outcome = service.send(record, draft, threading)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> {
                _state.update {
                    it.copy(
                        view = EmailView.MAILBOX,
                        compose = MailComposeDraft(),
                        notice = outcome.value.message
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ internals

    /** Cancels the network operation on the screen's Stop button. */
    fun stopBusy() {
        work?.cancel()
        work = null
        ticker?.cancel()
        ticker = null
        workToken++
        _state.update {
            it.copy(
                busy = null,
                // A read that is stopped changes nothing; a send or a delete may already have
                // reached the server, so the sentence never claims otherwise.
                notice = "Stopped. Nothing more is sent; refresh the folder to see what the server kept."
            )
        }
    }

    private fun track(operation: MailOperation, detail: String, block: suspend () -> Unit) {
        work?.cancel()
        ticker?.cancel()
        val token = ++workToken
        val step = operation.step()
        _state.update { it.copy(busy = BusyWork(operation, detail, step.seconds, step)) }
        ticker = viewModelScope.launch {
            var left = step.seconds
            while (left > 0 && token == workToken) {
                delay(1_000)
                left -= 1
                if (token != workToken) return@launch
                _state.update { current -> current.copy(busy = current.busy?.copy(secondsLeft = left)) }
            }
        }
        work = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Stop, or a newer operation taking the slot. Not a failure and not the owner's
                // business: rethrowing keeps the coroutine cancelled instead of turning it into a
                // dialog saying the server refused something.
                throw cancelled
            } catch (t: Throwable) {
                if (token == workToken) {
                    _state.update { it.copy(problem = EmailProblem(
                        headline = EmailCopy.headline(com.securevault.pro.data.email.repository.MailFailureKind.UNKNOWN),
                        sentence = t.message ?: t.javaClass.simpleName,
                        advice = "Nothing was lost. Try again, or open a different account.",
                        kind = com.securevault.pro.data.email.repository.MailFailureKind.UNKNOWN
                    )) }
                }
            } finally {
                // A superseded run must not clear the slot its successor now owns.
                if (token == workToken) {
                    ticker?.cancel()
                    ticker = null
                    _state.update { it.copy(busy = null) }
                }
            }
        }
    }

    private suspend fun loadFolders(accountId: String) {
        val record = recordFor(accountId) ?: return
        when (val outcome = service.listFolders(record)) {
            is MailOutcome.Problem -> {
                _state.update { it.copy(problem = EmailProblem.of(outcome)) }
                store.recordError(accountId, outcome.message)
            }
            is MailOutcome.Ready -> {
                val folders = outcome.value.sortedWith(MailFolderNames.comparator())
                _state.update { current ->
                    current.copy(
                        selection = current.selection.withFolders(folders),
                        folderCounts = current.folderCounts + (accountId to folders)
                    )
                }
                store.markSynced(accountId, System.currentTimeMillis(), "")
            }
        }
    }

    private suspend fun loadMessages() {
        val selection = _state.value.selection
        if (selection.folderWireName.isBlank()) return
        val record = recordFor(selection.accountId) ?: return
        when (val outcome = service.listMessages(record, selection.folderWireName, selection.limit)) {
            is MailOutcome.Problem -> _state.update { it.copy(problem = EmailProblem.of(outcome)) }
            is MailOutcome.Ready -> _state.update {
                it.copy(selection = it.selection.withMessages(outcome.value))
            }
        }
    }

    private suspend fun recordFor(id: String) =
        _state.value.rows.firstOrNull { it.id == id }?.record ?: store.find(id)

    private fun copyIntoCache(uri: Uri): StagedAttachment? = runCatching {
        val name = displayNameOf(uri) ?: "attachment"
        val target = java.io.File(service.cacheDirectory(), "out-${System.currentTimeMillis()}-$name")
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        StagedAttachment(name = name, path = target.absolutePath, sizeBytes = target.length())
    }.getOrNull()

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun timeLabel(at: Long): String =
        if (at <= 0L) "Never checked on this device"
        else "Checked " + EmailCopy.relativeTime(at, Date().time).lowercase()
}
