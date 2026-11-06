package com.securevault.pro.data.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A complete XML rendering of the backup payload. The result is only ever written
 * inside the same AES-256-GCM envelope as the JSON payload, so the secrets never
 * reach disk or Drive as plaintext XML.
 */
object BackupXmlSerializer {

    fun serialize(payload: BackupPayloadDto): String = buildString(4096) {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<secureVault version=\"").append(payload.version).append("\" generatedAt=\"")
            .append(System.currentTimeMillis()).append("\" generated=\"")
            .append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
            .append("\">\n")
        append("<entries>\n")
        payload.entries.forEach { entry ->
            append("<entry")
            attribute("id", entry.id)
            attribute("title", entry.title)
            attribute("username", entry.username)
            attribute("email", entry.email)
            attribute("website", entry.website)
            attribute("url", entry.url)
            attribute("category", entry.category)
            attribute("favorite", entry.isFavorite)
            attribute("hasTotp", entry.hasTotp)
            attribute("createdAt", entry.createdAt)
            attribute("updatedAt", entry.updatedAt)
            attribute("lastAccessedAt", entry.lastAccessedAt)
            attribute("expiresAt", entry.expiresAt)
            attribute("strengthScore", entry.strengthScore)
            attribute("hiddenInTravel", entry.isHiddenInTravel)
            append(">\n")
            field("password", entry.password)
            field("notes", entry.notes)
            field("totpSecret", entry.totpSecret)
            field("customFields", entry.customFieldsJson)
            field("tags", entry.tagsJson)
            append("</entry>\n")
        }
        append("</entries>\n<notes>\n")
        payload.notes.forEach { note ->
            append("<note")
            attribute("id", note.id)
            attribute("title", note.title)
            attribute("pinned", note.isPinned)
            attribute("color", note.color)
            attribute("createdAt", note.createdAt)
            attribute("updatedAt", note.updatedAt)
            append(">\n")
            field("content", note.content)
            field("tags", note.tagsJson)
            field("attachments", note.attachmentsJson)
            append("</note>\n")
        }
        append("</notes>\n<cards>\n")
        payload.cards.forEach { card ->
            append("<card")
            attribute("id", card.id)
            attribute("type", card.type)
            attribute("nickname", card.nickname)
            attribute("holderName", card.holderName)
            attribute("expiryMonth", card.expiryMonth)
            attribute("expiryYear", card.expiryYear)
            attribute("bankName", card.bankName)
            attribute("colorGradientStart", card.colorGradientStart)
            attribute("colorGradientEnd", card.colorGradientEnd)
            attribute("createdAt", card.createdAt)
            append(">\n")
            field("number", card.number)
            field("cvv", card.cvv)
            field("pin", card.pin)
            field("notes", card.notes)
            append("</card>\n")
        }
        append("</cards>\n<totpAccounts>\n")
        payload.totps.forEach { totp ->
            append("<account")
            attribute("id", totp.id)
            attribute("issuer", totp.issuer)
            attribute("accountName", totp.accountName)
            attribute("algorithm", totp.algorithm)
            attribute("digits", totp.digits)
            attribute("period", totp.period)
            attribute("createdAt", totp.createdAt)
            append(">\n")
            field("secret", totp.secret)
            append("</account>\n")
        }
        append("</totpAccounts>\n<contacts>\n")
        payload.contacts.forEach { contact ->
            append("<contact")
            attribute("id", contact.id)
            attribute("name", contact.name)
            attribute("category", contact.category)
            attribute("createdAt", contact.createdAt)
            attribute("updatedAt", contact.updatedAt)
            append(">\n")
            field("phone", contact.phone)
            field("email", contact.email)
            field("address", contact.address)
            field("notes", contact.notes)
            append("</contact>\n")
        }
        append("</contacts>\n<files>\n")
        payload.files.forEach { file ->
            append("<file")
            attribute("id", file.id)
            attribute("name", file.name)
            attribute("size", file.size)
            attribute("type", file.type)
            attribute("mimeType", file.mimeType)
            attribute("category", file.category)
            attribute("createdAt", file.createdAt)
            attribute("uploadedAt", file.uploadedAt)
            append(">\n")
            field("data", file.data)
            append("</file>\n")
        }
        append("</files>\n<cryptoWallets>\n")
        payload.cryptoWallets.forEach { wallet ->
            append("<wallet")
            attribute("id", wallet.id)
            attribute("label", wallet.label)
            attribute("kind", wallet.kind)
            attribute("totalUsd", wallet.totalUsd)
            attribute("lastSyncedAt", wallet.lastSyncedAt)
            attribute("createdAt", wallet.createdAt)
            attribute("updatedAt", wallet.updatedAt)
            append(">\n")
            field("secret", wallet.secret)
            field("keyBackup", wallet.keyBackup)
            field("balances", wallet.balancesJson)
            append("</wallet>\n")
        }
        append("</cryptoWallets>\n</secureVault>\n")
    }

    private fun StringBuilder.attribute(name: String, value: Any?) {
        if (value == null) return
        append(' ').append(name).append("=\"").append(escape(value.toString())).append('"')
    }

    private fun StringBuilder.field(name: String, value: Any?) {
        if (value == null) return
        append('<').append(name).append('>')
        append(escape(value.toString()))
        append("</").append(name).append(">\n")
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (char in value) {
            when (char) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                '\n' -> out.append("&#10;")
                '\r' -> out.append("&#13;")
                '\t' -> out.append("&#9;")
                else -> if (char.code < 0x20) out.append(' ') else out.append(char)
            }
        }
        return out.toString()
    }
}
