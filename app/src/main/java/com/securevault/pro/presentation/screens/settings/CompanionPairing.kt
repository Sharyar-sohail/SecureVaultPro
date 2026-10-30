package com.securevault.pro.presentation.screens.settings

import com.securevault.pro.data.remote.companion.CompanionUrl
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Handshake between the phone and the browser mirror. The payload carries only the
 * companion address and a short rotation code — never a key, passphrase or record.
 * Treat it as an "is this the same human?" check, not as authentication of secrets.
 */
object CompanionPairing {

    const val SCHEME = "securewallet"
    const val HOST = "pair"

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()
    private val FRAGMENT_CODE =
        Regex("(?:^|&)pair=([A-HJ-NP-Z2-9]{6})(?:&|\$)", RegexOption.IGNORE_CASE)

    fun newCode(): String =
        (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /**
     * A plain link to the companion page with the code in its fragment. The custom
     * `securewallet://` form used to be the only output, and no browser or camera can
     * open a scheme the site does not own: the scan produced nothing, so the phone
     * never reached the web app. A fragment also stays out of every access log and out
     * of the `Referer` the page sends on, which the query string would not have.
     */
    fun payload(url: String, code: String): String {
        val address = url.trim().trimEnd('/')
        val pair = code.trim().uppercase()
        return if (pair.isEmpty()) address else "$address#pair=$pair"
    }

    /** Returns the companion address plus optional code, or null for anything unusable. */
    fun parse(raw: String): Pair<String, String>? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme.equals(SCHEME, ignoreCase = true)) {
            if (!uri.host.equals(HOST, ignoreCase = true)) return null
            val query = uri.query.orEmpty()
                .split("&")
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) null else part.substring(0, index) to decode(part.substring(index + 1))
                }
                .toMap()
            val url = query["u"].orEmpty()
            return if (isTrustworthy(url)) url to query["c"].orEmpty().uppercase() else null
        }
        val direct = if (uri.authority.isNullOrBlank()) null
        else "${uri.scheme}://${uri.authority}${uri.path.orEmpty()}".trimEnd('/')
            .removeSuffix("#")
        if (direct == null || !isTrustworthy(direct)) return null
        return direct to fragmentCode(uri.fragment)
    }

    private fun fragmentCode(fragment: String?): String =
        FRAGMENT_CODE.find(fragment.orEmpty())?.groupValues?.get(1)?.uppercase().orEmpty()

    /** See [CompanionUrl] — cleartext is refused, loopback http stays available. */
    fun isTrustworthy(raw: String): Boolean = CompanionUrl.isTrustworthy(raw)

    fun rejectReason(raw: String): String = CompanionUrl.rejectReason(raw)

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
