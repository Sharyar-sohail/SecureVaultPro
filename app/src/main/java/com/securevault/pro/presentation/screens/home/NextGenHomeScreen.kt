package com.securevault.pro.presentation.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.SearchKind
import com.securevault.pro.presentation.components.BrandModuleIcon
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultModule
import com.securevault.pro.presentation.components.greetingForHour
import com.securevault.pro.presentation.navigation.Routes
import com.securevault.pro.presentation.theme.VaultPaletteController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * NEXTGEN FLAGSHIP ANTIGRAVITY HOME DASHBOARD (v2.0)
 *
 * Designed with:
 * - Ultra-clear hierarchy, full descriptive titles, and zero text truncation.
 * - Hardware Double-Bezel nested containers with ambient glowing spatial depth.
 * - Spacious Hero Telemetry Dashboard displaying complete records breakdown.
 * - Categorized Asymmetrical Bento Cards with purpose descriptions.
 * - Detached Floating Glass Island Command & Navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextGenHomeScreen(
    onNavigate: (String) -> Unit,
    onLock: () -> Unit,
    onToggleTheme: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = VaultPaletteController.current
    val calendar = Calendar.getInstance()
    val greeting = greetingForHour(calendar.get(Calendar.HOUR_OF_DAY))
    val today = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
    var quickAddOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val searching = state.query.isNotBlank()

    val totalItems = state.counts.passwords + state.counts.totps + state.counts.cards +
        state.counts.notes + state.counts.contacts + state.counts.files
    val cryptoCount = state.categoryCounts["CRYPTO"] ?: 0

    val isLight = palette.isLight
    val ambientTop = if (isLight) palette.cyan.copy(alpha = 0.08f) else palette.cyan.copy(alpha = 0.16f)
    val ambientMid = if (isLight) palette.indigo.copy(alpha = 0.05f) else palette.abyssElevated.copy(alpha = 0.45f)
    val bgBase = palette.abyssDark

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBase)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // -------------------------------------------------------------
            // 1. FLOATING SPATIAL COMMAND ISLAND (Top Bar)
            // -------------------------------------------------------------
            NextGenCommandIsland(
                greeting = greeting,
                today = today,
                syncRunning = state.syncRunning,
                onToggleTheme = onToggleTheme,
                onSyncNow = viewModel::syncNow,
                onLock = onLock,
                onOpenSettings = { onNavigate(Routes.SETTINGS) }
            )

            // -------------------------------------------------------------
            // 2. SEARCH BAR
            // -------------------------------------------------------------
            NextGenSearchCapsule(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                onClear = { viewModel.setQuery("") },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            // -------------------------------------------------------------
            // 3. MAIN DASHBOARD CONTENT
            // -------------------------------------------------------------
            if (searching) {
                SearchResultsPane(
                    state = state,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                    onOpen = { hit ->
                        onNavigate(
                            when (hit.kind) {
                                SearchKind.PASSWORD -> Routes.entryDetail(hit.id)
                                SearchKind.CARD -> Routes.cardEditor(hit.id)
                                SearchKind.NOTE -> Routes.noteEditor(hit.id)
                                SearchKind.CONTACT -> Routes.contactEditor(hit.id)
                                SearchKind.TOTP -> Routes.totpEditor(hit.id)
                            }
                        )
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 110.dp)
                ) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // -------------------------------------------------------------
                    // A. HERO TELEMETRY DASHBOARD
                    // -------------------------------------------------------------
                    NextGenNexusHeroCard(
                        totalCount = totalItems,
                        securityScore = state.report.score,
                        cloudEmail = state.cloudEmail,
                        passwordsCount = state.counts.passwords,
                        twoFaCount = state.counts.totps,
                        cardsCount = state.counts.cards,
                        cryptoCount = cryptoCount,
                        onQuickAdd = { quickAddOpen = true },
                        onQuickScan = { onNavigate(Routes.SCAN_HUB) },
                        onSyncNow = viewModel::syncNow
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // -------------------------------------------------------------
                    // B. CATEGORY 1: PRIMARY CREDENTIALS
                    // -------------------------------------------------------------
                    NextGenSectionHeader(
                        title = "PRIMARY CREDENTIALS",
                        subtitle = "Store and autofill passwords, logins and 2FA tokens",
                        badge = "ZERO-KNOWLEDGE"
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    NextGenWideCard(
                        title = "Passwords & Logins",
                        description = "Web credentials, master passkeys and vault logins",
                        countText = "${state.counts.passwords} Passwords",
                        badgeText = if (state.report.weakCount > 0) "${state.report.weakCount} Weak" else "All Strong",
                        badgeIsWarning = state.report.weakCount > 0,
                        module = VaultModule.PASSWORDS,
                        onClick = { onNavigate(Routes.vault("PASSWORDS")) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    NextGenWideCard(
                        title = "2FA Authenticator",
                        description = "Time-based one-time passwords with 30s rotating counter",
                        countText = "${state.counts.totps} Active Tokens",
                        badgeText = "TOTP Live",
                        badgeIsWarning = false,
                        module = VaultModule.TWO_FA,
                        onClick = { onNavigate(Routes.AUTH) }
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // -------------------------------------------------------------
                    // C. CATEGORY 2: FINANCIAL ASSETS
                    // -------------------------------------------------------------
                    NextGenSectionHeader(
                        title = "FINANCIAL ASSETS",
                        subtitle = "Secured debit cards, credit cards and crypto keys",
                        badge = "ENCRYPTED"
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenBentoTile(
                            title = "Payment Cards",
                            description = "Credit & debit cards with CVV",
                            countText = "${state.counts.cards} Cards",
                            module = VaultModule.CARDS,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.WALLET) }
                        )

                        NextGenBentoTile(
                            title = "Crypto Wallets",
                            description = "Multi-chain keys and seed phrases",
                            countText = "$cryptoCount Wallets",
                            module = VaultModule.CRYPTO,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.CRYPTO) }
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // -------------------------------------------------------------
                    // D. CATEGORY 3: PRIVATE VAULT & MEDIA
                    // -------------------------------------------------------------
                    NextGenSectionHeader(
                        title = "PRIVATE VAULT & MEDIA",
                        subtitle = "Confidential notes, attachments and secret photos",
                        badge = "ISOLATED"
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenBentoTile(
                            title = "Secure Notes",
                            description = "Encrypted markdown & memos",
                            countText = "${state.counts.notes} Notes",
                            module = VaultModule.NOTES,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.vault("NOTES")) }
                        )

                        NextGenBentoTile(
                            title = "Secret Image",
                            description = "Steganography hidden media",
                            countText = "Hidden Media",
                            module = VaultModule.STEGO,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.STEGO) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenBentoTile(
                            title = "File Vault",
                            description = "Private PDFs, docs & files",
                            countText = "${state.counts.files} Files",
                            module = VaultModule.FILES,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.FILES) }
                        )

                        NextGenBentoTile(
                            title = "Secure Contacts",
                            description = "Private directory & numbers",
                            countText = "${state.counts.contacts} Contacts",
                            module = VaultModule.CONTACTS,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.CONTACTS) }
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // -------------------------------------------------------------
                    // E. CATEGORY 4: DEFENSE & SYSTEM TOOLS
                    // -------------------------------------------------------------
                    NextGenSectionHeader(
                        title = "DEFENSE & SYSTEM TOOLS",
                        subtitle = "Security audits, backups, cloud sync and diagnostics",
                        badge = "SYSTEM"
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenBentoTile(
                            title = "Security Health",
                            description = "Vulnerability & breach scanner",
                            countText = "${state.report.score}% Rating",
                            module = VaultModule.HEALTH,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.HEALTH) }
                        )

                        NextGenBentoTile(
                            title = "Cloud Sync",
                            description = "Google Drive & realtime replication",
                            countText = if (state.cloudEmail.isNotBlank()) "Connected" else "Offline",
                            module = VaultModule.SYNC,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.SYNC_CENTER) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenBentoTile(
                            title = "Offline Backup",
                            description = "Air-gapped JSON and emergency recovery",
                            countText = "Encrypted",
                            module = VaultModule.BACKUP,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.BACKUP) }
                        )

                        NextGenBentoTile(
                            title = "Power Tools",
                            description = "Passphrase generator & shredder",
                            countText = "Available",
                            module = VaultModule.TOOLS,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.SECURITY_TOOLS) }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // -------------------------------------------------------------
        // QUICK ADD MODAL BOTTOM SHEET
        // -------------------------------------------------------------
        if (quickAddOpen) {
            ModalBottomSheet(
                onDismissRequest = { quickAddOpen = false },
                sheetState = sheetState,
                containerColor = palette.abyssSurface,
                contentColor = palette.textPrimary
            ) {
                NextGenQuickAddSheet(
                    onDismiss = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            quickAddOpen = false
                        }
                    },
                    onSelect = { route ->
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            quickAddOpen = false
                            onNavigate(route)
                        }
                    }
                )
            }
        }
    }
}

