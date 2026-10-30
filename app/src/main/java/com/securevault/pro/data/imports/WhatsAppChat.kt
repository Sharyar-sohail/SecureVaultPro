package com.securevault.pro.data.imports

/** What the batch picker does with a file taken out of the WhatsApp folder. */
enum class WhatsAppPick {
    /** A chat export (.txt, or the .zip WhatsApp builds around it) — becomes one sealed note. */
    Conversation,

    /** A photo, video, voice note or document — sealed straight into the file vault. */
    Media,

    /** An encrypted message database: never importable, and misleading to try. */
    Ignored
}

/**
 * Pure routing and addressing for WhatsApp imports: where the app keeps its files, how to
 * jump straight to one conversation, and which of the two importers a picked file belongs to.
 * No Android types, so the rules are testable on the JVM.
 */
object WhatsAppChat {

    private val BACKUP = Regex("(?i)(^|/)(msgstore|chatlock|wa)(\\.db)?(\\.journal|-wal|-shm)?\\.crypt\\d+$|" +
        "(?i)\\.db(\\.crypt\\d+)?$|\\.db-(journal|wal|shm)$")

    /**
     * WhatsApp names an export "WhatsApp Chat with Ali.zip" and file managers rename it to
     * anything with "chat" or "conversation" in it. A date alone is not enough: media archives
     * are named "VID-2026-05-01-WA0002…", so a date pattern would drag every video zip into the
     * text importer and report a failure the user never caused. The second branch catches
     * camel-case renames ("WhatsAppChatwithAli.zip") that have no separator at all.
     */
    private val EXPORT_NAME = Regex(
        "(?i)(^|[^a-z])(chat|conversation)([^a-z]|$)|(?-i:(?<=[a-z])(?=Chat))"
    )

    /** WhatsApp writes to `<pkg>/WhatsApp`; Business uses a folder with a space in it. */
    fun folderName(packageId: String): String =
        if (packageId == WhatsAppPackages.BUSINESS) "WhatsApp Business" else "WhatsApp"

    /**
     * SAF document id of the folder the picker should open on, so the user lands inside the
     * app's own tree instead of scrolling to it: `primary:Android/media/<pkg>/<folder>`.
     */
    fun browseDocId(packageId: String): String =
        "primary:Android/media/$packageId/${folderName(packageId)}"

    /**
     * Both WhatsApp packages keep their exportable files under this one folder, and it exists
     * whether the handset has one app, the other, or both — so it is the safest place to drop
     * a picker.
     */
    const val MEDIA_ROOT_DOC_ID = "primary:Android/media"

    /** Digits only, country code included, as `wa.me` wants them. Null when unusable. */
    fun normalizeNumber(raw: String): String? {
        val digits = raw.filter { it.isDigit() }.trimStart('0')
        return digits.takeIf { it.length in 7..15 }
    }

    /**
     * Deep link that opens one conversation rather than whatever chat WhatsApp happened to
     * leave on screen — the launcher intent restores the last chat, which is why "Open
     * WhatsApp" never looked like a way to choose one.
     */
    fun deepLink(number: String): String = "https://wa.me/$number"

    fun route(fileName: String, mime: String?): WhatsAppPick {
        val name = fileName.substringAfterLast('/')
        if (BACKUP.containsMatchIn(name)) return WhatsAppPick.Ignored
        val lowered = name.lowercase()
        if (lowered.endsWith(".txt")) return WhatsAppPick.Conversation
        if (lowered.endsWith(".zip")) {
            return if (EXPORT_NAME.containsMatchIn(name)) WhatsAppPick.Conversation else WhatsAppPick.Media
        }
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/") ||
                mime.startsWith("image/") || mime == "application/pdf")
        ) {
            return WhatsAppPick.Media
        }
        return WhatsAppPick.Media
    }
}
