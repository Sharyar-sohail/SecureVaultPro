package com.securevault.pro.data.email

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket

/**
 * The only thing the protocol classes are allowed to talk to.
 *
 * Keeping the socket behind an interface buys two things: the IMAP and SMTP engines are pure
 * classes that a JVM test can drive with an in-memory pipe, and the one place where TLS is decided
 * is [MailConnector], so no call site can quietly reach a server unprotected.
 */
interface MailTransport : Closeable {

    val input: InputStream
    val output: OutputStream

    /** The name we meant to reach; it is what the certificate is checked against. */
    val peerHost: String
    val peerPort: Int

    /** True once the bytes on this socket are inside a verified TLS session. */
    val isSecure: Boolean

    /** Human-readable security state for a settings row; never a secret. */
    fun securitySummary(): String

    override fun close()
}

/**
 * A [MailTransport] over a real socket. [rawSocket] is either an [SSLSocket] (TLS from byte one,
 * or the upgraded view of a plain socket) or a plain socket that is only ever used to carry the
 * protocol's pre-auth greeting and its STARTTLS command.
 */
class SocketTransport internal constructor(
    private val rawSocket: Socket,
    override val peerHost: String,
    override val peerPort: Int,
    private val tls: SSLSocket?
) : MailTransport {

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override val input: InputStream = rawSocket.getInputStream()
    override val output: OutputStream = rawSocket.getOutputStream()
    override val isSecure: Boolean get() = tls != null

    override fun securitySummary(): String {
        val socket = tls ?: return "not encrypted until STARTTLS"
        return runCatching {
            val session = socket.session
            val version = session.protocol ?: "TLS"
            val cipher = session.cipherSuite?.substringAfter('_')?.take(28) ?: ""
            "$version · $cipher".trim()
        }.getOrDefault("TLS session")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { rawSocket.shutdownOutput() }
        runCatching { rawSocket.close() }
    }

    /** The socket, for the upgrade path that reuses the same file descriptors. */
    internal fun underlying(): Socket = rawSocket

    internal fun ssl(): SSLSocket? = tls
}

/**
 * Opens connections. Every method blocks and belongs on [kotlinx.coroutines.Dispatchers.IO] inside
 * a `withTimeout`; none of them accepts a trust manager, a hostname verifier or an "ignore
 * certificates" flag, because there is no version of this code that has one.
 */
object MailConnector {

    /** TLS from the first byte: port 993 for IMAP, 465 for SMTP. */
    fun openTls(endpoint: MailEndpoint): SocketTransport {
        val host = endpoint.host.trim()
        require(host.isNotBlank()) { "No server name was given." }
        val factory = TlsPolicy.sslContext().socketFactory
        val socket = try {
            factory.createSocket() as SSLSocket
        } catch (e: IOException) {
            throw MailNetworkException("Could not create a TLS socket for $host.", e)
        }
        try {
            TlsPolicy.configure(socket, host)
            connect(socket, host, endpoint.port)
            socket.startHandshake()
            TlsPolicy.requireVerified(socket, host)
            return SocketTransport(socket, host, endpoint.port, socket)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            if (e is MailNetworkException) throw e
            throw MailNetworkException(readableOpenFailure(host, endpoint.port, e), e)
        }
    }

    /**
     * The bare half of a STARTTLS connection. Nothing that identifies the owner is ever written on
     * this transport: [SmtpEngine] and [ImapEngine] both refuse to authenticate while
     * [MailTransport.isSecure] is false.
     */
    fun openPlain(endpoint: MailEndpoint): SocketTransport {
        val host = endpoint.host.trim()
        require(host.isNotBlank()) { "No server name was given." }
        val socket = Socket()
        return try {
            connect(socket, host, endpoint.port)
            SocketTransport(socket, host, endpoint.port, tls = null)
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw MailNetworkException(readableOpenFailure(host, endpoint.port, e), e)
        }
    }

    /**
     * Upgrades an already-connected plain socket in place, as RFC 2595 / RFC 3501 require: the TLS
     * clienthello goes down the same connection the greeting arrived on, and the certificate is
     * checked against the *same* [host] the connection was made to.
     *
     * [readerHoldsBytes] is how many bytes the caller's read-ahead buffer still holds from before
     * the upgrade. It must be zero: those bytes are plaintext and would be handed back as decrypted
     * junk, so a caller that over-read gets a clear failure instead of a corrupt session.
     */
    fun upgradeToTls(transport: SocketTransport, host: String, readerHoldsBytes: Int): SocketTransport {
        val plain = transport.underlying()
        if (transport.ssl() != null) return transport
        if (readerHoldsBytes != 0) {
            // Closing rather than guessing: leftover buffered bytes would otherwise be read back as
            // decrypted junk and reported as a protocol error the owner cannot fix.
            runCatching { transport.close() }
            throw MailNetworkException(
                "The server sent more data than its STARTTLS answer, so the connection was closed " +
                    "instead of continuing unsafely. Try again."
            )
        }
        val ssl = try {
            // autostart = false: every parameter has to be in place before the handshake begins.
            TlsPolicy.sslContext().socketFactory
                .createSocket(plain, host, plain.port, false) as SSLSocket
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw MailNetworkException("The server would not start TLS: ${e.message}", e)
        }
        try {
            TlsPolicy.configure(ssl, host)
            ssl.startHandshake()
            TlsPolicy.requireVerified(ssl, host)
            return SocketTransport(ssl, host, plain.port, ssl)
        } catch (e: Throwable) {
            runCatching { ssl.close() }
            if (e is MailNetworkException) throw e
            throw MailNetworkException("TLS could not be established with $host: ${e.message}", e)
        }
    }

    /** Opens the endpoint exactly as its [SecurityMode] dictates. */
    fun open(endpoint: MailEndpoint): SocketTransport = when (endpoint.security) {
        SecurityMode.SSL_TLS -> openTls(endpoint)
        SecurityMode.STARTTLS -> openPlain(endpoint)
    }

    private fun connect(socket: Socket, host: String, port: Int) {
        try {
            socket.connect(InetSocketAddress(host, port), MailTimings.CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.keepAlive = true
        } catch (e: UnknownHostException) {
            throw MailNetworkException("$host could not be resolved. Check the server name.", e)
        } catch (e: IOException) {
            throw MailNetworkException(
                "Could not reach $host on port $port within " +
                    "${MailTimings.CONNECT_TIMEOUT_MS / 1000} seconds. " +
                    "Check the network, the port and the security choice.",
                e
            )
        }
    }

    private fun readableOpenFailure(host: String, port: Int, error: Throwable): String = when (error) {
        is javax.net.ssl.SSLPeerUnverifiedException ->
            "$host presented a certificate that does not belong to it. The connection was refused; " +
                "nothing was sent. ${error.message.orEmpty().take(160)}"
        is javax.net.ssl.SSLException ->
            "TLS with $host:$port failed: ${error.message.orEmpty().take(180)}"
        else -> "Could not reach $host:$port — ${error.message.orEmpty().take(160)}"
    }
}

/** A network or protocol problem the owner can act on. The message never contains a credential. */
class MailNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server answered something the protocol does not allow. */
class MailProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Authentication was refused. Says why, and never repeats the secret that was refused. */
class MailAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
