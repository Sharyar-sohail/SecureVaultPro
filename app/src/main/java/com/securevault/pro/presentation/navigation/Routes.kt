package com.securevault.pro.presentation.navigation

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val VAULT = "vault?category={category}"
    const val ENTRY_DETAIL = "entry/{entryId}"
    const val ENTRY_EDITOR = "entryEditor?entryId={entryId}"
    const val ENTRY_SCANNER = "entryEditor/scan"
    const val AUTH = "auth"
    const val TOTP_EDITOR = "totpEditor?accountId={accountId}&uri={uri}"
    const val SCANNER = "scanner"
    const val SCAN_HUB = "scan"
    const val OCR_SCANNER = "scan/ocr"
    const val DOC_SCANNER = "scan/document"
    const val FILE_SCANNER = "scan/files"
    const val SECURITY_TOOLS = "security-tools"
    const val SYNC_CENTER = "sync-center"
    const val WALLET = "wallet"
    const val CRYPTO = "crypto"
    const val VIEWER = "viewer/{fileId}"
    const val CARD_EDITOR = "cardEditor?cardId={cardId}"
    const val NOTES = "notes"
    const val NOTE_EDITOR = "noteEditor?noteId={noteId}"
    const val CONTACTS = "contacts"
    const val CONTACT_EDITOR = "contactEditor?contactId={contactId}"
    const val FILES = "files"
    const val VOICE_MEMOS = "files/voice"
    const val WHATSAPP_CHATS = "files/chats?chatId={chatId}"
    const val BACKUP = "backup"
    const val HEALTH = "health"
    const val SETTINGS = "settings"
    const val PAIR_SCANNER = "settings/pair-scan"

    // The sections added by this release wave. Each is one self-navigating composable
    // (EmailSection / StegoSection / PasskeySection; the recovery seed is embedded in
    // Security Tools and onboarding rather than routed), so the host needs one
    // constant and one `composable(...)` entry per feature rather than a route tree per screen.
    const val EMAIL = "email"
    const val STEGO = "stego"
    const val PASSKEY = "passkey"

    fun vault(category: String = "ALL"): String = "vault?category=$category"
    fun entryDetail(entryId: String): String = "entry/$entryId"
    fun entryEditor(entryId: String? = null): String =
        if (entryId.isNullOrBlank()) "entryEditor" else "entryEditor?entryId=$entryId"
    fun totpEditor(accountId: String? = null, uri: String? = null): String {
        val params = buildList {
            if (!accountId.isNullOrBlank()) add("accountId=$accountId")
            if (!uri.isNullOrBlank()) add("uri=${Uri.encode(uri)}")
        }
        return if (params.isEmpty()) "totpEditor" else "totpEditor?${params.joinToString("&")}"
    }
    fun cardEditor(cardId: String? = null): String =
        if (cardId.isNullOrBlank()) "cardEditor" else "cardEditor?cardId=$cardId"
    fun noteEditor(noteId: String? = null): String =
        if (noteId.isNullOrBlank()) "noteEditor" else "noteEditor?noteId=$noteId"
    fun contactEditor(contactId: String? = null): String =
        if (contactId.isNullOrBlank()) "contactEditor" else "contactEditor?contactId=$contactId"
    fun viewer(fileId: String): String = "viewer/$fileId"
    fun whatsAppChats(chatId: String? = null): String =
        if (chatId.isNullOrBlank()) "files/chats" else "files/chats?chatId=$chatId"

    val topLevelRoutes = setOf(HOME, VAULT, AUTH, CRYPTO, WALLET)

    /**
     * Where the owner is standing, in the words a crash report should use.
     *
     * `CrashLogger.noteStage` can only carry the navigation *state*, so every screen inside the vault
     * reports the same place and a report cannot say where the fault happened. The route is the one
     * value that does know, and it is already read here for the bottom bar, so the screens need no
     * wiring of their own. Names are for the owner who photographs the report, not for the log.
     */
    fun screenNameFor(route: String?): String {
        if (route.isNullOrBlank()) return "the app was still opening"
        return when (route.substringBefore('?').trimEnd('/')) {
            HOME -> "the home dashboard"
            VAULT -> "the saved passwords list"
            ENTRY_DETAIL -> "a saved password"
            ENTRY_EDITOR -> "the new-password form"
            ENTRY_SCANNER -> "the card and document scanner"
            AUTH -> "the authenticator codes"
            TOTP_EDITOR -> "the authenticator setup form"
            SCANNER -> "the code scanner"
            SCAN_HUB -> "the scanner hub"
            OCR_SCANNER -> "the text scanner"
            DOC_SCANNER -> "the document camera"
            FILE_SCANNER -> "the file scanner"
            SECURITY_TOOLS -> "security tools"
            SYNC_CENTER -> "the sync centre"
            WALLET -> "the card wallet"
            CARD_EDITOR -> "the card editor"
            CRYPTO -> "the crypto wallet"
            VIEWER -> "the file viewer"
            NOTES -> "the notes list"
            NOTE_EDITOR -> "the note editor"
            CONTACTS -> "the contacts"
            CONTACT_EDITOR -> "the contact editor"
            FILES -> "the file vault"
            VOICE_MEMOS -> "the voice memos"
            WHATSAPP_CHATS -> "the WhatsApp chats"
            BACKUP -> "backup and export"
            HEALTH -> "the security health report"
            SETTINGS -> "settings"
            PAIR_SCANNER -> "the device pairing scanner"
            EMAIL -> "the email inbox"
            STEGO -> "the hidden-message tool"
            PASSKEY -> "the passkeys"
            else -> "the screen called $route"
        }
    }
}
