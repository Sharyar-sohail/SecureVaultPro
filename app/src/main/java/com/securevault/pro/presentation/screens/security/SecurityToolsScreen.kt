package com.securevault.pro.presentation.screens.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.BuildConfig
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.screens.secureinput.RecoverySeedSection

@Composable
fun SecurityToolsScreen(
    onBack: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenPasskeys: () -> Unit,
    onOpenEmail: () -> Unit = {},
    onOpenStego: () -> Unit = {},
    viewModel: SecurityToolsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var autofillEnabled by remember { mutableStateOf(false) }

    // The seed is a screen of its own, so it is hosted here rather than given a route: an owner
    // reaches it without anything outside this package changing.
    var showRecoverySeed by remember { mutableStateOf(false) }
    if (showRecoverySeed) {
        RecoverySeedSection(onBack = {
            showRecoverySeed = false
            viewModel.refreshSeedRecord()
        })
        return
    }

    fun refreshAutofill() {
        autofillEnabled = runCatching {
            context.getSystemService(AutofillManager::class.java)
                ?.hasEnabledAutofillServices() == true
        }.getOrDefault(false)
    }

    LaunchedEffect(Unit) { refreshAutofill() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAutofill()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GlassScaffold(
        title = "Security tools",
        subtitle = "Themes · panic · autofill · sync",
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = vaultGutter(), end = vaultGutter(), top = 4.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(accent = VaultColor.decorative) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(VaultShapes.card)
                                .background(VaultColor.decorative.copy(alpha = 0.12f))
                                .border(1.dp, VaultColor.decorative.copy(alpha = 0.3f), VaultShapes.card),
                            contentAlignment = Alignment.Center
                        ) {
                            ThemedIcon(
                                glyph = Glyph.security,
                                contentDescription = null,
                                tint = VaultColor.decorative,
                                modifier = Modifier.size(27.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Privacy command center",
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong
                            )
                            Text(
                                text = "Tune the vault's appearance and active defences. Every control is device-bound.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TagChip("AES-256", accent = VaultColor.credit)
                        TagChip("KEYSTORE", accent = VaultColor.accent)
                        TagChip("600K PBKDF2", accent = VaultColor.warning)
                    }
                }
            }

            // A recovery seed belongs to the primary vault only: a restricted session must not be
            // able to pull the real archive into itself, so the tile is not offered there.
            if (!state.decoy) {
                item {
                    ToolCard(
                        icon = Glyph.key,
                        accent = VaultColor.accent,
                        title = "Master recovery seed",
                        description = if (state.seedWordCount > 0) {
                            "A ${state.seedWordCount}-word seed is registered here. It reopens this " +
                                "vault with no Google account, no network and no password hint - and " +
                                "so does any older seed you still hold."
                        } else {
                            "Twelve or twenty-four words that reopen this vault when everything else " +
                                "is gone. Made, proved and stored entirely on this device."
                        },
                        badge = if (state.seedWordCount > 0) "READY" else "SET UP",
                        badgeAccent = if (state.seedWordCount > 0) VaultColor.credit else VaultColor.accent,
                        onClick = { showRecoverySeed = true }
                    ) {
                        ThemedIcon(
                            glyph = Glyph.chevronRight,
                            contentDescription = null,
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            item {
                ToolCard(
                    icon = Glyph.fingerprint,
                    accent = VaultColor.accent,
                    title = "Passkeys",
                    description = "Sign in with a key this device keeps - no password to type, no " +
                        "code to read. Enrol against one site's domain, then check that the system " +
                        "will actually answer a challenge.",
                    badge = "FIDO2", badgeAccent = VaultColor.accent,
                    onClick = onOpenPasskeys
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronRight,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            item {
                ToolCard(
                    icon = Glyph.email,
                    accent = VaultColor.accent,
                    title = "Secure mail",
                    description = "A real IMAP and SMTP client: connect Google in one tap, or type your own host, port and passphrase. Server passwords are sealed into the vault, never written to a log.",
                    badge = "IMAP � SMTP", badgeAccent = VaultColor.accent,
                    onClick = onOpenEmail
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronRight,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            item {
                ToolCard(
                    icon = Glyph.image,
                    accent = VaultColor.decorative,
                    title = "Hidden in photos",
                    description = "Seal a message into the luminance of a photograph. It survives a black-and-white print, shows nothing to a QR scanner, and can be split across three carriers.",
                    badge = "STEGO", badgeAccent = VaultColor.decorative,
                    onClick = onOpenStego
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronRight,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (state.decoy) {
                item {
                    GlassCard(accent = VaultColor.warning, borderColor = VaultColor.warning.copy(alpha = 0.3f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.lock,
                                contentDescription = null,
                                tint = VaultColor.warning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Restricted session · themes change, but security switches are locked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }


            item {
                ToolCard(
                    icon = Glyph.vibration,
                    accent = VaultColor.warning,
                    title = "Panic shake",
                    description = "Detects a deliberate double shake and instantly locks the vault, hides task previews and clears the clipboard.",
                    badge = if (state.panicShake) "ARMED" else "OFF",
                    badgeAccent = if (state.panicShake) VaultColor.warning else VaultColor.textFaint
                ) {
                    Switch(
                        checked = state.panicShake,
                        onCheckedChange = viewModel::setPanicShake,
                        enabled = !state.decoy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VaultColor.background,
                            checkedTrackColor = VaultColor.warning,
                            uncheckedThumbColor = VaultColor.textFaint,
                            uncheckedTrackColor = VaultColor.elevated
                        )
                    )
                }
            }

            item {
                ToolCard(
                    icon = Glyph.contentPaste,
                    accent = VaultColor.credit,
                    title = "Autofill service",
                    description = if (autofillEnabled) {
                        "Secure Wallet Pro is the active autofill provider. Suggestions fill only from the primary vault."
                    } else {
                        "Enable “Secure Wallet Pro” in Android autofill settings to fill logins in apps and browsers."
                    },
                    badge = if (autofillEnabled) "ACTIVE" else "ENABLE",
                    badgeAccent = if (autofillEnabled) VaultColor.credit else VaultColor.accent,
                    onClick = { openAutofillSettings(context) }
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronRight,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            item {
                ToolCard(
                    icon = Glyph.healthAndSafety,
                    accent = VaultColor.debit,
                    title = "Password breach radar",
                    description = "Runs a free k-anonymity check against Pwned Passwords. Only a 5-character SHA-1 prefix is sent.",
                    badge = "FREE",
                    badgeAccent = VaultColor.debit,
                    onClick = onOpenHealth
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronRight,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            item {
                ToolCard(
                    icon = Glyph.sync,
                    accent = VaultColor.accent,
                    title = "Live cloud mirror",
                    description = if (state.autoSync) {
                        "Every add, edit or delete is sealed into JSON + XML and mirrored to Drive after a short debounce."
                    } else {
                        "Live sync is paused. Open Sync Center to resume or push a manual snapshot."
                    },
                    badge = if (state.autoSync) "ON" else "PAUSED",
                    badgeAccent = if (state.autoSync) VaultColor.accent else VaultColor.textFaint,
                    onClick = onOpenSync
                ) {
                    ThemedIcon(
                        glyph = Glyph.chevronRight,
                        contentDescription = null,
                        tint = VaultColor.textFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            item {
                ToolCard(
                    icon = Glyph.fingerprint,
                    accent = VaultColor.decorative,
                    title = "Biometric resume lock",
                    description = "Unlocking uses face or fingerprint. Returning from the background re-arms the lock screen.",
                    badge = "PROMPT",
                    badgeAccent = VaultColor.decorative
                ) {
                    ThemedIcon(
                        glyph = Glyph.shield,
                        contentDescription = null,
                        tint = VaultColor.decorative,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            item {
                GlassCard(accent = VaultColor.accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.lock,
                            contentDescription = null,
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = "Secure Wallet Pro ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE} · no analytics, no ad SDKs, no plaintext secrets off-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textFaint,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    icon: Glyph,
    accent: Color,
    title: String,
    description: String,
    badge: String,
    badgeAccent: Color,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    GlassCard(
        accent = accent,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = icon,
                contentDescription = null,
                accent = accent,
                size = 42.dp,
                iconSize = 21.dp,
                background = accent.copy(alpha = 0.12f)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(7.dp))
                    TagChip(badge, accent = badgeAccent)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

private fun openAutofillSettings(context: Context) {
    val request = Intent(
        Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(request) }.isFailure) {
        runCatching { context.startActivity(fallback) }
    }
}
