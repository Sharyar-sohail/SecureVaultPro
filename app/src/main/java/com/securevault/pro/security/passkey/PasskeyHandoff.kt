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
    private val _delivered = MutableStateFlow<Request?>(null)

    /** The request waiting to be honoured, or null. Read by the navigation host. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * The request the navigation host has already taken, kept for the screen that can act on it.
     *
     * This exists because of the order Android forces: the nav graph consumes the hand-off to decide
     * where to go, and only then does the Passkeys screen exist. Without a second, shorter-lived copy
     * the site the browser named is thrown away at the exact moment it becomes useful, and the owner
     * lands on a list of passkeys with no hint of what they were asked for. Nothing is secret here —
     * the same host string the browser's address bar already shows.
     */
    val delivered: StateFlow<Request?> = _delivered.asStateFlow()

    /**
     * One hand-off, and the relying party it is for. [signature] is the whole payload, so a repeated
     * `onNewIntent` with the same extras replaces rather than queues: two taps on the same autofill
     * sheet are one sign-in, not two.
     *
     * [arrivedAt] is what makes a hand-off safe to honour at all: an `Intent` can be re-delivered when
     * Android restores a task hours later, and a sign-in prompt from this morning must not fire a
     * biometric sheet this evening.
     */
    data class Request(val domain: String, val kind: String, val arrivedAt: Long = System.currentTimeMillis()) {
        val isSignIn: Boolean get() = kind == PasskeyAutofillBridge.PASSKEY_REQUEST_SIGN_IN

        /** True while a browser could still be waiting on this answer. */
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
            now - arrivedAt in 0..HANDOFF_TTL_MS
    }

    /** Called from every Activity entry point Android gives an `IntentSender`. Ignores unrelated intents. */
    fun offer(context: Context, intent: Intent?) {
        val raw = intent?.getStringExtra(PasskeyAutofillBridge.EXTRA_PASSKEY_DOMAIN) ?: return
        val normalized = PasskeyDomainRules.normalize(raw)
        if (normalized.isBlank()) return
        val kind = intent.getStringExtra(PasskeyAutofillBridge.EXTRA_PASSKEY_REQUEST)
            ?: PasskeyAutofillBridge.PASSKEY_REQUEST_SIGN_IN
        // A fresh sheet supersedes whatever the screen was still showing about the last one.
        if (_pending.value?.domain != normalized) _delivered.value = null
        _pending.value = Request(domain = normalized, kind = kind)
    }

    /**
     * Takes the waiting request and clears it, so a later unlock does not resurrect an old sheet.
     * A request older than [HANDOFF_TTL_MS] is dropped rather than honoured, and a fresh one is left
     * in [delivered] for the screen that can act on it.
     */
    fun consume(): Request? {
        val current = _pending.value
        _pending.value = null
        if (current == null || !current.isFresh()) return null
        _delivered.value = current
        return current
    }

    /** The screen has shown or acted on the delivered request, so it must not show it again. */
    fun clearDelivered() {
        _delivered.value = null
    }

    /** Test seam: put the flow back where a fresh process found it. */
    fun clear() {
        _pending.value = null
        _delivered.value = null
    }

    /**
     * How long a browser's sheet can reasonably be assumed to still want an answer. Five minutes is
     * longer than any WebAuthn request lives (this app's own sign-in clock is 60 seconds) and shorter
     * than a phone can be left in a restored task.
     */
    const val HANDOFF_TTL_MS: Long = 5 * 60 * 1000L
}
