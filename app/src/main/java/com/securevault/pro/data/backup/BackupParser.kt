package com.securevault.pro.data.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.securevault.pro.security.TotpGenerator
import java.util.UUID

object BackupParser {

    private val validEntryCategories = setOf("LOGINS", "WEBSITES", "APPS", "FINANCE", "OTHERS")
    private val validContactCategories = setOf("Personal", "Work", "Emergency", "VIP")
    private val validCardTypes = setOf("CREDIT", "DEBIT", "CRYPTO", "ID")
    private val validNoteColors = setOf("DEFAULT", "CYAN", "MINT", "LAVENDER", "CORAL")

    fun parse(json: String): ParsedBackup {
        val warnings = mutableListOf<String>()
        if (json.isBlank()) {
            return ParsedBackup(BackupPayloadDto(), warnings = listOf("Empty backup payload"))
        }
        // The XML mirror is written beside every JSON backup; a file whose only readable copy is
        // the XML one is still a complete vault, not a malformed JSON document.
        val lead = json.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (lead.startsWith("<")) {
            val payload = BackupXmlParser.parse(lead)
                ?: return ParsedBackup(BackupPayloadDto(), warnings = listOf("Malformed XML backup"))
            return ParsedBackup(
                payload = payload,
                warnings = listOf("Opened the XML copy of this vault")
            )
        }
        val rootElement = try {
            JsonParser.parseString(json.trim())
        } catch (e: Exception) {
            return ParsedBackup(BackupPayloadDto(), warnings = listOf("Malformed JSON: ${e.message}"))
        }

        if (rootElement.isJsonArray) {
            val entries = parseArray(rootElement.asJsonArray, "entries", warnings) { parseEntry(it, warnings) }
            return ParsedBackup(BackupPayloadDto(entries = entries), warnings = warnings)
        }
        if (!rootElement.isJsonObject) {
            return ParsedBackup(BackupPayloadDto(), warnings = listOf("Unrecognised backup root"))
        }

        var root = rootElement.asJsonObject
        // Some Web builds wrap the payload in { "data": { ... } }
        val nested = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        if (nested != null && root.get("entries") == null && root.get("passwords") == null &&
            (nested.get("entries") != null || nested.get("passwords") != null)
        ) {
            root = nested
        }

        val wipeRequested = root.boolOrFalse("isWiped", "wipe", "wipeAll", "clearAll", "dataWiped") ||
            root.get("mode")?.takeIf { it.isJsonPrimitive }?.asString.equals("wipe", ignoreCase = true) ||
            root.get("operation")?.takeIf { it.isJsonPrimitive }?.asString.equals("WIPE", ignoreCase = true)

        val version = root.intOrNull("version", "v") ?: 3
        val entriesArray = root.arrayOf("entries", "passwords", "logins", "vault", "items")
        val notesArray = root.arrayOf("notes", "secureNotes", "secure_notes")
        val cardsArray = root.arrayOf("cards", "wallets", "digitalCards", "digital_cards", "creditCards")
        val totpsArray = root.arrayOf("totps", "authenticators", "twoFactor", "two_factor", "otpAccounts", "tokens", "twoFA")
        val contactsArray = root.arrayOf("contacts", "phoneContacts", "secure_contacts", "addressBook")
        val filesArray = root.arrayOf("files", "documents", "fileVault", "phone_vault")
        val walletsArray = root.arrayOf("cryptoWallets", "crypto_wallets", "crypto", "web3Wallets")

        val entries = parseArray(entriesArray, "entries", warnings) { parseEntry(it, warnings) }
        val notes = parseArray(notesArray, "notes", warnings) { parseNote(it, warnings) }
        val cards = parseArray(cardsArray, "cards", warnings) { parseCard(it, warnings) }
        val totps = parseArray(totpsArray, "totps", warnings) { parseTotp(it, warnings) }
        val contacts = parseArray(contactsArray, "contacts", warnings) { parseContact(it, warnings) }
        val files = parseArray(filesArray, "files", warnings) { parseFile(it, warnings) }
        val cryptoWallets = parseArray(walletsArray, "wallets", warnings) { parseCryptoWallet(it, warnings) }

        return ParsedBackup(
            payload = BackupPayloadDto(
                version = if (version > 0) version else 3,
                entries = entries,
                notes = notes,
                cards = cards,
                totps = totps,
                contacts = contacts,
                files = files,
                cryptoWallets = cryptoWallets
            ),
            wipeRequested = wipeRequested,
            warnings = warnings,
            sourceVersion = version
        )
    }

