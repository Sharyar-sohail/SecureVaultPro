package com.securevault.pro.presentation.screens.health

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.ReusedGroup
import com.securevault.pro.domain.model.SecurityReport
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.SecurityGauge
import com.securevault.pro.presentation.components.StatTile
import com.securevault.pro.presentation.components.StrengthMeter
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.securityColor
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import kotlin.math.roundToInt
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun HealthScreen(
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    viewModel: HealthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report = state.report

    GlassScaffold(
        title = "Password health",
        subtitle = "On-device analysis · optional anonymous breach check",
        onBack = onBack
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = vaultGutter(), end = vaultGutter(), bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.decoy) {
                item {
                    GlassCard(accent = VaultColor.warning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedIcon(
                                glyph = Glyph.security,
                                contentDescription = null,
                                tint = VaultColor.warning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Restricted session · insights cover only the visible vault.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            if (state.loading) {
                item {
                    GlassCard(accent = VaultColor.accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = VaultColor.accent
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Analyzing every login locally…",
                                style = MaterialTheme.typography.bodySmall,
                                color = VaultColor.textBody
                            )
                        }
                    }
                }
            } else {
                item {
                    GlassCard(accent = securityColor(report.score)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SecurityGauge(
                                score = report.score,
                                size = 112.dp,
                                stroke = 10.dp
                            )
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Vault hygiene",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = VaultColor.textStrong
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = hygieneSummary(report),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColor.textBody,
                                    lineHeight = 18.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TagChip(
                                        text = "AVG ${report.averageStrength}",
                                        accent = securityColor(report.averageStrength)
                                    )
                                    TagChip(
                                        text = "2FA ${(report.twoFactorCoverage * 100).roundToInt()}%",
                                        accent = if (report.twoFactorCoverage >= 0.6f) VaultColor.credit else VaultColor.warning
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "${report.totalEntries} logins analyzed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint
                                )
                            }
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            label = "Weak",
                            value = report.weakCount,
                            icon = Glyph.warning,
                            accent = VaultColor.warning,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.select(HealthSection.WEAK) }
                        )
                        StatTile(
                            label = "Reused",
                            value = report.reusedCount,
                            icon = Glyph.contentCopy,
                            accent = VaultColor.debit,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.select(HealthSection.REUSED) }
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            label = "No 2FA",
                            value = report.missingTwoFactorCount,
                            icon = Glyph.security,
                            accent = VaultColor.accent,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.select(HealthSection.TWO_FACTOR) }
                        )
                        StatTile(
                            label = "Breached",
                            value = report.compromisedCount,
                            icon = Glyph.errorOutline,
                            accent = VaultColor.debit,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.select(HealthSection.COMPROMISED) }
                        )
                    }
                }

                item {
                    OnlineBreachCard(
                        state = state,
                        onRun = viewModel::runBreachCheck,
                        onOpenEntry = onOpenEntry
                    )
                }

                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(HealthSection.entries) { section ->
                            Pill(
                                label = sectionPillLabel(section),
                                selected = state.section == section,
                                count = sectionCount(report, section),
                                onClick = { viewModel.select(section) }
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sectionTitle(state.section),
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColor.textStrong,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${state.selectedCount} FOUND",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                            color = VaultColor.textFaint
                        )
                    }
                }

                when (state.section) {
                    HealthSection.WEAK -> {
                        if (report.weak.isEmpty()) {
                            item { AllClearCard("No weak passwords", "Every login clears the local entropy and length thresholds.") }
                        } else {
                            items(report.weak, key = { it.id }) { entry ->
                                HealthEntryRow(
                                    entry = entry,
                                    showStrength = true,
                                    badge = "${entry.strengthScore}",
                                    badgeAccent = securityColor(entry.strengthScore),
                                    onClick = { onOpenEntry(entry.id) }
                                )
                            }
                        }
                    }
                    HealthSection.TWO_FACTOR -> {
                        if (report.missingTwoFactor.isEmpty()) {
                            item { AllClearCard("Full 2FA coverage", "Every login has a live authenticator account attached.") }
                        } else {
                            items(report.missingTwoFactor, key = { it.id }) { entry ->
                                HealthEntryRow(
                                    entry = entry,
                                    showStrength = false,
                                    badge = "NO 2FA",
                                    badgeAccent = VaultColor.warning,
                                    onClick = { onOpenEntry(entry.id) }
                                )
                            }
                        }
                    }
                    HealthSection.COMPROMISED -> {
                        if (report.compromised.isEmpty()) {
                            item { AllClearCard("No breached passwords", "No login matches the bundled breach dictionary.") }
                        } else {
                            items(report.compromised, key = { it.id }) { entry ->
                                HealthEntryRow(
                                    entry = entry,
                                    showStrength = false,
                                    badge = "BREACH",
                                    badgeAccent = VaultColor.debit,
                                    onClick = { onOpenEntry(entry.id) }
                                )
                            }
                        }
                    }
                    HealthSection.REUSED -> {
                        if (report.reused.isEmpty()) {
                            item { AllClearCard("Every password is unique", "No two logins share the same password.") }
                        } else {
                            items(report.reused, key = { it.fingerprint }) { group ->
                                ReusedGroupCard(group = group, onOpenEntry = onOpenEntry)
                            }
                        }
                    }
                }

                item {
                    GlassCard(accent = VaultColor.accent) {
                        Text(
                            text = "How the score works",
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColor.textStrong
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Strength contributes 45%, uniqueness 20%, two-factor coverage 20% and breach checks 15%. Passwords are decrypted in memory only for this analysis; no hash, password or title leaves the device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineBreachCard(
    state: HealthUiState,
    onRun: () -> Unit,
    onOpenEntry: (String) -> Unit
) {
    val hits = state.breachHits
    val accent = when (state.breachStatus) {
        BreachScanStatus.DONE -> if (hits.isEmpty()) VaultColor.credit else VaultColor.debit
        BreachScanStatus.ERROR -> VaultColor.warning
        BreachScanStatus.SCANNING -> VaultColor.accent
        BreachScanStatus.IDLE -> VaultColor.accent
    }
    GlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = Glyph.security,
                contentDescription = null,
                accent = accent,
                size = 38.dp,
                iconSize = 19.dp,
                background = accent.copy(alpha = 0.11f)
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Live breach check",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong
                )
                Text(
                    text = "Pwned Passwords · k-anonymity",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
            TagChip(
                text = when (state.breachStatus) {
                    BreachScanStatus.DONE -> if (hits.isEmpty()) "CLEAR" else "${hits.size} HIT"
                    BreachScanStatus.ERROR -> "RETRY"
                    BreachScanStatus.SCANNING -> "SCANNING"
                    BreachScanStatus.IDLE -> "FREE"
                },
                accent = accent
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Only the first five characters of each SHA-1 hash are sent. Passwords, usernames and titles never leave this device.",
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody,
            lineHeight = 17.sp
        )
        when (state.breachStatus) {
            BreachScanStatus.SCANNING -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = VaultColor.accent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Auditing ${state.breachChecked.coerceAtLeast(0)} logins in memory…",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textBody
                    )
                }
            }
            BreachScanStatus.DONE -> {
                if (hits.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.checkCircle,
                            contentDescription = null,
                            tint = VaultColor.credit,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = "${state.breachChecked} logins checked against the breach corpus. None were exposed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.credit,
                            lineHeight = 15.sp
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${hits.size} exposed password${if (hits.size == 1) "" else "s"} · rotate these first",
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColor.debit
                    )
                    Spacer(Modifier.height(4.dp))
                    hits.take(4).forEach { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(VaultShapes.input)
                                .clickable { onOpenEntry(hit.entryId) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MonogramBadge(seed = hit.title, size = 29.dp)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = hit.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = VaultColor.textStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = hit.username.ifBlank { "No username" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TagChip(
                                text = "${java.text.NumberFormat.getIntegerInstance().format(hit.count)}×",
                                accent = VaultColor.debit
                            )
                        }
                    }
                    if (hits.size > 4) {
                        Text(
                            text = "+${hits.size - 4} more exposed passwords",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                }
            }
            BreachScanStatus.ERROR -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = state.breachError ?: "The breach service could not be reached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.warning,
                    lineHeight = 17.sp
                )
            }
            BreachScanStatus.IDLE -> Unit
        }
        Spacer(Modifier.height(12.dp))
        GradientButton(
            text = when (state.breachStatus) {
                BreachScanStatus.SCANNING -> "Checking range prefixes…"
                BreachScanStatus.DONE -> "Run check again"
                else -> "Check for breached passwords"
            },
            icon = Glyph.security,
            enabled = state.breachStatus != BreachScanStatus.SCANNING,
            loading = state.breachStatus == BreachScanStatus.SCANNING,
            modifier = Modifier.fillMaxWidth(),
            onClick = onRun
        )
        if (state.breachStatus == BreachScanStatus.DONE && state.breachCheckedAt > 0L) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Last lookup · ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(state.breachCheckedAt))} · ${state.breachUnique} unique passwords",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
    }
}

