package com.securevault.pro.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.securevault.pro.diagnostics.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM. A random data-encryption key (DEK) is wrapped by the non-exportable
 * Android Keystore key and used for bulk field/file encryption, which avoids the
 * costly per-operation Keystore round trip while keeping the DEK at rest protected.
 * On-disk ciphertext format: base64(iv).base64(ciphertext + tag)
 */
@Singleton
class CryptoManager @Inject constructor(
    @ApplicationContext context: Context
) {

    companion object {
        private const val KEY_ALIAS = "SecureVaultProMasterKey"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val MAX_FIELD_LENGTH = 8 * 1024 * 1024
        private const val PREFS = "svp_crypto_prefs"
        private const val DEK_WRAPPED = "dek_wrapped_v1"

        /**
         * A wrapped DEK that would not unwrap, kept aside instead of being overwritten. It holds
         * no usable key on its own — only this device's Keystore can open it — so keeping it costs
         * nothing and is the owner's only route back to records sealed before the fault.
         */
        private const val DEK_WRAPPED_ORPHANED = "dek_wrapped_orphaned_v1"
        private const val DEK_BYTES = 32
        private const val STREAM_CHUNK_BYTES = 128 * 1024
    }

    private val appContext = context.applicationContext

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val lock = Any()

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var cachedDek: SecretKey? = null

    private val cipherPool = ThreadLocal.withInitial {
        Cipher.getInstance(TRANSFORMATION)
    }

    fun encrypt(plaintext: String): String {
        require(plaintext.length <= MAX_FIELD_LENGTH) { "Field too large" }
        return encryptBytesInternal(plaintext.toByteArray(Charsets.UTF_8))
    }

    /** Returns null when the blob was not produced by this device's Keystore key. */
    fun decrypt(ciphertext: String?): String? {
        if (ciphertext.isNullOrEmpty()) return null
        return try {
            val bytes = decryptBytesInternal(ciphertext) ?: return null
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun encryptBytes(data: ByteArray): String = encryptBytesInternal(data)

    fun decryptBytes(ciphertext: String?): ByteArray? {
        if (ciphertext.isNullOrEmpty()) return null
        return try {
            decryptBytesInternal(ciphertext)
        } catch (_: Throwable) {
            null
        }
    }

    fun isEncryptedBlob(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        val parts = trimmed.split('.')
        if (parts.size !in 2..3) return false
        return try {
            val iv = decodeBase64(parts[0])
            val ct = decodeBase64(parts[1])
            iv.size == IV_BYTES && ct.size > GCM_TAG_BITS / 8
        } catch (_: Throwable) {
            false
        }
    }

    /** True when the blob was sealed with the current data key (not legacy Keystore ciphertext). */
    fun isDataKeyBlob(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val parts = value.trim().split('.')
        if (parts.size != 2) return false
        return try {
            val iv = decodeBase64(parts[0])
            if (iv.size != IV_BYTES) return false
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(decodeBase64(parts[1]))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun encryptWithDerivedKey(plaintext: String, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return base64(iv) + "." + base64(ct)
    }

    fun decryptWithDerivedKey(ciphertext: String, keyBytes: ByteArray): String? {
        return try {
            val parts = ciphertext.trim().split('.')
            require(parts.size == 2) { "Invalid envelope" }
            val iv = decodeBase64(parts[0])
            val ct = decodeBase64(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun encryptRawBytesWithDerivedKey(data: ByteArray, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        return base64(iv) + "." + base64(ct)
    }

    fun decryptRawBytesWithDerivedKey(ciphertext: String, keyBytes: ByteArray): ByteArray? {
        return try {
            val parts = ciphertext.trim().split('.')
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, decodeBase64(parts[0]))
            )
            cipher.doFinal(decodeBase64(parts[1]))
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    fun encodeBase64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    /** Encrypts raw bytes for the encrypted file vault: iv(12) || ciphertext. */
    fun encryptFileBytes(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        return iv + ct
    }

    fun decryptFileBytes(data: ByteArray): ByteArray? {
        if (data.size <= IV_BYTES + GCM_TAG_BITS / 8) return null
        val iv = data.copyOfRange(0, IV_BYTES)
        val ct = data.copyOfRange(IV_BYTES, data.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ct)
        } catch (_: Throwable) {
            // Legacy containers were sealed directly with the Keystore key.
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Streams a large file into the same iv(12) || ciphertext container as
     * [encryptFileBytes], so gigabyte-sized vault files never have to sit in RAM.
     * Returns the plaintext byte count.
     */
    fun encryptFileToStream(source: File, target: File): Long {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey())
        var written = 0L
        FileOutputStream(target).use { out ->
            out.write(cipher.iv)
            FileInputStream(source).use { input ->
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    written += read
                    out.write(cipher.update(buffer, 0, read))
                }
            }
            out.write(cipher.doFinal())
            out.flush()
            out.fd.sync()
        }
        return written
    }

    /**
     * Reverse of [encryptFileToStream]. GCM only authenticates at the final block, so a
     * tampered or foreign-key container is discarded here and never leaves a partial file.
     */
    fun decryptFileToStream(source: File, target: File): Boolean {
        val header = ByteArray(IV_BYTES)
        FileInputStream(source).use { input ->
            if (input.read(header) != IV_BYTES) return false
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(GCM_TAG_BITS, header))
            FileOutputStream(target).use { out ->
                FileInputStream(source).use { input ->
                    input.skip(IV_BYTES.toLong())
                    val buffer = ByteArray(STREAM_CHUNK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(cipher.update(buffer, 0, read))
                    }
                    out.write(cipher.doFinal())
                    out.flush()
                }
            }
            true
        } catch (_: Throwable) {
            target.delete()
            // Pre-stream containers were sealed straight with the Keystore key and are
            // all far below the size that would make reading them whole unsafe.
            val legacy = runCatching {
                val raw = FileInputStream(source).use { it.readBytes() }
                decryptFileBytes(raw.copyOfRange(IV_BYTES, raw.size))
            }.getOrNull() ?: return false
            runCatching { FileOutputStream(target).use { it.write(legacy) } }.isSuccess
        }
    }

    fun healthCheck(): Boolean {
        return try {
            val probe = "svp-health-" + System.nanoTime()
            decrypt(encrypt(probe)) == probe
        } catch (_: Throwable) {
            false
        }
    }

    /** Destroys the wrapping key and the wrapped DEK so every existing ciphertext becomes unreadable. */
    fun deleteKeystoreKey() = synchronized(lock) {
        cachedKey = null
        cachedDek = null
        runCatching { prefs.edit().remove(DEK_WRAPPED).remove(DEK_WRAPPED_ORPHANED).commit() }
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
        Unit
    }

    /**
     * Wraps key material with the non-exportable Keystore key itself — the same container and the
     * same `base64(iv).base64(ciphertext+tag)` shape the wrapped data key at [dataKey] uses, so a
     * per-user vault key is sealed by exactly the root the rest of the app already trusts.
     *
     * Returns null instead of throwing: a Keystore that will not answer is a sync that cannot
     * happen, not a reason to kill the screen the owner is standing on.
     */
    fun wrapWithKeystore(data: ByteArray): String? {
        if (data.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            base64(cipher.iv) + "." + base64(cipher.doFinal(data))
        } catch (_: Throwable) {
            null
        }
    }

    /** Reverse of [wrapWithKeystore]. Null when the blob is foreign, damaged or Keystore-lost. */
    fun unwrapWithKeystore(wrapped: String?): ByteArray? {
        if (wrapped.isNullOrBlank()) return null
        return try {
            val parts = wrapped.trim().split('.')
            require(parts.size == 2) { "Invalid keystore envelope" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(),
                GCMParameterSpec(GCM_TAG_BITS, decodeBase64(parts[0]))
            )
            cipher.doFinal(decodeBase64(parts[1]))
        } catch (_: Throwable) {
            null
        }
    }

    /** Wrapped DEK used for bulk encryption; created on first use. */
    private fun dataKey(): SecretKey {
        cachedDek?.let { return it }
        synchronized(lock) {
            cachedDek?.let { return it }
            val stored = prefs.getString(DEK_WRAPPED, null)
            if (!stored.isNullOrBlank()) {
                runCatching {
                    val parts = stored.split('.')
                    require(parts.size == 2)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        keystoreKey(),
                        GCMParameterSpec(GCM_TAG_BITS, decodeBase64(parts[0]))
                    )
                    val raw = cipher.doFinal(decodeBase64(parts[1]))
                    if (raw.size == DEK_BYTES) {
                        return SecretKeySpec(raw, "AES").also { cachedDek = it }
                    }
                }
                // Reaching here means a wrapped DEK exists but would not unwrap. That is far more
                // often a transient Keystore fault than a destroyed key, and silently replacing the
                // blob makes every record already in the database unreadable for good. Keep the
                // blob under its own key so a later launch — when the Keystore answers again — can
                // still open the vault, and say so in the report.
                if (prefs.getString(DEK_WRAPPED_ORPHANED, null).isNullOrBlank()) {
                    runCatching { prefs.edit().putString(DEK_WRAPPED_ORPHANED, stored).commit() }
                    CrashLogger.noteFault(
                        appContext,
                        "The stored data key would not unwrap; the previous copy was kept for recovery",
                        IllegalStateException("Keystore refused the wrapped DEK")
                    )
                }
            }
            val fresh = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val wrapped = base64(cipher.iv) + "." + base64(cipher.doFinal(fresh))
            prefs.edit().putString(DEK_WRAPPED, wrapped).commit()
            return SecretKeySpec(fresh, "AES").also { cachedDek = it }
        }
    }

    private fun encryptBytesInternal(data: ByteArray): String {
        val cipher = cipherPool.get() ?: Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey())
        val ct = cipher.doFinal(data)
        return base64(cipher.iv) + "." + base64(ct)
    }

    private fun decryptBytesInternal(value: String): ByteArray? {
        val trimmed = value.trim()
        if (trimmed.startsWith("data:")) {
            val comma = trimmed.indexOf(',')
            if (comma > 0) return decodeBase64(trimmed.substring(comma + 1))
        }
        val parts = trimmed.split('.')
        if (parts.size == 2) {
            val iv = decodeBase64(parts[0])
            if (iv.size != IV_BYTES) return null
            val ct = decodeBase64(parts[1])
            val cipher = cipherPool.get() ?: Cipher.getInstance(TRANSFORMATION)
            return try {
                cipher.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(ct)
            } catch (_: Throwable) {
                // Legacy ciphertext sealed directly with the Keystore key.
                val legacy = Cipher.getInstance(TRANSFORMATION)
                legacy.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                legacy.doFinal(ct)
            }
        }
        if (parts.size == 3) {
            // Legacy split-ciphertext format from earlier builds.
            val iv = decodeBase64(parts[0])
            val a = decodeBase64(parts[1])
            val b = decodeBase64(parts[2])
            val cipher = cipherPool.get() ?: Cipher.getInstance(TRANSFORMATION)
            return try {
                cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(b + a)
            } catch (_: Throwable) {
                cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(a + b)
            }
        }
        return null
    }

    private fun keystoreKey(): SecretKey = synchronized(lock) {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            cachedKey = existing
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        val generated = generator.generateKey()
        cachedKey = generated
        generated
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
