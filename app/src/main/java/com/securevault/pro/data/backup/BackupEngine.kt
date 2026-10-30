package com.securevault.pro.data.backup

import android.util.Base64
import com.securevault.pro.data.file.FileBackupItem
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.local.dao.DigitalCardDao
import com.securevault.pro.data.local.dao.SecureContactDao
import com.securevault.pro.data.local.dao.SecureNoteDao
import com.securevault.pro.data.local.dao.TotpDao
import com.securevault.pro.data.local.dao.VaultDao
import com.securevault.pro.data.local.entity.DigitalCardEntity
import com.securevault.pro.data.local.entity.SecureContactEntity
import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.TotpEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import com.securevault.pro.data.wallet.WalletRepository
import com.securevault.pro.security.CryptoManager
import com.securevault.pro.security.PasswordAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class MergeResult(
    val entries: Int = 0,
    val notes: Int = 0,
    val cards: Int = 0,
    val totps: Int = 0,
    val contacts: Int = 0,
    val files: Int = 0,
    val wallets: Int = 0,
    val skipped: Int = 0,
    val wipeApplied: Boolean = false,
    val warnings: List<String> = emptyList()
) {
    val total: Int get() = entries + notes + cards + totps + contacts + files + wallets

    fun toCounts() = BackupCounts(entries, notes, cards, totps, contacts, files, wallets)
}

/**
 * Stops a restore inventing a second copy of a record it already holds under a
 * different id — what happens when the same vault is imported twice or a foreign
 * backup uses its own keys. Only logins, authenticators and cards are content-checked,
 * because those are the tables whose identifying columns are stored in the clear;
 * notes, contacts and files are matched by id alone, since the fields that would tell
 * them apart are sealed and would have to be decrypted first.
 */
internal class DuplicateIndex(rows: List<Pair<String, String>>) {
    private val ids = rows.mapTo(HashSet()) { it.first }
    private val signatures = rows.mapTo(HashSet()) { it.second }

    fun isDuplicate(id: String, signature: String): Boolean {
        if (ids.contains(id)) {
            signatures += signature
            return false
        }
        if (signatures.contains(signature)) return true
        ids += id
        signatures += signature
        return false
    }

    companion object {
        fun key(vararg parts: String?): String =
            parts.joinToString("|") { it.orEmpty().trim().lowercase(java.util.Locale.US) }
    }
}

