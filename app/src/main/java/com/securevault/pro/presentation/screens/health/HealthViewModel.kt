package com.securevault.pro.presentation.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultMutation
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.SecurityReport
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.security.BreachCandidate
import com.securevault.pro.security.BreachChecker
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject

enum class HealthSection { WEAK, REUSED, TWO_FACTOR, COMPROMISED }

enum class BreachScanStatus { IDLE, SCANNING, DONE, ERROR }

data class BreachHitRow(
    val entryId: String,
    val title: String,
    val username: String,
    val count: Long
)

data class HealthUiState(
    val loading: Boolean = true,
    val report: SecurityReport = SecurityReport(),
    val section: HealthSection = HealthSection.WEAK,
    val decoy: Boolean = false,
    val breachStatus: BreachScanStatus = BreachScanStatus.IDLE,
    val breachHits: List<BreachHitRow> = emptyList(),
    val breachChecked: Int = 0,
    val breachUnique: Int = 0,
    val breachCheckedAt: Long = 0L,
    val breachError: String? = null
) {
    val selectedCount: Int
        get() = when (section) {
            HealthSection.WEAK -> report.weak.size
            HealthSection.REUSED -> report.reused.size
            HealthSection.TWO_FACTOR -> report.missingTwoFactor.size
            HealthSection.COMPROMISED -> report.compromised.size
        }
}

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val sessionManager: SessionManager,
    private val breachChecker: BreachChecker
) : ViewModel() {

    private val _state = MutableStateFlow(HealthUiState())
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.mutations.onStart { emit(VaultMutation()) }.collect { reload() }
        }
        viewModelScope.launch {
            sessionManager.mode.collect { reload() }
        }
    }

    fun select(section: HealthSection) {
        _state.update { it.copy(section = section) }
    }

    fun runBreachCheck() {
        if (_state.value.breachStatus == BreachScanStatus.SCANNING) return
        if (sessionManager.isDecoy()) {
            _state.update {
                it.copy(
                    breachStatus = BreachScanStatus.ERROR,
                    breachError = "The breach checker is unavailable in the restricted vault."
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    breachStatus = BreachScanStatus.SCANNING,
                    breachError = null,
                    breachHits = emptyList()
                )
            }
            val entries = runCatching { repository.passwordAuditCandidates() }
                .getOrDefault(emptyList())
            if (entries.isEmpty()) {
                _state.update {
                    it.copy(
                        breachStatus = BreachScanStatus.DONE,
                        breachChecked = 0,
                        breachUnique = 0,
                        breachCheckedAt = System.currentTimeMillis()
                    )
                }
                return@launch
            }
            val candidates = entries.map { BreachCandidate(key = it.id, password = it.password) }
            breachChecker.audit(candidates).fold(
                onSuccess = { audit ->
                    val byId = entries.associateBy { it.id }
                    val hits = audit.breached.mapNotNull { (id, count) ->
                        val entry = byId[id] ?: return@mapNotNull null
                        BreachHitRow(
                            entryId = id,
                            title = entry.title,
                            username = entry.username.ifBlank { entry.email },
                            count = count
                        )
                    }.sortedByDescending { it.count }
                    _state.update {
                        it.copy(
                            breachStatus = BreachScanStatus.DONE,
                            breachHits = hits,
                            breachChecked = audit.checked,
                            breachUnique = audit.uniquePasswords,
                            breachCheckedAt = audit.checkedAt,
                            breachError = null
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            breachStatus = BreachScanStatus.ERROR,
                            breachError = error.message?.take(180)
                                ?: "The breach lookup could not complete."
                        )
                    }
                }
            )
        }
    }

    private suspend fun reload() {
        val decoy = sessionManager.isDecoy()
        if (decoy) {
            // A restricted session never reads the real table, not even to count it.
            _state.update { it.copy(loading = false, report = SecurityReport(), decoy = true) }
            return
        }
        val report = runCatching { repository.buildSecurityReport() }
            .getOrDefault(SecurityReport())
        val credentials = runCatching { repository.passwordAuditCandidates() }
            .getOrDefault(emptyList())
        _state.update {
            it.copy(
                loading = false,
                report = scopeToCredentials(report, credentials),
                decoy = decoy
            )
        }
    }

    /**
     * Hygiene is a statement about credentials. An identity document, a vehicle record or a
     * banking slip carries no password at all, so the repository's whole-table view has to be
     * narrowed to the records that really hold one — otherwise every passwordless row is reported
     * as both a weak password and a missing second factor, and the average strength is dragged
     * down by zeros that were never passwords.
     */
    private fun scopeToCredentials(report: SecurityReport, credentials: List<VaultEntry>): SecurityReport {
        if (report.totalEntries == 0) return report
        val scoped = credentials.filter { it.password.isNotBlank() }
        if (scoped.size == report.totalEntries) return report
        val total = scoped.size
        if (total == 0) {
            return report.copy(
                totalEntries = 0,
                averageStrength = 0,
                weak = emptyList(),
                reused = emptyList(),
                missingTwoFactor = emptyList(),
                compromised = emptyList(),
                twoFactorCoverage = 0f,
                score = 0
            )
        }
        val credentialIds = scoped.mapTo(HashSet()) { it.id }
        val weak = report.weak.filter { it.id in credentialIds }
        val compromised = report.compromised.filter { it.id in credentialIds }
        val reusedGroups = report.reused
            .map { group -> group.copy(entries = group.entries.filter { it.id in credentialIds }) }
            .filter { it.entries.size > 1 }
        val reusedEntries = reusedGroups.sumOf { it.entries.size }
        val average = scoped.map { it.strengthScore }.average().roundToInt()
        val twoFaRatio = scoped.count { it.hasTotp }.toFloat() / total
        val reuseRatio = reusedEntries.toFloat() / total
        val compromisedRatio = compromised.size.toFloat() / total
        // The weighting published by VaultRepository.buildSecurityReport — strength 45,
        // uniqueness 20, two-factor coverage 20, breach 15 — applied to credentials only.
        val score = (
            average * 0.45 +
                (1f - reuseRatio.coerceIn(0f, 1f)) * 20 +
                twoFaRatio.coerceIn(0f, 1f) * 20 +
                (1f - compromisedRatio.coerceIn(0f, 1f)) * 15
            ).roundToInt().coerceIn(0, 100)
        return report.copy(
            totalEntries = total,
            averageStrength = average,
            weak = weak.sortedBy { it.strengthScore },
            reused = reusedGroups.sortedByDescending { it.entries.size },
            missingTwoFactor = scoped.filter { !it.hasTotp }.sortedBy { it.title },
            compromised = compromised,
            twoFactorCoverage = twoFaRatio,
            score = score
        )
    }
}
