package com.securevault.pro.presentation.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultPaletteController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * NEXTGEN FLAGSHIP ANTIGRAVITY HOME DASHBOARD (v2.0)
 *
 * Engineered with:
 * - Weightless spatial layering and ambient radial illumination.
 * - Hardware "Double-Bezel" (Doppelrand) nested container architecture.
 * - Dynamic Nexus Telemetry Arc (Replacing rigid old donut chart).
 * - Categorized Asymmetrical Spatial Bento Grid (Replacing monotonous 2-column list).
 * - Floating Command Island with tactile micro-interactions.
 * - Seamless support for Hyperion Obsidian Glass and Aurora Iris.
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
    val today = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
    var quickAddOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val searching = state.query.isNotBlank()

    val totalItems = state.counts.passwords + state.counts.totps + state.counts.cards +
        state.counts.notes + state.counts.contacts + state.counts.files
    val cryptoCount = state.categoryCounts["CRYPTO"] ?: 0

    // Ambient radial lighting colors for NextGen depth
    val isLight = palette.isLight
    val ambientTop = if (isLight) palette.cyan.copy(alpha = 0.08f) else palette.cyan.copy(alpha = 0.15f)
    val ambientMid = if (isLight) palette.indigo.copy(alpha = 0.05f) else palette.abyssElevated.copy(alpha = 0.4f)
    val bgBase = palette.abyssDark

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBase)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ambientTop, Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.1f),
                        radius = size.width * 0.85f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ambientMid, Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.55f),
                        radius = size.width * 0.75f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 1. FLOATING SPATIAL COMMAND ISLAND (Top Bar)
            NextGenCommandIsland(
                greeting = greeting,
                today = today,
                syncRunning = state.syncRunning,
                onToggleTheme = onToggleTheme,
                onSyncNow = viewModel::syncNow,
                onLock = onLock,
                onOpenSettings = { onNavigate(Routes.SETTINGS) }
            )

            // 2. FLOATING SEARCH CAPSULE
            NextGenSearchCapsule(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                onClear = { viewModel.setQuery("") },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            // 3. MAIN SCROLLABLE CONTENT OR SEARCH RESULTS
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
                        .padding(bottom = 100.dp)
                ) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // A. NEXUS 3D SPATIAL TELEMETRY CARD (Hero)
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

                    Spacer(modifier = Modifier.height(24.dp))

                    // B. CATEGORIZED SPATIAL BENTO GRID
                    NextGenBentoSectionHeader(title = "SECURITY CORE", badge = "ZERO-KNOWLEDGE")
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenFeaturedCard(
                            title = "Passwords",
                            subtitle = "${state.counts.passwords} Credentials",
                            badgeText = if (state.report.weakCount > 0) "${state.report.weakCount} Weak" else "Protected",
                            badgeIsWarning = state.report.weakCount > 0,
                            module = VaultModule.PASSWORDS,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.vault("PASSWORDS")) }
                        )

                        NextGenFeaturedCard(
                            title = "2FA Authenticator",
                            subtitle = "${state.counts.totps} Live Tokens",
                            badgeText = "TOTP 30s",
                            badgeIsWarning = false,
                            module = VaultModule.TWO_FA,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.AUTH) }
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    NextGenBentoSectionHeader(title = "FINANCIAL ASSETS", badge = "ENCRYPTED")
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenFinCard(
                            title = "Payment Cards",
                            count = state.counts.cards,
                            accentPill = "EMV / CVV",
                            module = VaultModule.CARDS,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.WALLET) }
                        )

                        NextGenFinCard(
                            title = "Crypto Wallets",
                            count = cryptoCount,
                            accentPill = "Multi-Chain",
                            module = VaultModule.CRYPTO,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.CRYPTO) }
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    NextGenBentoSectionHeader(title = "VAULT RECORDS & MEDIA", badge = "ISOLATED")
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenCompactTile(
                            title = "Secure Notes",
                            count = state.counts.notes,
                            module = VaultModule.NOTES,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.vault("NOTES")) }
                        )
                        NextGenCompactTile(
                            title = "Secret Image",
                            count = 1,
                            tag = "Steganography",
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
                        NextGenCompactTile(
                            title = "Encrypted Files",
                            count = state.counts.files,
                            module = VaultModule.FILES,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.FILES) }
                        )
                        NextGenCompactTile(
                            title = "Private Contacts",
                            count = state.counts.contacts,
                            module = VaultModule.CONTACTS,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.CONTACTS) }
                        )
                    }

                    Spacer(modifier = Modifier.height(22.dp))
                    NextGenBentoSectionHeader(title = "DEFENSE & TOOLS", badge = "ACTIVE")
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NextGenCompactTile(
                            title = "Security Health",
                            count = state.report.score,
                            tag = "${state.report.score}% Health",
                            module = VaultModule.HEALTH,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.HEALTH) }
                        )
                        NextGenCompactTile(
                            title = "Cloud Sync Center",
                            count = 1,
                            tag = "Drive Sync",
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
                        NextGenCompactTile(
                            title = "Offline Backup",
                            count = 1,
                            tag = "Air-Gapped",
                            module = VaultModule.BACKUP,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.BACKUP) }
                        )
                        NextGenCompactTile(
                            title = "Power Tools",
                            count = 1,
                            tag = "Generators",
                            module = VaultModule.TOOLS,
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.SECURITY_TOOLS) }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // QUICK ADD MODAL BOTTOM SHEET
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

