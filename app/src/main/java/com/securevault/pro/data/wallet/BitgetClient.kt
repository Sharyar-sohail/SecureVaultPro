package com.securevault.pro.data.wallet

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.securevault.pro.domain.model.BitgetAsset
import com.securevault.pro.domain.model.BitgetFailure
import com.securevault.pro.domain.model.BitgetFailureKind
import com.securevault.pro.domain.model.BitgetSecretBundle
import com.securevault.pro.domain.model.BitgetSnapshot
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signed, **read-only** REST client for the Bitget v2 account API.
 *
 * Endpoints implemented, spelled exactly as Bitget's own documentation numbers them
 * (`https://www.bitget.com/api-doc`, classic v2 section, verified 2026-10-29 — the docs pages are
 * client-rendered, so the wording below is quoted from Bitget's machine-readable copies of the same
 * pages, `https://www.bitget.com/docs/classic/rest-api.md` and
 * `https://www.bitget.com/docs/classic/uta-api-upgrade-guide.md`, plus the endpoint tables of the
 * maintained SDK `tiagosiebler/bitget-api`):
 *
 * ```
 * GET /api/v2/spot/account/assets          params: coin?, assetType?          (none sent)
 * GET /api/v2/mix/account/accounts         params: productType (required)
 * ```
 *
 * Bitget's own upgrade guide names these two as the balance endpoints:
 * "Get balance | `GET /api/v2/spot/account/assets` (spot) / `GET /api/v2/mix/account/accounts`
 * (futures) → `GET /api/v3/account/assets` (single endpoint for all products)", and it documents the
 * parameter casing this client sends: "`productType` (e.g. `usdt-futures`) | `category`
 * (e.g. `USDT-FUTURES`) | Same concept, renamed and **uppercased in v3**" — i.e. v2 is lower case.
 *
 * Signing, quoted from `https://www.bitget.com/docs/classic/rest-api.md` §Signature:
 *
 * > The request header of ACCESS-SIGN is to encrypt
 * > **timestamp + method.toUpperCase() + requestPath + "?" + queryString + body** string
 * > (+ means string concat) by **HMAC SHA256** algorithm with **secretKey**, and encode the
 * > encrypted result through **BASE64**.
 * >
 * > **timestamp**: Same as ACCESS-TIMESTAMP request header. Value equals to milliseconds since Epoch.
 * > **queryString**: If the queryString is empty, signature content is
 * > `timestamp + method.toUpperCase() + requestPath + body`.
 * > **body**: If the request body is empty (usually a GET request), the body can be omitted.
 * >
 * > **Timestamp**: the timestamp of the request must be within 30 seconds of the API server time,
 * > otherwise the request will be considered expired and rejected.
 *
 * Required headers, also verbatim from the same section: `ACCESS-KEY`, `ACCESS-SIGN`
 * (base64 signature), `ACCESS-TIMESTAMP` (milliseconds), `ACCESS-PASSPHRASE`, and
 * `Content-Type: application/json`.
 *
 * **Which key bytes the HMAC uses, and why.** Bitget's own Java sample in that document initialises
 * the MAC with `new SecretKeySpec(secretKey.getBytes("UTF-8"), "HmacSHA256")` and its Python sample
 * with `bytes(secret_key, encoding='utf8')`. This client therefore uses the **raw UTF-8 bytes of the
 * secret string** and deliberately does *not* base64-decode it first, even though Bitget secrets
 * happen to look like base64. Decoding first produces a different MAC over a shorter key and the
 * server answers 40003; [BitgetSignatureTest] pins both variants so the choice is explicit rather
 * than accidental.
 *
 * Nothing here trades, withdraws, transfers or signs a payload: only the two GETs above are ever
 * issued, the method is hard-coded, and the host list is closed.
 */
@Singleton
class BitgetClient @Inject constructor() {

