package com.securevault.pro.data.imports

import com.securevault.pro.data.imports.WhatsAppTranscript.MediaKind
import com.securevault.pro.data.imports.WhatsAppTranscript.Parsed
import com.securevault.pro.data.imports.WhatsAppTranscript.Reference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Joins a chat transcript to the media that is actually on the phone, and writes the record
 * that makes the archive an *evidence* copy rather than a pile of files.
 *
 * WhatsApp never stores the media folder path inside a transcript, but it does store the
 * filename, and the filename is what the phone wrote the file with. That is the join. When the
 * two names differ between WhatsApp versions, [WhatsAppTranscript.equivalenceKey] reduces both
 * to date + sequence + type. Anything left over is reported as missing instead of guessed at —
 * an archive that quietly drops a photo is worse than one that says it dropped a photo.
 *
 * Pure JVM again, so the matching rules and the manifest wording are covered by tests.
 */
object WhatsAppArchive {

    /** A file inside the granted WhatsApp folder. Mirrors the view model's doc row. */
    data class MediaDoc(val name: String, val relativePath: String, val size: Long, val modifiedAt: Long)

    /**
     * [how] records how certain the join is: `exact` for the same filename, `equivalent` for the
     * date+sequence spelling two WhatsApp versions give one photo, `in name` when the transcript
     * line buries the real filename inside a sentence, and `same day` for the weakest join that
     * is still defensible — same type, same receive date, and either the only file that day or a
     * clock time within two minutes. Anything that cannot be placed that precisely is reported
     * missing: guessing which of thirty holiday photos a line meant would put a wrong file into
     * an evidence archive.
     */
    data class Match(val reference: Reference, val doc: MediaDoc, val how: String)

    /**
     * What one conversation's transcript resolves to on this handset. [bytes] and the per-kind
     * counts are read off [matches] so a plan whose sizes get corrected after the files are
     * handled still reports the truth.
     */
    data class Plan(
        val matches: List<Match>,
        val missing: List<Reference>
    ) {
        val found: Int get() = matches.size
        val bytes: Long get() = matches.sumOf { it.doc.size }
        val counts: Map<MediaKind, Int> get() = matches.groupingBy { it.reference.kind }.eachCount()
        val images: Int get() = counts[MediaKind.Image] ?: 0
        val videos: Int get() = counts[MediaKind.Video] ?: 0
        val audio: Int get() = counts[MediaKind.Audio] ?: 0
        val documents: Int get() = counts[MediaKind.Document] ?: 0
    }

