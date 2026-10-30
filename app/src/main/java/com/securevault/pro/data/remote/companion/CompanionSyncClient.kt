package com.securevault.pro.data.remote.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class CompanionSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Talks to the desktop companion portal. The portal only ever receives the same
 * password-derived encrypted envelope that is uploaded to Drive.
 */
@Singleton
class CompanionSyncClient @Inject constructor() {

    suspend fun health(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = endpoint(baseUrl, "/api/health")
        runCatching {
            val connection = open(url, "GET")
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    suspend fun push(baseUrl: String, envelope: ByteArray): Int = withContext(Dispatchers.IO) {
        if (envelope.isEmpty()) throw CompanionSyncException("Refusing to push an empty vault")
        val url = endpoint(baseUrl, "/api/vault")
        try {
            val connection = open(url, "PUT").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setFixedLengthStreamingMode(envelope.size)
                connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(20).toInt()
            }
            try {
                connection.outputStream.use { it.write(envelope) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw CompanionSyncException("Companion rejected the sync (HTTP $code)")
                }
                envelope.size
            } finally {
                connection.disconnect()
            }
        } catch (e: CompanionSyncException) {
            throw e
        } catch (e: IOException) {
            throw CompanionSyncException("Companion is unreachable at $url", e)
        }
    }

    suspend fun pull(baseUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val url = endpoint(baseUrl, "/api/vault")
        try {
            val connection = open(url, "GET")
            try {
                val code = connection.responseCode
                if (code == 404) throw CompanionSyncException("No vault has been synced to the companion yet.")
                if (code !in 200..299) throw CompanionSyncException("Companion download failed (HTTP $code)")
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        } catch (e: CompanionSyncException) {
            throw e
        } catch (e: IOException) {
            throw CompanionSyncException("Companion is unreachable at $url", e)
        }
    }

    private fun endpoint(baseUrl: String, path: String): URL {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isBlank()) throw CompanionSyncException("Set the companion URL in Settings first")
        if (!CompanionUrl.isTrustworthy(normalized)) {
            throw CompanionSyncException(CompanionUrl.rejectReason(normalized))
        }
        if (CompanionUrl.isStaticHosting(normalized)) {
            throw CompanionSyncException(
                "Your companion is a Blogger page. It has no server behind it, so there " +
                    "is nothing to push to or pull from. Use Google Drive sync to move " +
                    "the vault between devices; the site reads the same encrypted copy."
            )
        }
        val full = when {
            normalized.endsWith(path) -> normalized
            else -> normalized + path
        }
        return try {
            URL(full)
        } catch (e: Throwable) {
            throw CompanionSyncException("Invalid companion URL")
        }
    }

    private fun open(url: URL, method: String): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("User-Agent", "SecureWalletPro/3.0")
        return connection
    }
}
