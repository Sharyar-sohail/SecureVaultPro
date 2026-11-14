package com.securevault.pro.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.securevault.pro.MainActivity
import com.securevault.pro.R
import com.securevault.pro.data.repository.AutofillQuery
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.security.SessionManager
import com.securevault.pro.security.passkey.PasskeyAutofillBridge
import com.securevault.pro.security.SessionMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class VaultAutofillService : AutofillService() {

    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var passkeyBridge: PasskeyAutofillBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val screen = ScreenParser.parse(structure, this)
        if (screen.usernameId == null && screen.passwordId == null) {
            callback.onSuccess(null)
            return
        }
        when (sessionManager.mode.value) {
            SessionMode.LOCKED -> callback.onSuccess(buildUnlockResponse(screen))
            SessionMode.DECOY -> callback.onSuccess(null)
            SessionMode.REAL -> fillReal(screen, cancellationSignal, callback)
        }
    }

    private fun fillReal(
        screen: AutofillScreen,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val job = scope.launch {
            try {
                val query = AutofillQuery(
                    webDomain = screen.webDomain,
                    packageName = screen.packageName,
                    appLabel = screen.appLabel,
                    typedUsername = screen.username,
                    hints = screen.hints
                )
                val candidates = withContext(Dispatchers.IO) {
                    repository.autofillCandidates(query)
                }
                val passkeys = screen.webDomain?.takeIf { it.isNotBlank() }?.let { host ->
                    withContext(Dispatchers.IO) {
                        runCatching { passkeyBridge.passkeyCandidatesFor(host) }.getOrDefault(emptyList())
                    }
                }.orEmpty()
                if (candidates.isEmpty() && passkeys.isEmpty()) {
                    callback.onSuccess(saveOnlyResponse(screen))
                    return@launch
                }
                val response = FillResponse.Builder()
                var added = 0
                candidates.forEach { entry ->
                    val usernameValue = entry.username.ifBlank { entry.email }
                    val title = entry.title.trim().ifBlank { "SECURE VAULT PRO" }
                    val presentation = RemoteViews(packageName, R.layout.autofill_dataset).apply {
                        setTextViewText(R.id.autofill_label, title.take(80))
                        setTextViewText(
                            R.id.autofill_detail,
                            usernameValue.ifBlank {
                                entry.domain.ifBlank { entry.category.lowercase() }
                            }.take(90)
                        )
                    }
                    val dataset = Dataset.Builder(presentation)
                    var populated = false
                    screen.usernameId?.let { id ->
                        if (usernameValue.isNotBlank()) {
                            dataset.setValue(id, AutofillValue.forText(usernameValue))
                            populated = true
                        }
                    }
                    screen.passwordId?.let { id ->
                        if (entry.password.isNotBlank()) {
                            dataset.setValue(id, AutofillValue.forText(entry.password))
                            populated = true
                        }
                    }
                    if (populated) {
                        response.addDataset(dataset.build())
                        added++
                    }
                }
                // A passkey is not a field value, so it cannot ride in setValue(). From API 33 a dataset
                // may carry its own authentication IntentSender instead: tapping it opens this app, where
                // the ceremony runs against the platform provider. Offered only when a sealed record
                // genuinely matches the visited host, and never in a decoy session.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && passkeyBridge.canOfferPasskeys()) {
                    passkeys.take(3).forEach { candidate ->
                        val sender = passkeyBridge.beginAuthentication(
                            this@VaultAutofillService,
                            candidate.record.rpDomain
                        ) ?: return@forEach
                        val passkeyView = RemoteViews(packageName, R.layout.autofill_dataset).apply {
                            setTextViewText(
                                R.id.autofill_label,
                                "Passkey · ${candidate.record.rpDomain}".take(80)
                            )
                            setTextViewText(R.id.autofill_detail, "Sign in with SECURE VAULT PRO")
                        }
                        runCatching {
                            response.addDataset(Dataset.Builder(passkeyView).setAuthentication(sender).build())
                            added++
                        }
                    }
                }

                val saveIds = listOfNotNull(screen.usernameId, screen.passwordId).toTypedArray()
                val saveInfo = if (saveIds.isNotEmpty()) {
                    runCatching {
                        SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, saveIds)
                            .setDescription("SECURE VAULT PRO")
                            .build()
                    }.getOrNull()
                } else {
                    null
                }
                if (saveInfo != null) {
                    response.setSaveInfo(saveInfo)
                }
                callback.onSuccess(if (added > 0) response.build() else saveOnlyResponse(screen))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                callback.onFailure(e.message?.take(120) ?: "Autofill is unavailable right now.")
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    /**
     * A response with no datasets and only a save request, for the case that matters most to the
     * owner: signing up somewhere new, where Chrome used to show nothing at all because this service
     * only attached `SaveInfo` once a matching vault entry existed.
     */
    private fun saveOnlyResponse(screen: AutofillScreen): FillResponse? {
        val ids = listOfNotNull(screen.usernameId, screen.passwordId).toTypedArray()
        if (ids.isEmpty()) return null
        return runCatching {
            FillResponse.Builder()
                .setSaveInfo(
                    SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, ids)
                        .setDescription("SECURE VAULT PRO")
                        .build()
                )
                .build()
        }.getOrNull()
    }

    private fun buildUnlockResponse(screen: AutofillScreen): FillResponse? {
        val ids = listOfNotNull(screen.usernameId, screen.passwordId).toTypedArray()
        if (ids.isEmpty()) return null
        return runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_CANCEL_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getActivity(this, UNLOCK_REQUEST_CODE, intent, flags)
            val presentation = RemoteViews(packageName, R.layout.autofill_dataset).apply {
                setTextViewText(R.id.autofill_label, "Unlock SECURE VAULT PRO")
                setTextViewText(R.id.autofill_detail, "Tap to open the vault, then autofill")
            }
            FillResponse.Builder()
                .setAuthentication(ids, pending.intentSender, presentation)
                .build()
        }.getOrNull()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        if (sessionManager.mode.value != SessionMode.REAL) {
            callback.onFailure("Unlock SECURE VAULT PRO before saving this login.")
            return
        }
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess()
            return
        }
        val screen = ScreenParser.parse(structure, this)
        if (screen.password.isBlank()) {
            callback.onSuccess()
            return
        }
        scope.launch {
            try {
                val title = screen.appLabel.ifBlank {
                    screen.webDomain?.takeIf { it.isNotBlank() } ?: "Saved login"
                }
                val saved = withContext(Dispatchers.IO) {
                    repository.saveAutofillCredential(
                        title = title,
                        username = screen.username,
                        password = screen.password,
                        webDomain = screen.webDomain,
                        packageName = screen.packageName
                    )
                }
                if (saved) callback.onSuccess()
                else callback.onFailure("Secure Wallet could not save this login.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                callback.onFailure(e.message?.take(120) ?: "Secure Wallet could not save this login.")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class FieldNode(
        val id: AutofillId,
        val isPassword: Boolean,
        val isUsernameCandidate: Boolean,
        val text: String
    )

    private data class AutofillScreen(
        val packageName: String,
        val appLabel: String,
        val webDomain: String?,
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val username: String,
        val password: String
    ) {
        val hints: List<String>
            get() = listOfNotNull(
                webDomain,
                packageName,
                appLabel,
                appLabel.substringAfterLast('.')
            ).filter { it.isNotBlank() }
    }

    private object ScreenParser {
        fun parse(structure: AssistStructure, context: Context): AutofillScreen {
            val packageName = structure.activityComponent?.packageName.orEmpty()
            val appLabel = resolveLabel(context, packageName)
            val nodes = mutableListOf<FieldNode>()
            val webDomains = mutableListOf<String>()
            for (index in 0 until structure.windowNodeCount) {
                walk(structure.getWindowNodeAt(index).rootViewNode, nodes, webDomains)
            }
            val webDomain = webDomains
                .map { it.trim().lowercase() }
                .firstOrNull { it.isNotBlank() }
            val passwordField = nodes.firstOrNull { it.isPassword }
            val usernameField = nodes.firstOrNull { it.isUsernameCandidate }
                ?: nodes.takeWhile { it != passwordField }
                    .lastOrNull { it.id != passwordField?.id && it.text.isNotEmpty() }
                ?: nodes.firstOrNull { !it.isPassword && it.id != passwordField?.id && it.text.isNotEmpty() }
            return AutofillScreen(
                packageName = packageName,
                appLabel = appLabel,
                webDomain = webDomain,
                usernameId = usernameField?.id,
                passwordId = passwordField?.id,
                username = usernameField?.text.orEmpty(),
                password = passwordField?.text.orEmpty()
            )
        }

        private fun walk(
            node: AssistStructure.ViewNode,
            output: MutableList<FieldNode>,
            webDomains: MutableList<String>
        ) {
            node.webDomain?.trim()?.takeIf { it.isNotBlank() }?.let(webDomains::add)
            val id = node.autofillId
            val hints = node.autofillHints?.map { it.lowercase() }.orEmpty()
            val identifier = (node.idEntry.orEmpty() + " " + node.hint.orEmpty()).lowercase()
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val isPassword = hints.any { it.contains("password") } ||
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
                identifier.contains("password") ||
                identifier.contains("passwd") ||
                identifier.contains("passphrase")
            val isUsername = hints.any {
                it.contains("username") || it.contains("email") ||
                    it.contains("phone") || it.contains("login") ||
                    it.contains("account") || it.contains("identifier")
            } || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                identifier.contains("user") || identifier.contains("email") ||
                identifier.contains("login") || identifier.contains("account") ||
                identifier.contains("identifier") || identifier.contains("phone") ||
                identifier.contains("mobile")
            val text = node.autofillValue?.textValue?.toString()
                ?: node.text?.toString().orEmpty()
            if (id != null && (isPassword || isUsername || node.autofillType != 0)) {
                output += FieldNode(
                    id = id,
                    isPassword = isPassword,
                    isUsernameCandidate = isUsername && !isPassword,
                    text = text
                )
            }
            for (childIndex in 0 until node.childCount) {
                walk(node.getChildAt(childIndex), output, webDomains)
            }
        }

        private fun resolveLabel(context: Context, packageName: String): String {
            if (packageName.isBlank()) return ""
            return runCatching {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }
    }

    private companion object {
        const val UNLOCK_REQUEST_CODE = 7102
    }
}
