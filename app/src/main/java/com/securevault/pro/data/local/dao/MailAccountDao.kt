package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.MailAccountEntity
import kotlinx.coroutines.flow.Flow

/**
 * The account rows of the built-in mail client.
 *
 * Every read filters on `deletedAt IS NULL`: a removed account stops being listed the moment the
 * owner asks, while the row itself is only ever dropped by the explicit
 * [purgeDeletedForever] call that forgets the sealed credential with it. There is no expiry, no
 * retention window and no background cleanup anywhere in this DAO.
 *
 * Not registered in `VaultDatabase` yet — see `agent_notes/email-ui.md` for the exact three lines
 * and the version bump the manager owns.
 */
@Dao
interface MailAccountDao {

    @Query("SELECT * FROM mail_accounts WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MailAccountEntity>>

    @Query("SELECT * FROM mail_accounts WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun getAll(): List<MailAccountEntity>

    @Query("SELECT * FROM mail_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MailAccountEntity?

    /** The sealed envelope alone; no caller needs to read the whole row to connect. */
    @Query("SELECT credentialSealed FROM mail_accounts WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun sealedCredential(id: String): String?

    @Upsert
    suspend fun upsert(account: MailAccountEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<MailAccountEntity>)

    /** The manual "remove this account" step: a tombstone, never a TTL. */
    @Query("UPDATE mail_accounts SET deletedAt = :at, updatedAt = :at, credentialSealed = '' WHERE id = :id")
    suspend fun markDeleted(id: String, at: Long)

    @Query("DELETE FROM mail_accounts WHERE deletedAt IS NOT NULL AND deletedAt > 0")
    suspend fun purgeDeletedForever()

    /**
     * The whole table, for the owner-pressed "Erase everything" only.
     *
     * Nothing else in this DAO drops rows: no expiry, no age, no background cleanup.
     */
    @Query("DELETE FROM mail_accounts")
    suspend fun deleteAll()

    @Query("UPDATE mail_accounts SET lastSyncAt = :at, lastError = :error, updatedAt = :at WHERE id = :id")
    suspend fun markSynced(id: String, at: Long, error: String)

    /** Stores a newly sealed envelope for one account, or clears it for an OAuth account. */
    @Query("UPDATE mail_accounts SET credentialSealed = :sealed, updatedAt = :at WHERE id = :id")
    suspend fun replaceCredential(id: String, sealed: String, at: Long)

    @Query("SELECT COUNT(*) FROM mail_accounts WHERE deletedAt IS NULL")
    suspend fun count(): Int
}
