package com.securevault.pro.data.repository

import com.google.gson.JsonParser
import com.securevault.pro.data.contacts.DeviceContact
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.local.dao.CryptoWalletDao
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
import com.securevault.pro.domain.model.CategoryCounts
import com.securevault.pro.domain.model.CustomField
import com.securevault.pro.domain.model.DecoyVault
import com.securevault.pro.domain.model.DigitalCard
import com.securevault.pro.domain.model.NoteAttachment
import com.securevault.pro.domain.model.ReusedGroup
import com.securevault.pro.domain.model.SearchHit
import com.securevault.pro.domain.model.SearchKind
import com.securevault.pro.domain.model.SecureContact
import com.securevault.pro.domain.model.SecureNote
import com.securevault.pro.domain.model.SecurityReport
import com.securevault.pro.domain.model.TotpAccount
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.CryptoManager
import com.securevault.pro.security.PasswordAnalyzer
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.SessionMode
import com.securevault.pro.security.TotpGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class ContactImportSummary(
    val imported: Int = 0,
    val merged: Int = 0,
    val skipped: Int = 0
) {
    val touched: Int get() = imported + merged
}

data class AutofillQuery(
    val webDomain: String? = null,
    val packageName: String = "",
    val appLabel: String = "",
    val typedUsername: String = "",
    val hints: List<String> = emptyList()
)

