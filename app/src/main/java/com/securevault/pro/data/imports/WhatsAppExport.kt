package com.securevault.pro.data.imports

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writing a vault chat back out as a file the rest of the world can open.
 *
 * An import is only half of a clone: the owner asked to "export and import a whole chat", and a
 * conversation that can only ever be read inside this app is a lock-in, not a vault. Both shapes
 * here are the ones WhatsApp itself produces, so a file exported from Secure Wallet re-imports
 * into Secure Wallet unchanged and opens in any text editor or archive tool beside it:
 *
 *  - a `.txt` transcript, exactly the lines WhatsApp wrote, and
 *  - a `.zip` with the transcript at the root and every sealed media file beside it.
 *
 * Pure JVM on purpose — the byte shapes are pinned by [WhatsAppExportTest] instead of by a handset.
 */
object WhatsAppExport {

    /**
     * One attachment destined for the archive. [open] is called only when that entry is written,
     * because a vault file is decrypted one at a time into scratch storage and the next decrypt
     * clears it — resolving them all up front would leave only the last one on disk.
     */
    data class Media(val name: String, val open: suspend () -> InputStream?)

    data class Stats(val entries: Int, val skipped: List<String>) {
        val empty: Boolean get() = entries == 0
    }

    /** The `.txt` body, with WhatsApp's own line order so the file re-imports as the same chat. */
    fun transcriptText(title: String, conversation: String): String =
        WhatsAppFormat.transcriptBody(title, conversation.trim())

    /** The transcript's own name inside an archive, matching what WhatsApp writes at the zip root. */
    fun transcriptEntryName(): String = WhatsAppFormat.ARCHIVE_TRANSCRIPT_ENTRY

    /**
     * Builds the archive at [target]. Media that cannot be opened is skipped and named in the
     * result rather than aborting the export — a chat with one missing video still comes out.
     */
    suspend fun writeArchive(
        target: File,
        title: String,
        conversation: String,
        media: List<Media>,
        onEntry: (String) -> Unit = {}
    ): Stats {
        val transcript = transcriptText(title, conversation)
        val names = HashSet<String>()
        var entries = 0
        val skipped = mutableListOf<String>()
        target.parentFile?.mkdirs()
        val transcriptName = transcriptEntryName()
        names.add(transcriptName)
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(transcriptName))
            zip.write(transcript.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries++
            onEntry(transcriptName)
            media.forEach { item ->
                val stream = runCatching { item.open() }.getOrNull()
                if (stream == null) {
                    skipped += item.name
                    return@forEach
                }
                val name = uniqueName(names, sanitizeEntry(item.name))
                // A write that fails here is a full disk, not a bad attachment: saying so beats
                // handing over a half-built archive the owner only discovers later.
                stream.use { source ->
                    zip.putNextEntry(ZipEntry(name))
                    source.copyTo(zip, 64 * 1024)
                    zip.closeEntry()
                }
                entries++
                onEntry(name)
            }
        }
        return Stats(entries = entries, skipped = skipped)
    }

    /** Writes the plain-text transcript, the "Without media" shape. */
    fun writeTranscript(target: File, title: String, conversation: String): File {
        target.parentFile?.mkdirs()
        target.writeText(transcriptText(title, conversation), Charsets.UTF_8)
        return target
    }

    /** Folder paths and duplicate names never survive into an archive: WhatsApp writes a flat root. */
    private fun sanitizeEntry(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }

    private fun uniqueName(used: MutableSet<String>, wanted: String): String {
        if (used.add(wanted)) return wanted
        val stem = wanted.substringBeforeLast('.', wanted)
        val extension = if ('.' in wanted) wanted.substringAfterLast('.') else ""
        var index = 1
        while (true) {
            val candidate = if (extension.isEmpty()) "$stem ($index)" else "$stem ($index).$extension"
            if (used.add(candidate)) return candidate
            index++
        }
    }
}