    private fun <T> parseArray(
        array: JsonArray?,
        label: String,
        warnings: MutableList<String>,
        parser: (JsonObject) -> T?
    ): List<T> {
        if (array == null) return emptyList()
        val out = ArrayList<T>(array.size())
        var skipped = 0
        for (element in array) {
            if (!element.isJsonObject) {
                skipped++
                continue
            }
            try {
                val parsed = parser(element.asJsonObject)
                if (parsed != null) out.add(parsed) else skipped++
            } catch (e: Exception) {
                skipped++
            }
        }
        if (skipped > 0) warnings += "Skipped $skipped malformed $label item(s)"
        return out
    }

    private fun parseEntry(obj: JsonObject, warnings: MutableList<String>): BackupEntryDto? {
        val title = obj.stringOf("title", "serviceName", "service_name", "name", "siteName", "app", "site")
            ?: obj.stringOf("website", "url") ?: "Untitled Entry"
        val username = obj.stringOf("username", "userName", "user_name", "login", "loginId", "login_id", "account")
            ?: ""
        val email = obj.stringOf("email", "emailAddress", "email_address") ?: ""
        val website = obj.stringOf("website", "siteUrl", "site_url", "url") ?: ""
        val url = obj.stringOf("url", "website", "siteUrl") ?: website
        val password = obj.stringOf("password", "pwd", "pass", "passwd", "secret") ?: ""
        val notes = obj.stringOf("notes", "note", "comment") ?: ""
        val category = normalizeEntryCategory(obj.stringOf("category", "type", "group"))
        val customFields = obj.get("customFieldsJson")?.asJsonStringValue()
            ?: obj.get("custom_fields_json")?.asJsonStringValue()
            ?: obj.get("customFields")?.toStringPreserving()
            ?: obj.get("custom_fields")?.toStringPreserving()
            ?: "[]"
        val tags = obj.get("tagsJson")?.asJsonStringValue()
            ?: obj.get("tags_json")?.asJsonStringValue()
            ?: obj.get("tags")?.toStringPreserving()
            ?: "[]"
        val totpSecret = obj.stringOf("totpSecret", "totp_secret", "otpSecret", "otp_secret") ?: ""
        val hasTotp = obj.boolOrNull("hasTotp", "has_totp", "totpEnabled") ?: totpSecret.isNotBlank()
        val id = obj.stringOf("id", "uuid", "_id") ?: deterministicId("entry", title, username, website, password)
        if (!TotpGenerator.isValidSecret(totpSecret) && totpSecret.isNotBlank()) {
            warnings += "Entry '$title' contains a non-standard TOTP secret (preserved)"
        }
        val createdAt = obj.longOf("createdAt", "created_at", "created") ?: System.currentTimeMillis()
        return BackupEntryDto(
            id = id,
            title = title,
            username = username,
            email = email,
            password = password,
            website = website,
            url = url,
            notes = notes,
            category = category,
            customFieldsJson = customFields,
            tagsJson = tags,
            isFavorite = obj.boolOrNull("isFavorite", "is_favorite", "favorite") ?: false,
            hasTotp = hasTotp,
            totpSecret = totpSecret,
            createdAt = createdAt,
            updatedAt = obj.longOf("updatedAt", "updated_at", "modifiedAt", "modified_at") ?: createdAt,
            lastAccessedAt = obj.longOf("lastAccessedAt", "last_accessed_at"),
            expiresAt = obj.longOf("expiresAt", "expires_at"),
            strengthScore = obj.intOrNull("strengthScore", "strength_score", "strength") ?: 0,
            deletedAt = obj.longOf("deletedAt", "deleted_at"),
            isHiddenInTravel = obj.boolOrNull("isHiddenInTravel", "is_hidden_in_travel", "hiddenInTravel") ?: false
        )
    }

