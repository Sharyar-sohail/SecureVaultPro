package com.securevault.pro.presentation.screens.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.domain.model.CustomField
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.security.PasswordAnalyzer
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject

data class EntryEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val decoy: Boolean = false,
    val title: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val website: String = "",
    val url: String = "",
    val notes: String = "",
    val category: String = "LOGINS",
    val customFields: List<CustomField> = emptyList(),
    val tagsInput: String = "",
    val isFavorite: Boolean = false,
    val hasTotp: Boolean = false,
    val totpInput: String = "",
    val totpWasSealed: Boolean = false,
    val hiddenInTravel: Boolean = false,
    val expiresAt: Long? = null,
    val passwordSealed: Boolean = false,
    val notesSealed: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val score: Int = 0
) {
    val canSave: Boolean get() = !saving && title.isNotBlank() && !decoy
}

@HiltViewModel
class EntryEditorViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val analyzer: PasswordAnalyzer,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val editorId: String? = savedStateHandle.get<String>("entryId")?.takeIf { it.isNotBlank() }
    private val draftId: String = editorId ?: UUID.randomUUID().toString()
    private val _state = MutableStateFlow(
        EntryEditorUiState(
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
            val entry = editorId?.let { repository.getEntry(it) }
            if (entry == null) {
                _state.update { it.copy(loading = false, isNew = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    isNew = false,
                    title = entry.title,
                    username = entry.username,
                    email = entry.email,
                    password = entry.password,
                    website = entry.website,
                    url = entry.url,
                    notes = entry.notes,
                    category = entry.category.takeIf { c -> c in EntryCategoryOptions } ?: "LOGINS",
                    customFields = entry.customFields,
                    tagsInput = entry.tags.joinToString(", "),
                    isFavorite = entry.isFavorite,
                    hasTotp = entry.hasTotp,
                    totpWasSealed = entry.totpSealed,
                    hiddenInTravel = entry.isHiddenInTravel,
                    expiresAt = entry.expiresAt,
                    passwordSealed = entry.passwordSealed,
                    notesSealed = entry.notesSealed,
                    score = analyzer.scoreFor(entry.password)
                )
            }
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value, error = null) }
    fun setUsername(value: String) = _state.update { it.copy(username = value) }
    fun setEmail(value: String) = _state.update { it.copy(email = value) }
    fun setWebsite(value: String) = _state.update { it.copy(website = value) }
    fun setUrl(value: String) = _state.update { it.copy(url = value) }
    fun setNotes(value: String) = _state.update { it.copy(notes = value) }
    fun setCategory(value: String) {
        _state.update { current ->
            val next = value.takeIf { it in EntryCategoryOptions } ?: current.category
            // A brand-new record inherits the fields its type actually needs; editing an
            // existing record never rewrites the owner's own field list.
            current.copy(
                category = next,
                customFields = if (current.isNew) current.customFields + missingTemplateFields(next, current.customFields)
                else current.customFields
            )
        }
    }
    fun setTags(value: String) = _state.update { it.copy(tagsInput = value) }
    fun setFavorite(value: Boolean) = _state.update { it.copy(isFavorite = value) }
    fun setHiddenInTravel(value: Boolean) = _state.update { it.copy(hiddenInTravel = value) }
    fun setExpiresAt(value: Long?) = _state.update { it.copy(expiresAt = value) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun setPassword(value: String) {
        _state.update {
            it.copy(
                password = value,
                score = analyzer.scoreFor(value),
                passwordSealed = false
            )
        }
    }

    fun generatePassword() {
        val generated = analyzer.generate(length = 22, symbols = true, digits = true, upper = true)
        setPassword(generated)
    }

    fun toggleTotp() {
        _state.update {
            if (it.hasTotp) {
                it.copy(hasTotp = false, totpInput = "")
            } else {
                it.copy(hasTotp = true)
            }
        }
    }

    fun setTotpInput(value: String) {
        _state.update {
            it.copy(
                totpInput = value,
                hasTotp = true
            )
        }
    }

    fun generateTotpSecret() {
        val bytes = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val secret = TotpGenerator.encodeBase32(bytes)
        _state.update { it.copy(totpInput = secret, hasTotp = true, error = null) }
    }

    fun applyScannedTotp(uri: String) {
        val parsed = TotpGenerator.parseOtpAuth(uri)
        if (parsed == null) {
            _state.update { it.copy(error = "That QR code is not a valid authenticator link.") }
            return
        }
        _state.update {
            it.copy(
                hasTotp = true,
                totpInput = uri.trim(),
                title = it.title.ifBlank {
                    parsed.issuer.takeIf { issuer -> issuer != "Authenticator" }.orEmpty()
                },
                username = it.username.ifBlank { parsed.accountName },
                error = null
            )
        }
    }

    fun addCustomField() {
        _state.update {
            if (it.customFields.size >= 12) it
            else it.copy(customFields = it.customFields + CustomField())
        }
    }

    fun updateCustomField(index: Int, transform: (CustomField) -> CustomField) {
        _state.update { current ->
            if (index !in current.customFields.indices) return@update current
            current.copy(
                customFields = current.customFields.mapIndexed { i, field ->
                    if (i == index) transform(field) else field
                }
            )
        }
    }

    fun removeCustomField(index: Int) {
        _state.update { current ->
            if (index !in current.customFields.indices) return@update current
            current.copy(customFields = current.customFields.filterIndexed { i, _ -> i != index })
        }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (current.decoy) {
            _state.update { it.copy(error = "The decoy vault is read-only.") }
            return
        }
        if (current.title.isBlank()) {
            _state.update { it.copy(error = "Give this secret a name.") }
            return
        }
        val rawTotp = current.totpInput.trim()
        val secretToStore = when {
            rawTotp.startsWith("otpauth://", ignoreCase = true) -> {
                if (TotpGenerator.parseOtpAuth(rawTotp) == null) {
                    _state.update { it.copy(error = "That otpauth:// link could not be parsed.") }
                    return
                }
                // Keep the full URI so algorithm, digits and period travel with the entry.
                rawTotp
            }
            rawTotp.isNotBlank() -> {
                if (!TotpGenerator.isValidSecret(rawTotp)) {
                    _state.update { it.copy(error = "The 2FA secret is not valid Base32.") }
                    return
                }
                rawTotp.replace(" ", "").uppercase()
            }
            else -> ""
        }
        val entry = VaultEntry(
            id = draftId,
            title = current.title.trim(),
            username = current.username.trim(),
            email = current.email.trim(),
            password = current.password,
            website = current.website.trim(),
            url = current.url.trim().ifBlank { current.website.trim() },
            notes = current.notes,
            category = current.category,
            customFields = current.customFields
                .filter { it.label.isNotBlank() || it.value.isNotBlank() }
                .map { it.copy(label = it.label.trim(), value = it.value.trim()) },
            tags = current.tagsInput.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            isFavorite = current.isFavorite,
            hasTotp = current.hasTotp,
            totpSecret = secretToStore,
            isHiddenInTravel = current.hiddenInTravel,
            expiresAt = current.expiresAt,
            passwordSealed = current.passwordSealed,
            notesSealed = current.notesSealed
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val clash = duplicateWarningFor(current)
            if (clash != null && current.error != clash) {
                // One warning press, then the owner's second press is taken as consent.
                _state.update { it.copy(saving = false, error = clash) }
                return@launch
            }
            val ok = repository.saveEntry(entry)
            _state.update {
                it.copy(
                    saving = false,
                    saved = ok,
                    error = if (ok) null else "Unable to save this secret."
                )
            }
        }
    }

    companion object {
        val EntryCategoryOptions = listOf(
            "LOGINS", "BANKING", "IDENTITY", "VEHICLE", "WEBSITES", "APPS", "FINANCE", "OTHERS"
        )

        /**
         * Fields each item type actually needs. The room stores custom fields as plain
         * label/value/concealed JSON, so a category template is expressed as labelled fields
         * rather than as a new schema type.
         */
        private val CategoryFieldTemplates: Map<String, List<Pair<String, Boolean>>> = mapOf(
            "BANKING" to listOf(
                "Bank name" to false,
                "Account number" to true,
                "Sort code / BSB" to true,
                "Card number" to true,
                "Card PIN" to true
            ),
            "IDENTITY" to listOf(
                "Document type" to false,
                "Document number" to true,
                "Issued by" to false,
                "Nationality" to false
            ),
            "VEHICLE" to listOf(
                "Plate number" to false,
                "VIN / chassis number" to true,
                "Licence number" to true,
                "Insurance provider" to false
            ),
            "FINANCE" to listOf(
                "Institution" to false,
                "Account number" to true,
                "Routing / IBAN" to true,
                "Tax ID" to true
            ),
            "APPS" to listOf(
                "Plan or package" to false,
                "Recovery code" to true
            )
        )
    }

    private fun missingTemplateFields(category: String, existing: List<CustomField>): List<CustomField> {
        val template = CategoryFieldTemplates[category].orEmpty()
        val taken = existing.map { it.label.trim().lowercase() }.toSet()
        val room = (12 - existing.size).coerceAtLeast(0)
        return template
            .filter { it.first.lowercase() !in taken }
            .take(room)
            .map { (label, concealed) -> CustomField(label = label, concealed = concealed) }
    }

    /**
     * Bitwarden/1Password both warn before a second copy of the same login is created.
     * Returns the wording for the collision, or null when nothing else in the vault matches.
     */
    private suspend fun duplicateWarningFor(current: EntryEditorUiState): String? {
        val identities = listOf(current.username.trim().lowercase(), current.email.trim().lowercase())
            .filter { it.isNotBlank() }
        val title = current.title.trim().lowercase()
        if (identities.isEmpty() && title.isBlank()) return null
        val host = hostOf(current.website.ifBlank { current.url })
        val rows = runCatching { repository.observeEntries().first() }.getOrDefault(emptyList())
        val clash = rows.firstOrNull { other ->
            if (other.id == draftId) return@firstOrNull false
            val others = listOf(other.username.trim().lowercase(), other.email.trim().lowercase())
            val samePerson = identities.isNotEmpty() && others.any { it.isNotBlank() && it in identities }
            val sameSite = host.isNotBlank() && hostOf(other.displayUrl) == host
            val sameName = title.isNotBlank() && other.title.trim().lowercase() == title
            (samePerson && (sameSite || other.category.equals(current.category, ignoreCase = true))) ||
                (sameName && other.category.equals(current.category, ignoreCase = true))
        } ?: return null
        val named = clash.title.ifBlank { clash.domain.ifBlank { "an entry without a name" } }
        return "You already have \"$named\" in ${clash.category.lowercase()}. " +
            "Press save again to keep both, or edit the existing entry instead."
    }

    private fun hostOf(value: String): String = value.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .substringBefore('/').substringBefore('?').substringBefore(':')
}
