package com.securevault.pro.presentation.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.TotpAccount
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TotpRow(
    val account: TotpAccount,
    val code: String,
    val seconds: Int,
    val progress: Float
)

data class AuthUiState(
    val rows: List<TotpRow> = emptyList(),
    val query: String = "",
    val total: Int = 0,
    val decoy: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: VaultRepository,
    sessionManager: SessionManager
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val ticker = MutableStateFlow(System.currentTimeMillis())

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                ticker.value = System.currentTimeMillis()
            }
        }
    }

    val state = combine(
        repository.observeTotps(),
        query,
        ticker,
        MutableStateFlow(sessionManager.isDecoy())
    ) { accounts, search, now, decoy ->
        val q = search.trim().lowercase()
        val filtered = accounts
            .asSequence()
            .filter {
                q.isBlank() ||
                    it.issuer.lowercase().contains(q) ||
                    it.accountName.lowercase().contains(q)
            }
            .sortedWith(compareBy<TotpAccount> { it.issuer.lowercase() }.thenBy { it.accountName.lowercase() })
            .map { account ->
                TotpRow(
                    account = account,
                    code = if (account.secret.isBlank() || account.sealed) {
                        "------"
                    } else {
                        TotpGenerator.generate(
                            secret = account.secret,
                            algorithm = account.algorithm,
                            digits = account.digits,
                            period = account.period,
                            timestampMillis = now
                        )
                    },
                    seconds = TotpGenerator.secondsRemaining(account.period, now),
                    progress = TotpGenerator.progress(account.period, now)
                )
            }
            .toList()
        AuthUiState(
            rows = filtered,
            query = search,
            total = accounts.size,
            decoy = decoy
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AuthUiState()
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteTotp(id) }
    }
}