// -------------------------------------------------------------
// COMMAND ISLAND (Top Bar)
// -------------------------------------------------------------
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(palette.mint, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "SECURE VAULT PRO 2.0",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        color = palette.cyan
                    )
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = greeting,
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    color = palette.textPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = today,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textMuted
                )
            )
        }

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
                contentDescription = "Lock Vault",
                accentHalo = true
            )

            NextGenCircleGlassButton(
                onClick = onOpenSettings,
                iconGlyph = Glyph.settings,
                contentDescription = "Settings"
            )
        }
    }
}

@Composable
private fun NextGenCircleGlassButton(
    onClick: () -> Unit,
    iconGlyph: Glyph,
    contentDescription: String,
    rotation: Float = 0f,
    tint: Color? = null,
    accentHalo: Boolean = false
) {
    val palette = VaultPaletteController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, label = "pressScale")

    val bg = if (accentHalo) palette.cyan.copy(alpha = 0.16f) else palette.abyssSurface.copy(alpha = 0.85f)
    val borderCol = if (accentHalo) palette.cyan.copy(alpha = 0.45f) else palette.abyssElevated.copy(alpha = 0.6f)
    val iconTint = tint ?: if (accentHalo) palette.cyan else palette.textPrimary

    Box(
        modifier = Modifier
            .size(40.dp)
            .scale(scale)
            .shadow(4.dp, CircleShape)
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

// -------------------------------------------------------------
// SEARCH CAPSULE
// -------------------------------------------------------------
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
            .shadow(6.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(palette.abyssSurface.copy(alpha = 0.88f))
            .border(1.dp, palette.abyssElevated.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
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

// -------------------------------------------------------------
// NEXUS HERO CARD
// -------------------------------------------------------------
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
            .shadow(16.dp, RoundedCornerShape(28.dp))
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
                        palette.cyan.copy(alpha = 0.5f),
                        palette.indigo.copy(alpha = 0.25f),
                        palette.abyssElevated
                    )
                ),
                RoundedCornerShape(28.dp)
            )
            .padding(18.dp)
    ) {
        Column {
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
                    Spacer(modifier = Modifier.width(6.dp))
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
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.cloudDone,
                        contentDescription = "Cloud",
                        tint = palette.mint,
                        size = 12.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (cloudEmail.isNotBlank()) "Google Drive" else "Encrypted",
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.mint
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val animatedScore by animateFloatAsState(
                        targetValue = securityScore.coerceIn(0, 100).toFloat(),
                        animationSpec = spring(stiffness = 50f),
                        label = "scoreArc"
                    )
                    val arcColor = when {
                        securityScore >= 80 -> palette.mint
                        securityScore >= 50 -> palette.amber
                        else -> palette.coral
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 8.dp.toPx()
                        drawArc(
                            color = arcColor.copy(alpha = 0.15f),
                            startAngle = 135f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(arcColor.copy(alpha = 0.7f), arcColor)
                            ),
                            startAngle = 135f,
                            sweepAngle = 270f * (animatedScore / 100f),
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$totalCount",
                            style = TextStyle(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1).sp,
                                color = palette.textPrimary
                            )
                        )
                        Text(
                            text = "ITEMS",
                            style = TextStyle(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = palette.textMuted
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NextGenMiniTelemetryPill(
                            label = "Passwords",
                            count = "$passwordsCount",
                            color = palette.cyan,
                            modifier = Modifier.weight(1f)
                        )
                        NextGenMiniTelemetryPill(
                            label = "2FA Codes",
                            count = "$twoFaCount",
                            color = palette.mint,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NextGenMiniTelemetryPill(
                            label = "Cards",
                            count = "$cardsCount",
                            color = palette.indigo,
                            modifier = Modifier.weight(1f)
                        )
                        NextGenMiniTelemetryPill(
                            label = "Crypto",
                            count = "$cryptoCount",
                            color = palette.amber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NextGenPillButton(
                    text = "New Item",
                    icon = Glyph.add,
                    isPrimary = true,
                    onClick = onQuickAdd,
                    modifier = Modifier.weight(1.2f)
                )

                NextGenPillButton(
                    text = "Scan QR",
                    icon = Glyph.cameraAlt,
                    isPrimary = false,
                    onClick = onQuickScan,
                    modifier = Modifier.weight(1f)
                )

                NextGenPillButton(
                    text = "Sync",
                    icon = Glyph.sync,
                    isPrimary = false,
                    onClick = onSyncNow,
                    modifier = Modifier.weight(0.9f)
                )
            }
        }
    }
}

@Composable
private fun NextGenMiniTelemetryPill(
    label: String,
    count: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.abyssDark.copy(alpha = 0.65f))
            .border(1.dp, palette.abyssElevated.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = palette.textMuted,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }
        Text(
            text = count,
            style = TextStyle(
                fontSize = 12.sp,
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
            .height(40.dp)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .then(bgModifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = null,
            tint = contentColor,
            size = 15.dp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        )
    }
}

// -------------------------------------------------------------
// BENTO SECTION TILES
// -------------------------------------------------------------
@Composable
private fun NextGenBentoSectionHeader(title: String, badge: String) {
    val palette = VaultPaletteController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.6.sp,
                color = palette.textMuted
            )
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(palette.cyan.copy(alpha = 0.12f))
                .border(0.8.dp, palette.cyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
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
}

@Composable
private fun NextGenFeaturedCard(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeIsWarning: Boolean,
    module: VaultModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "featScale")

    Box(
        modifier = modifier
            .height(130.dp)
            .scale(scale)
            .shadow(10.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(palette.abyssSurface.copy(alpha = 0.92f))
            .border(1.2.dp, palette.abyssElevated, RoundedCornerShape(22.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                BrandModuleIcon(
                    module = module,
                    size = 36.dp
                )

                val badgeBg = if (badgeIsWarning) palette.coral.copy(alpha = 0.16f) else palette.mint.copy(alpha = 0.16f)
                val badgeColor = if (badgeIsWarning) palette.coral else palette.mint
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
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

            Column {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = palette.textMuted
                    )
                )
            }
        }
    }
}