    private fun parseNote(obj: JsonObject, warnings: MutableList<String>): BackupNoteDto? {
        val content = obj.stringOf("content", "body", "text", "note", "value") ?: ""
        val title = obj.stringOf("title", "name", "heading") ?: "Untitled Note"
        if (content.isBlank() && title.isBlank()) return null
        val rawColor = obj.stringOf("color", "noteColor", "note_color")
        val color = when {
            rawColor != null && validNoteColors.contains(rawColor.uppercase()) -> rawColor.uppercase()
            else -> colorForCategory(obj.stringOf("category", "group"))
        }
        val tagsField = obj.get("tagsJson")?.asJsonStringValue()
            ?: obj.get("tags_json")?.asJsonStringValue()
        val tagsArray = obj.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
        val attachmentsArray = obj.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
        val tagsJson = buildNoteMeta(tagsField, tagsArray, attachmentsArray, warnings)
        val createdAt = obj.longOf("createdAt", "created_at", "created") ?: System.currentTimeMillis()
        return BackupNoteDto(
            id = obj.stringOf("id", "uuid", "_id") ?: deterministicId("note", title, content),
            title = title,
            content = content,
            isPinned = obj.boolOrNull("isPinned", "is_pinned", "pinned") ?: false,
            color = color,
            tagsJson = tagsJson,
            createdAt = createdAt,
            updatedAt = obj.longOf("updatedAt", "updated_at", "modifiedAt") ?: createdAt,
            attachmentsJson = attachmentsArray?.toString()
        )
    }

    private fun parseCard(obj: JsonObject, warnings: MutableList<String>): BackupCardDto? {
        val holder = obj.stringOf("holderName", "cardholderName", "cardholder_name", "holder", "cardHolder")
            ?: obj.stringOf("name") ?: ""
        val nickname = obj.stringOf("nickname", "label", "title", "name") ?: "Card"
        val number = obj.stringOf("number", "cardNumber", "card_number", "pan") ?: ""
        if (holder.isBlank() && number.isBlank() && nickname.isBlank()) return null
        val expiryRaw = obj.stringOf("expirationDate", "expiryDate", "expiry", "expires", "validThru", "valid_thru")
        var month = obj.intOrNull("expiryMonth", "expiry_month", "expMonth", "exp_month")
        var year = obj.intOrNull("expiryYear", "expiry_year", "expYear", "exp_year")
        val parsed = parseExpiry(expiryRaw)
        if (month == null) month = parsed?.first
        if (year == null) year = parsed?.second
        if (expiryRaw != null && parsed == null && month == null) {
            warnings += "Card '$nickname' has an unrecognised expiry format (preserved as default)"
        }
        val createdAt = obj.longOf("createdAt", "created_at", "created") ?: System.currentTimeMillis()
        val type = (obj.stringOf("type", "cardType", "card_type") ?: "CREDIT").uppercase()
            .takeIf { validCardTypes.contains(it) } ?: "CREDIT"
        return BackupCardDto(
            id = obj.stringOf("id", "uuid", "_id") ?: deterministicId("card", nickname, number, holder),
            type = type,
            nickname = nickname,
            holderName = holder,
            number = number,
            expiryMonth = (month ?: 1).coerceIn(1, 12),
            expiryYear = normalizeYear(year ?: 30),
            cvv = obj.stringOf("cvv", "cvc", "securityCode", "security_code", "cvn") ?: "",
            pin = obj.stringOf("pin", "atmPin", "atm_pin") ?: "",
            bankName = obj.stringOf("bankName", "bank_name", "bank", "issuer", "network") ?: "",
            notes = obj.stringOf("notes", "note", "comment") ?: "",
            colorGradientStart = obj.stringOf("colorGradientStart", "gradientStart", "colorStart") ?: "#1E273E",
            colorGradientEnd = obj.stringOf("colorGradientEnd", "gradientEnd", "colorEnd") ?: "#141B2D",
            createdAt = createdAt
        )
    }

