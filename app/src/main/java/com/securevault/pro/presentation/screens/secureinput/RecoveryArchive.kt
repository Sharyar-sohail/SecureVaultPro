package com.securevault.pro.presentation.screens.secureinput

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.securevault.pro.data.backup.BackupCounts
import com.securevault.pro.security.CryptoManager
import com.securevault.pro.security.KeyDerivation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seals and opens a **recovery archive**: an ordinary `{"v":3,"salt":…,"data":…}` envelope whose
 * passphrase is the seed-derived [RecoverySeedLogic.recoveryPassphrase].
 *
 * That is the whole trick, and the reason no encryption format changes here. The envelope shape,
 * the PBKDF2-HMAC-SHA256 600,000 profile, the 32-byte salt and the AES-256-GCM container are the
 * ones [com.securevault.pro.data.remote.google.CloudSyncManager] already writes and reads, so:
 *  - an archive made by this screen opens in the existing *Backup → restore from a file* flow, and
 *  - every backup that exists today still decrypts exactly as before — nothing about the key
 *    derivation, the Keystore alias or the `{v:3}` reader moved.
 *
 * The recovery path adds a *passphrase*, never a key format.
 */
@Singleton
class RecoveryArchiveCodec @Inject constructor(
    private val crypto: CryptoManager,
    private val keyDerivation: KeyDerivation,
    private val gson: Gson
) {

    /** Plaintext payload JSON in, archive bytes out. The random salt is per-archive, as everywhere else. */
    fun seal(payloadJson: String, passphrase: String): ByteArray {
        val salt = keyDerivation.generateSalt()
        val key = keyDerivation.deriveKey(
            passphrase.trim().toCharArray(),
            salt,
            KeyDerivation.PBKDF2_ITERATIONS
        )
        try {
            val sealed = crypto.encryptWithDerivedKey(payloadJson, key)
            val envelope = JsonObject().apply {
                addProperty("v", ENVELOPE_VERSION)
                addProperty("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                addProperty("data", sealed)
            }
            return gson.toJson(envelope).toByteArray(Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
    }

    /** Archive bytes out to the payload JSON inside — or a named reason, never a silent null. */
    fun open(bytes: ByteArray, passphrase: String): ArchiveOpenResult {
        if (bytes.isEmpty()) return ArchiveOpenResult.Empty
        val root = runCatching { JsonParser.parseString(bytes.toString(Charsets.UTF_8).trim()) }
            .getOrNull()
        if (root == null || !root.isJsonObject) return ArchiveOpenResult.NotAnArchive
        val obj = root.asJsonObject
        val saltText = obj.stringOrNull("salt", "kdfSalt") ?: return ArchiveOpenResult.NotAnArchive
        val data = obj.stringOrNull("data", "ciphertext", "payload") ?: return ArchiveOpenResult.NotAnArchive
        val salt = runCatching { crypto.decodeBase64(saltText) }.getOrNull()
        if (salt == null || salt.size < 8) return ArchiveOpenResult.NotAnArchive
        val version = obj.get("v")?.takeIf { it.isJsonPrimitive }?.asInt ?: ENVELOPE_VERSION
        if (version > ENVELOPE_VERSION) return ArchiveOpenResult.FutureVersion(version)
        val key = runCatching {
            keyDerivation.deriveKey(passphrase.trim().toCharArray(), salt, KeyDerivation.PBKDF2_ITERATIONS)
        }.getOrNull() ?: return ArchiveOpenResult.WrongPhrase
        return try {
            val plain = crypto.decryptWithDerivedKey(data, key)
            if (plain.isNullOrBlank()) ArchiveOpenResult.WrongPhrase
            else ArchiveOpenResult.Unsealed(plain)
        } finally {
            key.fill(0)
        }
    }

    private fun JsonObject.stringOrNull(vararg names: String): String? {
        for (name in names) {
            val element = get(name) ?: continue
            if (element.isJsonPrimitive) return element.asString.trim().ifBlank { null }
        }
        return null
    }

    companion object {
        /** The envelope version the whole app writes today; a recovery archive is version 3 like any other. */
        const val ENVELOPE_VERSION = 3
    }
}

/** What opening a recovery archive ended as. Named cases so the screen can say something useful. */
sealed class ArchiveOpenResult {

    /** The payload JSON inside the envelope. Nothing may log or persist this. */
    data class Unsealed(val payloadJson: String) : ArchiveOpenResult()

    /** The envelope authenticated against no key we can produce from that phrase. */
    object WrongPhrase : ArchiveOpenResult()

    /** The bytes are not a `{v:3}` envelope at all — a photo, a text file, a truncated download. */
    object NotAnArchive : ArchiveOpenResult()

    object Empty : ArchiveOpenResult()

    /** Written by a newer app than this one; refusing it beats importing a payload we mis-read. */
    data class FutureVersion(val version: Int) : ArchiveOpenResult()

    val message: String?
        get() = when (this) {
            is Unsealed -> null
            WrongPhrase -> "This phrase did not open the recovery file. Check every word and their order."
            NotAnArchive -> "That file is not a SECURE VAULT PRO recovery archive."
            Empty -> "The selected file is empty."
            is FutureVersion -> "This archive was written by a newer version (v$version) of the app."
        }
}

/** What the seed flow remembers about itself. Never the phrase, never a phrase-derived secret. */
data class RecoverySeedRecord(
    val createdAtMillis: Long,
    val wordCount: Int,
    val customPhrase: Boolean,
    val archiveAtMillis: Long,
    val archiveBytes: Int,
    val archiveCounts: BackupCounts
)

/**
 * The on-device half of the recovery seed: the sealed archive file plus a small record of when the
 * seed was made and what the archive holds.
 *
 * Only non-secret bookkeeping is persisted — timestamps, a word count, sizes. The phrase, its BIP-39
 * seed and the derived recovery passphrase never touch this file: the archive is the proof that the
 * phrase is the right one, because a wrong phrase will not authenticate it. Keeping the copy inside
 * the app's private directory means a locked phone still recovers with no network and no Google
 * account; the owner is separately offered a copy outside the app, because a private directory does
 * not survive a factory reset.
 */
@Singleton
class RecoverySeedStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val archiveDir: File
        get() = File(context.filesDir, DIR_NAME).apply { runCatching { mkdirs() } }

    val archiveFile: File get() = File(archiveDir, ARCHIVE_NAME)

    fun record(): RecoverySeedRecord? {
        val created = prefs.getLong(KEY_CREATED_AT, 0L)
        if (created <= 0L) return null
        return RecoverySeedRecord(
            createdAtMillis = created,
            wordCount = prefs.getInt(KEY_WORD_COUNT, 0),
            customPhrase = prefs.getBoolean(KEY_CUSTOM, false),
            archiveAtMillis = prefs.getLong(KEY_ARCHIVE_AT, 0L),
            archiveBytes = prefs.getInt(KEY_ARCHIVE_BYTES, 0),
            archiveCounts = BackupCounts(
                entries = prefs.getInt(KEY_COUNT_ENTRIES, 0),
                notes = prefs.getInt(KEY_COUNT_NOTES, 0),
                cards = prefs.getInt(KEY_COUNT_CARDS, 0),
                totps = prefs.getInt(KEY_COUNT_TOTPS, 0),
                contacts = prefs.getInt(KEY_COUNT_CONTACTS, 0),
                files = prefs.getInt(KEY_COUNT_FILES, 0),
                wallets = prefs.getInt(KEY_COUNT_WALLETS, 0)
            )
        )
    }

    fun rememberSeed(wordCount: Int, customPhrase: Boolean) {
        prefs.edit()
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .putInt(KEY_WORD_COUNT, wordCount)
            .putBoolean(KEY_CUSTOM, customPhrase)
            .apply()
    }

    /** Writes the archive, replacing the previous one, and records what it holds. Returns null on failure. */
    fun writeArchive(bytes: ByteArray, counts: BackupCounts): File? {
        val target = archiveFile
        val temp = File(target.parentFile, "$ARCHIVE_NAME.tmp")
        return runCatching {
            temp.outputStream().use { it.write(bytes) }
            if (target.exists() && !target.delete()) return@runCatching null
            if (!temp.renameTo(target)) {
                target.outputStream().use { it.write(bytes) }
                temp.delete()
            }
            prefs.edit()
                .putLong(KEY_CREATED_AT, prefs.getLong(KEY_CREATED_AT, System.currentTimeMillis()))
                .putLong(KEY_ARCHIVE_AT, System.currentTimeMillis())
                .putInt(KEY_ARCHIVE_BYTES, bytes.size)
                .putInt(KEY_COUNT_ENTRIES, counts.entries)
                .putInt(KEY_COUNT_NOTES, counts.notes)
                .putInt(KEY_COUNT_CARDS, counts.cards)
                .putInt(KEY_COUNT_TOTPS, counts.totps)
                .putInt(KEY_COUNT_CONTACTS, counts.contacts)
                .putInt(KEY_COUNT_FILES, counts.files)
                .putInt(KEY_COUNT_WALLETS, counts.wallets)
                .apply()
            target
        }.getOrNull()
    }

    fun readArchive(): ByteArray? = runCatching {
        val file = archiveFile
        if (!file.exists() || file.length() <= 0) null else file.readBytes()
    }.getOrNull()

    fun hasArchive(): Boolean = archiveFile.exists() && archiveFile.length() > 0

    /** Drops the bookkeeping only. The archive file is the owner's copy and is never auto-deleted. */
    fun forgetSeed() {
        prefs.edit()
            .remove(KEY_CREATED_AT)
            .remove(KEY_WORD_COUNT)
            .remove(KEY_CUSTOM)
            .remove(KEY_ARCHIVE_AT)
            .remove(KEY_ARCHIVE_BYTES)
            .remove(KEY_COUNT_ENTRIES)
            .remove(KEY_COUNT_NOTES)
            .remove(KEY_COUNT_CARDS)
            .remove(KEY_COUNT_TOTPS)
            .remove(KEY_COUNT_CONTACTS)
            .remove(KEY_COUNT_FILES)
            .remove(KEY_COUNT_WALLETS)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "svp_recovery_seed_prefs"
        private const val DIR_NAME = "recovery_seed"
        private const val ARCHIVE_NAME = "vault_recovery.enc"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_WORD_COUNT = "word_count"
        private const val KEY_CUSTOM = "custom_phrase"
        private const val KEY_ARCHIVE_AT = "archive_at"
        private const val KEY_ARCHIVE_BYTES = "archive_bytes"
        private const val KEY_COUNT_ENTRIES = "count_entries"
        private const val KEY_COUNT_NOTES = "count_notes"
        private const val KEY_COUNT_CARDS = "count_cards"
        private const val KEY_COUNT_TOTPS = "count_totps"
        private const val KEY_COUNT_CONTACTS = "count_contacts"
        private const val KEY_COUNT_FILES = "count_files"
        private const val KEY_COUNT_WALLETS = "count_wallets"

        /** The name offered to the system file sheet, so a downloaded copy is recognisable. */
        const val SUGGESTED_COPY_NAME = "SecureWallet_RecoverySeed.enc"
    }
}
