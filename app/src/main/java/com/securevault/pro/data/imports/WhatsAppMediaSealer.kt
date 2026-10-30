package com.securevault.pro.data.imports

import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.file.VaultFileMime
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file vault as the streaming importer's media sink.
 *
 * A `.zip` "Attach media" export is walked entry by entry; each file is already staged on disk, so
 * this hands that staged file straight to the vault's streaming seal rather than reading it into
 * memory. The file lands in the chat's own container (`chats/<chatId>`), linked to the chat by the
 * same note id every chat in the vault uses, which is what lets the conversation view draw the
 * photo inside the bubble that named it.
 */
@Singleton
class VaultWhatsAppMediaSealer @Inject constructor(
    private val files: FileVaultRepository
) : MediaSealer {

    override suspend fun seal(name: String, file: File, request: WhatsAppStreamingImporter.Request): String? {
        val saved = files.saveStream(
            source = file,
            displayName = name,
            mimeType = VaultFileMime.mime(name),
            category = files.guessCategory(name),
            noteId = request.noteId,
            folder = request.container
        )
        return saved.getOrNull()?.id
    }

    override suspend fun discard(id: String) {
        files.deleteFile(id)
    }
}
