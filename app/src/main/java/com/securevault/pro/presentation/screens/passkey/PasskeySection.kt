package com.securevault.pro.presentation.screens.passkey

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.security.passkey.PasskeyAvailability
import com.securevault.pro.security.passkey.PasskeyDescription
import com.securevault.pro.security.passkey.PasskeyDomainGroup
import com.securevault.pro.security.passkey.PasskeyRecordCodec
import com.securevault.pro.security.passkey.PasskeyTransportState

/**
 * The passkey section: what this device holds, what it would take to add more, and what is honest to
 * promise about either.
 *
 * The screen owns its own navigation — list, enrolment, detail — as a `when` over
 * [PasskeyUiState.view], because the feature has three states and not a graph. `onBack` is the only
 * thing it borrows from the app, per the entry-point contract.
 *
 * Two things it deliberately says out loud rather than hiding:
 *  - A passkey's private key never lives in this app. It is created and kept by the system's
 *    credential provider, which on a phone means Android 14 or newer plus the owner's Google account.
 *    When that transport is not there, the screen says which half is missing instead of showing a
 *    button that cannot work.
 *  - "Forget this record" is not "delete this passkey". The app can only forget its own sealed copy;
 *    the key itself is removed from the system screen this section links to. A ceremony also gets a
 *    visible countdown and a Cancel, so a provider sheet that never answers cannot strand anybody.
 */
@Composable
fun PasskeySection(
    onBack: () -> Unit,
    viewModel: PasskeyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    GlassScaffold(
        title = when (state.view) {
            PasskeyView.LIST -> "Passkeys"
            PasskeyView.ENROL -> "Add a passkey"
            PasskeyView.DETAIL -> state.selected?.rpDomain ?: "Passkey"
        },
        subtitle = when (state.view) {
            PasskeyView.LIST -> "${state.recordCount} on this device"
            PasskeyView.ENROL -> "Enrolled against one site's domain"
            PasskeyView.DETAIL -> state.selected?.transportLabel ?: "This device"
        },
        onBack = { if (state.view == PasskeyView.LIST) onBack() else viewModel.navigateBack() },
        snackbarHostState = snackbar,
        actions = {
            if (state.view == PasskeyView.LIST && state.recordCount > 0) {
                GlassIconButton(
                    icon = Glyph.settings,
                    contentDescription = "System passkey settings",
                    onClick = { viewModel.openSystemPasskeySettings(activity) }
                )
            }
        },
        fab = if (state.view == PasskeyView.LIST && state.canEnrol) {
            {
                GlassIconButton(
                    icon = Glyph.add,
                    contentDescription = "Add a passkey",
                    onClick = viewModel::openEnrolment,
                    tint = VaultColor.onAccent,
                    background = VaultColor.accent
                )
            }
        } else {
            null
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.busy) {
                CeremonyProgress(
                    secondsLeft = state.ceremonySecondsLeft,
                    onCancel = viewModel::cancelCeremony
                )
            }
            when (state.view) {
                PasskeyView.LIST -> PasskeyList(
                    state = state,
                    onOpenEnrolment = viewModel::openEnrolment,
                    onOpenDetail = viewModel::openDetail,
                    onBack = onBack,
                    onOpenSettings = { viewModel.openSystemPasskeySettings(activity) },
                    onRefresh = viewModel::refresh
                )
                PasskeyView.ENROL -> PasskeyEnrol(
                    state = state,
                    onDomain = viewModel::setDomainDraft,
                    onAccount = viewModel::setAccountDraft,
                    onSubmit = { viewModel.enrol(activity) },
                    onCancel = viewModel::navigateBack,
                    onOpenSettings = { viewModel.openSystemPasskeySettings(activity) }
                )
                PasskeyView.DETAIL -> PasskeyDetail(
                    state = state,
                    onSignIn = { viewModel.authenticate(activity) },
                    onRequestForget = { state.selected?.let(viewModel::requestDelete) },
                    onBack = viewModel::navigateBack
                )
            }
        }

        state.pendingDelete?.let { target ->
            ForgetConfirmation(
                target = target,
                onDismiss = viewModel::dismissDeleteRequest,
                onConfirm = viewModel::confirmDelete
            )
        }
    }
}

