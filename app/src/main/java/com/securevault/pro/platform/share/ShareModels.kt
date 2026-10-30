package com.securevault.pro.platform.share

/**
 * What happened when the vault tried to hand a file to another app.
 *
 * Every branch is a real, separately-observable failure. The rule this enum exists to enforce is
 * that a share call never returns a bare `true`/`false` or `Unit`: a decrypted secret that did not
 * leave the phone has to be *named on screen*, not swallowed. The two stages people most often
 * confuse are [NoProviderRoot] and [NoTarget] — the first means Android refused to mint a URI for
 * our own file (the `file_paths.xml` roots or the authority are wrong), the second means the file
 * left fine but no installed app wanted it. Telling the owner "the share sheet never opened" when
 * the truth is "this build has no FileProvider root for that folder" would send them hunting for
 * the wrong problem.
 */
enum class ShareStage {

    /** The system chooser was started. Nothing proves the *other* app saved the file, so this is
     *  as far as an in-process share can honestly report. */
    Launched,

    /** There was no payload to hand over (empty bytes, missing file, blank text). */
    NothingToShare,

    /** The staging folder could not be created or written. */
    StageUnwritable,

    /** FileProvider refused the file: it is outside the cache paths this app declares as shared. */
    NoProviderRoot,

    /** The chooser resolved, but Android knew no app to resolve it to. */
    NoTarget,

    /** Android refused the launch outright (`SecurityException` and friends). */
    Refused,

    /** The job reached the print framework. The printer itself reports the real outcome. */
    PrintStarted,

    /** This device has no print service — a tablet without a spooler, or headless work profile. */
    PrinterMissing,

    /** The file is fine but this app cannot rasterise it, so there would be no page to print. */
    Unprintable;

    /** True only for the two success states; everything else needs the owner's attention. */
    val delivered: Boolean get() = this == Launched || this == PrintStarted
}

/**
 * The result of one share/print request.
 *
 * Carries the [authority] and the [uri] that were actually used, because the two things an owner
 * asks after a failed share are "did the app get my file" and "which folder is it reading from" —
 * and both answers come out of this record rather than out of a log the user cannot open.
 */
data class ShareOutcome(
    val stage: ShareStage,
    val displayName: String,
    val detail: String,
    val authority: String? = null,
    val uri: String? = null
) {

    val delivered: Boolean get() = stage.delivered

    /** Body copy for the snackbar: a success line if it worked, the real reason if it did not. */
    fun message(onSuccess: String): String = if (delivered) onSuccess else detail

    /** A one-line, non-technical explanation for a card. Never empty. */
    fun headline(): String = when (stage) {
        ShareStage.Launched -> "Sent to the share sheet"
        ShareStage.PrintStarted -> "Print job started"
        ShareStage.NothingToShare -> "Nothing to send"
        ShareStage.StageUnwritable -> "Could not prepare the file"
        ShareStage.NoProviderRoot -> "This file is outside the shared folder"
        ShareStage.NoTarget -> "No app can open this"
        ShareStage.Refused -> "Android blocked the hand-over"
        ShareStage.PrinterMissing -> "This device cannot print"
        ShareStage.Unprintable -> "This type has no printable page"
    }

    companion object {

        /** Failure record with a message the owner can act on. */
        fun failed(
            stage: ShareStage,
            displayName: String,
            detail: String,
            authority: String? = null
        ): ShareOutcome = ShareOutcome(stage, displayName, detail, authority)

        /** A list of records collapsed to the first problem worth showing, for batch shares. */
        fun worstOf(outcomes: List<ShareOutcome>): ShareOutcome? =
            outcomes.firstOrNull { !it.delivered } ?: outcomes.lastOrNull()
    }
}

/**
 * MIME types, decided from the file name.
 *
 * Deliberately not `android.webkit.MimeTypeMap`: that class returns null (and therefore
 * `application/octet-stream`) for exactly the extensions this app produces — `.svf` vault
 * containers, `.webp`, a JPEG renamed by a chat export — and an octet-stream share is the one that
 * most chat apps refuse to preview. The table below is the set the vault actually writes, plus the
 * types a `PickOpenable`/`OpenDocument` import can bring in. Unknown extensions still fall back to
 * `application/octet-stream`, because that is what a receiver needs for a byte-for-byte copy.
 */
object ShareMime {

    const val BINARY = "application/octet-stream"
    const val PDF = "application/pdf"
    const val PLAIN_TEXT = "text/plain"

    private val BY_EXTENSION: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "bmp" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "svg" to "image/svg+xml",
        "pdf" to PDF,
        "txt" to PLAIN_TEXT,
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "log" to PLAIN_TEXT,
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "rtf" to "application/rtf",
        "epub" to "application/epub+zip",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/x-wav",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "apk" to "application/vnd.android.package-archive",
        "ics" to "text/calendar",
        "vcf" to "text/vcard",
        // The vault's own encrypted container. Not a media type, so it must go out as a file: the
        // receiver gets bytes it can re-import, never a preview of something that was decrypted.
        "svf" to BINARY
    )

    /** Extension of a file name, lowercased, without the dot; "" when there is none. */
    fun extensionOf(displayName: String?): String {
        val name = displayName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** The type to put on the intent for this name. Never blank, never null. */
    fun guess(displayName: String?): String =
        BY_EXTENSION[extensionOf(displayName)] ?: BINARY

    /** True when a receiver is likely to show this inline rather than download it. */
    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    fun isPdf(mimeType: String): Boolean = mimeType.equals(PDF, ignoreCase = true)

    fun isText(mimeType: String): Boolean =
        mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml"

    /**
     * The label shown next to a file in the share sheet's title line.
     * Images and PDFs keep their own name; anything else gets the type spelled out, because
     * "stego_carrier.svf" tells an owner nothing about what they are about to hand to a stranger.
     */
    fun labelFor(displayName: String): String {
        val mime = guess(displayName)
        return if (mime == BINARY && extensionOf(displayName) != "svf") {
            "${displayName.substringBeforeLast('.')}.${extensionOf(displayName).uppercase()}".trim('.')
        } else {
            displayName
        }
    }
}
