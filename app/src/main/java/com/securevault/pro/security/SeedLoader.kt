package com.securevault.pro.security

import android.content.Context
import com.securevault.pro.data.backup.BackupCounts
import com.securevault.pro.data.backup.BackupEngine
import com.securevault.pro.data.backup.BackupParser
import com.securevault.pro.data.backup.MergeResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class SeedResult(
    val merge: MergeResult,
    val total: Int,
    val alreadyPopulated: Boolean = false
)

/**
 * Ships the first-run vault as an authenticated, encrypted asset. The key material is
 * intentionally static: it protects the seed against casual inspection of the APK, and
 * every value is re-sealed with this device's Keystore during the merge.
 */
@Singleton
class SeedLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupEngine: BackupEngine
) {
    companion object {
        private const val ASSET_NAME = "seed_vault.enc"
        private const val MAGIC = "SVPSEED1"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val SEED_MATERIAL =
            "svp-asset-seed::v1::b93f1a4ca6de5f72::0e7c3d91a8b5426f::seed"
    }

    suspend fun restoreIfEmpty(
        onProgress: suspend (String, Int) -> Unit = { _, _ -> }
    ): SeedResult = withContext(Dispatchers.IO) {
        if (!backupEngine.isEmpty()) {
            return@withContext SeedResult(MergeResult(), backupEngine.countAll().total, alreadyPopulated = true)
        }
        restore(onProgress)
    }

    suspend fun restore(
        onProgress: suspend (String, Int) -> Unit = { _, _ -> }
    ): SeedResult = withContext(Dispatchers.IO) {
        onProgress("Opening embedded vault", 8)
        val plain = decryptAsset()
        onProgress("Verifying vault integrity", 24)
        val parsed = BackupParser.parse(plain)
        onProgress("Provisioning passwords and contacts", 40)
        val merge = backupEngine.merge(parsed) { stage, percent ->
            onProgress(stage, 40 + (percent * 0.58f).toInt().coerceIn(0, 58))
        }
        val counts = backupEngine.countAll()
        SeedResult(merge = merge, total = counts.total)
    }

    suspend fun seedCounts(): BackupCounts = withContext(Dispatchers.IO) {
        val plain = decryptAsset()
        val payload = BackupParser.parse(plain).payload
        BackupCounts(
            entries = payload.entries.size,
            notes = payload.notes.size,
            cards = payload.cards.size,
            totps = payload.totps.size,
            contacts = payload.contacts.size,
            files = payload.files.size,
            wallets = payload.cryptoWallets.size
        )
    }

    private fun decryptAsset(): String {
        val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
        require(bytes.size > MAGIC.length + IV_BYTES + TAG_BITS / 8) { "Seed asset is truncated" }
        val header = String(bytes, 0, MAGIC.length, Charsets.US_ASCII)
        require(header == MAGIC) { "Seed asset header mismatch" }
        val iv = bytes.copyOfRange(MAGIC.length, MAGIC.length + IV_BYTES)
        val ciphertext = bytes.copyOfRange(MAGIC.length + IV_BYTES, bytes.size)
        val key = MessageDigest.getInstance("SHA-256")
            .digest(SEED_MATERIAL.toByteArray(Charsets.UTF_8))
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Throwable) {
            throw IllegalStateException("Embedded vault failed authentication", e)
        } finally {
            key.fill(0)
        }
    }

}