    private fun parseTotp(obj: JsonObject, warnings: MutableList<String>): BackupTotpDto? {
        var secret = obj.stringOf("secret", "secretKey", "secret_key", "key", "sharedSecret")
            ?: obj.stringOf("totpSecret", "otpSecret") ?: ""
        var issuer = obj.stringOf("issuer", "service", "provider", "app", "title") ?: ""
        var accountName = obj.stringOf("accountName", "account_name", "account", "name", "username", "email") ?: ""
        var algorithm = obj.stringOf("algorithm", "algo", "hash") ?: "SHA1"
        var digits = obj.intOrNull("digits", "codeLength", "code_length") ?: 6
        var period = obj.intOrNull("period", "interval", "refreshPeriod") ?: 30
        if (secret.startsWith("otpauth://", ignoreCase = true)) {
            val parsed = TotpGenerator.parseOtpAuth(secret)
            if (parsed != null) {
                secret = parsed.secret
                if (issuer.isBlank()) issuer = parsed.issuer
                if (accountName.isBlank()) accountName = parsed.accountName
                algorithm = parsed.algorithm.displayName
                digits = parsed.digits
                period = parsed.period
            } else {
                warnings += "A 2FA entry contained an invalid otpauth URI (preserved)"
            }
        }
        if (secret.isBlank()) {
            warnings += "A 2FA entry was skipped because it had no secret"
            return null
        }
        val createdAt = obj.longOf("createdAt", "created_at", "created") ?: System.currentTimeMillis()
        return BackupTotpDto(
            id = obj.stringOf("id", "uuid", "_id") ?: deterministicId("totp", issuer, accountName, secret),
            issuer = issuer.ifBlank { "Authenticator" },
            accountName = accountName.ifBlank { "Account" },
            secret = secret,
            algorithm = algorithm.uppercase().takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: "SHA1",
            digits = digits.coerceIn(6, 8),
            period = period.coerceIn(15, 120),
            createdAt = createdAt
        )
    }

    private fun parseContact(obj: JsonObject, warnings: MutableList<String>): BackupContactDto? {
        val name = obj.stringOf("name", "fullName", "full_name", "displayName", "title") ?: ""
        val phone = obj.stringOf("phone", "phoneNumber", "phone_number", "mobile", "tel", "number") ?: ""
        val email = obj.stringOf("email", "emailAddress", "email_address") ?: ""
        val address = obj.stringOf("address", "location", "postalAddress") ?: ""
        val notes = obj.stringOf("notes", "note", "comment") ?: ""
        if (name.isBlank() && phone.isBlank() && email.isBlank()) {
            warnings += "A contact without a name or number was skipped"
            return null
        }
        val categoryRaw = obj.stringOf("category", "group", "type") ?: "Personal"
        val category = validContactCategories.firstOrNull { it.equals(categoryRaw, ignoreCase = true) } ?: "Personal"
        val createdAt = obj.longOf("createdAt", "created_at", "created") ?: System.currentTimeMillis()
        return BackupContactDto(
            id = obj.stringOf("id", "uuid", "_id") ?: deterministicId("contact", name, phone, email),
            name = name.ifBlank { phone.ifBlank { "Unnamed Contact" } },
            phone = phone,
            email = email,
            address = address,
            notes = notes,
            category = category,
            createdAt = createdAt,
            updatedAt = obj.longOf("updatedAt", "updated_at", "modifiedAt") ?: createdAt
        )
    }

