package com.securevault.pro.data.remote.companion

import java.net.URI

/**
 * Transport policy for the browser mirror. Cleartext is refused because Android's
 * network security policy and modern browsers both reject it — the old "invalid
 * certificate" failures were an http:// address being pushed over TLS code. Nothing
 * here relaxes certificate validation; the only cleartext hosts are the ones
 * network_security_config.xml whitelists, i.e. loopback and the emulator host gateway.
 */
object CompanionUrl {

    fun isTrustworthy(raw: String): Boolean {
        val url = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        if (url.host.isNullOrBlank()) return false
        if (url.scheme.equals("https", ignoreCase = true)) return true
        val host = url.host.lowercase().trim('[', ']')
        return url.scheme.equals("http", ignoreCase = true) && CLEARTEXT_HOSTS.contains(host)
    }

    private val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    /**
     * Blogger and similar static hosts serve the companion page but run no code, so an
     * address on them can never answer `/api/vault`. Detected here so the failure names
     * the route that does work instead of reporting a socket error after a 20s wait.
     */
    fun isStaticHosting(raw: String): Boolean {
        val host = runCatching { URI(raw.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        return host.endsWith("blogspot.com") || host.endsWith("blogger.com")
    }

    fun rejectReason(raw: String): String {
        val value = raw.trim()
        val scheme = runCatching { URI(if (value.contains("://")) value else "invalid://$value").scheme }
            .getOrNull()
        return if (scheme.equals("http", ignoreCase = true)) {
            "That address uses plain http, which Android and browsers refuse. Use the https:// address of the site."
        } else {
            "Enter the full https:// address of your companion site."
        }
    }
}