/** The list, grouped by relying party, with the availability banner above it when it matters. */
@Composable
private fun PasskeyList(
    state: PasskeyUiState,
    onOpenEnrolment: () -> Unit,
    onOpenDetail: (PasskeyDescription) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = vaultGutter(),
            end = vaultGutter(),
            top = 4.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AvailabilityBanner(state.availability, onOpenSettings)
        }
        if (state.loading) {
            item {
                LoadingRow()
            }
        } else if (state.recordCount == 0) {
            item {
                EmptyState(
                    icon = Glyph.key,
                    title = "No passkeys on this device yet",
                    message = when {
                        // No verdict at all is not the same as "no transport": the app could not
                        // read the device, and says that instead of guessing why the button is off.
                        state.availability == null ->
                            "This app could not read whether passkeys are available on this device, " +
                                "so it is not offering to add one. Unlock the vault and refresh."
                        state.isTransportBlocked ->
                            "This app cannot start a passkey on this Android version. Nothing is broken " +
                                "in your vault — there is simply no transport to ask."
                        else ->
                            "A passkey is created by your device and stored in your Google account, not " +
                                "in this app. Add one for a site that offers it."
                    },
                    actionLabel = if (state.canEnrol) "Add a passkey" else "Back",
                    onAction = if (state.canEnrol) onOpenEnrolment else onBack
                )
            }
            if (!state.canEnrol) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GhostButton(
                            text = "Add a passkey",
                            icon = if (state.canEnrol) Glyph.check else Glyph.info,
                            enabled = false,
                            onClick = onOpenEnrolment
                        )
                        GhostButton(text = "Refresh", icon = Glyph.refresh, onClick = onRefresh)
                    }
                }
            }
        } else {
            state.groups.forEach { group ->
                item {
                    DomainGroupCard(group = group, onOpenDetail = onOpenDetail)
                }
            }
            item {
                GhostButton(
                    text = "Add another passkey",
                    icon = Glyph.add,
                    enabled = state.canEnrol,
                    onClick = onOpenEnrolment
                )
            }
        }
    }
}

