package com.securevault.pro.presentation.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

enum class FileRisk { LOW, MEDIUM, HIGH }

data class FileScanState(
    val inspecting: Boolean = false,
    val sealing: Boolean = false,
    val name: String = "",
    val size: Long = 0L,
    val mimeType: String = "",
    val sha256: String = "",
    val detectedType: String = "",
    val risk: FileRisk? = null,
    val reasons: List<String> = emptyList(),
    val error: String? = null,
    val sealedName: String? = null,
    /** Vault id of the sealed copy, so Print/Share can decrypt exactly the file that was imported. */
    val sealedId: String? = null
) {
    val hasResult: Boolean get() = risk != null
    val tooLargeForVault: Boolean get() = size > 50L * 1024L * 1024L
}

@HiltViewModel
class FileScannerViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val fileVault: FileVaultRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(FileScanState())
    val state: StateFlow<FileScanState> = _state.asStateFlow()

    private var sourceBytes: ByteArray? = null

    fun inspect(name: String, mimeType: String, bytes: ByteArray) {
        if (_state.value.inspecting || _state.value.sealing) return
        if (bytes.isEmpty()) {
            _state.update { it.copy(error = "That file is empty.") }
            return
        }
        sourceBytes = bytes
        _state.value = FileScanState(inspecting = true, name = name.ifBlank { "file" })
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val hash = MessageDigest.getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    val detected = detectType(name, bytes)
                    val assessment = assessRisk(name, mimeType, bytes, detected)
                    Triple(hash, detected, assessment)
                }
                _state.update {
                    it.copy(
                        inspecting = false,
                        name = name.ifBlank { "file" },
                        size = bytes.size.toLong(),
                        mimeType = mimeType.ifBlank { fileVault.guessMime(name) },
                        sha256 = result.first,
                        detectedType = result.second,
                        risk = result.third.first,
                        reasons = result.third.second
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sourceBytes = null
                _state.update {
                    it.copy(
                        inspecting = false,
                        error = failure.message?.take(200) ?: "The file could not be inspected."
                    )
                }
            }
        }
    }

    fun seal() {
        val current = _state.value
        if (current.sealing || current.inspecting || !current.hasResult) return
        if (current.sealedName != null) return
        val bytes = sourceBytes
        if (bytes == null) {
            _state.update { it.copy(error = "Choose a file first.") }
            return
        }
        if (current.tooLargeForVault) {
            _state.update { it.copy(error = "This file exceeds the 50 MB vault limit.") }
            return
        }
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = "The restricted vault is read-only.") }
            return
        }
        _state.update { it.copy(sealing = true, error = null) }
        viewModelScope.launch {
            val saved = fileVault.saveFile(
                bytes = bytes,
                displayName = current.name,
                mimeType = current.mimeType,
                category = fileVault.guessCategory(current.name)
            )
            if (saved.isSuccess) repository.refreshFileCount()
            _state.update {
                it.copy(
                    sealing = false,
                    sealedName = saved.getOrNull()?.name,
                    sealedId = saved.getOrNull()?.id,
                    error = saved.exceptionOrNull()?.message
                        ?: if (saved.isFailure) "The file could not be encrypted." else null
                )
            }
        }
    }

    /**
     * Bytes Print/Share hands over: the decrypted sealed copy when the import was stored, otherwise
     * the exact bytes the inspector is showing. Nothing is written to the vault just to print.
     */
    suspend fun exportableBytes(): ByteArray? {
        val sealedId = _state.value.sealedId
        if (sealedId != null) {
            val sealed = withContext(Dispatchers.IO) { fileVault.readFile(sealedId) }
            if (sealed != null && sealed.isNotEmpty()) return sealed
        }
        return sourceBytes
    }

    fun reset() {
        if (_state.value.sealing) return
        sourceBytes = null
        _state.value = FileScanState()
    }

    private fun detectType(name: String, bytes: ByteArray): String {
        fun startsWith(vararg values: Int): Boolean =
            bytes.size >= values.size && values.indices.all { bytes[it] == values[it].toByte() }

        return when {
            startsWith(0x25, 0x50, 0x44, 0x46) -> "PDF document"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "PNG image"
            startsWith(0xFF, 0xD8, 0xFF) -> "JPEG image"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "GIF image"
            startsWith(0x52, 0x49, 0x46, 0x46) &&
                bytes.size > 12 &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "WebP image"
            startsWith(0x50, 0x4B, 0x03, 0x04) -> {
                if (name.endsWith(".apk", ignoreCase = true)) "Android package (APK)"
                else "ZIP / OOXML archive"
            }
            startsWith(0x7F, 0x45, 0x4C, 0x46) -> "ELF executable"
            startsWith(0x4D, 0x5A) -> "Windows PE executable"
            startsWith(0x64, 0x65, 0x78, 0x0A) -> "Android DEX bytecode"
            startsWith(0xD0, 0xCF, 0x11, 0xE0) -> "Legacy Office / OLE2 container"
            startsWith(0x1F, 0x8B) -> "Gzip archive"
            startsWith(0x52, 0x61, 0x72, 0x21) -> "RAR archive"
            startsWith(0x37, 0x7A, 0xBC, 0xAF) -> "7-Zip archive"
            bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte() ->
                "Script"
            looksLikeText(bytes) -> "Plain text"
            else -> "Unknown binary"
        }
    }

    private fun assessRisk(
        name: String,
        providedMime: String,
        bytes: ByteArray,
        detected: String
    ): Pair<FileRisk, List<String>> {
        val extension = name.substringAfterLast('.', "").lowercase()
        val reasons = mutableListOf<String>()
        var risk = FileRisk.LOW

        fun raise(level: FileRisk, reason: String) {
            if (level.ordinal > risk.ordinal) risk = level
            reasons += reason
        }

        if (detected.contains("executable", ignoreCase = true) ||
            detected.contains("DEX", ignoreCase = true) ||
            detected.contains("APK", ignoreCase = true)
        ) {
            raise(FileRisk.HIGH, "Executable code signature detected ($detected)")
        }
        if (extension in DANGEROUS_EXTENSIONS) {
            raise(FileRisk.HIGH, ".$extension files can execute code on a device")
        }
        val doubleExtension = DOUBLE_EXTENSION.find(name) != null
        if (doubleExtension) {
            raise(FileRisk.HIGH, "Disguised double extension (e.g. invoice.pdf.exe)")
        }
        if (extension in MACRO_EXTENSIONS) {
            raise(FileRisk.MEDIUM, "Macro-capable Office format")
        }
        if (detected.startsWith("Script")) {
            raise(FileRisk.MEDIUM, "Plain-text script that may run commands")
        }
        if (detected.startsWith("Unknown")) {
            raise(FileRisk.MEDIUM, "Unrecognised binary format")
        }
        if (detected.contains("archive", ignoreCase = true) &&
            extension !in setOf("zip", "docx", "xlsx", "pptx", "epub", "jar")
        ) {
            raise(FileRisk.MEDIUM, "Archive contents cannot be verified")
        }
        if (bytes.size > 25L * 1024L * 1024L) {
            raise(FileRisk.MEDIUM, "Large file · ${bytes.size / (1024 * 1024)} MB")
        }
        if (providedMime.isNotBlank() &&
            providedMime != "application/octet-stream" &&
            !providedMime.contains("zip", ignoreCase = true) &&
            !extensionMatches(providedMime, extension)
        ) {
            raise(FileRisk.MEDIUM, "Reported MIME type does not match the file extension")
        }
        if (reasons.isEmpty()) {
            reasons += "No executable signature or risky extension"
        }
        return risk to reasons.distinct()
    }

    private fun extensionMatches(mime: String, extension: String): Boolean = when {
        extension.isBlank() -> true
        mime.startsWith("image/") -> extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
        mime == "application/pdf" -> extension == "pdf"
        mime.startsWith("text/") -> extension in setOf("txt", "md", "csv", "log", "json", "xml", "html")
        mime.startsWith("audio/") -> extension in setOf("mp3", "wav", "ogg", "m4a", "flac")
        mime.startsWith("video/") -> extension in setOf("mp4", "mkv", "webm", "mov", "3gp")
        else -> true
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.take(512)
        if (sample.isEmpty()) return false
        var printable = 0
        for (byte in sample) {
            val value = byte.toInt() and 0xFF
            if (value == 9 || value == 10 || value == 13 || value in 32..126) printable++
        }
        return printable.toFloat() / sample.size > 0.9f
    }

    companion object {
        private val DANGEROUS_EXTENSIONS = setOf(
            "exe", "apk", "dex", "elf", "so", "dll", "msi", "scr", "com", "pif",
            "bat", "cmd", "ps1", "vbs", "vbe", "js", "jse", "wsf", "jar", "sh", "py"
        )
        private val MACRO_EXTENSIONS = setOf("docm", "xlsm", "pptm", "dotm", "xlam")
        private val DOUBLE_EXTENSION = Regex(
            """\.(pdf|docx?|xlsx?|pptx?|jpe?g|png|txt)\.(exe|apk|bat|cmd|scr|js|vbs|ps1|sh|jar|msi|com|pif|dll)$""",
            RegexOption.IGNORE_CASE
        )
    }
}
