package com.securevault.pro.presentation.screens.passkey

import android.app.Activity
import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.security.passkey.PasskeyAvailability
import com.securevault.pro.security.passkey.PasskeyDescription
import com.securevault.pro.security.passkey.PasskeyDomainGroup
import com.securevault.pro.security.passkey.PasskeyDomainRules
import com.securevault.pro.security.passkey.PasskeyEnrolmentResult
import com.securevault.pro.security.passkey.PasskeyHandoff
import com.securevault.pro.security.passkey.PasskeyRecord
import com.securevault.pro.security.passkey.PasskeyRecordCodec
import com.securevault.pro.security.passkey.PasskeyService
import com.securevault.pro.security.passkey.PasskeySignInResult
import com.securevault.pro.security.passkey.PasskeyTransportState
import com.securevault.pro.security.passkey.passkeyRefusalSentence
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which of the section's own screens is showing. The section owns this navigation, not the app. */
enum class PasskeyView { LIST, ENROL, DETAIL }

/**
 * One immutable snapshot of the passkey section.
 *
 * [ceremonySecondsLeft] is the visible countdown the project's pre-login rule asks of anything that
 * can block: a passkey sheet is the provider's UI, not ours, so a person who walks away must see the
 * app give up and hand the screen back rather than sit on a spinner forever.
 */
data class PasskeyUiState(
    val view: PasskeyView = PasskeyView.LIST,
    val loading: Boolean = true,
    val groups: List<PasskeyDomainGroup> = emptyList(),
    val recordCount: Int = 0,
    val availability: PasskeyAvailability? = null,
    val domainDraft: String = "",
    val accountDraft: String = "",
    val selected: PasskeyDescription? = null,
    val pendingDelete: PasskeyDescription? = null,
    val busy: Boolean = false,
    val ceremonySecondsLeft: Int = 0,
    val message: String? = null,
    val lastAssertion: String? = null,
    /**
     * The site a browser's passkey screen asked this app for, when that ask arrived on this visit.
     * Empty when nothing was asked. Kept separate from [domainDraft] so the owner can see which
     * request is *not* theirs.
     */
    val askedForDomain: String = "",
    /** True when this app holds a passkey record for [askedForDomain], so the card can say so. */
    val askedForHasPasskey: Boolean = false
) {
    val canEnrol: Boolean
        get() = availability?.canEnrol == true && !busy

    val canAuthenticate: Boolean
        get() = availability?.canAuthenticate == true && !busy

    val isTransportBlocked: Boolean
        get() = availability?.isTransportBlockedByPlatform == true

    val isEmptyList: Boolean get() = !loading && recordCount == 0 && view == PasskeyView.LIST

    /** A draft the app would actually accept, so the button can say why it is disabled. */
    val draftIsUsable: Boolean get() = PasskeyDomainRules.looksLikeAHost(domainDraft)
}

