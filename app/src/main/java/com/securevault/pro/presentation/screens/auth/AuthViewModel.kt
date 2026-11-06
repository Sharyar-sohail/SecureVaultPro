package com.securevault.pro.presentation.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.TotpAccount
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
    private val decoy = MutableStateFlow(sessionManager.isDecoy())

    /**
     * A cold one-second ticker: it only runs while [state] has a subscriber (combined with
     * SharingStarted.WhileSubscribed it stops a few seconds after the screen leaves the
     * composition). The previous init-block loop beat at 1 Hz for the whole lifetime of
     * the back-stack entry — it kept regenerating every code even while another screen was
     * open, draining the battery for pixels nobody could see.
     */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    /** HMAC result cache: one step never re-computes a code for the same counter+secret. */
    private data class CachedCode(val counter: Long, val secret: String, val code: String)
    private val codeMemo = ConcurrentHashMap<String, CachedCode>()

    val state = combine(
        repository.observeTotps(),
        query,
        ticker,
        decoy
    ) { accounts, search, now, decoyMode ->
        // RFC-vector hashing for every account used to run on the main thread once per
        // second; with a couple of dozen accounts that measurably janked scrolling.
        withContext(Dispatchers.Default) {
            val q = search.trim().lowercase()
            val liveIds = HashSet<String>(accounts.size)
            val filtered = accounts
                .asSequence()
                .filter {
                    q.isBlank() ||
                        it.issuer.lowercase().contains(q) ||
                        it.accountName.lowercase().contains(q)
                }
                .sortedWith(compareBy<TotpAccount> { it.issuer.lowercase() }.thenBy { it.accountName.lowercase() })
                .map { account ->
                    liveIds.add(account.id)
                    TotpRow(
                        account = account,
                        code = codeFor(account, now),
                        seconds = TotpGenerator.secondsRemaining(account.period, now),
                        progress = TotpGenerator.progress(account.period, now)
                    )
                }
                .toList()
            if (codeMemo.size > liveIds.size) codeMemo.keys.retainAll(liveIds)
            AuthUiState(
                rows = filtered,
                query = search,
                total = accounts.size,
                decoy = decoyMode
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AuthUiState()
    )

    /**
     * The RFC 6238 code only changes when the time step changes, so within one step the
     * memo serves the previous HMAC instead of rehashing every account every second.
     * The cache is keyed on the account id plus the exact secret, so an edit (a resealed
     * or replaced secret) invalidates it immediately; nothing is ever stored in a log.
     */
    private fun codeFor(account: TotpAccount, now: Long): String {
        if (account.secret.isBlank() || account.sealed) return "------"
        val safePeriod = account.period.coerceIn(15, 120)
        val counter = now / 1000L / safePeriod
        val cached = codeMemo[account.id]
        if (cached != null && cached.counter == counter && cached.secret == account.secret) {
            return cached.code
        }
        val code = TotpGenerator.generate(
            secret = account.secret,
            algorithm = account.algorithm,
            digits = account.digits,
            period = account.period,
            timestampMillis = now
        )
        codeMemo[account.id] = CachedCode(counter, account.secret, code)
        return code
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteTotp(id) }
    }
}
