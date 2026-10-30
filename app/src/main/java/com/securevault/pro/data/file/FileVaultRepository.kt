package com.securevault.pro.data.file

import android.content.Context
import android.util.Base64
import com.securevault.pro.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class VaultFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val category: String,
    val createdAt: Long,
    val uploadedAt: Long,
    val noteId: String? = null,
    /**
     * The vault-relative directory holding this file's encrypted container, when it is not in the
     * root. A WhatsApp conversation keeps its media in its own module — `chats/<chat id>` — so two
     * chats never share a folder path and one chat's files can be listed, exported or deleted
     * without touching another's. Null means the flat root, which is where every file sealed
     * before per-chat modules existed still lives, so old vaults keep opening.
     */
    val folder: String? = null,
    /**
     * The Google Drive file id this metadata mirrors. It is carried purely so the realtime `files`
     * node can name the byte source (`RECORD-FORMAT-v1.md` §3) — the bytes themselves never enter
     * the database and this field is the only thing the web needs to fetch them on demand with the
     * owner's own Drive session. Null for a file that has not been mirrored to Drive yet.
     */
    val driveId: String? = null
)

data class FileBackupItem(
    val id: String,
    val name: String,
    val size: Long,
    val type: String,
    val mimeType: String,
    val category: String,
    val data: String,
    val createdAt: Long,
    val uploadedAt: Long
)

/**
 * Extension → MIME and vault category for everything the file vault holds. A WhatsApp
 * "Attach media" export carries voice notes as `.opus` (`.ptt` in older zips, `.aac`
 * or `.m4a` on some handsets) and video as `.mp4` or `.3gp`; unclassified they landed
 * as `application/octet-stream` under Docs, so imported chats showed the clips but had
 * nothing to play them with. Kept as an explicit table instead of `MimeTypeMap` because
 * the categories have to agree with it and the mapping must resolve off-device.
 */
internal object VaultFileMime {

    private val byExtension: Map<String, String> = mapOf(
        // Photos
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "heif" to "image/heif", "tiff" to "image/tiff",
        "svg" to "image/svg+xml",
        // Voice notes and audio documents
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "wav" to "audio/x-wav", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "oga" to "audio/ogg", "opus" to "audio/ogg", "ptt" to "audio/ogg",
        "amr" to "audio/amr", "weba" to "audio/webm", "mid" to "audio/midi",
        // Chat videos
        "mp4" to "video/mp4", "3gp" to "video/3gpp", "3gpp" to "video/3gpp",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "avi" to "video/x-msvideo", "ts" to "video/mp2t",
        // Text, transcripts and office documents
        "txt" to "text/plain", "md" to "text/plain", "log" to "text/plain",
        "csv" to "text/csv", "json" to "application/json", "xml" to "text/xml",
        "html" to "text/html", "vcf" to "text/x-vcard", "ics" to "text/calendar",
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "epub" to "application/epub+zip", "apk" to "application/vnd.android.package-archive",
        // Chat exports and archives
        "zip" to "application/zip", "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed", "gz" to "application/gzip",
        "tar" to "application/x-tar"
    )

