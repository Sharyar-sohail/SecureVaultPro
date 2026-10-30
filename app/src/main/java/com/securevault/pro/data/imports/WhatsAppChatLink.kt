package com.securevault.pro.data.imports

import com.securevault.pro.domain.model.NoteAttachment

/**
 * Joins the files the owner picked *beside* a transcript to the conversation they belong to.
 *
 * A `.txt` export carries no media, so the photos, voice notes and documents that came with it can
 * only arrive when the owner selects them too. Sealing those files as ordinary vault entries was
 * the defect: the chat view then said "media not included in this export" while the file sat in the
 * vault, unlinked, because nothing carried the chat's id or its name into the note.
 *
 * The merge has to be idempotent for the other half of the defect: picking the same transcript and
 * the same photos again is a re-import of the same conversation, and a re-import must not grow the
 * attachment list or leave a second copy of every photo's metadata on the note.
 */
object WhatsAppChatLink {

    /**
     * How many attachments a note's metadata keeps. Matches the importer's own ceiling so a folder
     * of media and a zip of media end with the same ledger.
     */
    const val MAX_NOTE_ATTACHMENTS = 300

    /**
     * [existing] with [added] folded in. One entry per file name, case-insensitively: the file
     * sealed by this run replaces the entry of the same name, and the order the owner saw first is
     * kept so a re-import does not reshuffle the bubbles' files.
     */
    fun merge(
        existing: List<NoteAttachment>,
        added: List<NoteAttachment>,
        cap: Int = MAX_NOTE_ATTACHMENTS
    ): List<NoteAttachment> {
        if (cap <= 0) return emptyList()
        if (added.isEmpty()) return existing.take(cap)
        val byName = LinkedHashMap<String, NoteAttachment>(existing.size + added.size)
        existing.forEach { byName[it.name.lowercase()] = it }
        added.forEach { byName[it.name.lowercase()] = it }
        return byName.values.take(cap).toList()
    }
}