@Composable
private fun AvailabilityBanner(
    availability: PasskeyAvailability?,
    onOpenSettings: () -> Unit
) {
    if (availability == null) return
    if (availability.transport == PasskeyTransportState.READY && availability.canRunCeremony) {
        GlassCard(accent = VaultColor.credit, revealIndex = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedIcon(
                    glyph = Glyph.contactless,
                    contentDescription = null,
                    tint = VaultColor.credit,
                    size = 20.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Passkeys are available on this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColor.textStrong
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Your device creates the key and keeps it. This app stores only a sealed record " +
                    "of what the provider handed back, so it can tell you which sites you use.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody,
                lineHeight = 18.sp
            )
        }
        return
    }
    val (glyph, accent, title, detail) = when (availability.transport) {
        PasskeyTransportState.ANDROID_TOO_OLD -> Quad(
            Glyph.cloudOff,
            VaultColor.warning,
            "Passkeys need Android 14 or newer",
            "This app's credential library has no transport below API 34, and this app registers no " +
                "provider of its own. Your vault and its passwords are unaffected."
        )
        PasskeyTransportState.PROVIDER_NOT_REGISTERED -> Quad(
            Glyph.cloudOff,
            VaultColor.warning,
            "No credential provider answered",
            "Passkeys need the system provider — usually Android 14's credential store with a Google " +
                "account signed in. Check it in the system passkey settings."
        )
        PasskeyTransportState.UNKNOWN -> Quad(
            Glyph.errorOutline,
            VaultColor.debitInk,
            "The credential provider could not be read",
            "This app will not start a ceremony it cannot finish. Reopen the section after unlocking."
        )
        else -> Quad(
            Glyph.lock,
            VaultColor.warning,
            if (!availability.sessionIsPrimary) {
                "Locked or decoy session"
            } else {
                "This device's key is not opening"
            },
            if (!availability.sessionIsPrimary) {
                "Unlock your real vault to add or use a passkey."
            } else {
                "The vault's own key would not seal a new record, so nothing can be stored yet."
            }
        )
    }
    GlassCard(accent = accent, borderColor = accent.copy(alpha = 0.3f), revealIndex = 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = glyph,
                contentDescription = null,
                tint = accent,
                size = 20.dp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagChip(
                text = "API ${availability.sdkInt}",
                accent = accent
            )
            TagChip(text = availability.reasonLabel().uppercase(), accent = VaultColor.textFaint)
            GhostButton(text = "System settings", icon = Glyph.openInNew, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun DomainGroupCard(
    group: PasskeyDomainGroup,
    onOpenDetail: (PasskeyDescription) -> Unit
) {
    GlassCard(accent = VaultColor.accent, revealIndex = 1) {
        SectionHeader(title = group.domain)
        Spacer(Modifier.height(8.dp))
        group.records.forEach { record ->
            val description = PasskeyRecordCodec.describe(record)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShapes.input)
                    .clickable { onOpenDetail(description) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(VaultColor.accent.copy(alpha = 0.12f))
                        .border(1.dp, VaultColor.hairline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.fingerprint,
                        contentDescription = null,
                        tint = VaultColor.accent,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = description.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${description.transportLabel} · used ${formatShortDate(record.lastUsedAt)}" +
                            " · ${description.credentialTail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
                ThemedIcon(
                    glyph = Glyph.chevronRight,
                    contentDescription = null,
                    tint = VaultColor.textFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PasskeyEnrol(
    state: PasskeyUiState,
    onDomain: (String) -> Unit,
    onAccount: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vaultGutter(), vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(accent = VaultColor.secondary, revealIndex = 0) {
            Text(
                text = "What this actually does",
                style = MaterialTheme.typography.titleSmall,
                color = VaultColor.textStrong,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Your device asks the system credential provider to create a passkey for one " +
                    "domain. The provider shows its own sheet — a fingerprint, the screen lock, or your " +
                    "Google account — and keeps the private key itself. This app stores the sealed " +
                    "receipt, so the list here can tell you what you enrolled.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody,
                lineHeight = 19.sp
            )
        }
        if (state.availability != null && !state.canEnrol) {
            AvailabilityBanner(state.availability, onOpenSettings)
        }
        VaultTextField(
            value = state.domainDraft,
            onValueChange = onDomain,
            label = "Site domain",
            placeholder = "example.com",
            leadingIcon = Glyph.publicGlobe,
            enabled = !state.busy,
            isError = state.domainDraft.isNotBlank() && !state.draftIsUsable,
            supportingText = when {
                state.domainDraft.isBlank() -> "The domain the site enrols the passkey against."
                !state.draftIsUsable -> "Use a host like example.com — no path, no spaces."
                else -> "Enrolled as rp.id = ${state.domainDraft.trim().lowercase()}"
            }
        )
        VaultTextField(
            value = state.accountDraft,
            onValueChange = onAccount,
            label = "Account (optional)",
            placeholder = "you@example.com",
            leadingIcon = Glyph.person,
            enabled = !state.busy,
            supportingText = "Only a label for your list. The account the site sees is the one on its " +
                "own sign-up page."
        )
        GradientButton(
            text = if (state.busy) "Waiting for the passkey sheet" else "Create passkey",
            icon = Glyph.key,
            enabled = state.canEnrol && state.draftIsUsable,
            loading = state.busy,
            onClick = onSubmit
        )
        GhostButton(
            text = "Back",
            icon = Glyph.arrowBack,
            enabled = !state.busy,
            onClick = onCancel
        )
        if (!state.canEnrol) {
            Text(
                text = if (state.availability == null) {
                    "Adding is switched off: this app could not read the device's passkey support. " +
                        "Nothing has been sent anywhere."
                } else {
                    "Adding is switched off: ${state.availability.reasonLabel()}. " +
                        "Nothing has been sent anywhere."
                },
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textFaint,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun PasskeyDetail(
    state: PasskeyUiState,
    onSignIn: () -> Unit,
    onRequestForget: () -> Unit,
    onBack: () -> Unit
) {
    val description = state.selected ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = vaultGutter(), vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(accent = VaultColor.accent, revealIndex = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedIcon(
                    glyph = Glyph.key,
                    contentDescription = null,
                    tint = VaultColor.accent,
                    size = 22.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = description.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = VaultColor.textStrong,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            DetailRow("Domain", description.rpDomain)
            DetailRow("Relying party", description.rpName)
            DetailRow("Transport", description.transportLabel)
            DetailRow("Added", formatShortDate(description.createdAt))
            DetailRow("Last used", formatShortDate(description.lastUsedAt))
            DetailRow("Sign-ins here", description.useCount.toString())
            DetailRow("Credential id", description.credentialTail)
            DetailRow(
                "Sealed material",
                if (description.hasSealedMaterial) "Present, sealed by this device" else "Missing"
            )
        }
        state.lastAssertion?.let { line ->
            GlassCard(accent = VaultColor.credit, borderColor = VaultColor.credit.copy(alpha = 0.3f)) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
            }
        }
        GradientButton(
            text = "Sign in with it now",
            icon = Glyph.fingerprint,
            enabled = state.canAuthenticate,
            loading = state.busy,
            onClick = onSignIn
        )
        GhostButton(
            text = "Forget this record",
            icon = Glyph.delete,
            accent = VaultColor.debitInk,
            enabled = !state.busy,
            onClick = onRequestForget
        )
        Text(
            text = "Forgetting only removes this app's copy. The passkey stays on your device until you " +
                "delete it in the system passkey settings — this app cannot delete it for you, and it " +
                "never removes anything by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textFaint,
            lineHeight = 17.sp
        )
        GhostButton(text = "Back", icon = Glyph.arrowBack, onClick = onBack)
    }
}

/**
 * The countdown that keeps a provider sheet from becoming a dead end. It is the visible half of
 * [com.securevault.pro.security.passkey.PasskeyService]'s own watchdog: the app stops waiting on its
 * own, and this row always offers a way out.
 */
@Composable
private fun CeremonyProgress(secondsLeft: Int, onCancel: () -> Unit) {
    GlassCard(accent = VaultColor.warning, borderColor = VaultColor.warning.copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = Glyph.timer,
                contentDescription = null,
                tint = VaultColor.warning,
                size = 18.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (secondsLeft > 0) {
                    "Waiting for the system passkey sheet — $secondsLeft s left"
                } else {
                    "Still waiting; this app will stop shortly"
                },
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody,
                modifier = Modifier.weight(1f),
                lineHeight = 18.sp
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) {
                Text("Cancel", color = VaultColor.warningInk)
            }
        }
    }
}

@Composable
private fun ForgetConfirmation(
    target: PasskeyDescription,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VaultColor.elevated,
        titleContentColor = VaultColor.textStrong,
        textContentColor = VaultColor.textBody,
        title = { Text("Forget this passkey record?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${target.label} (${target.rpDomain})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColor.textStrong
                )
                Text(
                    text = "This removes this app's sealed record only. The passkey itself stays on " +
                        "your device and in your Google account, and the site can still sign you in " +
                        "with it. To delete the key, use the system passkey settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Forget this record", color = VaultColor.debitInk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep it", color = VaultColor.textBody)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textFaint,
            modifier = Modifier.widthIn(min = 96.dp, max = 140.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody,
            modifier = Modifier.weight(1f),
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun LoadingRow() {
    GlassCard(accent = VaultColor.textFaint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = Glyph.refresh,
                contentDescription = null,
                tint = VaultColor.textFaint,
                size = 18.dp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Reading this device's passkey records…",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
        }
    }
}

/** The Activity this view is attached to, or null. A passkey sheet needs a window to draw in. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext ?: return null
    }
    return null
}

/** Destructuring for the availability banner's four-part tuple. */
private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
