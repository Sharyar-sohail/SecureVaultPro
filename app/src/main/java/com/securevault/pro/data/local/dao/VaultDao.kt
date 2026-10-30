package com.securevault.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.securevault.pro.data.local.entity.VaultEntryEntity
import kotlinx.coroutines.flow.Flow

data class CategoryCountRow(
    val category: String,
    val count: Int
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries WHERE deletedAt IS NULL ORDER BY isFavorite DESC, updatedAt DESC")
    fun observeActive(): Flow<List<VaultEntryEntity>>

    @Query("SELECT COUNT(*) FROM vault_entries WHERE deletedAt IS NULL AND (:hideTravel = 0 OR isHiddenInTravel = 0)")
    fun observeActiveCount(hideTravel: Boolean): Flow<Int>

    @Query(
        "SELECT * FROM vault_entries WHERE deletedAt IS NULL AND (:hideTravel = 0 OR isHiddenInTravel = 0) " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int, hideTravel: Boolean): Flow<List<VaultEntryEntity>>

    @Query(
        "SELECT * FROM vault_entries WHERE deletedAt IS NULL AND isFavorite = 1 " +
            "AND (:hideTravel = 0 OR isHiddenInTravel = 0) ORDER BY updatedAt DESC LIMIT :limit"
    )
    fun observeFavoritesLimited(limit: Int, hideTravel: Boolean): Flow<List<VaultEntryEntity>>

    @Query(
        "SELECT category, COUNT(*) AS count FROM vault_entries WHERE deletedAt IS NULL " +
            "AND (:hideTravel = 0 OR isHiddenInTravel = 0) GROUP BY category"
    )
    fun observeCategoryCounts(hideTravel: Boolean): Flow<List<CategoryCountRow>>

    @Query("SELECT * FROM vault_entries WHERE deletedAt IS NULL ORDER BY isFavorite DESC, updatedAt DESC")
    suspend fun getAllActiveEntries(): List<VaultEntryEntity>

    @Query("SELECT * FROM vault_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VaultEntryEntity?

    @Query("SELECT COUNT(*) FROM vault_entries WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    @Query("SELECT * FROM vault_entries WHERE deletedAt IS NULL AND isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<VaultEntryEntity>>

    @Upsert
    suspend fun upsert(entry: VaultEntryEntity)

    @Upsert
    suspend fun upsertAll(entries: List<VaultEntryEntity>)

    @Query("UPDATE vault_entries SET lastAccessedAt = :time WHERE id = :id")
    suspend fun markAccessed(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE vault_entries SET isFavorite = :favorite, updatedAt = :time WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM vault_entries")
    suspend fun deleteAll()
}
