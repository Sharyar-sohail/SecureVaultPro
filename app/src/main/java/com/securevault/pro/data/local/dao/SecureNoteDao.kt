package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.SecureNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecureNoteDao {
    @Query("SELECT * FROM secure_notes ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SecureNoteEntity>>

    @Query("SELECT * FROM secure_notes ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAll(): List<SecureNoteEntity>

    @Query("SELECT COUNT(*) FROM secure_notes")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM secure_notes")
    suspend fun count(): Int

    @Query("SELECT * FROM secure_notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SecureNoteEntity?

    @Upsert
    suspend fun upsert(note: SecureNoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<SecureNoteEntity>)

    @Query("DELETE FROM secure_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM secure_notes")
    suspend fun deleteAll()
}