    companion object {
        const val METHOD = "GET"

        // Not `const`: their values come from `CryptoEndpoints`, which is an internal registry, and a
        // public `const` initialised from an internal one is the kind of thing a compiler is entitled
        // to dislike. Plain `val`s read the same strings once, at class load, and keep one source.
        val SPOT_ASSETS_PATH: String = CryptoEndpoints.BITGET_SPOT_ASSETS_PATH
        val MIX_ACCOUNTS_PATH: String = CryptoEndpoints.BITGET_MIX_ACCOUNTS_PATH
        const val MIX_PARAMS = "productType"

        /** Lower case, as the v2 documentation writes it. See the class comment. */
        val MIX_PRODUCT_TYPE: String = CryptoEndpoints.BITGET_MIX_PRODUCT_TYPE

        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000

        /** A balances page is a few kilobytes; anything larger is a misfire and gets dropped. */
        const val MAX_RESPONSE_BYTES = 512 * 1024

        /** Bitget's documented success code. */
        const val CODE_OK = "00000"

        // ------------------------------------------------------------------ pure signing

        /**
         * The pre-hash string, in the documented order. [queryString] is passed without its leading
         * `?`; the `?` is re-added only when there is a parameter, exactly as the docs describe.
         */
        fun preHash(
            timestamp: String,
            method: String,
            requestPath: String,
            queryString: String,
            body: String
        ): String {
            val query = if (queryString.isBlank()) "" else "?$queryString"
            // A blank body signs as absent, exactly as an empty query does: padding a GET with
            // whitespace would change the HMAC and every request would come back 40009.
            val payload = if (body.isBlank()) "" else body
            return timestamp + method.uppercase() + requestPath + query + payload
        }

        /** HMAC-SHA256 over UTF-8 bytes, Base64 encoded — see the class comment for why. */
        fun sign(preHash: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return java.util.Base64.getEncoder()
                .encodeToString(mac.doFinal(preHash.toByteArray(Charsets.UTF_8)))
        }

        /** Only ever used by the test that proves the discarded variant differs. */
        fun signWithBase64DecodedKey(preHash: String, secret: String): String {
            val decoded = runCatching { java.util.Base64.getDecoder().decode(secret) }.getOrNull()
                ?: secret.toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(decoded, "HmacSHA256"))
            return java.util.Base64.getEncoder()
                .encodeToString(mac.doFinal(preHash.toByteArray(Charsets.UTF_8)))
        }

        /** Milliseconds since the epoch, as the string that both signs and headers. */
        fun timestampOf(millis: Long): String = millis.toString()

