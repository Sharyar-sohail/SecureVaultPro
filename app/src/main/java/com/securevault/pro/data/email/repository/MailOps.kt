package com.securevault.pro.data.email.repository

import com.securevault.pro.data.email.MailAuthException
import com.securevault.pro.data.email.MailNetworkException
import com.securevault.pro.data.email.MailProtocolException
import com.securevault.pro.data.email.MailTimings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The two things every mail screen is allowed to show: a bounded wait and a readable sentence.
 *
 * [MailDeadline] is the wrapper, [MailFailures] is the translator. Both are pure enough to test on a
 * JVM, and the repository calls them around *every* network step so no screen can be hung by a server
 * that stops answering — which is the failure mode the owner has repeatedly reported as "the app
 * froze", and the reason [MailTimings] exists at all.
 */

/** A step of a mail operation, with the budget it is allowed to take. */
enum class MailStep(val label: String, val timeoutMs: Int) {

    /** Socket open + TLS handshake + greeting. */
    CONNECT("Connecting to the mail server", MailTimings.COMMAND_TIMEOUT_MS),

    /** The setup screen's login + CAPABILITY + LIST attempt. */
    VALIDATE("Testing the mail configuration", MailTimings.VALIDATION_TIMEOUT_MS),

    /** `LIST "" "*"`. */
    FOLDERS("Reading the folder list", MailTimings.COMMAND_TIMEOUT_MS),

    /** One batch of envelope metadata. */
    METADATA("Reading the message list", MailTimings.COMMAND_TIMEOUT_MS * 2),

    /** One folder's full sync, bodies included. */
    SYNC("Checking new mail", MailTimings.SYNC_TIMEOUT_MS),

    /** One message's headers plus its body parts. */
    OPEN("Opening the message", MailTimings.SYNC_TIMEOUT_MS),

    /** One part or attachment to the cache. */
    DOWNLOAD("Saving the attachment", MailTimings.SYNC_TIMEOUT_MS),

    /** The whole SMTP conversation for one message. */
    SEND("Sending the message", MailTimings.SYNC_TIMEOUT_MS),

    /** Flag and copy writes. */
    FLAG("Updating the message", MailTimings.COMMAND_TIMEOUT_MS),

    /** Minting a Google mail token, which is a network call in disguise. */
    TOKEN("Getting the Google sign-in token", MailTimings.CONNECT_TIMEOUT_MS);

    val seconds: Int get() = (timeoutMs / 1000).coerceAtLeast(1)
}

/** A wait that ran out. Carries the step so the sentence can name it. */
class MailTimeoutException(val step: MailStep) :
    Exception(MailFailures.timeoutSentence(step), null)

/**
 * A failure the repository has already turned into a sentence.
 *
 * A service method that needs a credential it cannot get, or a folder that is not there, throws this
 * instead of returning a half-result from inside a `mailCall` block: [MailOutcome] is what the caller
 * sees, and unwrapping a nested outcome with `return` inside a lambda is how a failure silently
 * becomes an empty list. The sentence and its [kind] survive translation untouched.
 */
class MailFlowException(val sentence: String, val kind: MailFailureKind = MailFailureKind.CONFIG) :
    Exception(sentence, null)

object MailFailures {

    /** The category a screen uses to pick its colour and its retry advice. */
    fun kindOf(error: Throwable): MailFailureKind = when (error) {
        is MailFlowException -> error.kind
        is MailTimeoutException -> MailFailureKind.TIMEOUT
        is CancellationException -> MailFailureKind.CANCELLED
        is MailAuthException -> MailFailureKind.AUTH
        is SSLPeerUnverifiedException -> MailFailureKind.TLS
        is MailProtocolException -> MailFailureKind.PROTOCOL
        is MailNetworkException -> MailFailureKind.NETWORK
        is UnknownHostException, is SocketTimeoutException, is SSLException, is IOException ->
            MailFailureKind.NETWORK
        is SecurityException -> MailFailureKind.CONFIG
        else -> MailFailureKind.UNKNOWN
    }

    /**
     * One plain sentence the owner can act on.
     *
     * The module's exceptions already carry sentences — this function's job is what the protocol
     * layer cannot know (a stalled read, a missing permission, a class name nobody reads) and to
     * make sure a bare `SocketTimeoutException` or `IllegalStateException: No server name…` never
     * reaches a screen as that. An exception's own message is kept when it is already readable
     * English; a class name alone never is.
     */
    fun describe(error: Throwable, step: MailStep? = null): String {
        val base = when (error) {
            is MailTimeoutException -> error.message.orEmpty()
            is CancellationException -> "The request was cancelled."
            is MailAuthException, is MailProtocolException, is MailNetworkException ->
                error.message.orEmpty()
            is UnknownHostException ->
                "${error.message.orEmpty().take(80).trim()} could not be resolved. " +
                    "Check the server name, or turn mobile data back on."
            is SocketTimeoutException ->
                "The server stopped answering in time${stepSuffix(step)}. " +
                    "Try again, and use the SSL/TLS port if you are on a slow network."
            is SSLPeerUnverifiedException ->
                "The server's certificate does not match its name, so nothing was sent. " +
                    "Check the server name — a mail host must answer with its own certificate."
            is SSLException ->
                "The encrypted handshake failed: ${error.message.orEmpty().take(160)}"
            is SecurityException ->
                "The app was not allowed to use the network. Check the permission in Android settings."
            is IllegalStateException, is IllegalArgumentException ->
                error.message.orEmpty().ifBlank {
                    "The mail settings are incomplete. Fill in every server field and try again."
                }
            else -> error.message.orEmpty()
        }
        val cleaned = base.trim().ifBlank { fallbackSentence(error, step) }
        // A message that *is* an exception name (or a stack-trace fragment) is replaced, not shown.
        return if (looksLikeRawException(cleaned)) fallbackSentence(error, step) else cleaned
    }

