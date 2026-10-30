package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CryptoWalletDao {
    @Query("SELECT * FROM crypto_wallets ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CryptoWalletEntity>>

    @Query("SELECT * FROM crypto_wallets ORDER BY createdAt ASC")
    suspend fun getAll(): List<CryptoWalletEntity>

    @Query("SELECT * FROM crypto_wallets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CryptoWalletEntity?

    @Query("SELECT COUNT(*) FROM crypto_wallets")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM crypto_wallets")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(wallet: CryptoWalletEntity)

    @Upsert
    suspend fun upsertAll(wallets: List<CryptoWalletEntity>)

    @Query("DELETE FROM crypto_wallets WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM crypto_wallets")
    suspend fun deleteAll()
}
