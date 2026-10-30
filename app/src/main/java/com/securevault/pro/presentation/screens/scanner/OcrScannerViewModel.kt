package com.securevault.pro.presentation.screens.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.file.FileVaultRepository
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.SecureNote
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
import javax.inject.Inject

data class OcrScannerState(
    val processing: Boolean = false,
    val preview: Bitmap? = null,
    val text: String = "",
    val error: String? = null,
    val savingNote: Boolean = false,
    val noteSaved: Boolean = false,
    val savingImage: Boolean = false,
    val imageSaved: Boolean = false
) {
    val hasImage: Boolean get() = preview != null
}

@HiltViewModel
class OcrScannerViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val fileVault: FileVaultRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(OcrScannerState())
    val state: StateFlow<OcrScannerState> = _state.asStateFlow()

    private var processedJpeg: ByteArray? = null

    fun scan(bytes: ByteArray) {
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = "The restricted vault is read-only.") }
            return
        }
        if (_state.value.processing) return
        if (bytes.isEmpty()) {
            _state.update { it.copy(error = "That image is empty.") }
            return
        }
        processedJpeg = null
        _state.value = OcrScannerState(processing = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val bitmap = prepareDocument(bytes, 1800)
                        ?: throw IllegalArgumentException("That image could not be decoded.")
                    val recognized = recognizeText(bitmap)
                    val jpeg = toJpeg(bitmap, 90)
                    val thumb = thumbnailOf(bitmap, 900)
                    if (thumb !== bitmap) bitmap.recycle()
                    Triple(recognized, thumb, jpeg)
                }
                processedJpeg = result.third
                _state.update {
                    it.copy(
                        processing = false,
                        preview = result.second,
                        text = result.first,
                        error = if (result.first.isBlank()) {
                            "No readable text was found. Try a sharper, well-lit photo."
                        } else {
                            null
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        processing = false,
                        error = failure.message?.take(200)
                            ?: "Text recognition could not finish."
                    )
                }
            }
        }
    }

    fun saveAsNote() {
        val current = _state.value
        if (current.savingNote || current.noteSaved) return
        if (current.text.isBlank()) {
            _state.update { it.copy(error = "There is no recognized text to save.") }
            return
        }
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = "The restricted vault is read-only.") }
            return
        }
        _state.update { it.copy(savingNote = true) }
        viewModelScope.launch {
            val saved = repository.saveNote(
                SecureNote(
                    title = "OCR scan · ${scanDisplayDate()}",
                    content = current.text,
                    color = "DEFAULT",
                    tags = listOf("OCR", "Scan")
                )
            )
            _state.update {
                it.copy(
                    savingNote = false,
                    noteSaved = saved,
                    error = if (saved) null else "The note could not be encrypted."
                )
            }
        }
    }

    fun saveImage() {
        val current = _state.value
        if (current.savingImage || current.imageSaved) return
        val jpeg = processedJpeg
        if (jpeg == null) {
            _state.update { it.copy(error = "Capture or select an image first.") }
            return
        }
        if (sessionManager.isDecoy()) {
            _state.update { it.copy(error = "The restricted vault is read-only.") }
            return
        }
        _state.update { it.copy(savingImage = true) }
        viewModelScope.launch {
            val result = fileVault.saveFile(
                bytes = jpeg,
                displayName = scanFileName("ocr", "jpg"),
                mimeType = "image/jpeg",
                category = "Scans"
            )
            if (result.isSuccess) repository.refreshFileCount()
            _state.update {
                it.copy(
                    savingImage = false,
                    imageSaved = result.isSuccess,
                    error = result.exceptionOrNull()?.message
                        ?: if (result.isFailure) "The image could not be sealed." else null
                )
            }
        }
    }

    fun clearResult() {
        processedJpeg = null
        _state.value = OcrScannerState()
    }
}
