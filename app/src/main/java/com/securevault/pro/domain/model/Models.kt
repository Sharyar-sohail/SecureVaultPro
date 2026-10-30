package com.securevault.pro.domain.model

import java.util.UUID

data class CustomField(
    val label: String = "",
    val value: String = "",
    val concealed: Boolean = false
)

data class VaultEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val website: String = "",
    val url: String = "",
    val notes: String = "",
    val category: String = "WEBSITES",
    val customFields: List<CustomField> = emptyList(),
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val hasTotp: Boolean = false,
    val totpSecret: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long? = null,
    val expiresAt: Long? = null,
    val strengthScore: Int = 0,
    val isHiddenInTravel: Boolean = false,
    val passwordSealed: Boolean = false,
    val notesSealed: Boolean = false,
    val totpSealed: Boolean = false
) {
    val displayUrl: String get() = website.ifBlank { url }
    val domain: String
        get() {
            val raw = displayUrl.trim()
            return raw.removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore(':').ifBlank { title }
        }
}

data class DigitalCard(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "CREDIT",
    val nickname: String = "",
    val holderName: String = "",
    val number: String = "",
    val expiryMonth: Int = 1,
    val expiryYear: Int = 30,
    val cvv: String = "",
    val pin: String = "",
    val bankName: String = "",
    val notes: String = "",
    val colorGradientStart: String = "#1E273E",
    val colorGradientEnd: String = "#141B2D",
    val createdAt: Long = System.currentTimeMillis(),
    val sealed: Boolean = false
) {
    val last4: String get() = number.filter(Char::isDigit).takeLast(4)
    val expiryLabel: String get() = "%02d/%02d".format(expiryMonth, expiryYear % 100)
    val brand: String
        get() {
            val digits = number.filter(Char::isDigit)
            return when {
                digits.startsWith("4") -> "VISA"
                digits.take(2).toIntOrNull() in 51..55 || digits.startsWith("2221") -> "MASTERCARD"
                digits.startsWith("34") || digits.startsWith("37") -> "AMEX"
                type == "CRYPTO" -> "CRYPTO"
                type == "ID" -> "IDENTITY"
                else -> "CARD"
            }
        }
}

data class TotpAccount(
    val id: String = UUID.randomUUID().toString(),
    val issuer: String = "",
    val accountName: String = "",
    val secret: String = "",
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val createdAt: Long = System.currentTimeMillis(),
    val sealed: Boolean = false
)

data class NoteAttachment(
    val id: String,
    val name: String,
    val mimeType: String = "application/octet-stream",
    val size: Long = 0
)

data class SecureNote(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val isPinned: Boolean = false,
    val color: String = "DEFAULT",
    val tags: List<String> = emptyList(),
    val attachments: List<NoteAttachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sealed: Boolean = false
)

data class SecureContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val category: String = "Personal",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val initials: String
        get() = name.split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "?" }
}

data class CategoryCounts(
    val passwords: Int = 0,
    val totps: Int = 0,
    val cards: Int = 0,
    val notes: Int = 0,
    val contacts: Int = 0,
    val files: Int = 0
)

enum class SearchKind { PASSWORD, CARD, NOTE, CONTACT, TOTP }

data class SearchHit(
    val kind: SearchKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val iconHint: String = ""
)

data class SecurityReport(
    val totalEntries: Int = 0,
    val averageStrength: Int = 0,
    val weak: List<VaultEntry> = emptyList(),
    val reused: List<ReusedGroup> = emptyList(),
    val missingTwoFactor: List<VaultEntry> = emptyList(),
    val compromised: List<VaultEntry> = emptyList(),
    val twoFactorCoverage: Float = 0f,
    val score: Int = 0
) {
    val reusedCount: Int get() = reused.sumOf { it.entries.size }
    val weakCount: Int get() = weak.size
    val missingTwoFactorCount: Int get() = missingTwoFactor.size
    val compromisedCount: Int get() = compromised.size
}

data class ReusedGroup(
    val fingerprint: String,
    val entries: List<VaultEntry>
)

val EntryCategories = listOf(
    "ALL", "LOGINS", "BANKING", "IDENTITY", "VEHICLE", "WEBSITES", "APPS", "FINANCE", "OTHERS"
)
val ContactCategories = listOf("Personal", "Work", "Emergency", "VIP")
val CardTypes = listOf("CREDIT", "DEBIT", "CRYPTO", "ID")
val NoteColors = listOf("DEFAULT", "CYAN", "MINT", "LAVENDER", "CORAL")

/**
 * Duress sessions must never reveal — or fabricate — records. The decoy vault is
 * intentionally empty so a coercer sees a working, plausible app with nothing
 * worth taking, and no real ciphertext is ever mounted in that session.
 */
object DecoyVault {
    val entries: List<VaultEntry> = emptyList()
    val notes: List<SecureNote> = emptyList()
    val cards: List<DigitalCard> = emptyList()
    val totps: List<TotpAccount> = emptyList()
    val contacts: List<SecureContact> = emptyList()
}