// =====================================================================
// COMPONENT 1: COMMAND ISLAND (Top Bar with Zero Truncation)
// =====================================================================
@Composable
private fun NextGenCommandIsland(
    greeting: String,
    today: String,
    syncRunning: Boolean,
    onToggleTheme: () -> Unit,
    onSyncNow: () -> Unit,
    onLock: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // Upper status line: Brand Identity on Left, Circular Action Buttons on Right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(palette.mint, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SECURE VAULT PRO 2.0",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        color = palette.cyan
                    )
                )
            }

            // Glass Action Cluster
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NextGenCircleGlassButton(
                    onClick = onToggleTheme,
                    iconGlyph = if (isLight) Glyph.lightMode else Glyph.darkMode,
                    contentDescription = "Toggle Theme"
                )

                val syncRotation = rememberInfiniteTransition(label = "syncSpin")
                val angle by syncRotation.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "syncAngle"
                )

                NextGenCircleGlassButton(
                    onClick = onSyncNow,
                    iconGlyph = Glyph.sync,
                    contentDescription = "Sync Cloud",
                    rotation = if (syncRunning) angle else 0f,
                    tint = if (syncRunning) palette.cyan else palette.textPrimary
                )

                NextGenCircleGlassButton(
                    onClick = onLock,
                    iconGlyph = Glyph.lock,
                    contentDescription = "Lock Vault"
                )

                NextGenCircleGlassButton(
                    onClick = onOpenSettings,
                    iconGlyph = Glyph.settings,
                    contentDescription = "Settings"
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Lower Greeting & Date: Full Width so it NEVER truncates!
        Text(
            text = greeting,
            style = TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                color = palette.textPrimary
            )
        )
        Text(
            text = today,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textMuted
            )
        )
    }
}