    private fun parseFile(obj: JsonObject, warnings: MutableList<String>): BackupFileDto? {
        val data = obj.get("data")?.asJsonStringValue()
            ?: obj.get("content")?.asJsonStringValue()
            ?: obj.get("base64")?.asJsonStringValue()
        val name = obj.stringOf("name", "filename", "fileName", "title") ?: "File"
        if (data.isNullOrBlank()) {
            warnings += "File '$name' skipped (no payload data)"
            return null
        }
        val mime = obj.stringOf("mimeType", "mime_type", "type") ?: "application/octet-stream"
        return BackupFileDto(
            id = obj.stringOf("id", "uuid", "_id"),
            name = name,
            size = obj.longOf("size", "bytes", "length") ?: 0L,
            type = obj.stringOf("type") ?: mime,
            mimeType = mime,
            category = obj.stringOf("category", "folder") ?: "Docs",
            data = data,
            createdAt = obj.longOf("createdAt", "created_at", "created") ?: System.currentTimeMillis(),
            uploadedAt = obj.longOf("uploadedAt", "uploaded_at", "modifiedAt") ?: System.currentTimeMillis()
        )
    }

    private fun parseCryptoWallet(obj: JsonObject, warnings: MutableList<String>): BackupCryptoWalletDto? {
        val secretElement = obj.get("secret") ?: obj.get("seed") ?: obj.get("privateKey")
            ?: obj.get("private_key")
        val secret = when {
            secretElement == null || secretElement.isJsonNull -> null
            secretElement.isJsonObject -> secretElement.toString()
            else -> secretElement.asJsonStringValue()
        }
        if (secret.isNullOrBlank()) {
            warnings += "A crypto wallet was skipped because it had no secret"
            return null
        }
        val label = obj.stringOf("label", "name", "title") ?: "Crypto wallet"
        return BackupCryptoWalletDto(
            id = obj.stringOf("id", "uuid", "_id"),
            label = label,
            kind = obj.stringOf("kind", "type"),
            secret = secret,
            balancesJson = obj.get("balancesJson")?.asJsonStringValue()
                ?: obj.get("balances")?.toString(),
            totalUsd = obj.doubleOf("totalUsd", "total_usd", "balanceUsd"),
            lastSyncedAt = obj.longOf("lastSyncedAt", "last_synced_at", "syncedAt"),
            createdAt = obj.longOf("createdAt", "created_at", "created"),
            updatedAt = obj.longOf("updatedAt", "updated_at", "modifiedAt")
        )
    }

    private fun buildNoteMeta(
        tagsJson: String?,
        tagsArray: JsonArray?,
        attachmentsArray: JsonArray?,
        warnings: MutableList<String>
    ): String {
        if (tagsJson.isNullOrBlank() && tagsArray == null && attachmentsArray == null) return "[]"
        var tags: JsonElement? = null
        var attachments: JsonArray? = null
        try {
            if (!tagsJson.isNullOrBlank()) {
                val parsed = JsonParser.parseString(tagsJson)
                if (parsed.isJsonObject) {
                    tags = parsed.asJsonObject.get("tags")
                    attachments = parsed.asJsonObject.getAsJsonArray("attachments")
                } else if (parsed.isJsonArray) {
                    tags = parsed
                }
            }
        } catch (_: Throwable) {
            // preserve raw below
        }
        if (tags == null && tagsArray != null) tags = tagsArray
        if (attachments == null && attachmentsArray != null) {
            attachments = attachmentsArray
        }
        if (attachments == null && tagsJson != null && !tagsJson.trim().startsWith("{")) {
            return tagsJson
        }
        val obj = JsonObject()
        obj.add("tags", tags ?: JsonArray())
        if (attachments != null) obj.add("attachments", attachments)
        if (tagsJson != null && tags == null && attachments == null) return tagsJson
        return obj.toString()
    }

    private fun colorForCategory(category: String?): String {
        return when (category?.lowercase()) {
            "security", "emergency", "urgent", "finance" -> "CORAL"
            "work", "business" -> "LAVENDER"
            "ideas", "personal", "health" -> "MINT"
            else -> "CYAN"
        }
    }

    private fun normalizeEntryCategory(raw: String?): String {
        val value = raw?.trim()?.uppercase() ?: return "WEBSITES"
        return when (value) {
            "LOGIN", "LOGINS", "PASSWORD", "PASSWORDS", "CREDENTIAL", "CREDENTIALS" -> "LOGINS"
            "WEBSITE", "WEBSITES", "WEB" -> "WEBSITES"
            "APP", "APPS", "APPLICATION", "APPLICATIONS" -> "APPS"
            "FINANCE", "BANK", "BANKING", "CARD", "WALLET" -> "FINANCE"
            else -> if (validEntryCategories.contains(value)) value else "OTHERS"
        }
    }

