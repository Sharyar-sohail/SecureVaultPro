package com.securevault.pro.data.backup

data class BackupPayloadDto(
    val version: Int = 3,
    val entries: List<BackupEntryDto> = emptyList(),
    val notes: List<BackupNoteDto> = emptyList(),
    val cards: List<BackupCardDto> = emptyList(),
    val totps: List<BackupTotpDto> = emptyList(),
    val contacts: List<BackupContactDto> = emptyList(),
    val files: List<BackupFileDto> = emptyList(),
    val cryptoWallets: List<BackupCryptoWalletDto> = emptyList()
) {
    val totalItems: Int
        get() = entries.size + notes.size + cards.size + totps.size + contacts.size + files.size +
            cryptoWallets.size
}

data class BackupEntryDto(
    val id: String? = null,
    val title: String? = null,
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
    val website: String? = null,
    val url: String? = null,
    val notes: String? = null,
    val category: String? = null,
    val customFieldsJson: String? = null,
    val tagsJson: String? = null,
    val isFavorite: Boolean? = null,
    val hasTotp: Boolean? = null,
    val totpSecret: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val lastAccessedAt: Long? = null,
    val expiresAt: Long? = null,
    val strengthScore: Int? = null,
    val deletedAt: Long? = null,
    val isHiddenInTravel: Boolean? = null
)

data class BackupCardDto(
    val id: String? = null,
    val type: String? = null,
    val nickname: String? = null,
    val holderName: String? = null,
    val number: String? = null,
    val expiryMonth: Int? = null,
    val expiryYear: Int? = null,
    val cvv: String? = null,
    val pin: String? = null,
    val bankName: String? = null,
    val notes: String? = null,
    val colorGradientStart: String? = null,
    val colorGradientEnd: String? = null,
    val createdAt: Long? = null
)

data class BackupTotpDto(
    val id: String? = null,
    val issuer: String? = null,
    val accountName: String? = null,
    val secret: String? = null,
    val algorithm: String? = null,
    val digits: Int? = null,
    val period: Int? = null,
    val createdAt: Long? = null
)

data class BackupNoteDto(
    val id: String? = null,
    val title: String? = null,
    val content: String? = null,
    val isPinned: Boolean? = null,
    val color: String? = null,
    val tagsJson: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val attachmentsJson: String? = null
)

data class BackupContactDto(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val category: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class BackupFileDto(
    val id: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val type: String? = null,
    val mimeType: String? = null,
    val category: String? = null,
    val data: String? = null,
    val createdAt: Long? = null,
    val uploadedAt: Long? = null
)

data class ParsedBackup(
    val payload: BackupPayloadDto,
    val wipeRequested: Boolean = false,
    val warnings: List<String> = emptyList(),
    val sourceVersion: Int = 3
) {
    val totalItems: Int get() = payload.totalItems
}

data class CloudBackupFile(
    val id: String,
    val name: String,
    val size: Long,
    val modifiedTime: Long,
    val isPrimary: Boolean
)

data class BackupCryptoWalletDto(
    val id: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val secret: String? = null,
    val balancesJson: String? = null,
    val totalUsd: Double? = null,
    val lastSyncedAt: Long? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class BackupCounts(
    val entries: Int = 0,
    val notes: Int = 0,
    val cards: Int = 0,
    val totps: Int = 0,
    val contacts: Int = 0,
    val files: Int = 0,
    val wallets: Int = 0
) {
    val total: Int get() = entries + notes + cards + totps + contacts + files + wallets
}

data class BackupPreview(
    val fileName: String,
    val counts: BackupCounts,
    val encrypted: Boolean,
    val warnings: List<String> = emptyList(),
    val readyForDirectRestore: Boolean = true
)

enum class CloudOperation { BACKUP, RESTORE, PREVIEW, IMPORT, EXPORT, SEED }

data class CloudSyncState(
    val operation: CloudOperation? = null,
    val active: Boolean = false,
    val percent: Int = 0,
    val title: String = "",
    val detail: String = "",
    val error: String? = null,
    val success: String? = null
)
