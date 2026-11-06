package com.securevault.pro.presentation.screens.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.DigitalCard
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CardEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val decoy: Boolean = false,
    val type: String = "CREDIT",
    val nickname: String = "",
    val holderName: String = "",
    val number: String = "",
    val monthText: String = "12",
    val yearText: String = "30",
    val cvv: String = "",
    val pin: String = "",
    val bankName: String = "",
    val notes: String = "",
    val gradientStart: String = "#1E273E",
    val gradientEnd: String = "#141B2D",
    val sealed: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
) {
    val month: Int? get() = monthText.toIntOrNull()
    val year: Int? get() = yearText.toIntOrNull()?.let { if (it > 99) it % 100 else it }
    val expiryLabel: String
        get() = "%02d/%02d".format(month ?: 0, year ?: 0)
    val last4: String get() = number.takeLast(4)
    val canSave: Boolean
        get() = !saving && !decoy && month != null && month in 1..12 && year != null && year in 0..99
}

@HiltViewModel
class CardEditorViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val editorId: String? = savedStateHandle.get<String>("cardId")?.takeIf { it.isNotBlank() }
    private val draftId: String = editorId ?: UUID.randomUUID().toString()
    private val _state = MutableStateFlow(
        CardEditorUiState(
            isNew = editorId == null,
            decoy = sessionManager.isDecoy()
        )
    )
    val state = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val card = editorId?.let { repository.getCard(it) }
            if (card == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    isNew = false,
                    type = card.type,
                    nickname = card.nickname,
                    holderName = card.holderName,
                    number = card.number,
                    monthText = card.expiryMonth.toString().padStart(2, '0'),
                    yearText = card.expiryYear.toString().padStart(2, '0'),
                    cvv = card.cvv,
                    pin = card.pin,
                    bankName = card.bankName,
                    notes = card.notes,
                    gradientStart = card.colorGradientStart,
                    gradientEnd = card.colorGradientEnd,
                    sealed = card.sealed
                )
            }
        }
    }

    fun setType(value: String) = _state.update { it.copy(type = value) }
    fun setNickname(value: String) = _state.update { it.copy(nickname = value, error = null) }
    fun setHolder(value: String) = _state.update { it.copy(holderName = value) }
    fun setBank(value: String) = _state.update { it.copy(bankName = value) }
    fun setNotes(value: String) = _state.update { it.copy(notes = value) }
    fun setNumber(value: String) = _state.update {
        it.copy(number = value.filter(Char::isDigit).take(19))
    }
    fun setCvv(value: String) = _state.update { it.copy(cvv = value.filter(Char::isDigit).take(4)) }
    fun setPin(value: String) = _state.update { it.copy(pin = value.filter(Char::isDigit).take(8)) }
    fun setMonth(value: String) = _state.update {
        it.copy(monthText = value.filter(Char::isDigit).take(2))
    }
    fun setYear(value: String) = _state.update {
        it.copy(yearText = value.filter(Char::isDigit).take(4))
    }
    fun setGradient(start: String, end: String) = _state.update {
        it.copy(gradientStart = start, gradientEnd = end)
    }
    fun clearError() = _state.update { it.copy(error = null) }

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (current.decoy) {
            _state.update { it.copy(error = "The decoy wallet is read-only.") }
            return
        }
        val month = current.month
        if (month == null || month !in 1..12) {            _state.update { it.copy(error = "Expiry month must be between 01 and 12.") }
            return
        }
        val year = current.year
        if (year == null || year !in 0..99) {
            _state.update { it.copy(error = "Expiry year must be two digits, e.g. 30.") }
            return
        }
        if (current.number.isNotBlank() && current.number.length < 12) {
            _state.update { it.copy(error = "That card number looks too short.") }
            return
        }
        if (current.nickname.isBlank() && current.bankName.isBlank()) {
            _state.update { it.copy(error = "Give this card a nickname or bank name.") }
            return
        }
        val card = DigitalCard(
            id = draftId,
            type = current.type,
            nickname = current.nickname.trim(),
            holderName = current.holderName.trim(),
            number = current.number,
            expiryMonth = month,
            expiryYear = year,
            cvv = current.cvv,
            pin = current.pin,
            bankName = current.bankName.trim(),
            notes = current.notes,
            colorGradientStart = current.gradientStart,
            colorGradientEnd = current.gradientEnd,
            sealed = current.sealed
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val ok = repository.saveCard(card)
            _state.update {
                it.copy(
                    saving = false,
                    saved = ok,
                    error = if (ok) null else "Unable to save this card."
                )
            }
        }
    }

    companion object {
        val CardGradientPresets = listOf(
            "#1E273E" to "#141B2D",
            "#0B3C4C" to "#071C26",
            "#16337E" to "#0B1F52",
            "#C1121F" to "#8E0D16",
            "#123B2E" to "#0A1F19",
            "#3B2E12" to "#1E1708"
        )
    }
}