@Composable
private fun NextGenFinCard(
    title: String,
    count: Int,
    accentPill: String,
    module: VaultModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "finScale")

    Box(
        modifier = modifier
            .height(115.dp)
            .scale(scale)
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.abyssSurface,
                        palette.abyssElevated.copy(alpha = 0.6f)
                    )
                )
            )
            .border(1.dp, palette.abyssElevated, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
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
                    text = "$count",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = palette.textPrimary
                    )
                )
            }

            Column {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = accentPill,
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.cyan
                    )
                )
            }
        }
    }
}

@Composable
private fun NextGenCompactTile(
    title: String,
    count: Int,
    tag: String? = null,
    module: VaultModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "compScale")

    Row(
        modifier = modifier
            .height(64.dp)
            .scale(scale)
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(palette.abyssSurface.copy(alpha = 0.85f))
            .border(1.dp, palette.abyssElevated.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandModuleIcon(
            module = module,
            size = 28.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = tag ?: "$count items",
                style = TextStyle(
                    fontSize = 10.sp,
                    color = palette.textMuted
                ),
                maxLines = 1
            )
        }
        ThemedIcon(
            glyph = Glyph.chevronRight,
            contentDescription = null,
            tint = palette.textMuted.copy(alpha = 0.5f),
            size = 14.dp
        )
    }
}

// -------------------------------------------------------------
// QUICK ADD BOTTOM SHEET
// -------------------------------------------------------------
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