@Singleton
class VaultRepository @Inject constructor(
    private val vaultDao: VaultDao,
    private val cardDao: DigitalCardDao,
    private val totpDao: TotpDao,
    private val noteDao: SecureNoteDao,
    private val contactDao: SecureContactDao,
    private val cryptoWalletDao: CryptoWalletDao,
    private val crypto: CryptoManager,
    private val analyzer: PasswordAnalyzer,
    private val sessionManager: SessionManager,
    private val appLockManager: AppLockManager,
    val fileVault: FileVaultRepository
) {
    private val entryCache = ConcurrentHashMap<String, Pair<Long, VaultEntry>>()
    private val cardCache = ConcurrentHashMap<String, Pair<Long, DigitalCard>>()
    private val totpCache = ConcurrentHashMap<String, Pair<Long, TotpAccount>>()
    private val noteCache = ConcurrentHashMap<String, Pair<Long, SecureNote>>()
    private val contactCache = ConcurrentHashMap<String, Pair<Long, SecureContact>>()

    private val fileCount = MutableStateFlow(0)
    private val _mutations = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val mutations: Flow<Unit> = _mutations

    init {
        fileVault.setMutationListener(::notifyMutation)
    }

    suspend fun refreshFileCount() {
        fileCount.value = fileVault.count()
    }

    fun notifyMutation() {
        _mutations.tryEmit(Unit)
    }

    fun observeEntries(): Flow<List<VaultEntry>> {
        return combine(vaultDao.observeActive(), sessionManager.mode, appLockManager.travelMode) { raw, mode, travel ->
            if (mode == SessionMode.DECOY) {
                DecoyVault.entries.take(if (travel) 2 else DecoyVault.entries.size)
            } else {
                withContext(Dispatchers.Default) {
                    raw.map { it.toDomain() }.filter { !travel || !it.isHiddenInTravel }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> travelAware(block: (Boolean) -> Flow<T>): Flow<T> =
        appLockManager.travelMode.flatMapLatest { block(it) }

    fun observeFavorites(limit: Int = 4): Flow<List<VaultEntry>> {
        val rows = travelAware { travel -> vaultDao.observeFavoritesLimited(limit, travel) }
        return combine(rows, sessionManager.mode) { raw, mode ->
            if (mode == SessionMode.DECOY) DecoyVault.entries.filter { it.isFavorite }.take(limit)
            else withContext(Dispatchers.Default) { raw.map { it.toDomain() } }
        }
    }

    fun observeRecent(limit: Int = 4): Flow<List<VaultEntry>> {
        val rows = travelAware { travel -> vaultDao.observeRecent(limit, travel) }
        return combine(rows, sessionManager.mode, appLockManager.travelMode) { raw, mode, travel ->
            if (mode == SessionMode.DECOY) {
                if (travel) DecoyVault.entries.take(2) else DecoyVault.entries.take(limit)
            } else {
                withContext(Dispatchers.Default) { raw.map { it.toDomain() } }
            }
        }
    }

    fun observeCategoryCounts(): Flow<Map<String, Int>> {
        val rows = travelAware { travel -> vaultDao.observeCategoryCounts(travel) }
        return combine(rows, sessionManager.mode) { groups, mode ->
            if (mode == SessionMode.DECOY) {
                DecoyVault.entries.groupingBy { it.category.uppercase() }.eachCount()
            } else {
                groups.associate { it.category.uppercase() to it.count }
            }
        }
    }

    fun observeCards(): Flow<List<DigitalCard>> {
        return combine(cardDao.observeAll(), sessionManager.mode) { raw, mode ->
            if (mode == SessionMode.DECOY) DecoyVault.cards
            else withContext(Dispatchers.Default) { raw.map { it.toDomain() } }
        }
    }

    fun observeTotps(): Flow<List<TotpAccount>> {
        return combine(totpDao.observeAll(), sessionManager.mode) { raw, mode ->
            if (mode == SessionMode.DECOY) DecoyVault.totps
            else withContext(Dispatchers.Default) { raw.map { it.toDomain() } }
        }
    }

    fun observeNotes(): Flow<List<SecureNote>> {
        return combine(noteDao.observeAll(), sessionManager.mode) { raw, mode ->
            if (mode == SessionMode.DECOY) DecoyVault.notes
            else withContext(Dispatchers.Default) { raw.map { it.toDomain() } }
        }
    }

    fun observeContacts(): Flow<List<SecureContact>> {
        return combine(contactDao.observeAll(), sessionManager.mode) { raw, mode ->
            if (mode == SessionMode.DECOY) DecoyVault.contacts
            else withContext(Dispatchers.Default) { raw.map { it.toDomain() } }
        }
    }

    fun observeCounts(): Flow<CategoryCounts> {
        val passwordCount = travelAware { travel -> vaultDao.observeActiveCount(travel) }
        val otherCounts = combine(
            totpDao.observeCount(),
            cardDao.observeCount(),
            noteDao.observeCount(),
            contactDao.observeCount()
        ) { totps, cards, notes, contacts -> intArrayOf(totps, cards, notes, contacts) }
        return combine(passwordCount, otherCounts, sessionManager.mode, appLockManager.travelMode, fileCount) {
                passwords, rest, mode, travel, files ->
            if (mode == SessionMode.DECOY) {
                CategoryCounts()
            } else {
                CategoryCounts(
                    passwords = passwords,
                    totps = rest[0],
                    cards = rest[1],
                    notes = rest[2],
                    contacts = rest[3],
                    files = files
                )
            }
        }
    }

    suspend fun getEntry(id: String): VaultEntry? = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext DecoyVault.entries.firstOrNull { it.id == id }
        vaultDao.getById(id)?.toDomain()
    }

    suspend fun getCard(id: String): DigitalCard? = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext DecoyVault.cards.firstOrNull { it.id == id }
        cardDao.getById(id)?.toDomain()
    }

    suspend fun getNote(id: String): SecureNote? = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext DecoyVault.notes.firstOrNull { it.id == id }
        noteDao.getById(id)?.toDomain()
    }

    suspend fun getContact(id: String): SecureContact? = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext DecoyVault.contacts.firstOrNull { it.id == id }
        contactDao.getById(id)?.toDomain()
    }

    suspend fun getTotp(id: String): TotpAccount? = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext DecoyVault.totps.firstOrNull { it.id == id }
        totpDao.getById(id)?.toDomain()
    }

    suspend fun saveEntry(entry: VaultEntry): Boolean = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext false
        val existing = vaultDao.getById(entry.id)
        val password = sealOrClear(entry.password, existing?.password, entry.passwordSealed)
        val notes = sealOrClear(entry.notes, existing?.notes, entry.notesSealed)
        val totpSecret = when {
            !entry.hasTotp -> ""
            entry.totpSecret.isNotBlank() -> crypto.encrypt(entry.totpSecret)
            existing?.totpSecret?.isNotBlank() == true -> existing.totpSecret
            else -> ""
        }
        val hasTotp = totpSecret.isNotBlank()
        val now = System.currentTimeMillis()
        val entity = VaultEntryEntity(
            id = entry.id,
            title = entry.title.trim().ifBlank { "Untitled" },
            username = entry.username.trim(),
            email = entry.email.trim(),
            password = password,
            website = entry.website.trim(),
            url = entry.url.trim(),
            notes = notes,
            category = entry.category.ifBlank { "WEBSITES" },
            customFieldsJson = encodeCustomFields(entry.customFields),
            tagsJson = encodeTags(entry.tags),
            isFavorite = entry.isFavorite || (existing?.isFavorite ?: false),
            hasTotp = hasTotp,
            totpSecret = totpSecret,
            createdAt = existing?.createdAt ?: entry.createdAt,
            updatedAt = now,
            lastAccessedAt = existing?.lastAccessedAt,
            expiresAt = entry.expiresAt,
            strengthScore = when {
                entry.password.isNotBlank() -> analyzer.scoreFor(entry.password)
                entry.passwordSealed -> existing?.strengthScore ?: 0
                else -> 0
            },
            deletedAt = null,
            isHiddenInTravel = entry.isHiddenInTravel
        )
        vaultDao.upsert(entity)
        syncLinkedTotp(entity)
        entryCache.remove(entry.id)
        notifyMutation()
        true
    }

    suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        vaultDao.hardDelete(id)
        totpDao.delete(linkedTotpId(id))
        totpCache.remove(linkedTotpId(id))
        entryCache.remove(id)
        notifyMutation()
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        val entity = vaultDao.getById(id) ?: return@withContext
        vaultDao.setFavorite(id, !entity.isFavorite)
        entryCache.remove(id)
        notifyMutation()
    }

    suspend fun markAccessed(id: String) = withContext(Dispatchers.IO) {
        if (!sessionManager.isDecoy()) vaultDao.markAccessed(id)
    }

    suspend fun saveCard(card: DigitalCard): Boolean = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext false
        val existing = cardDao.getById(card.id)
        val number = sealOrClear(card.number, existing?.number)
        val cvv = sealOrClear(card.cvv, existing?.cvv)
        val pin = sealOrClear(card.pin, existing?.pin)
        val notes = sealOrClear(card.notes, existing?.notes)
        cardDao.upsert(
            DigitalCardEntity(
                id = card.id,
                type = card.type,
                nickname = card.nickname.trim().ifBlank { "Card" },
                holderName = card.holderName.trim(),
                number = number,
                expiryMonth = card.expiryMonth.coerceIn(1, 12),
                expiryYear = normalizeYear(card.expiryYear),
                cvv = cvv,
                pin = pin,
                bankName = card.bankName.trim(),
                notes = notes,
                colorGradientStart = card.colorGradientStart,
                colorGradientEnd = card.colorGradientEnd,
                createdAt = existing?.createdAt ?: card.createdAt
            )
        )
        cardCache.remove(card.id)
        notifyMutation()
        true
    }

    suspend fun deleteCard(id: String) = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        cardDao.delete(id)
        cardCache.remove(id)
        notifyMutation()
    }

    suspend fun saveTotp(account: TotpAccount): Boolean = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext false
        val existing = totpDao.getById(account.id)
        val secret = when {
            account.secret.isNotBlank() -> crypto.encrypt(account.secret)
            existing?.secret?.isNotBlank() == true -> existing.secret
            else -> ""
        }
        if (secret.isBlank()) return@withContext false
        totpDao.upsert(
            TotpEntity(
                id = account.id,
                issuer = account.issuer.trim().ifBlank { "Authenticator" },
                accountName = account.accountName.trim(),
                secret = secret,
                algorithm = account.algorithm.uppercase().takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: "SHA1",
                digits = account.digits.coerceIn(6, 8),
                period = account.period.coerceIn(15, 120),
                createdAt = existing?.createdAt ?: account.createdAt
            )
        )
        totpCache.remove(account.id)
        notifyMutation()
        true
    }

    suspend fun deleteTotp(id: String) = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        totpDao.delete(id)
        totpCache.remove(id)
        notifyMutation()
    }

    suspend fun saveNote(note: SecureNote): Boolean = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext false
        val existing = noteDao.getById(note.id)
        val content = sealOrClear(note.content, existing?.content, note.sealed)
        noteDao.upsert(
            SecureNoteEntity(
                id = note.id,
                title = note.title.trim().ifBlank { "Untitled Note" },
                content = content,
                isPinned = note.isPinned || (existing?.isPinned ?: false),
                color = note.color.ifBlank { "DEFAULT" },
                tagsJson = encodeNoteMeta(note),
                createdAt = existing?.createdAt ?: note.createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
        noteCache.remove(note.id)
        notifyMutation()
        true
    }

    suspend fun importNotes(
        imported: List<SecureNote>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy() || imported.isEmpty()) {
            return@withContext Triple(0, 0, imported.size)
        }
        val existing = noteDao.getAll().associateBy { it.id }
        val pending = ArrayList<SecureNoteEntity>(imported.size)
        var added = 0
        var updated = 0
        var skipped = 0
        imported.forEachIndexed { index, note ->
            val current = existing[note.id]
            when {
                current == null -> {
                    pending += note.toImportedEntity(null)
                    added++
                }
                current.updatedAt >= note.updatedAt -> skipped++
                else -> {
                    pending += note.toImportedEntity(current)
                    updated++
                }
            }
            if (index % 25 == 0 || index == imported.lastIndex) {
                onProgress(index + 1, imported.size)
            }
        }
        pending.chunked(200).forEach { chunk -> noteDao.upsertAll(chunk) }
        if (pending.isNotEmpty()) {
            noteCache.clear()
            notifyMutation()
        }
        Triple(added, updated, skipped)
    }

    private fun SecureNote.toImportedEntity(existing: SecureNoteEntity?): SecureNoteEntity {
        val normalizedColor = color.uppercase().takeIf {
            it in setOf("DEFAULT", "CYAN", "MINT", "LAVENDER", "CORAL")
        } ?: "DEFAULT"
        return SecureNoteEntity(
            id = id,
            title = title.trim().ifBlank { "Untitled Note" }.take(180),
            content = crypto.encrypt(content),
            isPinned = isPinned || (existing?.isPinned ?: false),
            color = normalizedColor,
            tagsJson = encodeNoteMeta(this),
            createdAt = existing?.createdAt ?: createdAt,
            updatedAt = maxOf(updatedAt, existing?.updatedAt ?: 0L)
        )
    }

    suspend fun deleteNote(id: String) = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        val entity = noteDao.getById(id)
        entity?.let { note ->
            note.toDomain().attachments.forEach { fileVault.deleteFile(it.id) }
        }
        noteDao.delete(id)
        noteCache.remove(id)
        refreshFileCount()
        notifyMutation()
    }

    suspend fun toggleNotePin(id: String) = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        val entity = noteDao.getById(id) ?: return@withContext
        noteDao.upsert(entity.copy(isPinned = !entity.isPinned, updatedAt = System.currentTimeMillis()))
        noteCache.remove(id)
        notifyMutation()
    }

    suspend fun saveContact(contact: SecureContact): Boolean = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext false
        val existing = contactDao.getById(contact.id)
        contactDao.upsert(
            SecureContactEntity(
                id = contact.id,
                name = contact.name.trim().ifBlank { "Unnamed Contact" },
                phone = sealOrClear(contact.phone, existing?.phone),
                email = sealOrClear(contact.email, existing?.email),
                address = sealOrClear(contact.address, existing?.address),
                notes = sealOrClear(contact.notes, existing?.notes),
                category = contact.category.ifBlank { "Personal" },
                createdAt = existing?.createdAt ?: contact.createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
        contactCache.remove(contact.id)
        notifyMutation()
        true
    }

    suspend fun deleteContact(id: String) = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        contactDao.delete(id)
        contactCache.remove(id)
        notifyMutation()
    }

    suspend fun importDeviceContacts(
        contacts: List<DeviceContact>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ContactImportSummary = withContext(Dispatchers.IO) {        if (sessionManager.isDecoy()) return@withContext ContactImportSummary(skipped = contacts.size)
        if (contacts.isEmpty()) return@withContext ContactImportSummary()
        val existingRows = contactDao.getAll()
        val rowsById = existingRows.associateBy { it.id }.toMutableMap()
        val domainsById = existingRows.associate { it.id to it.toDomain() }.toMutableMap()
        val phoneIndex = HashMap<String, String>()
        val emailIndex = HashMap<String, String>()
        val nameIndex = HashMap<String, String>()
        fun index(domain: SecureContact) {
            val phoneKey = phoneKey(domain.phone)
            if (phoneKey.isNotBlank()) phoneIndex[phoneKey] = domain.id
            val emailKey = domain.email.trim().lowercase()
            if (emailKey.isNotBlank()) emailIndex[emailKey] = domain.id
            val nameKey = domain.name.trim().lowercase()
            if (nameKey.isNotBlank()) nameIndex[nameKey] = domain.id
        }
        domainsById.values.forEach(::index)

        val pending = LinkedHashMap<String, SecureContactEntity>()
        var imported = 0
        var merged = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        val total = contacts.size
        contacts.forEachIndexed { position, device ->
            val name = device.name.trim()
            val phone = device.phone.trim()
            val email = device.email.trim()
            if (name.isBlank() && phone.isBlank() && email.isBlank()) {
                skipped++
                return@forEachIndexed
            }
            val pKey = phoneKey(phone)
            val eKey = email.lowercase()
            val nKey = name.lowercase()
            val matchId = when {
                pKey.isNotBlank() -> phoneIndex[pKey]
                else -> null
            } ?: when {
                eKey.isNotBlank() -> emailIndex[eKey]
                else -> null
            } ?: when {
                pKey.isBlank() && eKey.isBlank() && nKey.isNotBlank() -> nameIndex[nKey]
                else -> null
            }
            if (matchId == null) {
                val id = java.util.UUID.randomUUID().toString()
                val row = SecureContactEntity(
                    id = id,
                    name = name.ifBlank { phone.ifBlank { email } },
                    phone = if (phone.isBlank()) "" else crypto.encrypt(phone),
                    email = if (email.isBlank()) "" else crypto.encrypt(email),
                    category = "Personal",
                    createdAt = now,
                    updatedAt = now
                )
                rowsById[id] = row
                val domain = SecureContact(
                    id = id,
                    name = row.name,
                    phone = phone,
                    email = email,
                    category = "Personal"
                )
                domainsById[id] = domain
                index(domain)
                pending[id] = row
                imported++
            } else {
                val existingDomain = domainsById[matchId] ?: return@forEachIndexed
                val row = rowsById[matchId] ?: return@forEachIndexed
                val needsPhone = existingDomain.phone.isBlank() && phone.isNotBlank()
                val needsEmail = existingDomain.email.isBlank() && email.isNotBlank()
                if (!needsPhone && !needsEmail) {
                    skipped++
                } else {
                    val mergedDomain = existingDomain.copy(
                        phone = if (needsPhone) phone else existingDomain.phone,
                        email = if (needsEmail) email else existingDomain.email,
                        updatedAt = now
                    )
                    val mergedRow = row.copy(
                        phone = if (needsPhone) crypto.encrypt(phone) else row.phone,
                        email = if (needsEmail) crypto.encrypt(email) else row.email,
                        updatedAt = now
                    )
                    rowsById[matchId] = mergedRow
                    domainsById[matchId] = mergedDomain
                    index(mergedDomain)
                    pending[matchId] = mergedRow
                    merged++
                }
            }
            if (position % 40 == 0 || position == total - 1) onProgress(position + 1, total)
        }
        pending.values.toList().chunked(250).forEach { chunk -> contactDao.upsertAll(chunk) }
        contactCache.clear()
        if (pending.isNotEmpty()) notifyMutation()
        ContactImportSummary(imported = imported, merged = merged, skipped = skipped)
    }

    /**
     * Legacy rows were sealed directly with the Keystore key, which costs a Keystore
     * round trip per field. Re-seal them with the wrapped data key so bulk reads
     * (2,800+ contacts) stay fast. Plaintext and foreign blobs are left untouched.
     */
    suspend fun migrateLegacyCiphertext() = withContext(Dispatchers.IO) {
        if (sessionManager.isDecoy()) return@withContext
        val contactUpdates = contactDao.getAll().mapNotNull { row ->
            val phone = resealWithDataKey(row.phone)
            val email = resealWithDataKey(row.email)
            val address = resealWithDataKey(row.address)
            val notes = resealWithDataKey(row.notes)
            if (phone == row.phone && email == row.email &&
                address == row.address && notes == row.notes
            ) {
                null
            } else {
                row.copy(phone = phone, email = email, address = address, notes = notes)
            }
        }
        if (contactUpdates.isNotEmpty()) {
            contactUpdates.chunked(100).forEach { contactDao.upsertAll(it) }
            contactCache.clear()
        }
        val entryUpdates = vaultDao.getAllActiveEntries().mapNotNull { row ->
            val password = resealWithDataKey(row.password)
            val notes = resealWithDataKey(row.notes)
            val totpSecret = resealWithDataKey(row.totpSecret)
            if (password == row.password && notes == row.notes && totpSecret == row.totpSecret) {
                null
            } else {
                row.copy(password = password, notes = notes, totpSecret = totpSecret)
            }
        }
        if (entryUpdates.isNotEmpty()) {
            entryUpdates.chunked(100).forEach { vaultDao.upsertAll(it) }
            entryCache.clear()
        }
    }

    private fun resealWithDataKey(value: String?): String {
        if (value.isNullOrBlank()) return value.orEmpty()
        if (!crypto.isEncryptedBlob(value)) return value
        if (crypto.isDataKeyBlob(value)) return value
        val plaintext = crypto.decrypt(value) ?: return value
        return crypto.encrypt(plaintext)
    }

    suspend fun passwordAuditCandidates(): List<VaultEntry> = withContext(Dispatchers.IO) {
        if (sessionManager.mode.value != SessionMode.REAL) return@withContext emptyList()
        vaultDao.getAllActiveEntries()
            .map { it.toDomain() }
            .filter { it.password.isNotBlank() }
    }

    suspend fun autofillCandidates(
        query: AutofillQuery,
        limit: Int = 12
    ): List<VaultEntry> = withContext(Dispatchers.IO) {
        if (sessionManager.mode.value != SessionMode.REAL) return@withContext emptyList()
        val host = normalizeDomain(query.webDomain.orEmpty())
        val packageName = query.packageName.trim().lowercase()
        val appLabel = query.appLabel.trim().lowercase()
        val typedUser = query.typedUsername.trim().lowercase()
        val hintHosts = query.hints.map { normalizeDomain(it) }
            .filter { it.isNotBlank() && it != packageName }.distinct()
        val entries = vaultDao.getAllActiveEntries()
            .map { it.toDomain() }
            .filter { it.password.isNotBlank() }
        val ranked = entries.mapNotNull { entry ->
            val websites = listOf(entry.website, entry.url)
                .map { normalizeDomain(it) }
                .filter { it.isNotBlank() }
                .distinct()
            val appPackage = entry.url.trim().lowercase()
                .removePrefix("androidapp://")
                .takeIf { entry.url.startsWith("androidapp://", ignoreCase = true) }
            var score = 0
            if (host.isNotBlank()) {
                websites.forEach { site ->
                    score = maxOf(
                        score,
                        when {
                            site == host -> 10
                            site.endsWith(".$host") || host.endsWith(".$site") -> 7
                            site.length >= 4 && host.length >= 4 &&
                                (site.contains(host) || host.contains(site)) -> 3
                            else -> 0
                        }
                    )
                }
                hintHosts.forEach { hint ->
                    if (hint.length >= 4 && websites.any { it.contains(hint) || hint.contains(it) }) {
                        score = maxOf(score, 3)
                    }
                }
            }
            if (packageName.isNotBlank() && appPackage == packageName) score = maxOf(score, 10)
            if (typedUser.isNotBlank() &&
                (entry.username.lowercase() == typedUser || entry.email.lowercase() == typedUser)
            ) {
                score += 4
            }
            val title = entry.title.lowercase()
            if (appLabel.length >= 3 && title.contains(appLabel)) score += 2
            if (packageName.isNotBlank() && entry.category == "APPS") score += 1
            if (score <= 0) null else Triple(score, entry.updatedAt, entry)
        }.sortedWith(
            compareByDescending<Triple<Int, Long, VaultEntry>> { it.first }
                .thenByDescending { it.second }
        ).map { it.third }
        if (ranked.isNotEmpty()) ranked.take(limit)
        else entries.sortedByDescending { it.updatedAt }.take(minOf(limit, 5))
    }

    suspend fun saveAutofillCredential(
        title: String,
        username: String,
        password: String,
        webDomain: String?,
        packageName: String?
    ): Boolean = withContext(Dispatchers.IO) {
        if (sessionManager.mode.value != SessionMode.REAL) return@withContext false
        if (password.isBlank()) return@withContext false
        val domainHint = normalizeDomain(webDomain.orEmpty())
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        val entries = vaultDao.getAllActiveEntries().map { it.toDomain() }
        fun hostMatches(entry: VaultEntry): Boolean {
            if (domainHint.isBlank()) return false
            val site = normalizeDomain(entry.website)
            val alt = normalizeDomain(entry.url)
            return site == domainHint || alt == domainHint ||
                site.endsWith(".$domainHint") || domainHint.endsWith(".$site") ||
                alt.endsWith(".$domainHint") || domainHint.endsWith(".$alt")
        }
        fun packageMatches(entry: VaultEntry): Boolean =
            pkg.isNotBlank() && entry.url.equals("androidapp://$pkg", ignoreCase = true)

        val match = entries.firstOrNull { entry ->
            entry.username.equals(username, ignoreCase = true) &&
                (hostMatches(entry) || packageMatches(entry))
        } ?: entries.firstOrNull { entry ->
            entry.username.isBlank() && (hostMatches(entry) || packageMatches(entry))
        }
        val saved = if (match != null) {
            saveEntry(match.copy(username = username.ifBlank { match.username }, password = password))
        } else {
            val fallbackTitle = title.trim().ifBlank {
                webDomain?.trim()?.takeIf { it.isNotBlank() }
                    ?: packageName?.substringAfterLast('.').orEmpty().ifBlank { "Saved login" }
            }
            saveEntry(
                VaultEntry(
                    title = fallbackTitle,
                    username = username,
                    password = password,
                    website = webDomain?.trim()?.let { if (it.startsWith("http")) it else "https://$it" }.orEmpty(),
                    url = if (webDomain.isNullOrBlank() && !packageName.isNullOrBlank()) {
                        "androidapp://$packageName"
                    } else {
                        webDomain?.trim().orEmpty()
                    },
                    category = if (webDomain.isNullOrBlank()) "APPS" else "WEBSITES"
                )
            )
        }
        saved
    }

    private suspend fun syncLinkedTotp(entity: VaultEntryEntity) {
        val linkedId = linkedTotpId(entity.id)
        val raw = if (entity.hasTotp) open(entity.totpSecret).first else ""
        val parsed = if (raw.startsWith("otpauth://", ignoreCase = true)) {
            TotpGenerator.parseOtpAuth(raw)
        } else {
            null
        }
        val secret = (parsed?.secret ?: raw).trim().replace(" ", "").uppercase()
        if (!entity.hasTotp || secret.isBlank() || !TotpGenerator.isValidSecret(secret)) {
            if (totpDao.getById(linkedId) != null) {
                totpDao.delete(linkedId)
                totpCache.remove(linkedId)
            }
            return
        }
        val existingLinked = totpDao.getById(linkedId)
        val standaloneDuplicate = totpDao.getAll().any {
            it.id != linkedId && open(it.secret).first == secret
        }
        if (standaloneDuplicate) {
            if (existingLinked != null) {
                totpDao.delete(linkedId)
                totpCache.remove(linkedId)
            }
            return
        }
        val now = System.currentTimeMillis()
        totpDao.upsert(
            TotpEntity(
                id = linkedId,
                issuer = entity.title.ifBlank { parsed?.issuer ?: "Authenticator" },
                accountName = parsed?.accountName?.takeIf { it.isNotBlank() }
                    ?: entity.username.ifBlank { entity.email },
                secret = crypto.encrypt(secret),
                algorithm = parsed?.algorithm?.name ?: existingLinked?.algorithm ?: "SHA1",
                digits = parsed?.digits ?: existingLinked?.digits ?: 6,
                period = parsed?.period ?: existingLinked?.period ?: 30,
                createdAt = existingLinked?.createdAt ?: now
            )
        )
        totpCache.remove(linkedId)
    }

    private fun linkedTotpId(entryId: String): String = "entry-totp-$entryId"

    private fun phoneKey(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return when {
            digits.length > 10 -> digits.takeLast(10)
            digits.length >= 7 -> digits
            else -> ""
        }
    }

    private fun normalizeDomain(raw: String): String {
        return raw.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore(':')
    }

    suspend fun globalSearch(query: String): List<SearchHit> = withContext(Dispatchers.Default) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        if (sessionManager.mode.value == SessionMode.DECOY) {
            return@withContext searchDecoy(q)
        }
        val hits = mutableListOf<SearchHit>()
        val entries = vaultDao.getAllActiveEntries().map { it.toDomain() }
        val cards = cardDao.getAll().map { it.toDomain() }
        val notes = noteDao.getAll().map { it.toDomain() }
        val contacts = contactDao.getAll().map { it.toDomain() }
        val totps = totpDao.getAll().map { it.toDomain() }

        entries.forEach { entry ->
            if (fuzzy(entry.title, q) || fuzzy(entry.username, q) || fuzzy(entry.email, q) ||
                fuzzy(entry.website, q) || fuzzy(entry.url, q) || entry.tags.any { fuzzy(it, q) }
            ) {
                hits += SearchHit(SearchKind.PASSWORD, entry.id, entry.title, entry.username.ifBlank { entry.email })
            }
        }
        cards.forEach { card ->
            if (fuzzy(card.nickname, q) || fuzzy(card.holderName, q) || fuzzy(card.bankName, q) || fuzzy(card.last4, q)) {
                hits += SearchHit(SearchKind.CARD, card.id, card.nickname, "•••• ${card.last4}")
            }
        }
        notes.forEach { note ->
            if (fuzzy(note.title, q) || fuzzy(note.content.take(4000), q)) {
                hits += SearchHit(SearchKind.NOTE, note.id, note.title, note.content.lineSequence().firstOrNull().orEmpty().take(80))
            }
        }
        contacts.forEach { contact ->
            if (fuzzy(contact.name, q) || fuzzy(contact.phone, q) || fuzzy(contact.email, q) || fuzzy(contact.notes, q)) {
                hits += SearchHit(SearchKind.CONTACT, contact.id, contact.name, contact.phone)
            }
        }
        totps.forEach { totp ->
            if (fuzzy(totp.issuer, q) || fuzzy(totp.accountName, q)) {
                hits += SearchHit(SearchKind.TOTP, totp.id, totp.issuer, totp.accountName)
            }
        }
        hits.take(50)
    }

    private fun searchDecoy(q: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        DecoyVault.entries.filter { fuzzy(it.title, q) || fuzzy(it.username, q) }.forEach {
            hits += SearchHit(SearchKind.PASSWORD, it.id, it.title, it.username)
        }
        DecoyVault.notes.filter { fuzzy(it.title, q) || fuzzy(it.content, q) }.forEach {
            hits += SearchHit(SearchKind.NOTE, it.id, it.title, it.content.take(60))
        }
        DecoyVault.contacts.filter { fuzzy(it.name, q) }.forEach {
            hits += SearchHit(SearchKind.CONTACT, it.id, it.name, it.phone)
        }
        DecoyVault.cards.filter { fuzzy(it.nickname, q) }.forEach {
            hits += SearchHit(SearchKind.CARD, it.id, it.nickname, "•••• ${it.last4}")
        }
        return hits
    }

    private fun fuzzy(haystack: String?, needle: String): Boolean {
        if (haystack.isNullOrBlank()) return false
        val value = haystack.lowercase()
        if (value.contains(needle)) return true
        var index = 0
        for (char in value) {
            if (index < needle.length && char == needle[index]) index++
        }
        return index == needle.length && needle.length >= 3
    }

    suspend fun buildSecurityReport(): SecurityReport = withContext(Dispatchers.Default) {
        if (sessionManager.mode.value == SessionMode.DECOY) {
            return@withContext SecurityReport()
        }
        val entries = vaultDao.getAllActiveEntries().map { it.toDomain() }
        if (entries.isEmpty()) return@withContext SecurityReport()
        val weak = entries.filter { analyzer.isWeak(it.password) }
        val compromised = entries.filter { analyzer.analyze(it.password).compromised }
        val missing2fa = entries.filter { !it.hasTotp }
        val grouped = entries.filter { it.password.isNotBlank() }
            .groupBy { fingerprint(it.password) }
            .filterValues { it.size > 1 }
        val reusedGroups = grouped.map { (fp, list) -> ReusedGroup(fp, list) }.sortedByDescending { it.entries.size }
        val reusedEntries = reusedGroups.sumOf { it.entries.size }
        val avgStrength = entries.map { it.strengthScore }.average().roundToInt()
        val twoFaRatio = entries.count { it.hasTotp }.toFloat() / entries.size
        val reuseRatio = reusedEntries.toFloat() / entries.size
        val compromisedRatio = compromised.size.toFloat() / entries.size
        val score = (
            avgStrength * 0.45 +
                (1f - reuseRatio.coerceIn(0f, 1f)) * 20 +
                twoFaRatio.coerceIn(0f, 1f) * 20 +
                (1f - compromisedRatio.coerceIn(0f, 1f)) * 15
            ).roundToInt().coerceIn(0, 100)
        SecurityReport(
            totalEntries = entries.size,
            averageStrength = avgStrength,
            weak = weak.sortedBy { it.strengthScore },
            reused = reusedGroups,
            missingTwoFactor = missing2fa,
            compromised = compromised,
            twoFactorCoverage = twoFaRatio,
            score = score
        )
    }

    /**
     * Tombstones from builds up to 3.0.10 (and from restored backups) stay in the database
     * forever: deleting them automatically would destroy data the owner never deleted by hand.
     * Every read path filters on `deletedAt IS NULL`, so they stay out of sight until Wipe All.
     */
    suspend fun wipeAllData() = withContext(Dispatchers.IO) {
        vaultDao.deleteAll()
        cardDao.deleteAll()
        totpDao.deleteAll()
        noteDao.deleteAll()
        contactDao.deleteAll()
        cryptoWalletDao.deleteAll()
        fileVault.wipeAll()
        entryCache.clear()
        cardCache.clear()
        totpCache.clear()
        noteCache.clear()
        contactCache.clear()
        fileCount.value = 0
        notifyMutation()
    }

    /**
     * Releases the mapping caches so a session switch can never hand back a record
     * decrypted under the previous key. The next read repopulates from Room.
     */
    fun clearCaches() {
        entryCache.clear()
        cardCache.clear()
        totpCache.clear()
        noteCache.clear()
        contactCache.clear()
    }

    /** Called after any path that writes Room behind the repository's back (restore, seed, import). */
    suspend fun reloadVaultState() = withContext(Dispatchers.IO) {
        clearCaches()
        refreshFileCount()
        notifyMutation()
    }

    // ---------------- Mapping ----------------

    private fun VaultEntryEntity.toDomain(): VaultEntry {
        entryCache[id]?.let { (timestamp, cached) -> if (timestamp == updatedAt) return cached }
        val passwordValue = open(password)
        val notesValue = open(notes)
        val totpValue = open(totpSecret)
        val domain = VaultEntry(
            id = id,
            title = title,
            username = username,
            email = email,
            password = passwordValue.first,
            website = website,
            url = url,
            notes = notesValue.first,
            category = category,
            customFields = parseCustomFields(customFieldsJson),
            tags = parseTags(tagsJson),
            isFavorite = isFavorite,
            hasTotp = hasTotp,
            totpSecret = totpValue.first,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastAccessedAt = lastAccessedAt,
            expiresAt = expiresAt,
            strengthScore = strengthScore,
            isHiddenInTravel = isHiddenInTravel,
            passwordSealed = passwordValue.second,
            notesSealed = notesValue.second,
            totpSealed = totpValue.second
        )
        entryCache[id] = updatedAt to domain
        return domain
    }

    private fun DigitalCardEntity.toDomain(): DigitalCard {
        cardCache[id]?.let { (timestamp, cached) -> if (timestamp == createdAt) return cached }
        val numberValue = open(number)
        val cvvValue = open(cvv)
        val pinValue = open(pin)
        val notesValue = open(notes)
        val domain = DigitalCard(
            id = id,
            type = type,
            nickname = nickname,
            holderName = holderName,
            number = numberValue.first,
            expiryMonth = expiryMonth,
            expiryYear = expiryYear,
            cvv = cvvValue.first,
            pin = pinValue.first,
            bankName = bankName,
            notes = notesValue.first,
            colorGradientStart = colorGradientStart,
            colorGradientEnd = colorGradientEnd,
            createdAt = createdAt,
            sealed = numberValue.second || cvvValue.second || pinValue.second
        )
        cardCache[id] = createdAt to domain
        return domain
    }

    private fun TotpEntity.toDomain(): TotpAccount {
        totpCache[id]?.let { (timestamp, cached) -> if (timestamp == createdAt) return cached }
        val secretValue = open(secret)
        val domain = TotpAccount(
            id = id,
            issuer = issuer,
            accountName = accountName,
            secret = secretValue.first,
            algorithm = algorithm,
            digits = digits,
            period = period,
            createdAt = createdAt,
            sealed = secretValue.second
        )
        totpCache[id] = createdAt to domain
        return domain
    }

    private fun SecureNoteEntity.toDomain(): SecureNote {
        noteCache[id]?.let { (timestamp, cached) -> if (timestamp == updatedAt) return cached }
        val contentValue = open(content)
        val meta = parseNoteMeta(tagsJson)
        val domain = SecureNote(
            id = id,
            title = title,
            content = contentValue.first,
            isPinned = isPinned,
            color = color,
            tags = meta.first,
            attachments = meta.second,
            createdAt = createdAt,
            updatedAt = updatedAt,
            sealed = contentValue.second
        )
        noteCache[id] = updatedAt to domain
        return domain
    }

    private fun SecureContactEntity.toDomain(): SecureContact {
        contactCache[id]?.let { (timestamp, cached) -> if (timestamp == updatedAt) return cached }
        val domain = SecureContact(
            id = id,
            name = name,
            phone = open(phone).first,
            email = open(email).first,
            address = open(address).first,
            notes = open(notes).first,
            category = category,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
        contactCache[id] = updatedAt to domain
        return domain
    }

    private fun open(value: String): Pair<String, Boolean> {
        if (value.isBlank()) return "" to false
        val decrypted = crypto.decrypt(value)
        if (decrypted != null) return decrypted to false
        return if (crypto.isEncryptedBlob(value)) "" to true else value to false
    }

    /**
     * Blank input means the user cleared the field, except for ciphertext sealed by
     * another device's Keystore — an unrelated save must not destroy it.
     */
    private fun sealOrClear(newValue: String, oldValue: String?, fieldSealed: Boolean = false): String {
        if (newValue.isNotBlank()) return crypto.encrypt(newValue)
        if (oldValue.isNullOrBlank()) return ""
        if (fieldSealed) return oldValue
        if (crypto.isEncryptedBlob(oldValue) && crypto.decrypt(oldValue) == null) return oldValue
        return ""
    }

    private fun normalizeYear(year: Int): Int {
        return when {
            year in 0..99 -> year
            year in 2000..2100 -> year - 2000
            else -> 30
        }
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun encodeCustomFields(fields: List<CustomField>): String {
        if (fields.isEmpty()) return "[]"
        return buildString {
            append('[')
            fields.filter { it.label.isNotBlank() || it.value.isNotBlank() }.forEachIndexed { index, field ->
                if (index > 0) append(',')
                append("{\"label\":").append(quote(field.label))
                append(",\"value\":").append(quote(field.value))
                append(",\"concealed\":").append(field.concealed)
                append('}')
            }
            append(']')
        }
    }

    private fun parseCustomFields(json: String): List<CustomField> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return emptyList()
            root.asJsonArray.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val label = obj.get("label")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: obj.get("key")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val value = obj.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val concealed = obj.get("concealed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                CustomField(label, value, concealed)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encodeTags(tags: List<String>): String {
        if (tags.isEmpty()) return "[]"
        return tags.filter { it.isNotBlank() }.joinToString(prefix = "[", postfix = "]") { quote(it.trim()) }
    }

    private fun parseTags(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            when {
                root.isJsonArray -> root.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                root.isJsonObject -> {
                    val tags = root.asJsonObject.getAsJsonArray("tags") ?: return emptyList()
                    tags.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                }
                else -> emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encodeNoteMeta(note: SecureNote): String {
        val attachments = note.attachments.joinToString(prefix = "[", postfix = "]") { attachment ->
            "{\"id\":${quote(attachment.id)},\"name\":${quote(attachment.name)}," +
                "\"mimeType\":${quote(attachment.mimeType)},\"size\":${attachment.size}}"
        }
        val tags = note.tags.joinToString(prefix = "[", postfix = "]") { quote(it) }
        return "{\"tags\":$tags,\"attachments\":$attachments}"
    }

    private fun parseNoteMeta(json: String): Pair<List<String>, List<NoteAttachment>> {
        if (json.isBlank()) return emptyList<String>() to emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (root.isJsonArray) {
                val tags = root.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                return tags to emptyList()
            }
            if (!root.isJsonObject) return emptyList<String>() to emptyList()
            val obj = root.asJsonObject
            val tags = obj.getAsJsonArray("tags")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()
            val attachments = obj.getAsJsonArray("attachments")?.mapNotNull { element ->
                val att = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                NoteAttachment(
                    id = att.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null,
                    name = att.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "Attachment",
                    mimeType = att.get("mimeType")?.takeIf { it.isJsonPrimitive }?.asString ?: "application/octet-stream",
                    size = att.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                )
            } ?: emptyList()
            tags to attachments
        } catch (_: Throwable) {
            emptyList<String>() to emptyList()
        }
    }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char < ' ') builder.append("\\u%04x".format(char.code)) else builder.append(char)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
