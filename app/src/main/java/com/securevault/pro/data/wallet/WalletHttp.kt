package com.securevault.pro.data.wallet

import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one HTTP and JSON-RPC door the balance scan walks through.
 *
 * Everything here is keyless and read-only: public addresses and public contracts. Two rules are
 * load-bearing for the scan. First, every call is made against an *ordered list of hosts* and the
 * first host that answers wins, so a blocked or rate-limited public node cannot blank a chain.
 * Second, a batch that a node refuses because of its size is retried smaller rather than dropped —
 * free public nodes differ in how many calls they will take in one request, and a scan that silently
 * loses the rest of a batch reports a wallet with missing tokens, which is worse than reporting
 * nothing at all.
 */
internal object WalletHttp {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "SecureWalletPro/3.0"

    /** One JSON-RPC call inside a batch. */
    data class Call(val method: String, val params: JSONArray)

    fun get(url: String): String {
        val connection = open(url, "GET")
        return try {
            read(connection)
        } finally {
            connection.disconnect()
        }
    }

    fun post(url: String, body: String): String {
        val connection = open(url, "POST").apply { doOutput = true }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            read(connection)
        } finally {
            connection.disconnect()
        }
    }

    /** A single RPC call, answered by the first host that replies. */
    fun call(endpoints: List<String>, method: String, params: JSONArray): String {
        val body = envelope(1, method, params)
        var lastFailure: Throwable? = null
        endpoints.forEach { endpoint ->
            runCatching {
                val root = JSONObject(post(endpoint, body))
                root.optJSONObject("error")?.let { error(it.optString("message") ?: "RPC error") }
                val result = root.optString("result")
                if (result.isBlank() || result == "null") error("Empty RPC result")
                return result
            }.onFailure { lastFailure = it }
        }
        error(describeFailure("RPC", lastFailure))
    }

    /**
     * [calls] executed in as few requests as the node will allow, aligned by index in the answer.
     *
     * A slot the node could not answer is `null`, never a zero — the caller decides what a missing
     * answer means, and a zero read from a failed call would show a real token balance as empty.
     */
    fun batch(endpoints: List<String>, calls: List<Call>): List<String?> {
        if (calls.isEmpty()) return emptyList()
        val out = arrayOfNulls<String>(calls.size)
        var window = MAX_BATCH
        var index = 0
        var hostIndex = 0
        while (index < calls.size) {
            if (hostIndex >= endpoints.size) break
            val chunk = calls.subList(index, minOf(index + window, calls.size))
            runCatching {
                sendBatch(endpoints[hostIndex], chunk, index)
            }.onSuccess { results ->
                results.forEachIndexed { slot, value -> value?.let { out[index + slot] = it } }
                index += chunk.size
            }.onFailure { failure ->
                when {
                    // A free tier that caps batch size: shrink and keep going on the same host.
                    isBatchTooLarge(failure.message) -> window = shrink(window)
                    else -> hostIndex++
                }
            }
        }
        if (index < calls.size) {
            // Still unanswered: fall back to one call at a time so a partial batch is never
            // mistaken for an empty wallet.
            val remaining = calls.subList(index, calls.size)
            fallbackResults(endpoints, remaining).forEachIndexed { slot, value ->
                value?.let { out[index + slot] = it }
            }
        }
        return out.toList()
    }

    private fun fallbackResults(endpoints: List<String>, calls: List<Call>): List<String?> =
        calls.map { call -> runCatching { call(endpoints, call.method, call.params) }.getOrNull() }

    private fun sendBatch(endpoint: String, chunk: List<Call>, offset: Int): List<String?> {
        val requests = JSONArray()
        chunk.forEachIndexed { slot, call ->
            requests.put(envelopeJson(offset + slot, call.method, call.params))
        }
        val answer = JSONArray(post(endpoint, requests.toString()))
        val byId = HashMap<Int, String>(answer.length())
        for (index in 0 until answer.length()) {
            val entry = answer.optJSONObject(index) ?: continue
            val id = entry.opt("id").toString().toIntOrNull() ?: continue
            if (entry.has("error")) continue
            val result = entry.optString("result")
            if (result.isNotBlank() && result != "null") byId[id] = result
        }
        return List(chunk.size) { byId[offset + it] }
    }

    private fun envelope(id: Int, method: String, params: JSONArray): String =
        envelopeJson(id, method, params).toString()

    private fun envelopeJson(id: Int, method: String, params: JSONArray): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .put("params", params)

    private fun isBatchTooLarge(message: String?): Boolean =
        message != null && (
            message.contains("batch", true) ||
                message.contains("Batch of more than", true) ||
                message.contains("too many", true) ||
                message.contains("limit", true)
            )

    private fun shrink(window: Int): Int = when {
        window > 20 -> 20
        window > 10 -> 10
        window > 3 -> 3
        else -> 1
    }

    /** A hex quantity straight to a number; blank or malformed reads as zero. */
    fun hexToBigInteger(hex: String?): BigInteger {
        val clean = hex?.removePrefix("0x").orEmpty()
        return clean.toBigIntegerOrNull(16) ?: BigInteger.ZERO
    }

    /** Turns a transport failure into something a user can act on. */
    fun describeFailure(label: String, cause: Throwable?): String {
        val message = cause?.message.orEmpty()
        val host = Regex("//([^/]+)").find(message)?.groupValues?.getOrNull(1)
        return when {
            message.isBlank() -> "$label nodes did not answer. Check your connection and refresh again."
            message.contains("Unable to resolve host", true) ||
                message.contains("ENOTFOUND", true) ||
                host != null -> "$label nodes are unreachable from this network. " +
                "Refresh in a moment or switch to mobile data."
            message.contains("rate", true) || message.contains("429", true) ->
                "$label public nodes are rate limited right now. Wait a minute and refresh again."
            else -> "$label lookup failed: ${message.take(120)}"
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            if (method == "POST") setRequestProperty("Content-Type", "application/json")
        }

    private fun read(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException(
                if (code == 429) "rate limited (HTTP 429)" else "HTTP $code"
            )
        }
        return text
    }

    /**
     * Public nodes differ in what they accept; this is the largest chunk ever sent first. The measured
     * answer from publicnode is far above it, and every host that refuses shrinks the window.
     */
    private const val MAX_BATCH = 40
}
