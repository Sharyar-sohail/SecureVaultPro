package com.securevault.pro.presentation.screens.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EntryDetailUiState(
    val loading: Boolean = true,
    val entry: VaultEntry? = null,
    val totpCode: String = "",
    val totpSeconds: Int = 30,
    val totpProgress: Float = 1f,
    val totpAlgorithm: String = "SHA1",
    val totpDigits: Int = 6,
    val decoy: Boolean = false,
    val deleted: Boolean = false
)

@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val entryId: String = savedStateHandle.get<String>("entryId").orEmpty()
    private val _state = MutableStateFlow(EntryDetailUiState(decoy = sessionManager.isDecoy()))
    val state = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val entry = repository.getEntry(entryId)
            _state.update {
                it.copy(
                    loading = false,
                    entry = entry,
                    decoy = sessionManager.isDecoy()
                )
            }
            if (entry != null) {
                repository.markAccessed(entry.id)
                tick()
            }
        }
    }

    fun toggleFavorite() {
        val entry = _state.value.entry ?: return
        viewModelScope.launch {
            repository.toggleFavorite(entry.id)
            _state.update { it.copy(entry = it.entry?.copy(isFavorite = !entry.isFavorite)) }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val entry = _state.value.entry ?: return
        viewModelScope.launch {
            repository.deleteEntry(entry.id)
            _state.update { it.copy(deleted = true) }
            onDeleted()
        }
    }

    private fun tick() {
        val entry = _state.value.entry ?: return
        if (!entry.hasTotp || entry.totpSecret.isBlank() || entry.totpSealed) {
            if (_state.value.totpCode.isNotEmpty()) {
                _state.update { it.copy(totpCode = "", totpSeconds = 30, totpProgress = 1f) }
            }
            return
        }
        val parsed = if (entry.totpSecret.startsWith("otpauth://", ignoreCase = true)) {
            TotpGenerator.parseOtpAuth(entry.totpSecret)
        } else {
            null
        }
        val algorithm = parsed?.algorithm?.name ?: "SHA1"
        val digits = parsed?.digits ?: 6
        val period = parsed?.period ?: 30
        val rawSecret = parsed?.secret ?: entry.totpSecret
        val code = TotpGenerator.generate(rawSecret, algorithm, digits, period)
        _state.update {
            it.copy(
                totpCode = code,
                totpSeconds = TotpGenerator.secondsRemaining(period),
                totpProgress = TotpGenerator.progress(period),
                totpAlgorithm = algorithm,
                totpDigits = digits
            )
        }
    }
}
