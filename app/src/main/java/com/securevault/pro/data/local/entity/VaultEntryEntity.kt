package com.securevault.pro.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_entries")
data class VaultEntryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val username: String,
    val email: String,
    val password: String,
    val website: String,
    val url: String,
    val notes: String,
    val category: String = "WEBSITES",
    val customFieldsJson: String = "[]",
    val tagsJson: String = "[]",
    val isFavorite: Boolean = false,
    val hasTotp: Boolean = false,
    val totpSecret: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long? = null,
    val expiresAt: Long? = null,
    val strengthScore: Int = 0,
    val deletedAt: Long? = null,
    val isHiddenInTravel: Boolean = false
)