@Singleton
class BackupEngine @Inject constructor(
    private val vaultDao: VaultDao,
    private val noteDao: SecureNoteDao,
    private val cardDao: DigitalCardDao,
    private val totpDao: TotpDao,
    private val contactDao: SecureContactDao,
    private val crypto: CryptoManager,
    private val analyzer: PasswordAnalyzer,
    private val fileVault: FileVaultRepository,
    private val walletRepository: WalletRepository
) {

    suspend fun buildPayload(): BackupPayloadDto = withContext(Dispatchers.IO) {
        val entries = vaultDao.getAllActiveEntries().map { it.toBackupDto() }
        val notes = noteDao.getAll().map { it.toBackupDto() }
        val cards = cardDao.getAll().map { it.toBackupDto() }
        val totps = totpDao.getAll().map { it.toBackupDto() }
        val contacts = contactDao.getAll().map { it.toBackupDto() }
        val files = fileVault.buildBackupItems().map { it.toBackupDto() }
        val wallets = walletRepository.buildBackup()
        BackupPayloadDto(
            version = 3,
            entries = entries,
            notes = notes,
            cards = cards,
            totps = totps,
            contacts = contacts,
            files = files,
            cryptoWallets = wallets
        )
    }

    suspend fun merge(
        parsed: ParsedBackup,
        replace: Boolean = false,
        onStage: suspend (String, Int) -> Unit = { _, _ -> }
    ): MergeResult = withContext(Dispatchers.IO) {
        val warnings = parsed.warnings.toMutableList()
        // Only an explicit `replace` from the owner can delete anything. A backup file that carries
        // `wipe:true` used to erase all six tables and the file vault by itself, and the startup
        // cloud hydration fed downloaded Drive envelopes straight through here — so one poisoned or
        // stale snapshot emptied the vault with no user action at all. The flag is now reported, never
        // honoured; nothing in this app ever writes it, so it only ever came from foreign files.
        val wipeApplied = replace
        if (parsed.wipeRequested && !replace) {
            warnings.add("This backup asked for the vault to be cleared first. Nothing was deleted - add it to what you already have.")
        }
        if (wipeApplied) {
            vaultDao.deleteAll()
            noteDao.deleteAll()
            cardDao.deleteAll()
            totpDao.deleteAll()
            contactDao.deleteAll()
            walletRepository.deleteAll()
            fileVault.wipeAll()
        }
        val payload = parsed.payload

        onStage("Importing passwords and logins", 10)
        val entryIndex = DuplicateIndex(
            vaultDao.getAllActiveEntries().map {
                it.id to DuplicateIndex.key(it.category, it.title, it.username, it.email, it.website)
            }
        )
        var skippedEntries = 0
        val entryEntities = payload.entries.mapNotNull { dto ->
            if (dto.deletedAt != null) return@mapNotNull null
            val title = dto.title?.trim().orEmpty().ifBlank { "Untitled Entry" }
            val password = sealImported(dto.password.orEmpty())
            VaultEntryEntity(
                id = dto.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                title = title,
                username = dto.username.orEmpty(),
                email = dto.email.orEmpty(),
                password = password,
                website = dto.website?.takeIf { it.isNotBlank() } ?: dto.url.orEmpty(),
                url = dto.url?.takeIf { it.isNotBlank() } ?: dto.website.orEmpty(),
                notes = sealImported(dto.notes.orEmpty()),
                category = normalizeEntryCategory(dto.category),
                customFieldsJson = normalizeJsonArray(dto.customFieldsJson),
                tagsJson = normalizeJsonArray(dto.tagsJson),
                isFavorite = dto.isFavorite ?: false,
                hasTotp = (dto.hasTotp ?: false) || !dto.totpSecret.isNullOrBlank(),
                totpSecret = sealImported(dto.totpSecret.orEmpty()),
                createdAt = dto.createdAt ?: System.currentTimeMillis(),
                updatedAt = dto.updatedAt ?: dto.createdAt ?: System.currentTimeMillis(),
                lastAccessedAt = dto.lastAccessedAt,
                expiresAt = dto.expiresAt,
                strengthScore = dto.strengthScore?.takeIf { it > 0 } ?: analyzer.scoreFor(dto.password.orEmpty()),
                deletedAt = null,
                isHiddenInTravel = dto.isHiddenInTravel ?: false
            )
        }
        var done = 0
        val freshEntries = entryEntities.filter { entity ->
            val duplicate = entryIndex.isDuplicate(
                entity.id,
                DuplicateIndex.key(entity.category, entity.title, entity.username, entity.email, entity.website)
            )
            if (duplicate) skippedEntries++
            !duplicate
        }
        freshEntries.chunked(100).forEach { chunk ->
            vaultDao.upsertAll(chunk)
            done += chunk.size
            onStage("Importing passwords", 10 + (15.0 * done / freshEntries.size.coerceAtLeast(1)).toInt())
        }

        onStage("Importing 2FA authenticators", 30)
        val totpIndex = DuplicateIndex(
            totpDao.getAll().map { it.id to DuplicateIndex.key(it.issuer, it.accountName) }
        )
        var skippedTotps = 0
        val totpEntities = payload.totps.map { dto ->
            TotpEntity(
                id = dto.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                issuer = dto.issuer?.trim().orEmpty().ifBlank { "Authenticator" },
                accountName = dto.accountName.orEmpty(),
                secret = sealImported(dto.secret.orEmpty()),
                algorithm = dto.algorithm?.uppercase()?.takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: "SHA1",
                digits = (dto.digits ?: 6).coerceIn(6, 8),
                period = (dto.period ?: 30).coerceIn(15, 120),
                createdAt = dto.createdAt ?: System.currentTimeMillis()
            )
        }.filter { it.secret.isNotBlank() }.filter { entity ->
            val duplicate = totpIndex.isDuplicate(
                entity.id,
                DuplicateIndex.key(entity.issuer, entity.accountName)
            )
            if (duplicate) skippedTotps++
            !duplicate
        }
        totpEntities.chunked(100).forEach { totpDao.upsertAll(it) }

        onStage("Importing secure notes", 40)
        val noteEntities = payload.notes.map { dto ->
            SecureNoteEntity(
                id = dto.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                title = dto.title?.trim().orEmpty().ifBlank { "Untitled Note" },
                content = sealImported(dto.content.orEmpty()),
                isPinned = dto.isPinned ?: false,
                color = dto.color?.uppercase()?.takeIf { it in setOf("DEFAULT", "CYAN", "MINT", "LAVENDER", "CORAL") } ?: "DEFAULT",
                tagsJson = dto.tagsJson?.takeIf { it.isNotBlank() } ?: "[]",
                createdAt = dto.createdAt ?: System.currentTimeMillis(),
                updatedAt = dto.updatedAt ?: dto.createdAt ?: System.currentTimeMillis()
            )
        }
        noteEntities.chunked(100).forEach { noteDao.upsertAll(it) }

        onStage("Importing digital cards", 50)
        val cardIndex = DuplicateIndex(
            cardDao.getAll().map { it.id to DuplicateIndex.key(it.type, it.nickname, it.holderName) }
        )
        var skippedCards = 0
        val cardEntities = payload.cards.map { dto ->
            DigitalCardEntity(
                id = dto.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                type = dto.type?.uppercase()?.takeIf { it in setOf("CREDIT", "DEBIT", "CRYPTO", "ID") } ?: "CREDIT",
                nickname = dto.nickname?.trim().orEmpty().ifBlank { "Card" },
                holderName = dto.holderName.orEmpty(),
                number = sealImported(dto.number.orEmpty()),
                expiryMonth = (dto.expiryMonth ?: 1).coerceIn(1, 12),
                expiryYear = (dto.expiryYear ?: 30).let { if (it > 99) it % 100 else it },
                cvv = sealImported(dto.cvv.orEmpty()),
                pin = sealImported(dto.pin.orEmpty()),
                bankName = dto.bankName.orEmpty(),
                notes = sealImported(dto.notes.orEmpty()),
                colorGradientStart = dto.colorGradientStart ?: "#1E273E",
                colorGradientEnd = dto.colorGradientEnd ?: "#141B2D",
                createdAt = dto.createdAt ?: System.currentTimeMillis()
            )
        }
        val freshCards = cardEntities.filter { entity ->
            val duplicate = cardIndex.isDuplicate(
                entity.id,
                DuplicateIndex.key(entity.type, entity.nickname, entity.holderName)
            )
            if (duplicate) skippedCards++
            !duplicate
        }
        freshCards.chunked(100).forEach { cardDao.upsertAll(it) }

        onStage("Importing encrypted contacts", 65)
        val contactEntities = payload.contacts.map { dto ->
            SecureContactEntity(
                id = dto.id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                name = dto.name?.trim().orEmpty().ifBlank { "Unnamed Contact" },
                phone = sealImported(dto.phone.orEmpty()),
                email = sealImported(dto.email.orEmpty()),
                address = sealImported(dto.address.orEmpty()),
                notes = sealImported(dto.notes.orEmpty()),
                category = dto.category?.takeIf { it in setOf("Personal", "Work", "Emergency", "VIP") } ?: "Personal",
                createdAt = dto.createdAt ?: System.currentTimeMillis(),
                updatedAt = dto.updatedAt ?: dto.createdAt ?: System.currentTimeMillis()
            )
        }
        done = 0
        contactEntities.chunked(250).forEach { chunk ->
            contactDao.upsertAll(chunk)
            done += chunk.size
            onStage(
                "Batch-inserting contacts",
                65 + (20.0 * done / contactEntities.size.coerceAtLeast(1)).toInt()
            )
        }

        onStage("Restoring crypto wallets", 86)
        var walletCount = 0
        payload.cryptoWallets.forEach { dto ->
            if (walletRepository.restore(dto)) walletCount++
            else warnings += "Wallet '${dto.label ?: "Wallet"}' was skipped"
        }

        onStage("Restoring encrypted files", 88)
        var fileCount = 0
        payload.files.forEach { dto ->
            val raw = dto.data?.let {
                try {
                    Base64.decode(it, Base64.DEFAULT)
                } catch (_: Throwable) {
                    null
                }
            }
            if (raw != null && raw.isNotEmpty()) {
                val result = fileVault.importEncryptedOrRaw(
                    id = dto.id,
                    displayName = dto.name ?: "File",
                    bytes = raw,
                    mimeType = dto.mimeType ?: dto.type ?: "application/octet-stream",
                    category = dto.category ?: "Docs",
                    createdAt = dto.createdAt ?: System.currentTimeMillis(),
                    uploadedAt = dto.uploadedAt ?: System.currentTimeMillis()
                )
                if (result.isSuccess) fileCount++
            } else {
                warnings += "File '${dto.name}' had invalid data and was skipped"
            }
        }
        onStage("Finalising local database", 96)
        MergeResult(
            entries = freshEntries.size,
            notes = noteEntities.size,
            cards = freshCards.size,
            totps = totpEntities.size,
            contacts = contactEntities.size,
            files = fileCount,
            wallets = walletCount,
            skipped = skippedEntries + skippedTotps + skippedCards,
            wipeApplied = wipeApplied,
            warnings = warnings.distinct().take(8)
        )
    }

    suspend fun countAll(): BackupCounts = withContext(Dispatchers.IO) {
        BackupCounts(
            entries = vaultDao.countActive(),
            notes = noteDao.count(),
            cards = cardDao.count(),
            totps = totpDao.count(),
            contacts = contactDao.count(),
            files = fileVault.count(),
            wallets = walletRepository.count()
        )
    }

    suspend fun isEmpty(): Boolean = countAll().total == 0

    fun sealImported(value: String): String {
        if (value.isBlank()) return ""
        // Sealed values — local or foreign — are preserved instead of double-encrypting.
        if (crypto.isEncryptedBlob(value)) return value
        return crypto.encrypt(value)
    }

    private fun reveal(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return crypto.decrypt(value) ?: value
    }

    private fun VaultEntryEntity.toBackupDto() = BackupEntryDto(
        id = id,
        title = title,
        username = username,
        email = email,
        password = reveal(password),
        website = website,
        url = url,
        notes = reveal(notes),
        category = category,
        customFieldsJson = customFieldsJson,
        tagsJson = tagsJson,
        isFavorite = isFavorite,
        hasTotp = hasTotp,
        totpSecret = reveal(totpSecret),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessedAt = lastAccessedAt,
        expiresAt = expiresAt,
        strengthScore = strengthScore,
        deletedAt = deletedAt,
        isHiddenInTravel = isHiddenInTravel
    )

    private fun SecureNoteEntity.toBackupDto() = BackupNoteDto(
        id = id,
        title = title,
        content = reveal(content),
        isPinned = isPinned,
        color = color,
        tagsJson = tagsJson,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun DigitalCardEntity.toBackupDto() = BackupCardDto(
        id = id,
        type = type,
        nickname = nickname,
        holderName = holderName,
        number = reveal(number),
        expiryMonth = expiryMonth,
        expiryYear = expiryYear,
        cvv = reveal(cvv),
        pin = reveal(pin),
        bankName = bankName,
        notes = reveal(notes),
        colorGradientStart = colorGradientStart,
        colorGradientEnd = colorGradientEnd,
        createdAt = createdAt
    )

    private fun TotpEntity.toBackupDto() = BackupTotpDto(
        id = id,
        issuer = issuer,
        accountName = accountName,
        secret = reveal(secret),
        algorithm = algorithm,
        digits = digits,
        period = period,
        createdAt = createdAt
    )

    private fun SecureContactEntity.toBackupDto() = BackupContactDto(
        id = id,
        name = name,
        phone = reveal(phone),
        email = reveal(email),
        address = reveal(address),
        notes = reveal(notes),
        category = category,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun FileBackupItem.toBackupDto() = BackupFileDto(
        id = id,
        name = name,
        size = size,
        type = type,
        mimeType = mimeType,
        category = category,
        data = data,
        createdAt = createdAt,
        uploadedAt = uploadedAt
    )

    private fun normalizeEntryCategory(raw: String?): String {
        val value = raw?.trim()?.uppercase() ?: return "WEBSITES"
        return when (value) {
            "LOGIN", "LOGINS", "PASSWORD", "PASSWORDS" -> "LOGINS"
            "WEBSITE", "WEBSITES", "WEB" -> "WEBSITES"
            "APP", "APPS" -> "APPS"
            "FINANCE", "BANK", "BANKING" -> "FINANCE"
            else -> "OTHERS"
        }
    }

    private fun normalizeJsonArray(value: String?): String {
        if (value.isNullOrBlank()) return "[]"
        val trimmed = value.trim()
        return if ((trimmed.startsWith("[") && trimmed.endsWith("]")) ||
            (trimmed.startsWith("{") && trimmed.endsWith("}"))
        ) trimmed else "[]"
    }
}
