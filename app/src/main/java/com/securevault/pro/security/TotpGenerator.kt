package com.securevault.pro.security

import android.net.Uri
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.pow

enum class TotpAlgorithm(val displayName: String) {
    SHA1("SHA1"),
    SHA256("SHA256"),
    SHA512("SHA512");

    companion object {
        fun from(value: String?): TotpAlgorithm {
            return when (value?.trim()?.uppercase()?.replace("-", "")) {
                "SHA256" -> SHA256
                "SHA512" -> SHA512
                else -> SHA1
            }
        }
    }
}

data class OtpAuthData(
    val issuer: String,
    val accountName: String,
    val secret: String,
    val algorithm: TotpAlgorithm,
    val digits: Int,
    val period: Int
)

object TotpGenerator {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val SUPPORTED_ALGORITHMS = setOf("SHA1", "SHA256", "SHA512")

    fun generate(
        secret: String,
        algorithm: String = "SHA1",
        digits: Int = 6,
        period: Int = 30,
        timestampMillis: Long = System.currentTimeMillis()
    ): String {
        return try {
            val key = decodeBase32(secret)
            if (key.isEmpty()) return "------"
            val safeDigits = digits.coerceIn(6, 8)
            val safePeriod = period.coerceIn(15, 120)
            val counter = timestampMillis / 1000L / safePeriod
            val message = ByteBuffer.allocate(8).putLong(counter).array()
            val mac = Mac.getInstance("Hmac" + TotpAlgorithm.from(algorithm).displayName)
            mac.init(SecretKeySpec(key, "RAW"))
            val hash = mac.doFinal(message)
            val offset = hash.last().toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
            val modulus = 10.0.pow(safeDigits).toLong()
            (binary.toLong() % modulus).toString().padStart(safeDigits, '0')
        } catch (_: Throwable) {
            "------"
        }
    }

    fun secondsRemaining(period: Int = 30, nowMillis: Long = System.currentTimeMillis()): Int {
        val safePeriod = period.coerceIn(15, 120)
        val elapsed = (nowMillis / 1000L) % safePeriod
        return (safePeriod - elapsed).toInt().let { if (it == 0) safePeriod else it }
    }

    fun progress(period: Int = 30, nowMillis: Long = System.currentTimeMillis()): Float {
        val safePeriod = period.coerceIn(15, 120)
        val fraction = ((nowMillis % (safePeriod * 1000L)).toFloat() / (safePeriod * 1000f))
        return 1f - fraction.coerceIn(0f, 1f)
    }

    fun isValidSecret(secret: String): Boolean = try {
        decodeBase32(secret).isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    fun decodeBase32(secret: String): ByteArray {
        val cleaned = sanitizeBase32(secret)
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        for (char in cleaned) {
            val index = BASE32_ALPHABET.indexOf(char)
            require(index >= 0) { "Invalid Base32 character: $char" }
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }
        return out.toByteArray()
    }

    /**
     * Tolerant Base32 normalisation, the way Aegis/Google Authenticator accept a typed
     * setup key: case-folded, every kind of space (including NBSP and zero-width
     * separators), hyphens, underscores and '=' padding removed, and the two look-alike
     * digits substituted — '0'→'O' and '1'→'I'. Those digits are absent from the Base32
     * alphabet (RFC 4648 uses ABCDEFGHIJKLMNOPQRSTUVWXYZ234567), so they can only ever be
     * typing mistakes; a secret that previously decoded to "------" now decodes correctly.
     * No stored secret is affected: every secret that passed validation before decodes to
     * exactly the same bytes.
     */
    private fun sanitizeBase32(secret: String): String = buildString(secret.length) {
        for (char in secret.uppercase()) {
            when (char) {
                '0' -> append('O')
                '1' -> append('I')
                '\u00A0', '\u2007', '\u202F', '\u200B', '\u200C', '\u2060' -> Unit
                '-', '_', '=' -> Unit
                else -> if (!char.isWhitespace()) append(char)
            }
        }
    }

    /** Canonical uppercase, unpadded Base32 for a tolerant input; null if it never decodes. */
    fun canonicalSecret(raw: String): String? = try {
        val bytes = decodeBase32(raw)
        if (bytes.isEmpty()) null else encodeBase32(bytes)
    } catch (_: Throwable) {
        null
    }

    fun encodeBase32(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val builder = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                builder.append(BASE32_ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            builder.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return builder.toString()
    }

    fun parseOtpAuth(raw: String): OtpAuthData? {
        return try {
            val uri = Uri.parse(raw.trim())
            if (!"otpauth".equals(uri.scheme, ignoreCase = true)) return null
            if (!"totp".equals(uri.host, ignoreCase = true)) return null
            val secret = canonicalSecret(uri.getQueryParameter("secret").orEmpty()) ?: return null
            // KeyURI spec: only SHA1/SHA256/SHA512 are registered algorithms, digits is
            // 6..8 and period is normally 30 or 60. A URI that explicitly asks for
            // something else (MD5, 9 digits, period 10) cannot be honoured — the old code
            // silently fell back to SHA1/6/30 and produced plausible-but-wrong codes for
            // years. Refuse the link instead so the editor can say so.
            val algorithmRaw = uri.getQueryParameter("algorithm")
            val algorithm = if (algorithmRaw.isNullOrBlank()) {
                TotpAlgorithm.SHA1
            } else {
                val normalized = algorithmRaw.trim().uppercase().replace("-", "")
                if (normalized !in SUPPORTED_ALGORITHMS) return null
                TotpAlgorithm.from(normalized)
            }
            val digitsRaw = uri.getQueryParameter("digits")
            val digits = if (digitsRaw.isNullOrBlank()) 6
            else digitsRaw.trim().toIntOrNull()?.takeIf { it in 6..8 } ?: return null
            val periodRaw = uri.getQueryParameter("period")
            val period = if (periodRaw.isNullOrBlank()) 30
            else periodRaw.trim().toIntOrNull()?.takeIf { it in 15..120 } ?: return null
            val labelRaw = Uri.decode(uri.path ?: "").trimStart('/')
            val labelIssuer = if (labelRaw.contains(':')) labelRaw.substringBefore(':').trim() else ""
            val labelAccount = if (labelRaw.contains(':')) labelRaw.substringAfter(':').trim() else labelRaw
            // Spec order: the issuer query parameter wins over the label prefix.
            val issuer = uri.getQueryParameter("issuer")?.trim()?.takeIf { it.isNotBlank() }
                ?: labelIssuer.ifBlank { "Authenticator" }
            OtpAuthData(
                issuer = issuer,
                accountName = labelAccount.ifBlank { "Account" },
                secret = secret,
                algorithm = algorithm,
                digits = digits,
                period = period
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun codesMatch(secret: String, code: String, algorithm: String = "SHA1", digits: Int = 6, period: Int = 30): Boolean {
        val generated = generate(secret, algorithm, digits, period)
        return generated == code.trim()
    }

    fun estimateRefreshDelay(period: Int): Long {
        val remaining = secondsRemaining(period)
        return ceil(remaining * 1000.0).toLong()
    }
}