@Composable
private fun NextGenCircleGlassButton(
    onClick: () -> Unit,
    iconGlyph: Glyph,
    contentDescription: String,
    rotation: Float = 0f,
    tint: Color? = null
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, label = "pressScale")

    val bg = if (isLight) palette.abyssSurface.copy(alpha = 0.95f) else palette.abyssSurface.copy(alpha = 0.85f)
    val borderCol = if (isLight) palette.abyssElevated.copy(alpha = 0.45f) else palette.abyssElevated.copy(alpha = 0.6f)
    val iconTint = tint ?: palette.textPrimary

    Box(
        modifier = Modifier
            .size(38.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, borderCol, CircleShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            glyph = iconGlyph,
            contentDescription = contentDescription,
            tint = iconTint,
            size = 18.dp,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

// =====================================================================
// COMPONENT 2: SEARCH CAPSULE
// =====================================================================
@Composable
private fun NextGenSearchCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(if (palette.isLight) 0.dp else 6.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(palette.abyssSurface.copy(alpha = 0.9f))
            .border(1.dp, palette.abyssElevated, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = Glyph.search,
            contentDescription = "Search",
            tint = palette.cyan,
            size = 20.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search credentials, cards, notes, files...",
                    style = TextStyle(fontSize = 14.sp, color = palette.textMuted)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(palette.cyan),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(palette.abyssElevated)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.close,
                    contentDescription = "Clear",
                    tint = palette.textPrimary,
                    size = 14.dp
                )
            }
        }
    }
}

