package com.securevault.pro.security.passkey

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The arriving half of the autofill hand-off.
 *
 * [PasskeyAutofillBridge.beginAuthentication] can only put an `IntentSender` in front of the browser
 * that opens this app's `MainActivity` with [PasskeyAutofillBridge.EXTRA_PASSKEY_DOMAIN] attached —
 * an `AutofillService` has no Activity, so it cannot run a WebAuthn ceremony itself, and the nav graph
 * is not something the service can reach. This object is where that intent is parked instead of being
 * dropped on the floor: [MainActivity][com.securevault.pro.MainActivity] offers it here from
 * `onCreate`/`onNewIntent`, and the navigation host consumes it once the vault is actually open.
 *
 * Why a process-level flow rather than the savedStateHandle of a back-stack entry: the hand-off
 * arrives before the owner unlocks, and often before the nav graph exists at all (cold start from a
 * browser sheet). A pending domain is therefore kept across the unlock, exactly once.
 *
 * Nothing secret lives here. A domain name is the same string a browser's address bar shows; the
 * credential itself stays in [PasskeyStore], sealed, and only ever leaves toward the platform
 * credential manager that asked for it.
 */
object PasskeyHandoff {

    private val _pending = MutableStateFlow<Request?>(null)

    /** The request waiting to be honoured, or null. Read by the navigation host. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * One hand-off, and the relying party it is for. [signature] is the whole payload, so a repeated
     * `onNewIntent` with the same extras replaces rather than queues: two taps on the same autofill
     * sheet are one sign-in, not two.
     */
    data class Request(val domain: String, val kind: String) {
        val isSignIn: Boolean get() = kind == PasskeyAutofillBridge.PASSKEY_REQUEST_SIGN_IN
    }

    /** Called from every Activity entry point Android gives an `IntentSender`. Ignores unrelated intents. */
    fun offer(context: Context, intent: Intent?) {
        val raw = intent?.getStringExtra(PasskeyAutofillBridge.EXTRA_PASSKEY_DOMAIN) ?: return
        val normalized = PasskeyDomainRules.normalize(raw)
        if (normalized.isBlank()) return
        val kind = intent.getStringExtra(PasskeyAutofillBridge.EXTRA_PASSKEY_REQUEST)
            ?: PasskeyAutofillBridge.PASSKEY_REQUEST_SIGN_IN
        _pending.value = Request(domain = normalized, kind = kind)
    }

    /** Takes the waiting request and clears it, so a later unlock does not resurrect an old sheet. */
    fun consume(): Request? = _pending.value.also { _pending.value = null }

    /** Test seam: put the flow back where a fresh process found it. */
    fun clear() {
        _pending.value = null
    }
}
