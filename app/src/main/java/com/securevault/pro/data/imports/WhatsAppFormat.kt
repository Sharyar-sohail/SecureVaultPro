package com.securevault.pro.data.imports

import java.text.Normalizer

/**
 * The naming and refusal rules WhatsApp exports obey, kept away from Android types so they can be
 * pinned by a test rather than argued about on a handset.
 *
 * Three things genuinely confuse an importer and each gets one rule here:
 *  1. an encrypted message database (`msgstore.db.crypt14`) is not a chat and no app can read it;
 *  2. the transcript inside an export archive is named differently per platform and per re-export
 *     (`Ali _chat.txt`, `WPP00001.txt`, `_…_media.txt`, `9234567890@s.whatsapp.net.txt`), so its
 *     name only ranks candidates — the bytes decide;
 *  3. the conversation title the owner recognises usually lives in the *archive* name, not in the
 *     transcript name, which iOS writes as a running number.
 */
object WhatsAppFormat {

    /** `msgstore.db.crypt14`, `chatlock.db`, `-wal`, `-shm` — WhatsApp's own encrypted stores. */
    private val BACKUP_NAME = Regex("(?i)\\.(db|sql|db-journal|db-wal|db-shm)$|crypt\\d{2}")

    /**
     * Names that carry no conversation of their own: WhatsApp's credits file and a bare running
     * number (`WPP00001.txt`, `_MEDIA001.mp4`) tell the owner nothing, so the archive name wins.
     */
    private val NAMELESS_STEM = Regex("(?i)^(wpp\\d+|_?media\\d*|\\d+)$")

    /** The bookkeeping files WhatsApp drops beside the transcript inside an archive. */
    private val BOOKKEEPING = Regex("(?i)^_?missing.*\\.txt$|(?i)medias?\\s*removed")

    /**
     * `_media/_manifest.xml` — the table of every file in the media folder, with its type, date and
     * sha256. It describes the archive; it is not a message and it is not a photo, so sealing it as
     * media would put a spreadsheet of the owner's files inside the chat.
     */
    private val MANIFEST_XML = Regex("(?i)^_?manifest\\.xml$|^_?manifest_[\\w-]*\\.xml$")

    /** True when the file is WhatsApp's encrypted message store, which nothing outside WhatsApp can open. */
    fun isEncryptedBackup(name: String): Boolean =
        BACKUP_NAME.containsMatchIn(name.substringAfterLast('/').substringAfterLast('\\'))

    /**
     * WhatsApp's own manifest inside an export archive — never the conversation and never a file the
     * owner keeps: `_MISSING.txt`, `_WEBSITES_listing.txt`, `_MEDIAS REMOVED.txt`, `_media.xml`
     * (which is only a listing of the media folder) and `_manifest.xml`.
     */
    fun isBookkeeping(name: String): Boolean {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        return WhatsAppTranscript.isTranscriptManifest(fileName) ||
            BOOKKEEPING.containsMatchIn(fileName) ||
            MANIFEST_XML.matches(fileName) ||
            isListingOfMediaFolder(fileName)
    }

    /** `_media.xml` sits beside the transcript in some desktop exports and lists the media folder. */
    fun isListingOfMediaFolder(name: String): Boolean {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        return fileName.matches(Regex("(?i)^_?media\\.xml$"))
    }

    /** The structured messages table WhatsApp can write beside the transcript. */
    fun isStructuredMessages(name: String): Boolean = WhatsAppStructuredCsv.isStructuredMessagesFile(name)

    /**
     * Whether a file inside an archive may compete to be the conversation: any `.txt` that is not
     * WhatsApp's own bookkeeping. Name decides eligibility only — [WhatsAppTranscript.bestTranscript]
     * and the message count decide which file really is the chat.
     */
    fun isTranscriptCandidate(name: String): Boolean =
        !isBookkeeping(name) && WhatsAppTranscript.isTranscriptCandidate(name)

    /**
     * A name-based tie-break for the rare case where two `.txt` files in one archive carry the same
     * number of messages: a name containing the chat scaffolding, a WhatsApp JID or the iOS
     * `…_media.txt` spelling outranks a document the owner happened to keep in the folder.
     */
    fun transcriptRank(name: String): Int {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return when {
            fileName.contains("chat") || fileName.contains("whatsapp") || fileName.contains("conversation") -> 3
            fileName.contains("@s.whatsapp.net") || fileName.contains("@g.us") || fileName.contains("@c.us") -> 3
            fileName.endsWith("_media.txt") || fileName.contains("_media") -> 2
            NAMELESS_STEM.matches(fileName.removeSuffix(".txt")) -> 1
            else -> 0
        }
    }

    /**
     * The title the owner sees. The archive name wins whenever it says something, because iOS names
     * the transcript inside it `WPP00001.txt` while the zip still reads
     * "WhatsApp Chat with Ali.zip"; an archive with no usable name falls back to the transcript.
     */
    fun chatTitle(archiveName: String, transcriptName: String): String {
        val fromArchive = usableTitle(archiveName)
        if (fromArchive != null) return fromArchive
        val fromTranscript = usableTitle(transcriptName)
        if (fromTranscript != null) return fromTranscript
        return "WhatsApp chat"
    }

    private fun usableTitle(name: String): String? {
        val value = WhatsAppTranscript.titleFrom(name)
        if (value.isBlank() || value == "WhatsApp chat") return null
        val stem = name.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".txt")
        if (NAMELESS_STEM.matches(stem)) return null
        return value
    }

    /**
     * The transcript body of an exported chat: what WhatsApp itself writes, so a chat exported from
     * the vault re-imports as the same conversation instead of becoming a note about a note.
     */
    fun transcriptBody(title: String, conversation: String): String = buildString {
        appendLine("Secure Wallet Pro · WhatsApp chat · $title")
        appendLine("Re-exported from the encrypted vault — every line below is exactly as it was archived.")
        appendLine()
        append(conversation.trim())
        appendLine()
    }

    /**
     * A download-safe file name. WhatsApp's own export is called "WhatsApp Chat with <chat>.txt",
     * which is what a mailbox, a laptop or a second phone will recognise.
     */
    fun exportedTranscriptName(title: String): String =
        "${safeFileStem(if (title.isBlank() || title == "WhatsApp chat") "WhatsApp chat" else "WhatsApp Chat with $title")}.txt"

    /** The archive name for a chat exported with its media, matching what WhatsApp writes. */
    fun exportedArchiveName(title: String): String =
        "${safeFileStem(if (title.isBlank() || title == "WhatsApp chat") "WhatsApp chat" else "WhatsApp Chat with $title")}.zip"

    /**
     * Strips what the filesystem and SAF providers reject, keeps Unicode letters, and caps the
     * length: a chat title is user data and can be anything the owner typed.
     */
    fun safeFileStem(value: String): String {
        val flat = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
        return flat.take(90).ifBlank { "WhatsApp chat" }
    }

    /** The name the transcript gets inside a vault-built archive — the same shape WhatsApp uses. */
    const val ARCHIVE_TRANSCRIPT_ENTRY = "WhatsApp Chat.txt"
}
