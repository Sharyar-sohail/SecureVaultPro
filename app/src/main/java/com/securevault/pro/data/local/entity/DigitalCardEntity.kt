package com.securevault.pro.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "digital_cards")
data class DigitalCardEntity(
    @PrimaryKey val id: String,
    val type: String,
    val nickname: String,
    val holderName: String,
    val number: String,
    val expiryMonth: Int,
    val expiryYear: Int,
    val cvv: String,
    val pin: String,
    val bankName: String,
    val notes: String,
    val colorGradientStart: String = "#1E273E",
    val colorGradientEnd: String = "#141B2D",
    val createdAt: Long = System.currentTimeMillis()
)