    fun plan(references: List<Reference>, docs: List<MediaDoc>): Plan {
        val byName = HashMap<String, MutableList<MediaDoc>>(docs.size)
        val byKey = HashMap<String, MutableList<MediaDoc>>()
        // Disk names are indexed by their last token too: a transcript line can bury a real
        // filename inside a sentence, and the tail is what still matches the file on the phone.
        val byLastToken = HashMap<String, MutableList<MediaDoc>>()
        for (doc in docs) {
            byName.getOrPut(doc.name.lowercase()) { mutableListOf() }.add(doc)
            byKey.getOrPut(WhatsAppTranscript.equivalenceKey(doc.name)) { mutableListOf() }.add(doc)
            byLastToken.getOrPut(doc.name.lowercase().substringAfterLast(' ')) { mutableListOf() }.add(doc)
        }
        val taken = HashSet<String>(references.size)
        val matches = ArrayList<Match>(references.size)
        val unmatched = ArrayList<Reference>()
        val missing = ArrayList<Reference>()

        // Attachment-marked references first: prose that happens to name a file must not be
        // able to claim the doc that a real attachment line points at.
        val ordered = references.filter { it.attached } + references.filter { !it.attached }
        for (reference in ordered) {
            val wanted = reference.fileName.lowercase()
            val exact = byName[wanted]?.firstOrNull { !taken.contains(it.relativePath) }
            val equivalent = if (exact == null) {
                byKey[WhatsAppTranscript.equivalenceKey(reference.fileName)]
                    ?.firstOrNull { !taken.contains(it.relativePath) && kindOf(it.name) == reference.kind }
            } else null
            val tail = if (exact == null && equivalent == null) {
                byLastToken[wanted.substringAfterLast(' ')]
                    ?.filter { candidate ->
                        !taken.contains(candidate.relativePath) &&
                            kindOf(candidate.name) == reference.kind &&
                            candidate.name.length > 4 &&
                            wanted.endsWith(candidate.name.lowercase())
                    }
                    ?.maxByOrNull { it.name.length }
            } else null
            val chosen = exact ?: equivalent ?: tail
            if (chosen == null) {
                unmatched.add(reference)
            } else {
                taken.add(chosen.relativePath)
                val how = when {
                    exact != null -> "exact"
                    equivalent != null -> "equivalent"
                    else -> "in name"
                }
                matches.add(Match(reference, chosen, how))
            }
        }

        // Last tier: the transcript date is in every WhatsApp media name, so a file that was
        // renamed or saved under the other spelling still lands on one calendar day.
        val byDay = HashMap<String, MutableList<MediaDoc>>()
        for (doc in docs) {
            val day = dayOf(doc)
            if (day.isNotEmpty()) {
                byDay.getOrPut("${kindOf(doc.name)}|$day") { mutableListOf() }.add(doc)
            }
        }
        for (reference in unmatched) {
            val candidates = byDay["${reference.kind}|${WhatsAppTranscript.dayOf(reference.fileName)}"]
                ?.filter { !taken.contains(it.relativePath) }
                .orEmpty()
            val refTime = WhatsAppTranscript.timeOf(reference.fileName)
            val chosen = when {
                candidates.isEmpty() -> null
                refTime >= 0 -> candidates
                    .map { it to kotlin.math.abs(docTime(it) - refTime) }
                    .filter { (_, delta) -> delta <= SAME_DAY_TIME_TOLERANCE_SECONDS }
                    .minByOrNull { (_, delta) -> delta }
                    ?.first
                candidates.size == 1 -> candidates.first()
                else -> null
            }
            if (chosen == null) {
                missing.add(reference)
            } else {
                taken.add(chosen.relativePath)
                matches.add(Match(reference, chosen, "same day"))
            }
        }

        return Plan(
            matches = matches,
            missing = missing.distinctBy { it.fileName.lowercase() }
        )
    }

    private fun kindOf(fileName: String): MediaKind = WhatsAppTranscript.kindOf(fileName)

