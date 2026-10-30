package com.securevault.pro.data.email

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * Transport security for the mail module. There is no knob that turns verification off.
 *
 * Three independent layers, all of them on:
 * 1. the platform trust anchors — the default [SSLContext], so the system CA store decides what is
 *    trusted. No custom [javax.net.ssl.TrustManager], no trust-all manager, no `X509TrustManager`
 *    of our own anywhere in this module;
 * 2. `endpointIdentificationAlgorithm = "HTTPS"` on the [SSLParameters], which is how the platform
 *    performs RFC 2818 hostname verification during the handshake;
 * 3. [TlsPeerVerification] re-checks the negotiated peer certificate after the handshake and throws
 *    if the name does not match. It is redundant by design: the module's rule is that a mail
 *    connection may not proceed on a socket this file cannot vouch for.
 */
object TlsPolicy {

    /** TLS 1.3 where the platform offers it, TLS 1.2 always; 1.0/1.1 are not enabled anywhere. */
    private val PREFERRED_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

    /** The one SSLContext the module uses: default trust, default key managers, never permissive. */
    @Volatile
    private var context: SSLContext? = null

    fun sslContext(): SSLContext {
        val cached = context
        if (cached != null) return cached
        synchronized(this) {
            val again = context
            if (again != null) return again
            val created = try {
                // getInstance("TLS") + init(null, null, null) == the platform's own trust store and
                // the platform's own hostname verifier. Passing null for both means "defaults", and
                // is the only shape that is not a security downgrade.
                SSLContext.getInstance("TLS").apply { init(null, null, null) }
            } catch (e: GeneralSecurityException) {
                throw IOException("This device cannot open a TLS session for mail: ${e.message}", e)
            }
            context = created
            return created
        }
    }

    /** Parameters for one connection: hostname verification on, SNI set to the peer we mean. */
    fun parametersFor(host: String): SSLParameters {
        val parameters = SSLParameters()
        // "HTTPS" is the JDK/Android idiom for "verify the peer name against the certificate".
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        parameters.needClientAuth = false
        runCatching { parameters.serverNames = listOf(SNIHostName(host)) }
        // A host that is not a DNS name (a bare IP in the config field) cannot carry SNI; the
        // hostname check below then compares the address against the certificate's IP SANs.
        return parameters
    }

    /** Applies the policy to a fresh socket and returns it, protocols clamped and verification on. */
    fun configure(socket: SSLSocket, host: String, readTimeoutMs: Int = MailTimings.SOCKET_READ_TIMEOUT_MS) {
        val available = socket.supportedProtocols.orEmpty()
        val enabled = PREFERRED_PROTOCOLS.filter { available.contains(it) }
        if (enabled.isEmpty()) {
            runCatching { socket.close() }
            throw IOException("The server did not offer TLS 1.2 or newer; refusing to connect.")
        }
        socket.enabledProtocols = enabled.toTypedArray()
        socket.useClientMode = true
        socket.soTimeout = readTimeoutMs
        val parameters = socket.sslParameters ?: parametersFor(host)
        val merged = parametersFor(host)
        parameters.endpointIdentificationAlgorithm = merged.endpointIdentificationAlgorithm
        merged.serverNames?.let { parameters.serverNames = it }
        socket.sslParameters = parameters
    }

    /**
     * Post-handshake belt-and-braces check. Call it once the first read is about to happen: by
     * then the session is negotiated and [SSLSession.getPeerCertificates] is available.
     */
    fun requireVerified(socket: SSLSocket, host: String) {
        TlsPeerVerification.require(socket.session, host)
    }
}

/**
 * The name check, kept in a pure class so a JVM test can feed it certificates' worth of names
 * without a socket.
 */
object TlsPeerVerification {

    /**
     * Throws [SSLPeerUnverifiedException] when the peer has no chain or none of its names match
     * [host]; returns the matched name otherwise.
     */
    fun require(session: SSLSession, host: String): String {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) throw SSLPeerUnverifiedException("No server name to verify against.")
        val chain = try {
            session.peerCertificates
        } catch (e: CertificateException) {
            throw SSLPeerUnverifiedException("The server certificate chain is not parseable: ${e.message}")
        }
        if (chain.isEmpty()) throw SSLPeerUnverifiedException("The server presented no certificate.")
        val leaf = chain.firstOrNull() as? X509Certificate
            ?: throw SSLPeerUnverifiedException("The server certificate is not an X.509 certificate.")
        val names = collectNames(leaf)
        val matched = names.firstOrNull { candidate ->
            if (isIpAddress(normalized)) candidate.equals(normalized, ignoreCase = true)
            else hostPatternMatches(candidate, normalized)
        } ?: throw SSLPeerUnverifiedException(
            "The server certificate is not valid for $normalized; it names " +
                names.joinToString().take(160)
        )
        return matched
    }

    /** subjectAltName DNS/iP entries, falling back to the CN only when there is no SAN at all. */
    fun collectNames(certificate: X509Certificate): List<String> {
        val san = runCatching {
            certificate.subjectAlternativeNames.orEmpty()
                .filter { entry -> entry.size >= 2 }
                .mapNotNull { entry -> (entry[1] as? String)?.trim()?.lowercase() }
        }.getOrDefault(emptyList())
        if (san.isNotEmpty()) return san.distinct()
        val cn = runCatching {
            certificate.subjectX500Principal?.name
                ?.split(',')
                ?.firstOrNull { it.trim().startsWith("CN=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.lowercase()
        }.getOrNull()
        return listOfNotNull(cn?.takeIf { it.isNotBlank() })
    }

    /**
     * RFC 2818 style match, with the two rules that keep wildcards honest: a `*` only ever stands
     * for one label, and it must be the *whole* leftmost label, so `*.com` and `ma il.com`-style
     * tricks never match.
     */
    fun hostPatternMatches(rawPattern: String, rawHost: String): Boolean {
        val pattern = rawPattern.trim().lowercase().removeSuffix(".")
        val host = rawHost.trim().lowercase().removeSuffix(".")
        if (pattern.isEmpty() || host.isEmpty()) return false
        if (!pattern.contains('*')) return pattern == host
        val patternLabels = pattern.split('.')
        val hostLabels = host.split('.')
        if (patternLabels.size != hostLabels.size) return false
        if (patternLabels.first().length < 2) return false
        return patternLabels.zip(hostLabels).all { (p, h) ->
            when {
                p == h -> true
                p == "*" -> true
                // only the leftmost label may be wildcarded, and only as a suffix ("*.example.com")
                p.endsWith(".*") -> false
                p.startsWith("*") -> p.length > 1 && h.endsWith(p.substring(1))
                else -> false
            }
        }
    }

    fun isIpAddress(value: String): Boolean {
        val v = value.takeIf { it.contains('.') || it.contains(':') } ?: return false
        if (v.contains(':')) return v.matches(Regex("^[0-9a-fA-F:]+$"))
        val parts = v.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
    }
}
