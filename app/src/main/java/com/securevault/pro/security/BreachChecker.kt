package com.securevault.pro.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class BreachCandidate(
    val key: String,
    val password: String
)

data class BreachAudit(
    val checked: Int,
    val uniquePasswords: Int,
    val breached: Map<String, Long>,
    val failedLookups: Int,
    val checkedAt: Long
)

/**
 * Talks to the Pwned Passwords range API using k-anonymity: only the first five
 * characters of the SHA-1 digest ever leave the device, and the response is
 * padded so the server cannot infer which suffix was queried.
 */
@Singleton
class BreachChecker @Inject constructor() {

    companion object {
        private const val ENDPOINT = "https://api.pwnedpasswords.com/range/"
        private const val CACHE_LIMIT = 192
        private const val TIMEOUT_MS = 15_000
    }

    private val cache = object : LinkedHashMap<String, Map<String, Long>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Long>>?): Boolean =
            size > CACHE_LIMIT
    }

    suspend fun audit(candidates: List<BreachCandidate>): Result<BreachAudit> = withContext(Dispatchers.IO) {
        try {
            val unique = candidates
                .filter { it.password.isNotBlank() }
                .associateBy { it.password }
                .values
            if (unique.isEmpty()) {
                return@withContext Result.success(
                    BreachAudit(
                        checked = 0,
                        uniquePasswords = 0,
                        breached = emptyMap(),
                        failedLookups = 0,
                        checkedAt = System.currentTimeMillis()
                    )
                )
            }
            val limiter = Semaphore(4)
            var failures = 0
            val outcomes = coroutineScope {
                unique.map { candidate ->
                    async {
                        candidate.password to limiter.withPermit {
                            runCatching { lookup(candidate.password) }
                        }
                    }
                }.awaitAll()
            }
            val breachedByPassword = buildMap {
                outcomes.forEach { (password, outcome) ->
                    val value = outcome.getOrNull()
                    if (value == null) failures++ else put(password, value)
                }
            }
            val breached = linkedMapOf<String, Long>()
            candidates.forEach { candidate ->
                val count = breachedByPassword[candidate.password] ?: 0L
                if (count > 0) breached[candidate.key] = count
            }
            if (failures > 0 && failures == unique.size) {
                Result.failure(IOException("Breach service is unreachable. Check your connection and retry."))
            } else {
                Result.success(
                    BreachAudit(
                        checked = candidates.size,
                        uniquePasswords = unique.size,
                        breached = breached,
                        failedLookups = failures,
                        checkedAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun lookup(password: String): Long {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
            .uppercase(Locale.US)
        val prefix = digest.substring(0, 5)
        val suffix = digest.substring(5)
        val range = cachedRange(prefix) ?: fetchRange(prefix).also { storeRange(prefix, it) }
        return range[suffix] ?: 0L
    }

    private fun cachedRange(prefix: String): Map<String, Long>? = synchronized(cache) {
        cache[prefix]
    }

    private fun storeRange(prefix: String, range: Map<String, Long>) = synchronized(cache) {
        cache[prefix] = range
    }

    private fun fetchRange(prefix: String): Map<String, Long> {
        val connection = (URL("$ENDPOINT$prefix").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Add-Padding", "true")
            setRequestProperty("User-Agent", "SecureWalletPro-Breach-Audit")
        }
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Breach service returned HTTP $code")
            }
            val result = HashMap<String, Long>(2048)
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) return@forEach
                    val hashSuffix = line.substring(0, separator).trim().uppercase(Locale.US)
                    val count = line.substring(separator + 1).trim().toLongOrNull() ?: 0L
                    if (hashSuffix.isNotEmpty()) result[hashSuffix] = count
                }
            }
            result
        } finally {
            connection.disconnect()
        }
    }
}
