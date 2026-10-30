package com.securevault.pro.data.remote.realtime

import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.local.dao.CryptoWalletDao
import com.securevault.pro.data.local.dao.DigitalCardDao
import com.securevault.pro.data.local.dao.SecureContactDao
import com.securevault.pro.data.local.dao.SecureNoteDao
import com.securevault.pro.data.local.dao.TotpDao
import com.securevault.pro.data.local.dao.VaultDao
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import com.securevault.pro.data.local.entity.DigitalCardEntity
import com.securevault.pro.data.local.entity.SecureContactEntity
import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.TotpEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that knows which Room DAO (or, for files, the [FileVaultRepository]) owns which
 * realtime module. The engine reaches records only through here, so a module-to-store mapping lives
 * in a single readable table rather than scattered across the transport.
 *
 * This class is bound to Android/Room and is not part of the pure, testable core — but every method
 * is a thin, exhaustive `when` over [RealtimeModule], so the mapping itself is trivially auditable and
 * adding a module cannot silently miss one. No Room schema change; deletes go through the DAOs.
 */
@Singleton
class RealtimeStore @Inject constructor(
    private val vaultDao: VaultDao,
    private val noteDao: SecureNoteDao,
    private val cardDao: DigitalCardDao,
    private val totpDao: TotpDao,
    private val contactDao: SecureContactDao,
    private val cryptoWalletDao: CryptoWalletDao,
    private val fileVault: FileVaultRepository
) {

    /** Every live local record for a module. Entries exclude soft-deleted rows by construction. */
    suspend fun loadAll(module: RealtimeModule): List<Any> = when (module) {
        RealtimeModule.NOTES -> noteDao.getAll()
        RealtimeModule.ENTRIES -> vaultDao.getAllActiveEntries()
        RealtimeModule.CARDS -> cardDao.getAll()
        RealtimeModule.TOTPS -> totpDao.getAll()
        RealtimeModule.CONTACTS -> contactDao.getAll()
        RealtimeModule.CRYPTO_WALLETS -> cryptoWalletDao.getAll()
        RealtimeModule.FILES -> fileVault.listFiles()
    }

    /** The single local record for [id], or null when it is gone (which the reconcile reads as a delete). */
    suspend fun load(module: RealtimeModule, id: String): Any? = when (module) {
        RealtimeModule.NOTES -> noteDao.getById(id)
        RealtimeModule.ENTRIES -> vaultDao.getById(id)?.takeIf { it.deletedAt == null }
        RealtimeModule.CARDS -> cardDao.getById(id)
        RealtimeModule.TOTPS -> totpDao.getById(id)
        RealtimeModule.CONTACTS -> contactDao.getById(id)
        RealtimeModule.CRYPTO_WALLETS -> cryptoWalletDao.getById(id)
        RealtimeModule.FILES -> fileVault.metadata(id)
    }

    /**
     * Writes a remote record into local storage. The [record] is whatever
     * [RealtimeRecordCodec.decode] produced for this module, so the `when` is exhaustive and a type
     * mismatch is a bug caught here rather than a cast failure at the call site.
     */
    suspend fun upsert(module: RealtimeModule, record: Any): Boolean = when (module) {
        RealtimeModule.NOTES -> { (record as? SecureNoteEntity)?.let { noteDao.upsert(it) } != null }
        RealtimeModule.ENTRIES -> { (record as? VaultEntryEntity)?.let { vaultDao.upsert(it) } != null }
        RealtimeModule.CARDS -> { (record as? DigitalCardEntity)?.let { cardDao.upsert(it) } != null }
        RealtimeModule.TOTPS -> { (record as? TotpEntity)?.let { totpDao.upsert(it) } != null }
        RealtimeModule.CONTACTS -> { (record as? SecureContactEntity)?.let { contactDao.upsert(it) } != null }
        RealtimeModule.CRYPTO_WALLETS -> { (record as? CryptoWalletEntity)?.let { cryptoWalletDao.upsert(it) } != null }
        RealtimeModule.FILES -> { (record as? com.securevault.pro.data.file.VaultFile)?.let { fileVault.upsertMetadata(it) }; true }
    }

    /**
     * Removes a record locally after a winning tombstone. A hard delete; a tombstone prevents resurrection.
     *
     * `FILES` deliberately removes the encrypted `.svf` body and not only its metadata, on the same
     * terms as every other module: a tombstone is only ever written by an explicit delete the owner
     * made on a device, and a file whose row the owner threw away but whose bytes are still on disk is
     * a copy they cannot see, cannot list and cannot delete from the app. Nothing here deletes on a
     * timer, by absence, or from a snapshot's contents — see the retention guards in
     * `WipeOrderingGuardTest` and `SharedInbox`, which cover the cases that would.
     */
    suspend fun delete(module: RealtimeModule, id: String) {
        when (module) {
            RealtimeModule.NOTES -> noteDao.delete(id)
            RealtimeModule.ENTRIES -> vaultDao.hardDelete(id)
            RealtimeModule.CARDS -> cardDao.delete(id)
            RealtimeModule.TOTPS -> totpDao.delete(id)
            RealtimeModule.CONTACTS -> contactDao.delete(id)
            RealtimeModule.CRYPTO_WALLETS -> cryptoWalletDao.delete(id)
            RealtimeModule.FILES -> fileVault.deleteFile(id)
        }
    }
}
