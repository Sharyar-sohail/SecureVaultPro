package com.securevault.pro.data.email.repository

import com.securevault.pro.data.email.ImapEngine
import com.securevault.pro.data.email.MailConnector
import com.securevault.pro.data.email.MailEndpoint
import com.securevault.pro.data.email.MailProtocolException
import com.securevault.pro.data.email.MailTransport
import com.securevault.pro.data.email.SecurityMode
import com.securevault.pro.data.email.SmtpEngine
import com.securevault.pro.data.email.SmtpLineReader
import java.io.File

/**
 * Opening a session, in the one order a server accepts.
 *
 * These are plain blocking functions — they belong on [kotlinx.coroutines.Dispatchers.IO] inside a
 * [MailDeadline], and [MailboxService] is the only caller, so the socket rules live once:
 *
 * - a STARTTLS endpoint is upgraded **before** anything that identifies the owner is written. Both
 *   engines already refuse to authenticate on a transport that is not secure, and this file makes
 *   sure that branch is never even reached;
 * - after an upgrade a **fresh engine is built on the new transport**: the old reader's bytes are
 *   plaintext, and `ImapEngine.startTls`/`SmtpEngine.startTls` already insist that nothing was read
 *   past the upgrade answer. The old transport is deliberately *not* closed on success, because the
 *   upgraded socket wraps the same file descriptor — closing it would kill the TLS session;
 * - the IMAP tag counter restarts inside a new engine, and reusing `A0001` after the server has
 *   already seen it is a spec violation some hosts answer with `BAD`. [MailSessions.openImap]
 *   therefore burns exactly as many tags as the pre-upgrade phase used, which is the only safe way
 *   to continue a session across the TLS boundary;
 * - capabilities are re-read after TLS, because what a server offers in the clear is not what it
 *   offers inside a tunnel (`IDLE`, `BINARY`, `AUTH=XOAUTH2` all appear only after the upgrade);
 * - whatever happens, the transport is closed on the way out.
 */

/** How one connection proves who it is. The secret exists only inside this value. */
sealed interface MailCredential {

    /** The login name the server expects — an address, or a `user%domain.com` form on some hosts. */
    val user: String

    data class Password(override val user: String, val secret: String) : MailCredential
    data class Xoauth2(override val user: String, val accessToken: String) : MailCredential

    /** A label for a log or a screen. Never contains the secret or the token. */
    fun describe(): String = when (this) {
        is Password -> "password for $user"
        is Xoauth2 -> "Google sign-in for $user"
    }
}

/** An authenticated IMAP session. [close] logs out and shuts the socket; it never throws. */
class ImapSession(
    val engine: ImapEngine,
    private val transport: MailTransport
) {
    val host: String get() = transport.peerHost
    val port: Int get() = transport.peerPort
    val security: String get() = transport.securitySummary()

    /** True when the server advertises `BINARY`, i.e. a part can be fetched already decoded. */
    val supportsBinary: Boolean get() = engine.capability.any { it.equals("BINARY", ignoreCase = true) }

    /** True when the server advertises `UIDPLUS`, i.e. COPY can report the new UID. Not required. */
    val supportsIdle: Boolean get() = engine.capability.any { it.equals("IDLE", ignoreCase = true) }

    fun close() {
        runCatching { engine.logout() }
        runCatching { transport.close() }
    }
}

/** An authenticated SMTP session, ready for `MAIL FROM` / `RCPT TO` / `DATA`. */
class SmtpSession(
    val engine: SmtpEngine,
    private val transport: MailTransport
) {
    val host: String get() = transport.peerHost
    val port: Int get() = transport.peerPort
    val security: String get() = transport.securitySummary()
    val supportsSize: Boolean get() = engine.extensions.contains("SIZE")

    fun close() {
        runCatching { engine.quit() }
        runCatching { transport.close() }
    }
}

object MailSessions {

