package com.securevault.pro.security.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * One backup envelope, however old, reduced to the three things decryption needs.
 *
 * [salt] and [data] are the bytes PBKDF2 and AES-GCM run over. [version] and [iterations] are what
 * the file claims about itself — claims, not facts: the shipped writers have always recorded the
 * format they used, and [ITERATION_FIELDS] is the union of the field names they have used to say so.
 * A file that predates the field reports null and the opener sweeps the known counts instead.
 */
data class BackupEnvelope(
    val salt: ByteArray,
    val data: String,
    val version: Int,
    val iterations: Int?,
    /** True when the object had a recognisable envelope shape; false for a plain or unknown file. */
    val recognised: Boolean,
    /**
     * False when the file states no version at all. [version] still carries the current default so
     * diagnostics read sensibly, but an unstated file is older than v3 for decryption purposes: the
     * reader must offer it the pre-v3 keys and counts, not the narrower current list.
     */
    val statedVersion: Boolean = true
) {
    val saltBytes: Int get() = salt.size

    /** `iv.ciphertext` is the shipped shape; anything else is a legacy or foreign layout. */
    val payloadShape: String
        get() = when (data.trim().split('.').size) {
            2 -> "iv.payload"
            3 -> "iv.part.part"
            else -> "unsplit"
        }

    override fun equals(other: Any?): Boolean = other is BackupEnvelope &&
        salt.contentEquals(other.salt) && data == other.data && version == other.version &&
        iterations == other.iterations && recognised == other.recognised

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + data.hashCode()
        result = 31 * result + version
        result = 31 * result + (iterations ?: 0)
        result = 31 * result + recognised.hashCode()
        return result
    }

    companion object {
        /** The keys past builds used for the per-file salt. */
        val SALT_FIELDS = listOf("salt", "kdfSalt", "kdf_salt")

        /** The keys past builds used for the sealed body. */
        val DATA_FIELDS = listOf("data", "ciphertext", "payload", "blob")

        /** The keys past builds used to state their own PBKDF2 count. */
        val ITERATION_FIELDS = listOf("iterations", "iter", "kdfIterations", "pbkdf2Iterations")

        /** The keys past builds used to state their envelope version. */
        val VERSION_FIELDS = listOf("v", "version")

        /** The count every release since the format landed uses when the file does not say. */
        const val DEFAULT_ITERATIONS = 600_000
    }
}

/**
 * Reads an envelope from the bytes as they sit in Drive, an export file, or a pre-wipe archive ZIP.
 *
 * Every shape the app has ever written is accepted, and one that is not an envelope at all is
 * reported as `recognised = false` rather than guessed at — a silent guess is how a plaintext export
 * once got handed to the JSON reader and came back as "corrupted backup".
 */
object BackupEnvelopeFormat {

    private val LEADING_NOISE = charArrayOf('\uFEFF', ' ', '\n', '\r', '\t')

    /**
     * Parse [bytes]. Returns null only when the input holds no JSON object at any layer: an empty
     * file, or bytes that never were an envelope. A file that parses but has no salt/body is a
     * *recognised=false* envelope, because the caller still has to tell the owner what was found.
     */
    fun parse(bytes: ByteArray): BackupEnvelope? =
        bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }?.let { parseText(it) }

    fun parseText(text: String): BackupEnvelope? {
        val trimmed = text.trim().trimStart(*LEADING_NOISE)
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{")) return readObject(trimmed)
        val decoded = decodeBase64OrNull(trimmed) ?: return null
        val asText = runCatching { decoded.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!asText.trim().startsWith("{")) return null
        return readObject(asText)
    }

    /** A sealed body as it goes on the wire: the shipped `v`/`salt`/`data` triple. */
    fun envelopeJson(version: Int, saltBase64: String, data: String): String {
        val obj = JsonObject()
        obj.addProperty("v", version)
        obj.addProperty("salt", saltBase64)
        obj.addProperty("data", data)
        return obj.toString()
    }

    private fun readObject(text: String): BackupEnvelope? {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val saltElement = firstPrimitive(obj, BackupEnvelope.SALT_FIELDS)
        val dataElement = firstPrimitive(obj, BackupEnvelope.DATA_FIELDS)
        val salt = saltElement?.let { decodeBase64OrNull(it) }
        val statedVersion = firstPrimitive(obj, BackupEnvelope.VERSION_FIELDS) != null
        if (saltElement == null || dataElement == null || salt == null) {
            return BackupEnvelope(
                salt = ByteArray(0),
                data = dataElement.orEmpty(),
                version = readVersion(obj),
                iterations = readIterations(obj),
                recognised = false,
                statedVersion = statedVersion
            )
        }
        if (salt.size < 8) {
            return BackupEnvelope(
                salt = salt,
                data = dataElement,
                version = readVersion(obj),
                iterations = readIterations(obj),
                recognised = false,
                statedVersion = statedVersion
            )
        }
        var data = dataElement.trim()
        if (!data.contains('.')) {
            val iv = firstPrimitive(obj, listOf("iv", "nonce"))
            if (iv.isNullOrBlank()) {
                return BackupEnvelope(
                    salt = salt,
                    data = data,
                    version = readVersion(obj),
                    iterations = readIterations(obj),
                    recognised = false,
                    statedVersion = statedVersion
                )
            }
            data = "$iv.$data"
        }
        return BackupEnvelope(
            salt = salt,
            data = data,
            version = readVersion(obj),
            iterations = readIterations(obj),
            recognised = true,
            statedVersion = statedVersion
        )
    }

    private fun readVersion(obj: JsonObject): Int =
        firstPrimitive(obj, BackupEnvelope.VERSION_FIELDS)?.trim()?.toIntOrNull() ?: 3

    private fun readIterations(obj: JsonObject): Int? =
        BackupEnvelope.ITERATION_FIELDS.asSequence()
            .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.toIntOrNull() }
            .firstOrNull()

    private fun firstPrimitive(obj: JsonObject, names: List<String>): String? {
        for (name in names) {
            val element = obj.get(name) ?: continue
            if (!element.isJsonPrimitive) continue
            val value = element.asString.trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    fun decodeBase64OrNull(value: String): ByteArray? {
        val clean = value.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.isBlank()) return null
        return runCatching { Base64.getMimeDecoder().decode(clean) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
    }

    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