@HiltViewModel
class PasskeyViewModel @Inject constructor(
    private val service: PasskeyService
) : ViewModel() {

    private val _state = MutableStateFlow(PasskeyUiState())
    val state: StateFlow<PasskeyUiState> = _state.asStateFlow()

    private var ceremonyJob: Job? = null
    private var countdownJob: Job? = null

    init {
        refresh()
    }

    /** Re-reads the store and the device verdict. Cheap: a listing needs no decryption. */
    fun refresh() {
        viewModelScope.launch {
            val availability = runCatching { service.availability() }.getOrNull()
            val read = runCatching { service.records() }
            // A read that failed is not an empty list: saying "no passkeys" over a failed read
            // would be a claim about the owner's data that this app cannot make.
            if (read.isFailure) {
                _state.update {
                    it.copy(
                        loading = false,
                        availability = availability,
                        groups = emptyList(),
                        recordCount = 0,
                        message = RECORDS_UNREADABLE_MESSAGE
                    )
                }
                return@launch
            }
            val records = read.getOrDefault(emptyList())
            _state.update { current ->
                current.copy(
                    loading = false,
                    availability = availability,
                    groups = PasskeyDomainRules.groupByDomain(records),
                    recordCount = records.size,
                    // Whether the site the browser asked for is one this app can actually answer —
                    // recomputed on every read, because a record may have been added or forgotten
                    // since the ask arrived and the card must not claim otherwise.
                    askedForHasPasskey = current.askedForDomain.isNotBlank() && records.any {
                        it.rpDomain == current.askedForDomain
                    },
                    // The detail screen was opened from a snapshot; keep its counters honest after
                    // a ceremony without making the owner go back and open it again.
                    selected = current.selected?.let { shown ->
                        records.firstOrNull { it.id == shown.id }?.let(PasskeyRecordCodec::describe)
                    } ?: current.selected
                )
            }
        }
    }

    /**
     * Tells the screen which site a browser's passkey screen asked this app about, or clears the note
     * when nothing was asked. Called by the section with what [PasskeyHandoff] delivered.
     */
    fun noteHandoff(domain: String?) {
        val normalized = PasskeyDomainRules.normalize(domain.orEmpty())
        _state.update { it.copy(askedForDomain = normalized, askedForHasPasskey = false) }
        if (normalized.isNotBlank()) refresh()
    }

    /** The owner says "not now": the note goes away and nothing else about the visit changes. */
    fun dismissAskedDomain() {
        PasskeyHandoff.clearDelivered()
        _state.update { it.copy(askedForDomain = "", askedForHasPasskey = false) }
    }

    /** Opens the enrolment form pre-filled with the site the browser named, for a first-time add. */
    fun enrolForAskedDomain() {
        val domain = _state.value.askedForDomain
        if (domain.isBlank()) return
        _state.update {
            it.copy(view = PasskeyView.ENROL, domainDraft = domain, message = null)
        }
    }

    /**
     * Signs in to the site the browser named, using whatever passkey this app holds for it.
     *
     * The browser that asked is not the browser that gets the answer: this app has no way to hand an
     * answer back to a site it did not start the request for, and it never claimed otherwise. What
     * this does prove, on a phone whose passkey service answers, is that the saved key opens and
     * works — which is the half the owner can check with their own face.
     */
    fun signInForAskedDomain(activity: Activity?) {
        val domain = _state.value.askedForDomain
        if (domain.isBlank()) return
        if (activity == null) {
            _state.update { it.copy(message = NO_ACTIVITY_MESSAGE) }
            return
        }
        beginCeremony(PasskeyService.SIGN_IN_WATCHDOG_MS) {
            val result = service.authenticate(activity, domain)
            _state.update { it.copy(lastAssertion = result.ownerSentence()) }
            if (result is PasskeySignInResult.SignedIn) refresh()
        }
    }

    fun openEnrolment() {
        _state.update {
            it.copy(view = PasskeyView.ENROL, domainDraft = it.domainDraft, message = null)
        }
    }

    fun openDetail(description: PasskeyDescription) {
        _state.update {
            it.copy(
                view = PasskeyView.DETAIL,
                selected = description,
                lastAssertion = null,
                message = null
            )
        }
    }

    /** Back out of whatever the section is showing, without leaving the app. */
    fun navigateBack() {
        _state.update {
            it.copy(view = PasskeyView.LIST, selected = null, message = null, lastAssertion = null)
        }
        refresh()
    }

    fun setDomainDraft(value: String) {
        _state.update { it.copy(domainDraft = value, message = null) }
    }

    fun setAccountDraft(value: String) {
        _state.update { it.copy(accountDraft = value) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * Enrols a passkey for the draft domain. [activity] must be real: the credential provider shows a
     * sheet, and a sheet needs a window. When the caller has no Activity the section says so instead of
     * starting a ceremony that cannot run.
     */
    fun enrol(activity: Activity?) {
        val snapshot = _state.value
        if (activity == null) {
            _state.update { it.copy(message = NO_ACTIVITY_MESSAGE) }
            return
        }
        val domain = PasskeyDomainRules.normalize(snapshot.domainDraft)
        if (!PasskeyDomainRules.isRegistrable(domain)) {
            _state.update {
                it.copy(message = "Enter the site's host to enrol against, like example.com.")
            }
            return
        }
        if (snapshot.availability?.canEnrol != true) {
            _state.update { it.copy(message = refusalSentence(snapshot.availability)) }
            return
        }
        beginCeremony(PasskeyService.ENROLMENT_WATCHDOG_MS) {
            val result = service.enrol(
                context = activity,
                rpDomain = domain,
                rpName = PasskeyDomainRules.normalize(domain),
                userName = snapshot.accountDraft
            )
            val sentence = result.ownerSentence()
            val enrolled = result is PasskeyEnrolmentResult.Enrolled
            _state.update {
                it.copy(
                    message = sentence,
                    view = if (enrolled) PasskeyView.LIST else it.view,
                    domainDraft = if (enrolled) "" else it.domainDraft,
                    accountDraft = if (enrolled) "" else it.accountDraft
                )
            }
            // The list is drawn from the store, so without this the key just created would be
            // missing from it until the section was reopened.
            if (enrolled) refresh()
        }
    }

    /** Runs a sign-in ceremony for the selected record's domain. */
    fun authenticate(activity: Activity?) {
        val record = selectedRecord() ?: _state.value.groups.firstOrNull()?.records?.firstOrNull()
        if (record == null) {
            _state.update { it.copy(message = "Nothing is selected to sign in with.") }
            return
        }
        if (activity == null) {
            _state.update { it.copy(message = NO_ACTIVITY_MESSAGE) }
            return
        }
        beginCeremony(PasskeyService.SIGN_IN_WATCHDOG_MS) {
            val result = service.authenticate(activity, record.rpDomain, record.credentialId)
            _state.update { it.copy(lastAssertion = result.ownerSentence(), message = null) }
            // A successful assertion moves the use counter the detail screen and list both show.
            if (result is com.securevault.pro.security.passkey.PasskeySignInResult.SignedIn) refresh()
        }
    }

    /** Cancels a running ceremony. The owner always has a way out of a sheet that is not answering. */
    fun cancelCeremony() {
        ceremonyJob?.cancel()
        ceremonyJob = null
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(busy = false, ceremonySecondsLeft = 0, message = null) }
        refresh()
    }

    /** Asks to forget a record; the UI must route this through [confirmDelete] before it happens. */
    fun requestDelete(description: PasskeyDescription) {
        _state.update { it.copy(pendingDelete = description, message = null) }
    }

    fun dismissDeleteRequest() {
        _state.update { it.copy(pendingDelete = null) }
    }

    /**
     * The only path that removes anything, and it requires the owner's confirmation twice over: this
     * function exists solely because a confirm dialog pressed "Forget this record", and the service
     * still demands `confirmedByOwner = true`. Nothing scheduled, aged or capped ever reaches it, and
     * the passkey itself is not deleted — the record is.
     */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            val removed = service.forgetRecord(target.id, confirmedByOwner = true)
            _state.update {
                it.copy(
                    pendingDelete = null,
                    view = if (it.selected?.id == target.id) PasskeyView.LIST else it.view,
                    selected = if (it.selected?.id == target.id) null else it.selected,
                    message = if (removed) {
                        "This app forgot its record of that passkey. Your device still holds the key."
                    } else {
                        "Nothing was removed. Unlock the vault and try again."
                    }
                )
            }
            refresh()
        }
    }

    /**
     * The system's own passkey screen, where an owner can delete the key itself — the only removal
     * this app cannot do for them, and the reason that screen is offered rather than mentioned.
     */
    fun openSystemPasskeySettings(activity: Activity?) {
        val pending: PendingIntent? = runCatching { service.settingsPendingIntent() }.getOrNull()
        when {
            pending == null -> _state.update {
                it.copy(message = "This Android version has no passkey settings screen to open.")
            }
            activity == null -> _state.update { it.copy(message = NO_ACTIVITY_MESSAGE) }
            else -> runCatching { pending.send() }.onFailure {
                _state.update {
                    it.copy(message = "The system passkey screen could not be opened.")
                }
            }
        }
    }

    private fun selectedRecord(): PasskeyRecord? {
        val id = _state.value.selected?.id ?: return null
        return _state.value.groups.flatMap { it.records }.firstOrNull { it.id == id }
    }

    private fun beginCeremony(timeoutMs: Long, block: suspend () -> Unit) {
        cancelCeremony()
        _state.update {
            it.copy(
                busy = true,
                ceremonySecondsLeft = (timeoutMs / 1000L).toInt(),
                message = null
            )
        }
        countdownJob = viewModelScope.launch {
            var remaining = (timeoutMs / 1000L).toInt()
            while (remaining > 0 && _state.value.busy) {
                delay(1000)
                remaining -= 1
                _state.update { it.copy(ceremonySecondsLeft = remaining.coerceAtLeast(0)) }
            }
        }
        ceremonyJob = viewModelScope.launch {
            try {
                block()
            } finally {
                countdownJob?.cancel()
                _state.update { it.copy(busy = false, ceremonySecondsLeft = 0) }
            }
        }
    }

    /**
     * Why the app will not start a passkey — in the same words the result classes use, so the button,
     * the banner and the toast never give three different explanations for one refusal. Null means the
     * app could not form a verdict at all, which is said plainly rather than guessed at.
     */
    private fun refusalSentence(availability: PasskeyAvailability?): String =
        availability?.let(::passkeyRefusalSentence)
            ?: "This app could not read this phone's passkey support, so it will not start one."

    private companion object {
        const val NO_ACTIVITY_MESSAGE =
            "This screen needs a window to show the system's passkey sheet. Reopen the section and try again."

        const val RECORDS_UNREADABLE_MESSAGE =
            "This device's passkey records could not be read, so this list is not showing them. " +
                "Nothing was deleted — unlock the vault and refresh."
    }
}