    private fun parseExpiry(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(" ", "")
        val parts = cleaned.split('/', '-', '.').filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val month = parts[0].toIntOrNull() ?: return null
            val year = parts[1].toIntOrNull() ?: return null
            if (month !in 1..12) return null
            return month to normalizeYear(year)
        }
        val digits = cleaned.filter(Char::isDigit)
        return when (digits.length) {
            3 -> (digits[0].digitToInt() to ("${digits[1]}${digits[2]}").toInt())
            4 -> {
                val month = digits.take(2).toIntOrNull() ?: return null
                val year = digits.drop(2).toIntOrNull() ?: return null
                if (month in 1..12) month to year else null
            }
            6 -> {
                val month = digits.take(2).toIntOrNull() ?: return null
                val year = digits.drop(2).toIntOrNull() ?: return null
                if (month in 1..12) month to year else null
            }
            else -> null
        }
    }

    private fun normalizeYear(year: Int): Int {
        return when {
            year in 0..99 -> year
            year in 2000..2100 -> year % 100
            year in 100..999 && year % 100 in 0..99 -> year % 100
            else -> 30
        }
    }

    private fun deterministicId(prefix: String, vararg parts: String): String {
        return UUID.nameUUIDFromBytes("$prefix|${parts.joinToString("|")}".toByteArray(Charsets.UTF_8)).toString()
    }

    // ---------- JSON helpers ----------

    private fun JsonObject.elementOf(vararg keys: String): JsonElement? {
        for (key in keys) {
            val element = get(key)
            if (element != null && !element.isJsonNull) return element
        }
        return null
    }

    private fun JsonObject.stringOf(vararg keys: String): String? {
        val element = elementOf(*keys) ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        val value = try {
            primitive.asString
        } catch (_: Throwable) {
            return null
        }
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.longOf(vararg keys: String): Long? {
        val element = elementOf(*keys) ?: return null
        val value = when {
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                if (primitive.isNumber) {
                    try { primitive.asLong } catch (_: Throwable) { null }
                } else {
                    primitive.asString.trim().toLongOrNull()
                }
            }
            else -> null
        } ?: return null
        return if (value in 1..9_999_999_999L) value * 1000L else value
    }

    private fun JsonObject.intOrNull(vararg keys: String): Int? {
        return longOf(*keys)?.toInt()
    }

    private fun JsonObject.doubleOf(vararg keys: String): Double? {
        val element = elementOf(*keys) ?: return null
        if (!element.isJsonPrimitive) return null
        return try {
            element.asJsonPrimitive.asDouble
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonObject.boolOrNull(vararg keys: String): Boolean? {
        val element = elementOf(*keys) ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asInt != 0
            primitive.isString -> primitive.asString.equals("true", true) || primitive.asString == "1"
            else -> null
        }
    }

    private fun JsonObject.boolOrFalse(vararg keys: String): Boolean = boolOrNull(*keys) ?: false

    private fun JsonObject.arrayOf(vararg keys: String): JsonArray? {
        for (key in keys) {
            val element = get(key) ?: continue
            if (element.isJsonArray) return element.asJsonArray
            if (element.isJsonObject) {
                // Support maps keyed by id: { "id1": {...}, "id2": {...} }
                val values = element.asJsonObject.entrySet().map { it.value }
                if (values.isNotEmpty() && values.all { it.isJsonObject }) {
                    val array = JsonArray()
                    values.forEach { array.add(it) }
                    return array
                }
            }
        }
        return null
    }

    private fun JsonElement.asJsonStringValue(): String? {
        if (!isJsonPrimitive) return null
        return try {
            asJsonPrimitive.asString
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonElement.toStringPreserving(): String {
        return if (isJsonPrimitive) {
            try {
                asJsonPrimitive.asString
            } catch (_: Throwable) {
                toString()
            }
        } else {
            toString()
        }
    }
}
