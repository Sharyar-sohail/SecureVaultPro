package com.securevault.pro.presentation.screens.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.TotpAccount
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.TotpAlgorithm
import com.securevault.pro.security.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject

data class TotpEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val decoy: Boolean = false,
    val issuer: String = "",
    val accountName: String = "",
    val secret: String = "",
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val sealed: Boolean = false,
    val previewCode: String = "------",
    val seconds: Int = 30,
    val progress: Float = 1f,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false
) {
    val secretValid: Boolean get() = secret.isNotBlank() && TotpGenerator.isValidSecret(secret)
    val canSave: Boolean get() = !saving && !decoy && secretValid
}

@HiltViewModel
class TotpEditorViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val editorId: String? = savedStateHandle.get<String>("accountId")?.takeIf { it.isNotBlank() }
    private val draftId: String = editorId ?: UUID.randomUUID().toString()
    private val initialUri: String? = savedStateHandle.get<String>("uri")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(
        TotpEditorUiState(
            isNew = editorId == null,
            decoy = sessionManager.isDecoy()
        )
    )
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

    private fun load() {
        viewModelScope.launch {
            if (editorId == null && initialUri != null) {
                val parsed = TotpGenerator.parseOtpAuth(initialUri)
                if (parsed != null) {
                    _state.update {
                        it.copy(
                            loading = false,
                            issuer = parsed.issuer,
                            accountName = parsed.accountName,
                            secret = parsed.secret,
                            algorithm = parsed.algorithm.name,
                            digits = parsed.digits,
                            period = parsed.period
                        )
                    }
                    tick()
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "That QR code is not a valid TOTP authenticator link."
                        )
                    }
                }
                return@launch
            }
            val account = editorId?.let { repository.getTotp(it) }
            if (account == null) {
                _state.update { it.copy(loading = false, isNew = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    isNew = false,
                    issuer = account.issuer,
                    accountName = account.accountName,
                    secret = account.secret,
                    algorithm = account.algorithm,
                    digits = account.digits,
                    period = account.period,
                    sealed = account.sealed
                )
            }
            tick()
        }
    }

    fun setIssuer(value: String) = _state.update { it.copy(issuer = value, error = null) }
    fun setAccountName(value: String) = _state.update { it.copy(accountName = value) }
    fun setSecret(value: String) = _state.update {
        it.copy(secret = value.filterNot { c -> c.isWhitespace() }.uppercase(), error = null)
    }
    fun setAlgorithm(value: String) = _state.update { it.copy(algorithm = value) }
    fun setDigits(value: Int) = _state.update { it.copy(digits = value.coerceIn(6, 8)) }
    fun setPeriod(value: Int) = _state.update { it.copy(period = value.coerceIn(15, 120)) }

    fun generateSecret() {
        val bytes = ByteArray(20).also { SecureRandom().nextBytes(it) }
        _state.update {
            it.copy(
                secret = TotpGenerator.encodeBase32(bytes),
                error = null
            )
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (current.decoy) {
            _state.update { it.copy(error = "The decoy vault is read-only.") }
            return
        }
        if (!current.secretValid) {
            _state.update { it.copy(error = "Enter a valid Base32 secret.") }
            return
        }
        // Store the canonical uppercase unpadded Base32 form of what was typed or
        // scanned. decodeBase32 already ignores spacing, hyphens, padding and the two
        // look-alike digits, so the key bytes — and every code ever produced from them —
        // are identical; only the sealed plaintext becomes uniform, which also keeps the
        // repository's duplicate-secret check exact. The sealing itself is untouched.
        val canonical = TotpGenerator.canonicalSecret(current.secret) ?: current.secret.trim().uppercase()
        val account = TotpAccount(
            id = draftId,
            issuer = current.issuer.trim().ifBlank { "Authenticator" },
            accountName = current.accountName.trim(),
            secret = canonical,
            algorithm = TotpAlgorithm.from(current.algorithm).name,
            digits = current.digits.coerceIn(6, 8),
            period = current.period.coerceIn(15, 120)
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val ok = repository.saveTotp(account)
            _state.update {
                it.copy(
                    saving = false,
                    saved = ok,
                    error = if (ok) null else "Unable to save this authenticator."
                )
            }
        }
    }

    fun delete() {
        val id = editorId ?: return
        if (_state.value.decoy) return
        viewModelScope.launch {
            repository.deleteTotp(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    private fun tick() {
        val current = _state.value
        if (!current.secretValid) return
        val now = System.currentTimeMillis()
        val code = TotpGenerator.generate(
            secret = current.secret,
            algorithm = current.algorithm,
            digits = current.digits,
            period = current.period,
            timestampMillis = now
        )
        _state.update {
            it.copy(
                previewCode = code,
                seconds = TotpGenerator.secondsRemaining(current.period, now),
                progress = TotpGenerator.progress(current.period, now)
            )
        }
    }
}