// =====================================================================
// COMPONENT 3: NEXUS HERO TELEMETRY CARD (Spacious & Clean)
// =====================================================================
@Composable
private fun NextGenNexusHeroCard(
    totalCount: Int,
    securityScore: Int,
    cloudEmail: String,
    passwordsCount: Int,
    twoFaCount: Int,
    cardsCount: Int,
    cryptoCount: Int,
    onQuickAdd: () -> Unit,
    onQuickScan: () -> Unit,
    onSyncNow: () -> Unit
) {
    val palette = VaultPaletteController.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (palette.isLight) 0.dp else 16.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.abyssSurface,
                        palette.abyssElevated.copy(alpha = 0.7f),
                        palette.abyssSurface
                    )
                )
            )
            .border(
                1.5.dp,
                Brush.linearGradient(
                    listOf(
                        palette.cyan.copy(alpha = 0.45f),
                        palette.indigo.copy(alpha = 0.25f),
                        palette.abyssElevated
                    )
                ),
                RoundedCornerShape(28.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            // Card Header: Telemetry indicator on Left, Cloud Status on Right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(palette.mint, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VAULT TELEMETRY",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            color = palette.cyan
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.mint.copy(alpha = 0.14f))
                        .border(1.dp, palette.mint.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.cloudDone,
                        contentDescription = "Cloud",
                        tint = palette.mint,
                        size = 12.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (cloudEmail.isNotBlank()) "Google Drive Active" else "End-to-End Encrypted",
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.mint
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Main Total Counter with Security Health Rating
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "$totalCount",
                        style = TextStyle(
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1).sp,
                            color = palette.textPrimary
                        )
                    )
                    Text(
                        text = "TOTAL ENCRYPTED RECORDS",
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = palette.textMuted
                        )
                    )
                }

                // Security Health Score Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.abyssDark.copy(alpha = 0.7f))
                        .border(1.dp, palette.abyssElevated, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$securityScore%",
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = if (securityScore >= 80) palette.mint else if (securityScore >= 50) palette.amber else palette.coral
                            )
                        )
                        Text(
                            text = "SECURITY",
                            style = TextStyle(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textMuted
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 4 Clear Stats Cards (Spacious 2x2 Grid - NEVER CLIPPED)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NextGenTelemetryStatCard(
                    title = "Passwords",
                    count = "$passwordsCount",
                    color = palette.cyan,
                    modifier = Modifier.weight(1f)
                )
                NextGenTelemetryStatCard(
                    title = "2FA Tokens",
                    count = "$twoFaCount",
                    color = palette.mint,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NextGenTelemetryStatCard(
                    title = "Payment Cards",
                    count = "$cardsCount",
                    color = palette.indigo,
                    modifier = Modifier.weight(1f)
                )
                NextGenTelemetryStatCard(
                    title = "Crypto Wallets",
                    count = "$cryptoCount",
                    color = palette.amber,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons (Evenly aligned, perfectly balanced 3-column action bar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NextGenPillButton(
                    text = "New Record",
                    icon = Glyph.add,
                    isPrimary = true,
                    onClick = onQuickAdd,
                    modifier = Modifier.weight(1f)
                )

                NextGenPillButton(
                    text = "Scan QR",
                    icon = Glyph.cameraAlt,
                    isPrimary = false,
                    onClick = onQuickScan,
                    modifier = Modifier.weight(1f)
                )

                NextGenPillButton(
                    text = "Sync Now",
                    icon = Glyph.sync,
                    isPrimary = false,
                    onClick = onSyncNow,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NextGenTelemetryStatCard(
    title: String,
    count: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.abyssDark.copy(alpha = 0.6f))
            .border(1.dp, palette.abyssElevated.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = count,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            )
        )
    }
}

@Composable
private fun NextGenPillButton(
    text: String,
    icon: Glyph,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, label = "btnScale")

    val bgModifier = if (isPrimary) {
        Modifier.background(
            Brush.horizontalGradient(
                listOf(palette.cyan, palette.indigo)
            )
        )
    } else {
        Modifier
            .background(palette.abyssDark.copy(alpha = 0.75f))
            .border(1.dp, palette.abyssElevated, RoundedCornerShape(20.dp))
    }

    val contentColor = if (isPrimary) Color.White else palette.textPrimary

    Row(
        modifier = modifier
            .height(42.dp)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .then(bgModifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = contentColor,
            size = 15.dp
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = TextStyle(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =====================================================================
// COMPONENT 4: SECTION HEADERS & DETAILED CARDS
// =====================================================================
@Composable
private fun NextGenSectionHeader(
    title: String,
    subtitle: String,
    badge: String
) {
    val palette = VaultPaletteController.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                    color = palette.cyan
                )
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.cyan.copy(alpha = 0.12f))
                    .border(0.8.dp, palette.cyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badge,
                    style = TextStyle(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = palette.cyan
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = palette.textMuted
            )
        )
    }
}

@Composable
private fun NextGenWideCard(
    title: String,
    description: String,
    countText: String,
    badgeText: String,
    badgeIsWarning: Boolean,
    module: VaultModule,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, label = "wideScale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(if (palette.isLight) 0.dp else 10.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(palette.abyssSurface.copy(alpha = 0.92f))
            .border(1.2.dp, palette.abyssElevated, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandModuleIcon(
            module = module,
            size = 42.dp
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                val badgeBg = if (badgeIsWarning) palette.coral.copy(alpha = 0.16f) else palette.mint.copy(alpha = 0.16f)
                val badgeColor = if (badgeIsWarning) palette.coral else palette.mint
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = TextStyle(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = palette.textMuted
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = countText,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.cyan
                )
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        ThemedIcon(
            glyph = Glyph.chevronRight,
            contentDescription = null,
            tint = palette.textMuted.copy(alpha = 0.6f),
            size = 18.dp
        )
    }
}

@Composable
private fun NextGenBentoTile(
    title: String,
    description: String,
    countText: String,
    module: VaultModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "tileScale")

    Column(
        modifier = modifier
            .scale(scale)
            .shadow(if (palette.isLight) 0.dp else 8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(palette.abyssSurface.copy(alpha = 0.9f))
            .border(1.dp, palette.abyssElevated, RoundedCornerShape(18.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandModuleIcon(
                module = module,
                size = 32.dp
            )
            Text(
                text = countText,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.cyan
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = title,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = description,
            style = TextStyle(
                fontSize = 10.sp,
                color = palette.textMuted,
                lineHeight = 13.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =====================================================================
// COMPONENT 5: QUICK ADD BOTTOM SHEET
// =====================================================================
@Composable
private fun NextGenQuickAddSheet(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val palette = VaultPaletteController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp)
    ) {
        Text(
            text = "Create New Record",
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = palette.textPrimary
            )
        )
        Text(
            text = "Select vault record type to securely encrypt",
            style = TextStyle(
                fontSize = 13.sp,
                color = palette.textMuted
            )
        )

        Spacer(modifier = Modifier.height(18.dp))

        val options = listOf(
            Triple("New Password", Routes.entryEditor(), VaultModule.PASSWORDS),
            Triple("New 2FA Token", Routes.totpEditor(), VaultModule.TWO_FA),
            Triple("New Payment Card", Routes.cardEditor(), VaultModule.CARDS),
            Triple("New Crypto Wallet", Routes.CRYPTO, VaultModule.CRYPTO),
            Triple("New Secure Note", Routes.noteEditor(), VaultModule.NOTES),
            Triple("New Contact", Routes.contactEditor(), VaultModule.CONTACTS),
            Triple("Secret Image", Routes.STEGO, VaultModule.STEGO)
        )

        options.forEach { (title, route, module) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.abyssDark.copy(alpha = 0.5f))
                    .border(1.dp, palette.abyssElevated, RoundedCornerShape(14.dp))
                    .clickable { onSelect(route) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandModuleIcon(module = module, size = 26.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
                ThemedIcon(
                    glyph = Glyph.chevronRight,
                    contentDescription = null,
                    tint = palette.textMuted,
                    size = 14.dp
                )
            }
        }
    }
}