@Composable
private fun AllClearCard(title: String, message: String) {
    GlassCard(accent = VaultColor.credit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(VaultShapes.input)
                    .background(VaultColor.credit.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.checkCircle,
                    contentDescription = null,
                    tint = VaultColor.credit,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun HealthEntryRow(
    entry: VaultEntry,
    showStrength: Boolean,
    badge: String,
    badgeAccent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    GlassCard(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(seed = entry.title, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.username.ifBlank { entry.email }.ifBlank { entry.domain },
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showStrength) {
                    Spacer(Modifier.height(7.dp))
                    StrengthMeter(
                        score = entry.strengthScore,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                TagChip(text = badge, accent = badgeAccent)
                Spacer(Modifier.height(5.dp))
                ThemedIcon(
                    glyph = Glyph.chevronRight,
                    contentDescription = null,
                    tint = VaultColor.textFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ReusedGroupCard(group: ReusedGroup, onOpenEntry: (String) -> Unit) {
    val palette = VaultPaletteController.current
    GlassCard(accent = VaultColor.debit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(VaultShapes.input)
                    .background(VaultColor.debit.copy(alpha = 0.11f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.contentCopy,
                    contentDescription = null,
                    tint = VaultColor.debit,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${group.entries.size} accounts share one password",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong
                )
                Text(
                    text = "Fingerprint ${group.fingerprint.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        group.entries.take(6).forEachIndexed { index, entry ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.surfaceOverlay.copy(alpha = 0.05f))
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenEntry(entry.id) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonogramBadge(seed = entry.title, size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.username.ifBlank { entry.email }.ifBlank { entry.domain },
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ThemedIcon(
                    glyph = Glyph.chevronRight,
                    contentDescription = null,
                    tint = VaultColor.textFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (group.entries.size > 6) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "+${group.entries.size - 6} more accounts share this password",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
    }
}

private fun sectionTitle(section: HealthSection): String = when (section) {
    HealthSection.WEAK -> "Weak passwords"
    HealthSection.REUSED -> "Reused passwords"
    HealthSection.TWO_FACTOR -> "Missing two-factor"
    HealthSection.COMPROMISED -> "Breached passwords"
}

private fun sectionPillLabel(section: HealthSection): String = when (section) {
    HealthSection.WEAK -> "Weak"
    HealthSection.REUSED -> "Reused"
    HealthSection.TWO_FACTOR -> "No 2FA"
    HealthSection.COMPROMISED -> "Breached"
}

private fun sectionCount(report: SecurityReport, section: HealthSection): Int = when (section) {
    HealthSection.WEAK -> report.weak.size
    HealthSection.REUSED -> report.reusedCount
    HealthSection.TWO_FACTOR -> report.missingTwoFactor.size
    HealthSection.COMPROMISED -> report.compromised.size
}

private fun hygieneSummary(report: SecurityReport): String {
    if (report.totalEntries == 0) return "Add logins to generate a health report."
    val issues = report.weakCount + report.reusedCount +
        report.missingTwoFactorCount + report.compromisedCount
    return when {
        issues == 0 -> "Every login is strong, unique and protected with 2FA."
        report.compromisedCount > 0 -> "Breached passwords deserve attention first."
        report.reusedCount > 0 -> "Shared passwords weaken the whole vault."
        report.weakCount > 0 -> "Strengthen the weakest logins first."
        else -> "Add 2FA to your most important logins."
    }
}
