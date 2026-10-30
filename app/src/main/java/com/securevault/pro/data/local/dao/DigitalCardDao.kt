package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.DigitalCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DigitalCardDao {
    @Query("SELECT * FROM digital_cards ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DigitalCardEntity>>

    @Query("SELECT * FROM digital_cards ORDER BY createdAt DESC")
    suspend fun getAll(): List<DigitalCardEntity>

    @Query("SELECT COUNT(*) FROM digital_cards")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM digital_cards")
    suspend fun count(): Int

    @Query("SELECT * FROM digital_cards WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DigitalCardEntity?

    @Upsert
    suspend fun upsert(card: DigitalCardEntity)

    @Upsert
    suspend fun upsertAll(cards: List<DigitalCardEntity>)

    @Query("DELETE FROM digital_cards WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM digital_cards")
    suspend fun deleteAll()
}
