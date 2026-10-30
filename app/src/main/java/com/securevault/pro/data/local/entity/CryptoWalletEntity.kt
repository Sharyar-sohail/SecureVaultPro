package com.securevault.pro.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "crypto_wallets")
data class CryptoWalletEntity(
    @PrimaryKey val id: String,
    val label: String,
    val kind: String,
    val secret: String,
    val addressesJson: String,
    val balancesJson: String = "[]",
    val totalUsd: Double = 0.0,
    val lastSyncedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
