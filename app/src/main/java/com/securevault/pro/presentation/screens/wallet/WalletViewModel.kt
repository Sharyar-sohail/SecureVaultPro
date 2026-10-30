package com.securevault.pro.presentation.screens.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.DigitalCard
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletUiState(
    val cards: List<DigitalCard> = emptyList(),
    val decoy: Boolean = false
)

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val repository: VaultRepository,
    sessionManager: SessionManager
) : ViewModel() {

    val state = combine(
        repository.observeCards(),
        MutableStateFlow(sessionManager.isDecoy())
    ) { cards, decoy ->
        WalletUiState(cards = cards.sortedByDescending { it.createdAt }, decoy = decoy)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WalletUiState()
    )

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteCard(id) }
    }
}
