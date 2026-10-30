package com.securevault.pro.presentation.screens.files

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class VoiceRecorderState(
    val decoy: Boolean = false,
    val recording: Boolean = false,
    val saving: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val error: String? = null,
    val savedName: String? = null,
    val memos: List<VaultFile> = emptyList()
)

@HiltViewModel
class VoiceRecorderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VaultRepository,
    sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceRecorderState(decoy = sessionManager.isDecoy()))
    val state: StateFlow<VoiceRecorderState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var ticker: Job? = null
    private var startedAt = 0L

    init {
        refreshMemos()
    }

    fun refreshMemos() {
        viewModelScope.launch {
            val memos = withContext(Dispatchers.IO) {
                repository.fileVault.listFiles()
                    .filter { it.category.equals("Audio", ignoreCase = true) }
            }
            _state.update { it.copy(memos = memos) }
        }
    }

    fun startRecording() {
        val snapshot = _state.value
        if (snapshot.decoy) {
            _state.update { it.copy(error = "Recording is disabled in the restricted vault.") }
            return
        }
        if (snapshot.recording || snapshot.saving) return
        val dir = File(context.cacheDir, "voice_memos").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(dir, "voice_$stamp.m4a")
        try {
            val active = newRecorder()
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioEncodingBitRate(128_000)
            active.setAudioSamplingRate(44_100)
            active.setOutputFile(target.absolutePath)
            active.prepare()
            active.start()
            recorder = active
            outputFile = target
            startedAt = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    recording = true,
                    saving = false,
                    elapsedMs = 0L,
                    amplitude = 0f,
                    error = null,
                    savedName = null
                )
            }
            startTicker()
        } catch (throwable: Throwable) {
            runCatching { recorder?.release() }
            recorder = null
            target.delete()
            outputFile = null
            _state.update {
                it.copy(
                    recording = false,
                    error = throwable.message?.take(160)
                        ?: "The microphone could not start."
                )
            }
        }
    }

    fun stopAndSave() {
        val target = outputFile
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        releaseRecorder(stop = true)
        stopTicker()
        outputFile = null
        if (target == null) {
            _state.update { it.copy(recording = false, elapsedMs = 0L, amplitude = 0f) }
            return
        }
        if (elapsed < 900L) {
            target.delete()
            _state.update {
                it.copy(
                    recording = false,
                    elapsedMs = 0L,
                    amplitude = 0f,
                    error = "Record for at least one second."
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(recording = false, saving = true, amplitude = 0f) }
            try {
                val bytes = withContext(Dispatchers.IO) { target.readBytes() }
                val name = target.name
                val result = withContext(Dispatchers.IO) {
                    repository.fileVault.saveFile(
                        bytes = bytes,
                        displayName = name,
                        mimeType = "audio/mp4",
                        category = "Audio"
                    )
                }
                target.delete()
                result.fold(
                    onSuccess = {
                        repository.refreshFileCount()
                        _state.update {
                            it.copy(saving = false, savedName = name, elapsedMs = 0L)
                        }
                        refreshMemos()
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(
                                saving = false,
                                error = error.message?.take(160)
                                    ?: "The memo could not be sealed into the vault."
                            )
                        }
                    }
                )
            } catch (throwable: Throwable) {
                target.delete()
                _state.update {
                    it.copy(
                        saving = false,
                        error = throwable.message?.take(160)
                            ?: "The memo could not be saved."
                    )
                }
            }
        }
    }

    fun cancelRecording() {
        releaseRecorder(stop = false)
        stopTicker()
        outputFile?.delete()
        outputFile = null
        _state.update { it.copy(recording = false, elapsedMs = 0L, amplitude = 0f) }
    }

    fun dismissSaved() {
        _state.update { it.copy(savedName = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        releaseRecorder(stop = false)
        stopTicker()
        outputFile?.delete()
        outputFile = null
        super.onCleared()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(110)
                val active = recorder ?: break
                val amplitude = runCatching { active.maxAmplitude }.getOrDefault(0)
                _state.update {
                    it.copy(
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                        amplitude = (amplitude / 32767f).coerceIn(0f, 1f)
                    )
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun releaseRecorder(stop: Boolean) {
        val active = recorder ?: return
        recorder = null
        if (stop) runCatching { active.stop() }
        runCatching { active.reset() }
        runCatching { active.release() }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
}
