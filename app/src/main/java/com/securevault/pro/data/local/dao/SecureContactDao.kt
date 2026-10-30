package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.SecureContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecureContactDao {
    @Query("SELECT * FROM secure_contacts ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SecureContactEntity>>

    @Query("SELECT * FROM secure_contacts ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<SecureContactEntity>

    @Query("SELECT COUNT(*) FROM secure_contacts")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM secure_contacts")
    suspend fun count(): Int

    @Query("SELECT * FROM secure_contacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SecureContactEntity?

    @Upsert
    suspend fun upsert(contact: SecureContactEntity)

    @Upsert
    suspend fun upsertAll(contacts: List<SecureContactEntity>)

    @Query("DELETE FROM secure_contacts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM secure_contacts")
    suspend fun deleteAll()
}
