package com.securevault.pro.presentation.screens.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val jpeg: ByteArray,
    val thumbnail: Bitmap
)

data class DocumentScannerState(
    val pages: List<ScanPage> = emptyList(),
    val processing: Boolean = false,
    val sealing: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val sealedName: String? = null,
    /** Vault id of the sealed PDF, so Print/Share can decrypt exactly what was just saved. */
    val sealedId: String? = null
) {
    val pageCount: Int get() = pages.size
}

@HiltViewModel
class DocumentScannerViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val fileVault: FileVaultRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(DocumentScannerState())
    val state: StateFlow<DocumentScannerState> = _state.asStateFlow()

    fun addPages(images: List<ByteArray>) {
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = "The restricted vault is read-only.") }
            return
        }
        if (images.isEmpty() || _state.value.processing || _state.value.sealing) return
        val available = 30 - _state.value.pageCount
        if (available <= 0) {
            _state.update { it.copy(error = "A document can contain up to 30 pages.") }
            return
        }
        _state.update { it.copy(processing = true, error = null, sealedName = null) }
        viewModelScope.launch {
            try {
                val incoming = images.take(available)
                val existing = _state.value.pageCount
                for ((index, bytes) in incoming.withIndex()) {
                    val page = withContext(Dispatchers.Default) {
                        val bitmap = prepareDocument(bytes, 2200)
                            ?: throw IllegalArgumentException("Page ${index + 1} could not be decoded.")
                        val jpeg = toJpeg(bitmap, 88)
                        val thumb = thumbnailOf(bitmap, 480)
                        if (thumb !== bitmap) bitmap.recycle()
                        ScanPage(jpeg = jpeg, thumbnail = thumb)
                    }
                    _state.update { current ->
                        current.copy(
                            pages = current.pages + page,
                            progress = (current.pageCount - existing).toFloat() / incoming.size
                        )
                    }
                }
                _state.update { it.copy(processing = false, progress = 0f) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        processing = false,
                        progress = 0f,
                        error = failure.message?.take(200) ?: "The page could not be prepared."
                    )
                }
            }
        }
    }

    /**
     * A page the user already framed and filtered on the review sheet: it is decoded and
     * thumbnailed, never re-detected, so what they approved is exactly what gets sealed.
     */
    fun addScannedPage(jpeg: ByteArray) {
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = "The restricted vault is read-only.") }
            return
        }
        if (_state.value.processing || _state.value.sealing) return
        if (_state.value.pageCount >= 30) {
            _state.update { it.copy(error = "A document can contain up to 30 pages.") }
            return
        }
        _state.update { it.copy(processing = true, error = null, sealedName = null) }
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.Default) {
                    val bitmap = normalizedBitmap(jpeg, 2200)
                        ?: throw IllegalArgumentException("The page could not be decoded.")
                    val thumbnail = thumbnailOf(bitmap, 480)
                    ScanPage(jpeg = toJpeg(bitmap, 90), thumbnail = thumbnail)
                }
                _state.update { current ->
                    current.copy(
                        pages = current.pages + page,
                        processing = false,
                        progress = 0f
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        processing = false,
                        progress = 0f,
                        error = failure.message?.take(200) ?: "The page could not be added."
                    )
                }
            }
        }
    }

    fun removePage(id: String) {
        if (_state.value.sealing) return
        _state.update { current ->
            current.copy(pages = current.pages.filterNot { it.id == id }, sealedName = null)
        }
    }

    fun sealPdf() {
        val current = _state.value
        if (current.sealing || current.processing) return
        if (current.pages.isEmpty()) {
            _state.update { it.copy(error = "Capture at least one page first.") }
            return
        }
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = "The restricted vault is read-only.") }
            return
        }
        _state.update { it.copy(sealing = true, progress = 0.2f, error = null, sealedName = null) }
        viewModelScope.launch {
            try {
                val pdf = withContext(Dispatchers.Default) {
                    buildPdf(current.pages.map { it.jpeg })
                }
                _state.update { it.copy(progress = 0.8f) }
                val name = scanFileName("document", "pdf")
                val saved = withContext(Dispatchers.IO) {
                    fileVault.saveFile(
                        bytes = pdf,
                        displayName = name,
                        mimeType = "application/pdf",
                        category = "Scans"
                    )
                }
                if (saved.isSuccess) repository.refreshFileCount()
                _state.update {
                    it.copy(
                        sealing = false,
                        progress = 1f,
                        sealedName = if (saved.isSuccess) saved.getOrNull()?.name ?: name else null,
                        sealedId = saved.getOrNull()?.id,
                        error = saved.exceptionOrNull()?.message
                            ?: if (saved.isFailure) "The PDF could not be encrypted." else null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        sealing = false,
                        progress = 0f,
                        error = failure.message?.take(200) ?: "The PDF could not be assembled."
                    )
                }
            }
        }
    }

    /**
     * The PDF bytes Print/Share hands to `VaultShare`.
     *
     * A sealed document is decrypted from its vault container on demand rather than kept in screen
     * state, so a 30-page job never pins a second plaintext copy in memory. An unsealed session is
     * composed with the same [buildPdf] the seal uses, so printing a draft prints exactly the pages
     * on screen. Null means there is nothing to hand over, and the screen says so.
     */
    suspend fun exportablePdf(): ByteArray? {
        val sealedId = _state.value.sealedId
        if (sealedId != null) {
            val sealed = withContext(Dispatchers.IO) { fileVault.readFile(sealedId) }
            if (sealed != null && sealed.isNotEmpty()) return sealed
        }
        val pages = _state.value.pages
        if (pages.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            runCatching { buildPdf(pages.map { it.jpeg }) }.getOrNull()
        }
    }

    /** The name the share sheet and the print job show: the sealed name when there is one. */
    fun exportableName(): String = _state.value.sealedName ?: scanFileName("document", "pdf")

    fun reset() {
        if (_state.value.sealing) return
        _state.value = DocumentScannerState()
    }
}
