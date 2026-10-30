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
        val cleaned = secret.uppercase().replace(Regex("[\\s\\-=]"), "")
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
            val secret = uri.getQueryParameter("secret")?.trim()?.replace(" ", "") ?: return null
            if (!isValidSecret(secret)) return null
            val labelRaw = Uri.decode(uri.path ?: "").trimStart('/')
            val labelIssuer = if (labelRaw.contains(':')) labelRaw.substringBefore(':').trim() else ""
            val labelAccount = if (labelRaw.contains(':')) labelRaw.substringAfter(':').trim() else labelRaw
            val issuer = uri.getQueryParameter("issuer")?.trim()?.takeIf { it.isNotBlank() }
                ?: labelIssuer.ifBlank { "Authenticator" }
            val algorithm = TotpAlgorithm.from(uri.getQueryParameter("algorithm"))
            val digits = uri.getQueryParameter("digits")?.toIntOrNull()?.coerceIn(6, 8) ?: 6
            val period = uri.getQueryParameter("period")?.toIntOrNull()?.coerceIn(15, 120) ?: 30
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
