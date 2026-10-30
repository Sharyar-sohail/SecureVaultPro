package com.securevault.pro.data.imports

/**
 * Reading WhatsApp's own folder from inside the vault.
 *
 * The document picker can only ever hand back files the user can see one at a time, which is
 * why "import my chats" never worked: a conversation's export lives in `WhatsApp Exports`, its
 * photos and voice notes in type folders under `WhatsApp Media`, and tapping through both is
 * not importing a chat. Once the folder is granted, the whole tree can be walked and sorted by
 * these rules, so "every chat", "this chat" and "the files from it" become one tap each.
 *
 * Only string logic lives here, so the rules run on the JVM; the content-provider walk sits in
 * the view model.
 */
object WhatsAppTree {

    /** WhatsApp writes exports here; Business uses the same name inside its own package folder. */
    const val EXPORTS_DIR = "WhatsApp Exports"

    /** Photos, videos, voice notes and documents, grouped by type. */
    const val MEDIA_DIR = "WhatsApp Media"

    /** The real tree is container/type/file — deeper than this is not WhatsApp's layout. */
    const val MAX_DEPTH = 4

    /** The folder the picker should open on: `<pkg>/WhatsApp` under shared storage. */
    fun rootRelativePath(packageId: String): String =
        "Android/media/$packageId/${WhatsAppChat.folderName(packageId)}"

    /**
     * A tree URI for [rootRelativePath], percent-encoded the way the external-storage provider
     * expects: SAF keeps the whole document id ("primary:Android/media/…") as one encoded path
     * segment, so the volume name has to be in front of the path, not beside it.
     */
    fun initialTreeUri(relativePath: String): String =
        "content://com.android.externalstorage.documents/tree/${encode("primary:$relativePath")}"

    private fun encode(value: String): String = buildString {
        for (ch in value) {
            if (ch.isLetterOrDigit() || ch in "-._~") append(ch)
            else for (byte in ch.toString().toByteArray(Charsets.UTF_8)) {
                append('%')
                append(HEX[(byte.toInt() shr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /**
     * How a folder or file found inside the granted tree should be treated.
     *
     * The container names are matched anywhere in the path, not at a fixed depth: current
     * WhatsApp writes to `Android/media/<pkg>/WhatsApp/…` while older installs keep the same
     * folders under a `WhatsApp` folder at the root of shared storage, and either may be the
     * folder the user grants. Subfolders of `WhatsApp Media` are named per type ("Images",
     * "Voice Notes", …) and vary by version and locale, so everything in that container is
     * taken as media rather than matched by folder name.
     */
    fun classify(relativePath: String, isDirectory: Boolean): Kind {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        val name = segments.lastOrNull().orEmpty()
        val inExports = segments.contains(EXPORTS_DIR)
        val inMedia = segments.contains(MEDIA_DIR)
        val route = if (isDirectory) null else WhatsAppChat.route(name, null)
        val junk = name.startsWith(".") || name.endsWith(".tmb", ignoreCase = true) ||
            segments.any { it.endsWith("Thumbnails", ignoreCase = true) }
        return when {
            isDirectory && name == EXPORTS_DIR -> Kind.ExportsFolder
            isDirectory && inMedia -> Kind.MediaFolder
            isDirectory && segments.size == 1 -> Kind.RootFolder
            isDirectory -> Kind.Skip
            route == WhatsAppPick.Ignored -> Kind.Unreadable
            junk -> Kind.Skip
            route == WhatsAppPick.Conversation && inExports -> Kind.ChatExport
            inMedia -> Kind.MediaFile
            else -> Kind.Skip
        }
    }

    /** True when the walker should open this directory and look inside it. */
    fun descends(kind: Kind): Boolean =
        kind == Kind.RootFolder || kind == Kind.MediaFolder || kind == Kind.ExportsFolder

    enum class Kind {
        /** A folder of WhatsApp media — every file under it is a photo, video, note or document. */
        MediaFolder,

        /** The folder WhatsApp writes its "Export chat" files into. */
        ExportsFolder,

        /** A top-level folder in the granted root, offered as a choice before it is walked. */
        RootFolder,

        /** A chat transcript, or an export .zip that carries its own attachments. */
        ChatExport,

        /** A photo, video, voice note or document. */
        MediaFile,

        /** Thumbnails, keystores, stray files: never importable, never worth reporting as a failure. */
        Skip,

        /** A `msgstore.db.crypt14` and friends — encrypted with a key only WhatsApp holds. */
        Unreadable
    }

    /**
     * What WhatsApp puts in front of every export of one conversation —
     * "WhatsApp Chat with Ali.txt" and "WhatsApp Chat with Ali (1).zip" both reduce to "ali".
     * Numbered re-exports collapse onto the same chat, which is what makes "this chat" findable.
     *
     * The scaffolding words are dropped wherever they appear rather than stripped from the
     * front, so a group called "Family Chat" still keys to "family" instead of "family chat".
     */
    fun chatKey(fileName: String): String = tokens(fileName)

    /** The same rules as [chatKey], applied to what the user typed, so the two can be compared. */
    fun searchKey(query: String): String = tokens(query)

    /**
     * The conversation name as WhatsApp wrote it, without the "WhatsApp Chat with" scaffolding.
     * Shown in the picker, so casing and spaces survive — [chatKey] is for matching only.
     */
    fun displayName(fileName: String): String {
        val name = stem(fileName)
        return LEAD_NOISE.replace(name, " ").trim().ifBlank { name }
    }

    private val LEAD_NOISE = Regex(
        "(?i)^(whatsapp\\s*chat\\s*(with|mit|avec|con)?|export\\s*chat|exportchat|chat\\s*with|conversation\\s*(with)?)" +
            "[\\s_-]*"
    )

    private fun tokens(value: String): String = stem(value)
        .split(SEPARATOR)
        .filter { it.isNotBlank() && it.lowercase() !in NOISE }
        .joinToString(" ")
        .lowercase()

    /** A name without a dot keeps itself: dropping the extension must not drop the whole name. */
    private fun stem(fileName: String): String {
        val cleaned = fileName.substringAfterLast('/').replace(RENUMBERED, " ")
        val dot = cleaned.lastIndexOf('.')
        return (if (dot > 0) cleaned.substring(0, dot) else cleaned).trim()
    }

    /** "WhatsApp Chat with Ali (3).txt" — the "(3)" is a file-manager rename, not part of the chat. */
    private val RENUMBERED = Regex("\\(\\d+\\)")

    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    private val NOISE = setOf(
        "whatsapp", "chat", "chats", "export", "exportchat", "conversation",
        "with", "mit", "con", "avec", "me", "my"
    )

    /**
     * True when a file belongs to the conversation named in [query]. Blank or "all" matches
     * every chat. Matching runs both ways because WhatsApp truncates long group subjects in the
     * export name, so the typed name is often longer than the key the file was left with.
     */
    fun matchesChat(fileName: String, query: String): Boolean {
        val wanted = searchKey(query)
        if (wanted.isBlank() || wanted == "all") return true
        val found = chatKey(fileName)
        if (found.isBlank()) return false
        return found == wanted || found.contains(wanted) || wanted.contains(found)
    }

    /** A folder path is "about" a chat when its own name or the chat's name sits in the path. */
    fun folderMatchesQuery(relativePath: String, query: String): Boolean {
        val wanted = searchKey(query)
        if (wanted.isBlank() || wanted == "all") return true
        return searchKey(relativePath).contains(wanted) || wanted.contains(searchKey(relativePath))
    }
}