    /**
     * Connect, secure, sign in and read the post-TLS capability set.
     *
     * [cacheDir] is where the engine's own sink spills a literal too large for memory; null means the
     * system temp directory, which the protocol layer already handles.
     */
    fun openImap(endpoint: MailEndpoint, credential: MailCredential, cacheDir: File?): ImapSession {
        val host = endpoint.host.trim()
        if (host.isBlank()) throw MailProtocolException("No incoming mail server is configured for this account.")
        val bootstrap = MailConnector.open(endpoint)
        var live: MailTransport = bootstrap
        try {
            val first = ImapEngine.attach(bootstrap, cacheDir)
            first.readGreeting()
            var usedTags = 1
            first.capabilityCommand()
            if (endpoint.security == SecurityMode.STARTTLS) {
                // `startTls` throws unless the server offered it, so the plaintext path can never be
                // authenticated: the engine's own check plus this order.
                val upgraded = first.startTls(host)
                usedTags++
                live = upgraded
                val session = ImapEngine.attach(upgraded, cacheDir)
                repeat(usedTags) { session.nextTag() }
                authenticateImap(session, credential)
                session.capabilityCommand()
                return ImapSession(session, upgraded)
            }
            authenticateImap(first, credential)
            return ImapSession(first, bootstrap)
        } catch (error: Throwable) {
            runCatching { live.close() }
            throw error
        }
    }

    /** Connect, secure, sign in. [ehloName] is the client name the server is greeted with. */
    fun openSmtp(endpoint: MailEndpoint, credential: MailCredential, ehloName: String): SmtpSession {
        val host = endpoint.host.trim()
        if (host.isBlank()) throw MailProtocolException("No outgoing mail server is configured for this account.")
        val bootstrap = MailConnector.open(endpoint)
        var live: MailTransport = bootstrap
        try {
            val first = smtpEngineFor(bootstrap)
            first.readGreeting()
            first.ehlo(ehloName)
            if (endpoint.security == SecurityMode.STARTTLS) {
                val upgraded = first.startTls(host)
                live = upgraded
                // RFC 3207: everything learned before the tunnel is thrown away, EHLO included.
                val second = smtpEngineFor(upgraded)
                second.ehlo(ehloName)
                authenticateSmtp(second, credential)
                return SmtpSession(second, upgraded)
            }
            authenticateSmtp(first, credential)
            return SmtpSession(first, bootstrap)
        } catch (error: Throwable) {
            runCatching { live.close() }
            throw error
        }
    }

    /** The greeting + CAPABILITY only: the setup screen's "can I reach this server" probe. */
    fun probeImap(endpoint: MailEndpoint, cacheDir: File?): Pair<String, Set<String>> {
        val transport = MailConnector.open(endpoint)
        try {
            val engine = ImapEngine.attach(transport, cacheDir)
            val greeting = engine.readGreeting()
            val capability = engine.capabilityCommand()
            return greeting to capability
        } finally {
            runCatching { transport.close() }
        }
    }

    /** The greeting + EHLO only: the same probe for the outgoing half. */
    fun probeSmtp(endpoint: MailEndpoint, ehloName: String): Pair<String, Set<String>> {
        val transport = MailConnector.open(endpoint)
        try {
            val engine = smtpEngineFor(transport)
            val greeting = engine.readGreeting()
            val extensions = engine.ehlo(ehloName)
            return greeting to extensions
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun smtpEngineFor(transport: MailTransport): SmtpEngine =
        SmtpEngine(transport, transport.output, SmtpLineReader(transport.input))

    private fun authenticateImap(engine: ImapEngine, credential: MailCredential) {
        when (credential) {
            is MailCredential.Password -> engine.login(credential.user, credential.secret)
            is MailCredential.Xoauth2 -> engine.authenticateXoauth2(credential.user, credential.accessToken)
        }
    }

    private fun authenticateSmtp(engine: SmtpEngine, credential: MailCredential) {
        when (credential) {
            is MailCredential.Password -> engine.authPassword(credential.user, credential.secret)
            is MailCredential.Xoauth2 -> engine.authXoauth2(credential.user, credential.accessToken)
        }
    }
}