    fun mime(name: String): String =
        byExtension[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun category(name: String): String = when {
        mime(name).startsWith("audio/") -> "Audio"
        mime(name).startsWith("image/") || mime(name).startsWith("video/") -> "Media"
        name.startsWith("scan_", ignoreCase = true) -> "Scans"
        else -> "Docs"
    }
}

@Singleton
class FileVaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager
) {
    companion object {
        private const val DIR_NAME = "phone_vault"
        private const val INDEX_FILE = ".index.enc"
        private const val MAGIC = "SVF1"

        /** Local vault files may reach 2 GB; anything larger is not a phone-sized document. */
        const val MAX_FILE_BYTES = 2L * 1000L * 1000L * 1000L

        /**
         * A Drive mirror embeds each file base64 inside the encrypted vault payload, so
         * files above this stay on the device and are reported as local-only.
         */
        const val CLOUD_MIRROR_MAX_BYTES = 25L * 1024L * 1024L

        /** [saveFile] takes the whole payload in RAM, so its own ceiling stays modest. */
        const val IN_MEMORY_MAX_BYTES = 100L * 1024L * 1024L
        private const val COPY_CHUNK_BYTES = 128 * 1024

        /**
         * One safe path segment of a chat module: it must start with a letter or digit, so `..`,
         * a hidden dot-file and a separator can never be expressed inside it.
         */
        private val SAFE_FOLDER_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")
    }

    private val mutex = Mutex()
    private val vaultDir: File by lazy {
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }
    private val indexFile: File by lazy { File(vaultDir, INDEX_FILE) }

    @Volatile
    private var mutationListener: (() -> Unit)? = null

    /** Wired to [com.securevault.pro.data.repository.VaultRepository] so file writes reach live sync. */
    fun setMutationListener(listener: (() -> Unit)?) {
        mutationListener = listener
    }

    private fun notifyMutation() {
        mutationListener?.invoke()
    }

    suspend fun listFiles(): List<VaultFile> = withContext(Dispatchers.IO) {
        mutex.withLock { loadIndex().sortedByDescending { it.uploadedAt } }
    }

    /**
     * The parts of [folder] when it is a safe relative path, or null when it is absent or hostile.
     * Only plain segments of letters, digits, dot, dash and underscore are allowed, no `..`, no
     * leading separator and at most four levels deep, so a caller cannot aim a vault file at any
     * other corner of the device. Chat modules use `chats/<chat id>`.
     */
    private fun folderSegments(folder: String?): List<String>? {
        val value = folder?.trim()?.replace('\\', '/')?.trim('/') ?: return null
        if (value.isBlank() || value.length > 160) return null
        val segments = value.split('/')
        if (segments.isEmpty() || segments.size > 4) return null
        if (segments.any { !SAFE_FOLDER_SEGMENT.matches(it) }) return null
        return segments
    }

    /** The folder path as it is stored in the index, or null for the flat root. */
    private fun storedFolder(folder: String?): String? = folderSegments(folder)?.joinToString("/")

    private fun folderFile(folder: String?): File? =
        folderSegments(folder)?.let { File(vaultDir, it.joinToString(File.separator)) }

    /** The encrypted container of one file: `<folder>/<id>.svf`, or `<id>.svf` in the flat root. */
    private fun containerOf(id: String, folder: String?): File {
        val dir = folderFile(folder) ?: vaultDir
        return File(dir, "$id.svf")
    }

    /** Creates a chat's own directory before its first file is written into it. */
    private fun ensureFolder(folder: String?) {
        folderFile(folder)?.mkdirs()
    }

    /** Removes a chat directory the last file in it just left, so no empty module is orphaned. */
    private fun pruneEmptyFolder(folder: String?) {
        val dir = folderFile(folder) ?: return
        runCatching { if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete() }
        val parent = dir.parentFile
        runCatching {
            if (parent != null && parent != vaultDir && parent.list()?.isEmpty() == true) parent.delete()
        }
    }

    suspend fun saveFile(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        category: String = "Docs",
        noteId: String? = null,
        folder: String? = null
    ): Result<VaultFile> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty file"))
        if (bytes.size > IN_MEMORY_MAX_BYTES) {
            return@withContext Result.failure(IllegalArgumentException("This file is larger than ${megabyteLabel(IN_MEMORY_MAX_BYTES)}"))
        }
        runCatching {
            mutex.withLock {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val safeName = sanitizeName(displayName)
                val encrypted = crypto.encryptFileBytes(bytes)
                ensureFolder(folder)
                writeEncryptedContainer(containerOf(id, folder), encrypted)
                val entry = VaultFile(
                    id = id,
                    name = safeName,
                    size = bytes.size.toLong(),
                    mimeType = mimeType.ifBlank { guessMime(safeName) },
                    category = category.ifBlank { guessCategory(safeName) },
                    createdAt = now,
                    uploadedAt = now,
                    noteId = noteId,
                    folder = storedFolder(folder)
                )
                saveIndex(loadIndex() + entry)
                entry
            }
        }.also { if (it.isSuccess) notifyMutation() }
    }

    /** Streams a staged plaintext file into the vault without ever holding it in memory. */
    suspend fun saveStream(
        source: File,
        displayName: String,
        mimeType: String,
        category: String = "Docs",
        noteId: String? = null,
        folder: String? = null
    ): Result<VaultFile> = withContext(Dispatchers.IO) {
        if (!source.exists()) return@withContext Result.failure(IllegalArgumentException("The selected file could not be read"))
        if (source.length() == 0L) return@withContext Result.failure(IllegalArgumentException("Empty file"))
        if (source.length() > MAX_FILE_BYTES) {
            return@withContext Result.failure(IllegalArgumentException("This file is larger than 2 GB"))
        }
        runCatching {
            mutex.withLock {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val safeName = sanitizeName(displayName)
                val sealed = File(vaultDir, "$id.sealed.tmp")
                ensureFolder(folder)
                val container = containerOf(id, folder)
                val plainBytes = try {
                    val plain = crypto.encryptFileToStream(source, sealed)
                    prependMagic(sealed, container)
                    plain
                } finally {
                    sealed.delete()
                }
                val entry = VaultFile(
                    id = id,
                    name = safeName,
                    size = plainBytes,
                    mimeType = mimeType.ifBlank { guessMime(safeName) },
                    category = category.ifBlank { guessCategory(safeName) },
                    createdAt = now,
                    uploadedAt = now,
                    noteId = noteId,
                    folder = storedFolder(folder)
                )
                saveIndex(loadIndex() + entry)
                entry
            }
        }.also { if (it.isSuccess) notifyMutation() }
    }

    /** Decrypts straight to a cache file so multi-gigabyte originals can still be shared or viewed. */
    suspend fun exportStreamToCache(id: String): File? = withContext(Dispatchers.IO) {
        val meta = mutex.withLock { loadIndex().firstOrNull { it.id == id } } ?: return@withContext null
        val container = containerOf(meta.id, meta.folder)
        if (!container.exists() || container.length() < 5) return@withContext null
        val shareDir = File(context.cacheDir, "secure_share").apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val out = File(shareDir, sanitizeName(meta.name))
        val body = File(vaultDir, "$id.plain.tmp")
        runCatching {
            copyWithoutMagic(container, body)
            if (!crypto.decryptFileToStream(body, out)) throw IllegalStateException("The file could not be decrypted")
        }.getOrNull()
        body.delete()
        if (out.exists()) out else null
    }

    private fun prependMagic(sealed: File, container: File) {
        container.parentFile?.mkdirs()
        FileOutputStream(container).use { out ->
            out.write(MAGIC.toByteArray(Charsets.UTF_8))
            FileInputStream(sealed).use { it.copyTo(out, COPY_CHUNK_BYTES) }
            out.flush()
            out.fd.sync()
        }
    }

    private fun copyWithoutMagic(container: File, target: File) {
        FileInputStream(container).use { input ->
            FileOutputStream(target).use { out ->
                input.skip(MAGIC.toByteArray(Charsets.UTF_8).size.toLong())
                input.copyTo(out, COPY_CHUNK_BYTES)
            }
        }
    }

    suspend fun importEncryptedOrRaw(
        id: String?,
        displayName: String,
        bytes: ByteArray,
        mimeType: String,
        category: String,
        createdAt: Long,
        uploadedAt: Long
    ): Result<VaultFile> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext Result.success(
            VaultFile(id ?: UUID.randomUUID().toString(), sanitizeName(displayName), 0, mimeType, category, createdAt, uploadedAt)
        )
        runCatching {
            mutex.withLock {
                val fileId = id?.takeIf { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9_.-]+")) }
                    ?: UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                // A restore replaces a file that may live in a chat's own module: it goes back where
                // it came from, never to the flat root beside its twin.
                val folder = storedFolder(loadIndex().firstOrNull { it.id == fileId }?.folder)
                val payload = if (isEncryptedContainer(bytes)) {
                    if (crypto.decryptFileBytes(bytes.copyOfRange(4, bytes.size)) == null) {
                        encryptRaw(bytes)
                    } else bytes.copyOfRange(4, bytes.size)
                } else {
                    encryptRaw(bytes)
                }
                ensureFolder(folder)
                writeEncryptedContainer(containerOf(fileId, folder), payload)
                val entry = VaultFile(
                    id = fileId,
                    name = sanitizeName(displayName),
                    size = bytes.size.toLong(),
                    mimeType = mimeType.ifBlank { guessMime(displayName) },
                    category = category.ifBlank { guessCategory(displayName) },
                    createdAt = if (createdAt > 0) createdAt else now,
                    uploadedAt = if (uploadedAt > 0) uploadedAt else now,
                    folder = folder
                )
                saveIndex(loadIndex().filterNot { it.id == fileId } + entry)
                entry
            }
        }.also { if (it.isSuccess) notifyMutation() }
    }

    suspend fun readFile(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val file = mutex.withLock { containerOf(id, metaFolder(loadIndex(), id)) }
            if (!file.exists()) return@withContext null
            val raw = file.readBytes()
            if (!isEncryptedContainer(raw)) return@withContext null
            crypto.decryptFileBytes(raw.copyOfRange(4, raw.size))
        } catch (_: Throwable) {
            null
        }
    }

    /** The index row of one file, whatever module it lives in. */
    private fun metaFolder(index: List<VaultFile>, id: String): String? =
        index.firstOrNull { it.id == id }?.folder

    suspend fun metadata(id: String): VaultFile? = withContext(Dispatchers.IO) {
        mutex.withLock { loadIndex().firstOrNull { it.id == id } }
    }

    /**
     * Writes a [VaultFile] into the index without touching any bytes.
     *
     * The realtime `files` node carries metadata only (`RECORD-FORMAT-v1.md` §3): the web edits a
     * file's name or attaches it to a note and the phone must reflect that, but the media bytes live
     * in Drive and never pass through this path. A local file that already has a container keeps its
     * container on disk; only the index row changes.
     */
    suspend fun upsertMetadata(meta: VaultFile): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = loadIndex()
            saveIndex(current.filterNot { it.id == meta.id } + meta)
        }
    }

    /**
     * Records the Drive file id for an existing vault file so the realtime node can name it.
     * Returns null when the id is unknown, so a caller knows the file is not mirrored.
     */
    suspend fun setDriveId(id: String, driveId: String?): VaultFile? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = loadIndex()
            val existing = current.firstOrNull { it.id == id } ?: return@withLock null
            val updated = existing.copy(driveId = driveId?.takeIf { it.isNotBlank() })
            saveIndex(current.map { if (it.id == id) updated else it })
            updated
        }
    }

    suspend fun deleteFile(id: String): Boolean = withContext(Dispatchers.IO) {
        val removed = mutex.withLock {
            val current = loadIndex()
            val folder = metaFolder(current, id)
            val file = containerOf(id, folder)
            val existed = file.exists()
            if (existed) file.delete()
            val next = current.filterNot { it.id == id }
            if (next.size != current.size) {
                saveIndex(next)
                // When the last file of a chat module is gone the directory itself goes with it,
                // so a deleted conversation leaves no empty folder behind on the device.
                if (next.none { it.folder == folder && folder != null }) pruneEmptyFolder(folder)
            }
            existed || next.size != current.size
        }
        if (removed) notifyMutation()
        removed
    }

    suspend fun buildBackupItems(): List<FileBackupItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadIndex().mapNotNull { meta ->
                try {
                    val file = containerOf(meta.id, meta.folder)
                    if (!file.exists() || file.length() > MAX_FILE_BYTES + 1024) return@mapNotNull null
                    // Oversized originals stay on the device: the mirror embeds each file
                    // base64 inside one payload, which cannot carry gigabytes.
                    if (meta.size > CLOUD_MIRROR_MAX_BYTES) return@mapNotNull null
                    val raw = file.readBytes()
                    if (!isEncryptedContainer(raw)) return@mapNotNull null
                    // Backups carry plaintext so another device (with a different local
                    // data key) can re-seal the file. The envelope itself stays encrypted.
                    val plaintext = crypto.decryptFileBytes(raw.copyOfRange(4, raw.size))
                        ?: return@mapNotNull null
                    FileBackupItem(
                        id = meta.id,
                        name = meta.name,
                        size = plaintext.size.toLong(),
                        type = meta.mimeType,
                        mimeType = meta.mimeType,
                        category = meta.category,
                        data = Base64.encodeToString(plaintext, Base64.NO_WRAP),
                        createdAt = meta.createdAt,
                        uploadedAt = meta.uploadedAt
                    )
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    suspend fun exportToCache(id: String): File? = withContext(Dispatchers.IO) {
        val meta = mutex.withLock { loadIndex().firstOrNull { it.id == id } } ?: return@withContext null
        val container = containerOf(meta.id, meta.folder)
        if (container.exists() && container.length() > CLOUD_MIRROR_MAX_BYTES) {
            return@withContext exportStreamToCache(id)
        }
        val plaintext = readFile(id) ?: return@withContext null
        val shareDir = File(context.cacheDir, "secure_share").apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val out = File(shareDir, sanitizeName(meta.name))
        FileOutputStream(out).use { it.write(plaintext) }
        out
    }

    /**
     * Removes every byte the vault holds. Walks into the chat modules too — a plain `listFiles()`
     * would leave each `chats/<id>` subtree (and every decrypted temp file inside it) on disk after
     * a wipe, which is exactly what a wipe must prevent.
     */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            vaultDir.listFiles()?.forEach { it.deleteRecursively() }
            indexFile.delete()
        }
    }

    suspend fun count(): Int = listFiles().size

    private fun saveIndex(entries: List<VaultFile>) {
        val array = JSONArray()
        entries.forEach { meta ->
            array.put(
                JSONObject().apply {
                    put("id", meta.id)
                    put("name", meta.name)
                    put("size", meta.size)
                    put("mimeType", meta.mimeType)
                    put("category", meta.category)
                    put("createdAt", meta.createdAt)
                    put("uploadedAt", meta.uploadedAt)
                    meta.noteId?.let { put("noteId", it) }
                    meta.folder?.let { put("folder", it) }
                    meta.driveId?.let { put("driveId", it) }
                }
            )
        }
        val encrypted = crypto.encrypt(array.toString())
        val temp = File(vaultDir, "$INDEX_FILE.tmp")
        temp.writeText(encrypted, Charsets.UTF_8)
        if (indexFile.exists()) indexFile.delete()
        temp.renameTo(indexFile)
    }

    private fun loadIndex(): List<VaultFile> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val plaintext = crypto.decrypt(indexFile.readText(Charsets.UTF_8)) ?: return emptyList()
            val array = JSONArray(plaintext)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        VaultFile(
                            id = obj.optString("id"),
                            name = obj.optString("name", "File"),
                            size = obj.optLong("size", 0L),
                            mimeType = obj.optString("mimeType", "application/octet-stream"),
                            category = obj.optString("category", "Docs"),
                            createdAt = obj.optLong("createdAt", 0L),
                            uploadedAt = obj.optLong("uploadedAt", 0L),
                            noteId = obj.optString("noteId").takeIf { it.isNotBlank() },
                            folder = obj.optString("folder").takeIf { it.isNotBlank() },
                            driveId = obj.optString("driveId").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }.filter { it.id.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeEncryptedContainer(file: File, ivAndCiphertext: ByteArray) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(MAGIC.toByteArray(Charsets.UTF_8))
            out.write(ivAndCiphertext)
            out.fd.sync()
        }
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) throw IllegalStateException("Unable to persist encrypted file")
    }

    private fun encryptRaw(bytes: ByteArray): ByteArray = crypto.encryptFileBytes(bytes)

    private fun isEncryptedContainer(bytes: ByteArray): Boolean {
        if (bytes.size < 4 + 12 + 16) return false
        return String(bytes.copyOfRange(0, 4), Charsets.UTF_8) == MAGIC
    }

    private fun sanitizeName(name: String): String {
        val trimmed = name.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        return trimmed.take(120).ifBlank { "file_${System.currentTimeMillis()}" }
    }

    fun guessMime(name: String): String = VaultFileMime.mime(name)

    fun guessCategory(name: String): String = VaultFileMime.category(name)

    fun contentHash(bytes: ByteArray): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)

    private fun megabyteLabel(bytes: Long) = "${bytes / (1024 * 1024)} MB"
}
