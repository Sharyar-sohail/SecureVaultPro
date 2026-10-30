package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.TotpEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TotpDao {
    @Query("SELECT * FROM totp_accounts ORDER BY issuer COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TotpEntity>>

    @Query("SELECT * FROM totp_accounts ORDER BY issuer COLLATE NOCASE ASC")
    suspend fun getAll(): List<TotpEntity>

    @Query("SELECT COUNT(*) FROM totp_accounts")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM totp_accounts")
    suspend fun count(): Int

    @Query("SELECT * FROM totp_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TotpEntity?

    @Upsert
    suspend fun upsert(account: TotpEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<TotpEntity>)

    @Query("DELETE FROM totp_accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM totp_accounts")
    suspend fun deleteAll()
}