    /** The sentence a timeout produces — pure, so a test can pin its wording. */
    fun timeoutSentence(step: MailStep): String =
        "${step.label} stopped after ${step.seconds} seconds because the server did not answer. " +
            "Nothing was lost; try again, or check the network."

    private fun fallbackSentence(error: Throwable, step: MailStep?): String =
        "${step?.label ?: "That mail operation"} did not finish: " +
            error.javaClass.simpleName.lowercase().replace("exception", "error") +
            ". Check the connection and try again."

    private fun looksLikeRawException(text: String): Boolean =
        text.isBlank() ||
            text.length < 4 ||
            text.matches(Regex("^[A-Za-z0-9_.$]*(Exception|Error|Throwable)$"))

    private fun stepSuffix(step: MailStep?): String =
        if (step == null) "" else " while ${step.label.lowercase()}"
}

/** What kind of trouble a screen is being shown, so it can pick the right advice. */
enum class MailFailureKind {
    NETWORK, TLS, AUTH, PROTOCOL, TIMEOUT, CONFIG, CANCELLED, UNKNOWN;

    /** The retry advice line under an error banner. */
    fun advice(): String = when (this) {
        TIMEOUT -> "The server took too long. Try again, or check the port and security mode."
        NETWORK -> "Check the connection and the server name, then try again."
        TLS -> "The certificate did not match the server. Do not try to bypass it — fix the host name."
        AUTH -> "Re-type the password, or create an app password at the provider."
        PROTOCOL -> "The server answered something unexpected. Try again in a moment."
        CONFIG -> "Something is missing from the setup. Fill in every field."
        CANCELLED -> "Cancelled."
        UNKNOWN -> "Try again. If it keeps happening, check the server settings."
    }
}

/**
 * Runs [block] with [step]'s budget.
 *
 * Two clocks are always in play and this is the honest note about it: the coroutine deadline below
 * bounds *this app's* waiting, and the socket's own `soTimeout`
 * ([MailTimings.SOCKET_READ_TIMEOUT_MS], set by `TlsPolicy.configure`) bounds the blocking read
 * inside it. A syscall cannot be interrupted by a coroutine, so the socket timeout is what actually
 * frees the thread; the deadline is what guarantees a screen reports something even when a library
 * call refuses to return, and it is also what lets a Cancel press take effect between commands.
 */
object MailDeadline {

    suspend fun <T> call(step: MailStep, block: suspend () -> T): T = try {
        withTimeout(step.timeoutMs.toLong()) { block() }
    } catch (e: TimeoutCancellationException) {
        throw MailTimeoutException(step)
    } catch (e: CancellationException) {
        // An outside cancel (screen disposed, vault locked) must stay a cancel.
        throw e
    }

    /** The same, for a blocking body: the caller is already on the IO dispatcher. */
    suspend fun <T> blocking(step: MailStep, block: () -> T): T = call(step) { block() }
}

/**
 * The one result shape the mail screens read: a value, or a sentence plus its kind. No screen ever
 * touches an exception, and no failure is silently turned into an empty list.
 */
sealed interface MailOutcome<out T> {

    data class Ready<T>(val value: T) : MailOutcome<T>

    data class Problem(
        val message: String,
        val kind: MailFailureKind,
        val advice: String = kind.advice()
    ) : MailOutcome<Nothing>

    val isReady: Boolean get() = this is Ready

    companion object {
        fun <T> ready(value: T): MailOutcome<T> = Ready(value)
        fun problem(error: Throwable, step: MailStep): MailOutcome<Nothing> {
            val kind = MailFailures.kindOf(error)
            return Problem(MailFailures.describe(error, step), kind)
        }
        fun problem(message: String, kind: MailFailureKind = MailFailureKind.CONFIG): MailOutcome<Nothing> =
            Problem(message, kind)
    }
}

/** Run [block], turning anything it throws into a readable [MailOutcome]. */
suspend fun <T> mailCall(step: MailStep, block: suspend () -> T): MailOutcome<T> =
    try {
        MailOutcome.Ready(MailDeadline.call(step) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        MailOutcome.problem(e, step)
    }
