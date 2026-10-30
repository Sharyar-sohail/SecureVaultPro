package com.securevault.pro.presentation.screens.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

data class ViewerState(
    val loading: Boolean = true,
    val id: String = "",
    val title: String = "",
    val mimeType: String = "",
    val size: Long = 0L,
    val path: String? = null,
    val text: String? = null,
    val error: String? = null,
    val decoy: Boolean = false
) {
    val kind: ViewerKind
        get() {
            val mime = mimeType.lowercase()
            val ext = title.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> ViewerKind.IMAGE
                mime.startsWith("video/") || ext in VIDEO_EXTENSIONS -> ViewerKind.VIDEO
                mime.startsWith("audio/") || ext in AUDIO_EXTENSIONS -> ViewerKind.AUDIO
                mime == "application/pdf" || ext == "pdf" -> ViewerKind.PDF
                ext in OFFICE_TEXT_EXTENSIONS -> ViewerKind.OFFICE
                mime.startsWith("text/") || ext in TEXT_EXTENSIONS -> ViewerKind.TEXT
                else -> ViewerKind.UNSUPPORTED
            }
        }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")
        internal val TEXT_EXTENSIONS =
            setOf("txt", "md", "markdown", "log", "csv", "json", "xml", "html", "htm", "kt", "java")
        private val OFFICE_TEXT_EXTENSIONS = setOf("docx", "xlsx", "pptx", "odt", "ods")
    }
}

enum class ViewerKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, OFFICE, UNSUPPORTED }

@HiltViewModel
class SecureViewerViewModel @Inject constructor(
    private val fileVault: FileVaultRepository,
    @ApplicationContext private val context: Context,
    sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerState(decoy = sessionManager.isDecoy()))
    val state = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(id: String) {
        if (id.isBlank() || loadedId == id) return
        loadedId = id
        _state.value = ViewerState(loading = true, id = id, decoy = _state.value.decoy)
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { fileVault.metadata(id) }
            if (meta == null) {
                _state.value = ViewerState(
                    loading = false,
                    id = id,
                    error = if (_state.value.decoy) {
                        "Demonstration files are placeholders in the restricted vault."
                    } else {
                        "This file is no longer available in the vault."
                    },
                    decoy = _state.value.decoy
                )
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) { fileVault.readFile(id) }
            if (bytes == null) {
                _state.value = ViewerState(
                    loading = false,
                    id = id,
                    title = meta.name,
                    mimeType = meta.mimeType,
                    size = meta.size,
                    error = "The file could not be decrypted. It may belong to a previous installation.",
                    decoy = _state.value.decoy
                )
                return@launch
            }
            val cacheFile = withContext(Dispatchers.IO) { writeCache(id, meta.name, bytes) }
            val text = withContext(Dispatchers.IO) { extractText(cacheFile, meta.name, meta.mimeType, bytes) }
            _state.value = ViewerState(
                loading = false,
                id = id,
                title = meta.name,
                mimeType = meta.mimeType,
                size = bytes.size.toLong(),
                path = cacheFile?.absolutePath,
                text = text,
                decoy = _state.value.decoy
            )
        }
    }

    fun cleanup() {
        runCatching {
            cacheDir().listFiles()?.forEach { it.delete() }
        }
        loadedId = null
    }

    override fun onCleared() {
        cleanup()
        super.onCleared()
    }

    private fun cacheDir(): File = File(context.cacheDir, "secure_viewer").apply { mkdirs() }

    private fun writeCache(id: String, name: String, bytes: ByteArray): File? = runCatching {
        val dir = cacheDir()
        dir.listFiles()?.forEach { it.delete() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(80)
        val target = File(dir, "${id.take(36)}_$safeName")
        target.writeBytes(bytes)
        target
    }.getOrNull()

    private fun extractText(file: File?, name: String, mime: String, bytes: ByteArray): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (file == null) return null
        return runCatching {
            when {
                ext == "docx" || ext == "pptx" -> officeText(file, ext)
                ext == "xlsx" -> spreadsheetText(file)
                ext in setOf("odt", "ods") -> odtText(file)
                mime.startsWith("text/") || ext in ViewerState.TEXT_EXTENSIONS -> {
                    val slice = if (bytes.size > MAX_TEXT_BYTES) bytes.copyOf(MAX_TEXT_BYTES) else bytes
                    val raw = slice.toString(Charsets.UTF_8)
                    if (ext == "html" || ext == "htm" || mime.contains("html")) htmlToText(raw) else raw
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun officeText(file: File, ext: String): String {
        val entry = if (ext == "pptx") "ppt/slides/slide1.xml" else "word/document.xml"
        ZipFile(file).use { zip ->
            val target = zip.getEntry(entry) ?: zip.entries().asSequence()
                .firstOrNull { it.name.endsWith(".xml") && it.name.startsWith("ppt/slides/slide") }
            if (target == null) return ""
            val xml = zip.getInputStream(target).bufferedReader().use { it.readText().take(MAX_XML_CHARS) }
            return xmlText(xml)
        }
    }

    private fun spreadsheetText(file: File): String {
        ZipFile(file).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")?.let {
                zip.getInputStream(it).bufferedReader().use { reader -> reader.readText().take(MAX_XML_CHARS) }
            }.orEmpty()
            val strings = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(shared)
                .map { unescape(it.groupValues[1]) }
                .toList()
            return if (strings.isEmpty()) "" else strings.joinToString("\n")
        }
    }

    private fun odtText(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("content.xml") ?: return ""
            val xml = zip.getInputStream(entry).bufferedReader().use { it.readText().take(MAX_XML_CHARS) }
            return xmlText(xml)
        }
    }

    private fun xmlText(xml: String): String = xml
        .replace(Regex("</w:p>|</a:p>|</text:p>|</table:table-row>"), "\n")
        .replace(Regex("<w:tab[^>]*/>|<text:tab[^>]*/>"), "\t")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .let(::unescape)

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .let(::unescape)

    private fun unescape(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private companion object {
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        const val MAX_XML_CHARS = 8 * 1024 * 1024
    }
}