        /** `a=1&b=2`, URL-encoded, in insertion order. The same string is signed and sent. */
        fun encodeQuery(params: Map<String, String>): String = params.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (key, value) ->
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            }

        // ------------------------------------------------------------------ pure parsing

        /** Bitget answers `{code, msg, data}`; `code` is the string `"00000"` on success. */
        fun decodeEnvelope(text: String): DecodedReply {
            val root = runCatching { JsonParser.parseString(text) }.getOrNull()
            // A body we cannot read is reported with an empty code, which `failureOf` turns into the
            // "answered, but not in the documented format" sentence rather than a bogus code number.
            if (root == null || !root.isJsonObject) return DecodedReply.failure("")
            val obj = root.asJsonObject
            val code = obj.stringOf("code") ?: ""
            val message = obj.stringOf("msg") ?: ""
            if (code != CODE_OK) return DecodedReply.failure(code, message)
            return DecodedReply(code = code, message = message, data = obj.get("data"), accepted = true)
        }

        /** `data` of `GET /api/v2/spot/account/assets`: `coin`, `available`, `frozen`, `locked`. */
        fun spotAssets(data: JsonElement?): List<BitgetAsset> = rowsOf(data).mapNotNull { row ->
            val symbol = row.stringOf("coin")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val available = decimalOf(row, "available")
            val onHold = decimalOf(row, "frozen") + decimalOf(row, "locked")
            BitgetAsset(
                account = BitgetSnapshot.ACCOUNT_SPOT,
                symbol = symbol,
                caption = "Spot wallet",
                available = available,
                onHold = onHold,
                priceUsd = 0.0
            )
        }

        /**
         * `data` of `GET /api/v2/mix/account/accounts`: one entry per margin coin. `accountEquity`
         * is the whole futures account value in [marginCoin]; `isolatedMargin` and `crossedMargin`
         * are what is committed against open positions.
         */
        fun mixAssets(data: JsonElement?): List<BitgetAsset> = rowsOf(data).mapNotNull { row ->
            val symbol = row.stringOf("marginCoin")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val equity = decimalOf(row, "accountEquity")
            val margin = decimalOf(row, "isolatedMargin") + decimalOf(row, "crossedMargin")
            BitgetAsset(
                account = BitgetSnapshot.ACCOUNT_MIX,
                symbol = symbol,
                caption = "Futures equity",
                available = equity,
                onHold = margin,
                priceUsd = 0.0
            )
        }

        /**
         * Turns a rejection into a sentence with the fix in it. Never echoes the server text and
         * never mentions a header value, so no key material can leak through an error message.
         */
        fun failureOf(httpCode: Int, code: String): BitgetFailure = when (code) {
            "40001" -> BitgetFailure(
                kind = BitgetFailureKind.INVALID_KEY,
                message = "Bitget has no record of that API key. It may have been deleted, or you " +
                    "pasted a key created on a different Bitget account. Create a fresh read-only " +
                    "key and enter all three values again.",
                code = code
            )
            "40003" -> BitgetFailure(
                kind = BitgetFailureKind.SIGNATURE,
                message = "The signature did not match. The usual cause is a mistyped Secret key — " +
                    "it is easy to lose a character when copying. Remove the credentials and paste " +
                    "the key, secret and passphrase again straight from Bitget.",
                code = code
            )
            "40006" -> BitgetFailure(
                kind = BitgetFailureKind.PASSPHRASE,
                message = "The passphrase does not match this API key. It is the word you chose when " +
                    "creating the key, not your Bitget login password, and Bitget cannot show it again " +
                    "— if it is lost, delete the key and create a new one.",
                code = code
            )
            "40009" -> BitgetFailure(
                kind = BitgetFailureKind.CLOCK_SKEW,
                message = "Bitget rejected the request as too old: this phone's clock is more than 30 " +
                    "seconds away from Bitget's server time. Open Settings › Date & time and turn on " +
                    "\"Set automatically\", then refresh. Nothing was retried, so no repeated " +
                    "requests were made while the clock was wrong.",
                code = code
            )
            "429" -> BitgetFailure(
                kind = BitgetFailureKind.RATE_LIMITED,
                message = "Bitget is rate limiting this key. Wait about a minute before refreshing " +
                    "again — the balances shown are the last ones read.",
                code = code
            )
            "403" -> BitgetFailure(
                kind = BitgetFailureKind.READ_ONLY_DENIED,
                message = "Bitget refused this key for the account endpoints. Check that the key was " +
                    "created with the Read permission only, and that its IP restriction allows this " +
                    "network.",
                code = code
            )
            else -> when {
                httpCode == 429 -> BitgetFailure(
                    kind = BitgetFailureKind.RATE_LIMITED,
                    message = "Bitget is rate limiting this key (too many requests). Wait about a " +
                        "minute and refresh again.",
                    code = code
                )
                httpCode == 401 || httpCode == 403 -> BitgetFailure(
                    kind = BitgetFailureKind.INVALID_KEY,
                    message = "Bitget rejected this key (HTTP $httpCode). Check that it still exists " +
                        "and that only the Read permission is ticked.",
                    code = code
                )
                code.isBlank() -> BitgetFailure(
                    kind = BitgetFailureKind.NETWORK,
                    message = "Bitget answered, but not in the format it documents. Refresh again in " +
                        "a moment; if it keeps happening, check for an app update.",
                    code = code
                )
                else -> BitgetFailure(
                    kind = BitgetFailureKind.OTHER,
                    message = "Bitget refused the request (code $code). Confirm the key is read-only " +
                        "and still active, then refresh.",
                    code = code
                )
            }
        }

        fun networkFailure(reason: Throwable?): BitgetFailure {
            val message = reason?.message.orEmpty()
            val kind = when {
                message.contains("Unable to resolve host", true) ||
                    message.contains("ENOTFOUND", true) ||
                    reason is java.net.UnknownHostException -> BitgetFailureKind.NETWORK
                reason is SocketTimeoutException ||
                    message.contains("timeout", true) ||
                    message.contains("timed out", true) -> BitgetFailureKind.NETWORK
                else -> BitgetFailureKind.NETWORK
            }
            val text = when (kind) {
                BitgetFailureKind.NETWORK -> {
                    val timeout = reason is SocketTimeoutException ||
                        message.contains("timeout", true) || message.contains("timed out", true)
                    if (timeout) {
                        "Bitget did not answer in time. Your connection may be captive or blocking the " +
                            "exchange — try mobile data and refresh again."
                    } else {
                        "api.bitget.com could not be reached from this network. Check your connection " +
                            "(a VPN or a restrictive Wi-Fi can block it) and refresh."
                    }
                }
                else -> "The read failed before Bitget replied. Check your connection and refresh."
            }
            return BitgetFailure(kind = kind, message = text, code = "")
        }

        private fun rowsOf(data: JsonElement?): List<JsonObject> {
            val array = data as? JsonArray ?: return emptyList()
            return buildList {
                for (index in 0 until array.size()) {
                    val row = array.get(index)
                    if (row is JsonObject) add(row)
                }
            }
        }

        private fun JsonObject.stringOf(key: String): String? =
            this.get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()

        /** Bitget sends every number as a string; a bad one reads as zero rather than throwing. */
        private fun decimalOf(row: JsonObject, key: String): Double =
            row.stringOf(key)?.toBigDecimalOrNull()?.toDouble() ?: 0.0
    }

    /** The `{code,msg,data}` reply after the code has been checked. */
    data class DecodedReply(
        val code: String,
        val message: String,
        val data: JsonElement?,
        val accepted: Boolean
    ) {
        companion object {
            fun failure(code: String, message: String = "") =
                DecodedReply(code = code, message = message, data = null, accepted = false)
        }
    }

    /** Outcome of one signed GET. A rejection is data, never an exception carrying a secret. */
    sealed class Call {
        data class Accepted(val data: JsonElement?) : Call()
        data class Rejected(val failure: BitgetFailure) : Call()
    }

    /** Signed spot assets. Returns an empty list for an account that holds nothing. */
    fun fetchSpotAssets(credential: BitgetSecretBundle): Call =
        get(SPOT_ASSETS_PATH, emptyMap(), credential)

    /** Signed USDT-margined futures accounts. */
    fun fetchMixAccounts(credential: BitgetSecretBundle, productType: String): Call =
        get(MIX_ACCOUNTS_PATH, linkedMapOf(MIX_PARAMS to productType), credential)

    // ----------------------------------------------------------------------- transport

    private fun get(path: String, params: Map<String, String>, credential: BitgetSecretBundle): Call {
        val query = encodeQuery(params)
        val timestamp = timestampOf(System.currentTimeMillis())
        // Body is empty for a GET, which the docs say may be omitted from the pre-hash.
        val signature = sign(preHash(timestamp, METHOD, path, query, ""), credential.apiSecret)

        var connection: HttpURLConnection? = null
        return try {
            val target = URL(CryptoEndpoints.bitgetApi.first() + path + if (query.isBlank()) "" else "?$query")
            if (target.protocol != "https" || !isAllowedHost(target.host)) {
                return Call.Rejected(
                    BitgetFailure(
                        kind = BitgetFailureKind.OTHER,
                        message = "The Bitget address this build is configured for was rejected by " +
                            "the security check. Update the app and try again."
                    )
                )
            }
            connection = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = METHOD
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // A redirect would replay the ACCESS-KEY header to whichever host the server names,
                // so redirects are refused instead of followed.
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "SecureWalletPro/3.0")
                setRequestProperty("locale", "en-US")
                setRequestProperty("ACCESS-KEY", credential.apiKey)
                setRequestProperty("ACCESS-TIMESTAMP", timestamp)
                setRequestProperty("ACCESS-SIGN", signature)
                setRequestProperty("ACCESS-PASSPHRASE", credential.passphrase)
            }
            val httpCode = connection.responseCode
            val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.buffered()?.readLimited(MAX_RESPONSE_BYTES).orEmpty()

            if (httpCode == 429) return Call.Rejected(failureOf(httpCode = 429, code = "429"))
            if (httpCode in 300..399) return Call.Rejected(
                BitgetFailure(
                    kind = BitgetFailureKind.NETWORK,
                    message = "Something between you and Bitget tried to redirect the request, and it " +
                        "was refused to keep your key from being sent on. Turn off any VPN or " +
                        "proxy-based firewall and refresh.",
                    code = httpCode.toString()
                )
            )

            val reply = decodeEnvelope(text)
            if (!reply.accepted) return Call.Rejected(failureOf(httpCode = httpCode, code = reply.code))
            if (httpCode !in 200..299) return Call.Rejected(failureOf(httpCode = httpCode, code = ""))
            Call.Accepted(reply.data)
        } catch (failure: Throwable) {
            if (failure is Oversized) {
                Call.Rejected(
                    BitgetFailure(
                        kind = BitgetFailureKind.OTHER,
                        message = "Bitget sent a reply far larger than an account balance sheet " +
                            "should be, so it was discarded before reading it all. Refresh again in " +
                            "a moment.",
                        code = ""
                    )
                )
            } else {
                Call.Rejected(networkFailure(failure))
            }
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Raised by [readLimited]; carries no data, so it can never leak a response body. */
    private class Oversized : Exception("Bitget response exceeded the read limit")

    private fun isAllowedHost(host: String): Boolean =
        CryptoEndpoints.bitgetApi.any { base ->
            runCatching { URL(base).host.equals(host, ignoreCase = true) }.getOrDefault(false)
        }

    /** Reads at most [limit] bytes so a runaway response cannot exhaust the heap. */
    private fun java.io.BufferedInputStream.readLimited(limit: Int): String {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                runCatching { close() }
                throw Oversized()
            }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }
}