    /**
     * The day a file belongs to: the date WhatsApp put in its name, else the local day it was
     * last written. Both the reference name and the file are read in the phone's own timezone,
     * which is the timezone WhatsApp printed the name in.
     */
    private fun dayOf(doc: MediaDoc): String {
        val fromName = WhatsAppTranscript.dayOf(doc.name)
        if (fromName.isNotEmpty()) return fromName
        if (doc.modifiedAt <= 0L) return ""
        val calendar = Calendar.getInstance().apply { timeInMillis = doc.modifiedAt }
        return String.format(
            Locale.US,
            "%04d%02d%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Seconds since midnight, from the name when it carries a clock, else from the file's own time. */
    private fun docTime(doc: MediaDoc): Int {
        val fromName = WhatsAppTranscript.timeOf(doc.name)
        if (fromName >= 0) return fromName
        if (doc.modifiedAt <= 0L) return -1
        val calendar = Calendar.getInstance().apply { timeInMillis = doc.modifiedAt }
        return calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 +
            calendar.get(Calendar.SECOND)
    }

    private const val SAME_DAY_TIME_TOLERANCE_SECONDS = 120

    /** Every file the chat references that is *not* in the vault yet, grouped for the UI. */
    fun missingByKind(plan: Plan): Map<MediaKind, List<Reference>> =
        plan.missing.groupBy({ it.kind }, { it })

    /**
     * The proof block. It records what was read, where it came from, when this phone handed it
     * over and the digest of every part, so a copy made months ago can still be shown to be the
     * same copy — which is the whole point of keeping a chat in a vault instead of a folder.
     */
    fun manifest(
        chat: String,
        sourceName: String,
        sourcePath: String,
        sourceBytes: Long,
        sourceModifiedAt: Long,
        parsed: Parsed,
        plan: Plan,
        hashes: Map<String, String>,
        provenance: String,
        archivedAt: Long = System.currentTimeMillis()
    ): String = buildString {
        appendLine(WhatsAppTranscript.PROOF_MARKER)
        appendLine("Chat: $chat")
        appendLine("Source file: $sourceName")
        appendLine("Source location: $sourcePath")
        appendLine("Source size: ${formatBytes(sourceBytes)} · last written ${formatStamp(sourceModifiedAt)}")
        appendLine("Transcript SHA-256: ${parsed.sha256.ifBlank { "unavailable" }}")
        appendLine(
            "Messages: ${parsed.messageCount} · participants: " +
                if (parsed.participants.isEmpty()) "not named" else parsed.participants.joinToString(", ")
        )
        appendLine("Span: ${parsed.firstStamp.ifBlank { "unknown" }} → ${parsed.lastStamp.ifBlank { "unknown" }}")
        appendLine(
            "Media referenced: ${parsed.references.size} · archived: ${plan.found} " +
                "(${plan.images} photos, ${plan.videos} videos, ${plan.audio} voice, ${plan.documents} docs, " +
                "${formatBytes(plan.bytes)}) · not on this phone: ${plan.missing.size}"
        )
        if (parsed.omittedMedia > 0) {
            appendLine("Media WhatsApp itself left out of the export: ${parsed.omittedMedia}")
        }
        plan.matches.groupingBy { it.how }.eachCount().let { how ->
            appendLine(
                "Joined by filename: ${how["exact"] ?: 0} · by date and sequence: ${how["equivalent"] ?: 0} · " +
                    "by name inside the line: ${how["in name"] ?: 0} · by same day: ${how["same day"] ?: 0}"
            )
        }
        if (plan.matches.isNotEmpty()) {
            appendLine()
            appendLine("Archived files")
            for (match in plan.matches.take(MANIFEST_LIST_MAX)) {
                val digest = hashes[match.doc.relativePath]
                val shown = when {
                    digest == null -> "hash pending"
                    digest.length == 64 -> digest.take(16)
                    else -> digest
                }
                appendLine("• ${match.doc.name} · ${formatBytes(match.doc.size)} · $shown · ${match.how}")
            }
            if (plan.matches.size > MANIFEST_LIST_MAX) {
                appendLine("… and ${plan.matches.size - MANIFEST_LIST_MAX} more listed in the Files module.")
            }
        }
        if (plan.missing.isNotEmpty()) {
            appendLine()
            appendLine("Referenced but not found on this phone")
            for (reference in plan.missing.take(MANIFEST_LIST_MAX)) {
                appendLine("• ${reference.fileName} (${reference.kind.name.lowercase()})")
            }
            if (plan.missing.size > MANIFEST_LIST_MAX) {
                appendLine("… and ${plan.missing.size - MANIFEST_LIST_MAX} more.")
            }
        }
        appendLine()
        appendLine("Archived: ${formatStamp(archivedAt)}")
        appendLine("By: $provenance")
        appendLine("Encryption: AES-256-GCM on this device · nothing uploaded in the clear.")
    }

    private const val MANIFEST_LIST_MAX = 200

    fun formatBytes(value: Long): String = when {
        value >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", value / 1_000_000_000.0)
        value >= 1_000_000L -> String.format(Locale.US, "%.1f MB", value / 1_000_000.0)
        value >= 1_000L -> "${value / 1000} KB"
        else -> "$value B"
    }

    private val stampFormat = SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.US)

    fun formatStamp(millis: Long): String =
        if (millis <= 0L) "unknown" else runCatching { stampFormat.format(Date(millis)) }.getOrDefault("unknown")
}
