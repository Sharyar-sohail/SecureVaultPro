package com.securevault.pro.presentation.screens.home

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
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
import com.securevault.pro.presentation.theme.VaultPalette
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
    val importNotice by viewModel.importNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

    val layoutModeKey = "home_layout_style"
    val prefs = remember { context.getSharedPreferences("home_prefs", Context.MODE_PRIVATE) }
    var layoutStyle by rememberSaveable {
        val saved = prefs.getString(layoutModeKey, HomeLayoutStyle.BENTO.name)
        mutableStateOf(runCatching { HomeLayoutStyle.valueOf(saved ?: "") }.getOrDefault(HomeLayoutStyle.BENTO))
    }

    val isLight = palette.isLight
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
                        .padding(bottom = 12.dp)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // -------------------------------------------------------------
                    // A. INFOGRAPHIC TELEMETRY DASHBOARD (Compact & Data-Dense)
                    // -------------------------------------------------------------
                    NextGenInfographicTelemetryCard(
                        totalCount = totalItems,
                        securityScore = state.report.score,
                        cloudEmail = state.cloudEmail,
                        passwordsCount = state.counts.passwords,
                        twoFaCount = state.counts.totps,
                        cardsCount = state.counts.cards,
                        cryptoCount = cryptoCount,
                        notesCount = state.counts.notes,
                        contactsCount = state.counts.contacts,
                        filesCount = state.counts.files
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // -------------------------------------------------------------
                    // B. LAYOUT SWITCHER (Bento | Stream | Compact)
                    // -------------------------------------------------------------
                    NextGenLayoutSwitcher(
                        currentStyle = layoutStyle,
                        onStyleSelected = { newStyle ->
                            layoutStyle = newStyle
                            prefs.edit().putString(layoutModeKey, newStyle.name).apply()
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // -------------------------------------------------------------
                    // C. SELECTED DYNAMIC VAULT MODULES LAYOUT
                    // -------------------------------------------------------------
                    when (layoutStyle) {
                        HomeLayoutStyle.BENTO -> NextGenBentoLayout(
                            state = state,
                            cryptoCount = cryptoCount,
                            palette = palette,
                            onNavigate = onNavigate
                        )
                        HomeLayoutStyle.STREAM -> NextGenStreamLayout(
                            state = state,
                            cryptoCount = cryptoCount,
                            palette = palette,
                            onNavigate = onNavigate
                        )
                        HomeLayoutStyle.COMPACT -> NextGenCompactLayout(
                            state = state,
                            cryptoCount = cryptoCount,
                            palette = palette,
                            onNavigate = onNavigate
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
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

        importNotice?.let { msg ->
            val isSuccess = msg.contains("Successfully")
            AlertDialog(
                onDismissRequest = viewModel::clearImportNotice,
                containerColor = palette.abyssSurface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = if (isSuccess) Glyph.checkCircle else Glyph.warning,
                            contentDescription = null,
                            tint = if (isSuccess) palette.mint else palette.coral,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isSuccess) "CSV Import Successful" else "Import Error",
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        )
                    }
                },
                text = {
                    Text(text = msg, style = TextStyle(fontSize = 14.sp, color = palette.textSecondary))
                },
                confirmButton = {
                    TextButton(onClick = viewModel::clearImportNotice) {
                        Text("OK", color = palette.cyan, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // FLOATING '+' ACTION BUTTON (Quick Add / QR Scanner - Placed at bottom right near nav bar)
        FloatingActionButton(
            onClick = { quickAddOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 14.dp),
            containerColor = palette.cyan,
            contentColor = if (palette.isLight) Color.White else palette.abyssDark,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
        ) {
            ThemedIcon(
                glyph = Glyph.add,
                contentDescription = "Add Item",
                tint = if (palette.isLight) Color.White else palette.abyssDark,
                size = 28.dp
            )
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
        // Upper status line: Brand Identity on Left, Exactly 4 Circular Action Buttons on Right
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

            // Exactly 4 buttons: Theme, Sync, Lock, Settings — PERFECT VISIBILITY & ACCESSIBILITY
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

        Spacer(modifier = Modifier.height(4.dp))

        // Lower Greeting & Date: Compact Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = greeting,
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    color = palette.textPrimary
                )
            )
            Text(
                text = today,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textMuted
                )
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
    tint: Color? = null
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, label = "pressScale")

    val bg = if (isLight) Color(0xFFF1F5F9) else palette.abyssElevated
    val borderCol = if (isLight) Color(0xFFCBD5E1) else Color.White.copy(alpha = 0.12f)
    val iconTint = tint ?: (if (isLight) Color(0xFF1E293B) else palette.textPrimary)

    Box(
        modifier = Modifier
            .size(36.dp)
            .scale(scale)
            .shadow(if (isLight) 1.dp else 0.dp, CircleShape)
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
            .shadow(if (palette.isLight) 2.dp else 0.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(if (palette.isLight) Color(0xFFF8FAFC) else palette.abyssSurface.copy(alpha = 0.95f))
            .border(1.dp, if (palette.isLight) Color(0xFFE2E8F0) else palette.abyssElevated, RoundedCornerShape(24.dp))
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
// COMPONENT 3: INFOGRAPHIC TELEMETRY CARD (Precision & Compact)
// =====================================================================
@Composable
private fun NextGenInfographicTelemetryCard(
    totalCount: Int,
    securityScore: Int,
    cloudEmail: String,
    passwordsCount: Int,
    twoFaCount: Int,
    cardsCount: Int,
    cryptoCount: Int,
    notesCount: Int,
    contactsCount: Int,
    filesCount: Int
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight

    val slices = remember(passwordsCount, twoFaCount, cardsCount, cryptoCount, notesCount, contactsCount, filesCount) {
        listOf(
            passwordsCount.toFloat() to palette.cyan,
            twoFaCount.toFloat() to palette.mint,
            cardsCount.toFloat() to palette.indigo,
            cryptoCount.toFloat() to palette.amber,
            contactsCount.toFloat() to palette.coral,
            notesCount.toFloat() to Color(0xFF8B5CF6),
            filesCount.toFloat() to Color(0xFF0EA5E9)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (isLight) 2.dp else 0.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(palette.abyssSurface)
            .border(
                1.dp,
                if (isLight) Color(0xFFE2E8F0) else palette.cyan.copy(alpha = 0.25f),
                RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Infographic Radial Donut Gauge
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                NextGenVaultDonutGauge(
                    slices = slices,
                    modifier = Modifier.fillMaxSize()
                )
                ThemedIcon(
                    glyph = Glyph.shield,
                    contentDescription = null,
                    tint = palette.cyan,
                    size = 18.dp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2. Metrics & Segmented Distribution Bar
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$totalCount",
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp,
                                color = palette.textPrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ENCRYPTED RECORDS",
                            style = TextStyle(
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = palette.textMuted
                            ),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    // Security Rating Badge
                    val ratingColor = if (securityScore >= 80) palette.mint else if (securityScore >= 50) palette.amber else palette.coral
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isLight) Color(0xFFF1F5F9) else palette.abyssElevated)
                            .border(1.dp, ratingColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(5.dp).background(ratingColor, CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$securityScore% SECURE",
                            style = TextStyle(
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = ratingColor
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Segmented Infographic Bar
                val sumTotal = slices.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isLight) Color(0xFFE2E8F0) else palette.abyssElevated)
                ) {
                    if (totalCount == 0) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight().background(palette.cyan.copy(alpha = 0.25f)))
                    } else {
                        slices.forEach { (count, color) ->
                            if (count > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(count / sumTotal)
                                        .fillMaxHeight()
                                        .background(color)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(5.dp))

                // Mini Legend Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InfographicLegendDot(color = palette.cyan, label = "Pass: $passwordsCount")
                        InfographicLegendDot(color = palette.mint, label = "2FA: $twoFaCount")
                        if (contactsCount > 0) {
                            InfographicLegendDot(color = palette.coral, label = "Cont: $contactsCount")
                        } else if (cardsCount > 0) {
                            InfographicLegendDot(color = palette.indigo, label = "Cards: $cardsCount")
                        } else {
                            InfographicLegendDot(color = palette.amber, label = "Crypto: $cryptoCount")
                        }
                    }

                    // Cloud indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(if (cloudEmail.isNotBlank()) palette.mint else palette.cyan, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (cloudEmail.isNotBlank()) "Drive" else "Vault",
                            style = TextStyle(
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (cloudEmail.isNotBlank()) palette.mint else palette.cyan
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfographicLegendDot(color: Color, label: String) {
    val palette = VaultPaletteController.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(5.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textSecondary
            )
        )
    }
}

@Composable
private fun NextGenVaultDonutGauge(
    slices: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val total = slices.sumOf { it.first.toDouble() }.toFloat()
        val stroke = 5.dp.toPx()
        val radius = (size.minDimension - stroke) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        if (total <= 0f) {
            drawCircle(
                color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                radius = radius,
                center = center,
                style = Stroke(width = stroke)
            )
        } else {
            var currentAngle = -90f
            slices.forEach { (value, color) ->
                if (value > 0f) {
                    val sweep = (value / total) * 360f
                    drawArc(
                        color = color,
                        startAngle = currentAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    currentAngle += sweep
                }
            }
        }
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

enum class HomeLayoutStyle(val label: String, val icon: Glyph) {
    BENTO("Bento", Glyph.gridOn),
    STREAM("Stream", Glyph.notes),
    COMPACT("Compact", Glyph.bolt)
}

@Composable
private fun NextGenLayoutSwitcher(
    currentStyle: HomeLayoutStyle,
    onStyleSelected: (HomeLayoutStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isLight) Color(0xFFE2E8F0) else palette.abyssSurface)
            .border(1.dp, if (isLight) Color(0xFFCBD5E1) else palette.cyan.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HomeLayoutStyle.entries.forEach { style ->
            val isSelected = currentStyle == style
            val bg = if (isSelected) {
                if (isLight) Color.White else palette.abyssElevated
            } else Color.Transparent
            val border = if (isSelected) {
                palette.cyan.copy(alpha = 0.5f)
            } else Color.Transparent
            val tint = if (isSelected) palette.cyan else palette.textMuted

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .then(if (isSelected) Modifier.border(1.dp, border, RoundedCornerShape(11.dp)) else Modifier)
                    .clickable { onStyleSelected(style) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemedIcon(
                    glyph = style.icon,
                    contentDescription = style.label,
                    tint = tint,
                    size = 14.dp
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = style.label,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) palette.textPrimary else palette.textMuted
                    )
                )
            }
        }
    }
}

@Composable
private fun NextGenBentoLayout(
    state: HomeUiState,
    cryptoCount: Int,
    palette: VaultPalette,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NextGenSectionHeader(
            title = "VAULT MODULES",
            subtitle = "Zero-knowledge encrypted categories",
            badge = "LIVE"
        )
        Spacer(modifier = Modifier.height(2.dp))

        // Row 1: Passwords & 2FA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NextGenBentoTile(
                title = "Passwords",
                description = "Credentials & logins",
                countText = "${state.counts.passwords}",
                module = VaultModule.PASSWORDS,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.vault("ALL")) }
            )
            NextGenBentoTile(
                title = "2FA Tokens",
                description = "TOTP authenticator",
                countText = "${state.counts.totps}",
                module = VaultModule.TWO_FA,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.AUTH) }
            )
        }

        // Row 2: Cards & Crypto
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NextGenBentoTile(
                title = "Payment Cards",
                description = "Credit & debit cards",
                countText = "${state.counts.cards}",
                module = VaultModule.CARDS,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.WALLET) }
            )
            NextGenBentoTile(
                title = "Crypto Wallets",
                description = "Seed phrases & keys",
                countText = "$cryptoCount",
                module = VaultModule.CRYPTO,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.CRYPTO) }
            )
        }

        // Row 3: Notes & Stego
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NextGenBentoTile(
                title = "Secure Notes",
                description = "Encrypted memos",
                countText = "${state.counts.notes}",
                module = VaultModule.NOTES,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.NOTES) }
            )
            NextGenBentoTile(
                title = "Secret Image",
                description = "Stego hidden media",
                countText = "Stego",
                module = VaultModule.STEGO,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.STEGO) }
            )
        }

        // Row 4: File Vault & Contacts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NextGenBentoTile(
                title = "File Vault",
                description = "Private documents",
                countText = "${state.counts.files}",
                module = VaultModule.FILES,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.FILES) }
            )
            NextGenBentoTile(
                title = "Secure Contacts",
                description = "Private address book",
                countText = "${state.counts.contacts}",
                module = VaultModule.CONTACTS,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.CONTACTS) }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        NextGenToolsStrip(palette = palette, onNavigate = onNavigate)
    }
}

@Composable
private fun NextGenStreamLayout(
    state: HomeUiState,
    cryptoCount: Int,
    palette: VaultPalette,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        NextGenSectionHeader(
            title = "SECURE STREAMS",
            subtitle = "Linear zero-knowledge encrypted vaults",
            badge = "STREAM"
        )
        Spacer(modifier = Modifier.height(2.dp))

        NextGenStreamCard(
            title = "Passwords & Logins",
            subtitle = "Websites, apps & system credentials",
            count = state.counts.passwords,
            icon = Glyph.key,
            accent = palette.amber,
            onClick = { onNavigate(Routes.vault("ALL")) }
        )
        NextGenStreamCard(
            title = "2FA Authenticator",
            subtitle = "Time-based OTP & security tokens",
            count = state.counts.totps,
            icon = Glyph.shield,
            accent = palette.cyan,
            onClick = { onNavigate(Routes.AUTH) }
        )
        NextGenStreamCard(
            title = "Payment Cards",
            subtitle = "Encrypted credit, debit & banking cards",
            count = state.counts.cards,
            icon = Glyph.creditCard,
            accent = palette.mint,
            onClick = { onNavigate(Routes.WALLET) }
        )
        NextGenStreamCard(
            title = "Crypto Wallets",
            subtitle = "Seed phrases, private keys & blockchain wallets",
            count = cryptoCount,
            icon = Glyph.currencyBitcoin,
            accent = Color(0xFFF59E0B),
            onClick = { onNavigate(Routes.CRYPTO) }
        )
        NextGenStreamCard(
            title = "Secure Notes",
            subtitle = "AES-256 sealed memos & sensitive documents",
            count = state.counts.notes,
            icon = Glyph.notes,
            accent = Color(0xFF8B5CF6),
            onClick = { onNavigate(Routes.NOTES) }
        )
        NextGenStreamCard(
            title = "Secret Image Steganography",
            subtitle = "Pixel-embedded hidden stego media",
            countText = "STEGO",
            icon = Glyph.image,
            accent = Color(0xFFEC4899),
            onClick = { onNavigate(Routes.STEGO) }
        )
        NextGenStreamCard(
            title = "Private File Vault",
            subtitle = "Hardware-sealed PDF, scans & secret documents",
            count = state.counts.files,
            icon = Glyph.folder,
            accent = Color(0xFF0EA5E9),
            onClick = { onNavigate(Routes.FILES) }
        )
        NextGenStreamCard(
            title = "Secure Contacts",
            subtitle = "Confidential directory & private address book",
            count = state.counts.contacts,
            icon = Glyph.contacts,
            accent = palette.coral,
            onClick = { onNavigate(Routes.CONTACTS) }
        )

        Spacer(modifier = Modifier.height(4.dp))
        NextGenToolsStrip(palette = palette, onNavigate = onNavigate)
    }
}

@Composable
private fun NextGenStreamCard(
    title: String,
    subtitle: String,
    count: Int? = null,
    countText: String? = null,
    icon: Glyph,
    accent: Color,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.98f else 1f, label = "streamScale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(if (isLight) 2.dp else 0.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(palette.abyssSurface)
            .border(1.dp, if (isLight) Color(0xFFE2E8F0) else palette.abyssElevated, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.06f))
                    )
                )
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            ThemedIcon(
                glyph = icon,
                contentDescription = title,
                tint = accent,
                size = 19.dp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = 10.5.sp,
                    color = palette.textMuted
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        val text = countText ?: "${count ?: 0}"
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.12f))
                .border(0.8.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        ThemedIcon(
            glyph = Glyph.chevronRight,
            contentDescription = null,
            tint = palette.textMuted.copy(alpha = 0.6f),
            size = 14.dp
        )
    }
}

@Composable
private fun NextGenCompactLayout(
    state: HomeUiState,
    cryptoCount: Int,
    palette: VaultPalette,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NextGenSectionHeader(
            title = "TACTICAL HUD MATRIX",
            subtitle = "Zero-scroll high-density cyber modules",
            badge = "HUD"
        )
        Spacer(modifier = Modifier.height(2.dp))

        // Row 1 (4 items)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NextGenTacticalTile(
                title = "Passwords",
                count = state.counts.passwords,
                icon = Glyph.key,
                accent = palette.amber,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.vault("ALL")) }
            )
            NextGenTacticalTile(
                title = "2FA Auth",
                count = state.counts.totps,
                icon = Glyph.shield,
                accent = palette.cyan,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.AUTH) }
            )
            NextGenTacticalTile(
                title = "Cards",
                count = state.counts.cards,
                icon = Glyph.creditCard,
                accent = palette.mint,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.WALLET) }
            )
            NextGenTacticalTile(
                title = "Crypto",
                count = cryptoCount,
                icon = Glyph.currencyBitcoin,
                accent = Color(0xFFF59E0B),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.CRYPTO) }
            )
        }

        // Row 2 (4 items)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NextGenTacticalTile(
                title = "Notes",
                count = state.counts.notes,
                icon = Glyph.notes,
                accent = Color(0xFF8B5CF6),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.NOTES) }
            )
            NextGenTacticalTile(
                title = "Stego",
                countText = "IMG",
                icon = Glyph.image,
                accent = Color(0xFFEC4899),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.STEGO) }
            )
            NextGenTacticalTile(
                title = "Files",
                count = state.counts.files,
                icon = Glyph.folder,
                accent = Color(0xFF0EA5E9),
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.FILES) }
            )
            NextGenTacticalTile(
                title = "Contacts",
                count = state.counts.contacts,
                icon = Glyph.contacts,
                accent = palette.coral,
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Routes.CONTACTS) }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        NextGenToolsStrip(palette = palette, onNavigate = onNavigate)
    }
}

@Composable
private fun NextGenTacticalTile(
    title: String,
    count: Int? = null,
    countText: String? = null,
    icon: Glyph,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, label = "tacticalScale")

    Column(
        modifier = modifier
            .scale(scale)
            .shadow(if (isLight) 2.dp else 0.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(palette.abyssSurface)
            .border(1.dp, if (isLight) Color(0xFFE2E8F0) else palette.abyssElevated, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f))
                .border(0.8.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            ThemedIcon(
                glyph = icon,
                contentDescription = title,
                tint = accent,
                size = 18.dp
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = title,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = countText ?: "${count ?: 0}",
            style = TextStyle(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun NextGenToolsStrip(
    palette: VaultPalette,
    onNavigate: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NextGenToolPill(
            title = "Security",
            icon = Glyph.shield,
            accent = palette.mint,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(Routes.HEALTH) }
        )
        NextGenToolPill(
            title = "Cloud Sync",
            icon = Glyph.cloud,
            accent = palette.cyan,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(Routes.SYNC_CENTER) }
        )
        NextGenToolPill(
            title = "Backup",
            icon = Glyph.fileDownload,
            accent = palette.indigo,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(Routes.BACKUP) }
        )
        NextGenToolPill(
            title = "Tools",
            icon = Glyph.security,
            accent = palette.amber,
            modifier = Modifier.weight(1f),
            onClick = { onNavigate(Routes.SECURITY_TOOLS) }
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
    val isLight = palette.isLight
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "tileScale")

    Column(
        modifier = modifier
            .scale(scale)
            .shadow(if (isLight) 2.dp else 0.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(palette.abyssSurface)
            .border(1.dp, if (isLight) Color(0xFFE2E8F0) else palette.abyssElevated, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandModuleIcon(
                module = module,
                size = 28.dp
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.cyan.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = countText,
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.cyan
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = description,
            style = TextStyle(
                fontSize = 9.5.sp,
                color = palette.textMuted
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NextGenToolPill(
    title: String,
    icon: Glyph,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    val isLight = palette.isLight

    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.abyssSurface)
            .border(1.dp, if (isLight) Color(0xFFE2E8F0) else palette.abyssElevated, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = title,
            tint = accent,
            size = 14.dp
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary
            ),
            maxLines = 1,
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
            text = "Select vault record type",
            style = TextStyle(
                fontSize = 13.sp,
                color = palette.textMuted
            )
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Top Priority Action: Scan QR Code
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (palette.isLight) palette.abyssElevated else palette.abyssSurface)
                .border(1.2.dp, palette.cyan.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .clickable { onSelect(Routes.SCAN_HUB) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.cyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.qrCodeScanner,
                    contentDescription = "Scan QR",
                    tint = palette.cyan,
                    size = 20.dp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Scan QR Code",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.cyan
                    )
                )
                Text(
                    text = "Scan 2FA TOTP secret, Wi-Fi or text QR",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = palette.textSecondary
                    )
                )
            }
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = palette.cyan,
                size = 14.dp
            )
        }

        val options = listOf(
            Triple("New Password", Routes.entryEditor(), VaultModule.PASSWORDS),
            Triple("New 2FA Token", Routes.totpEditor(), VaultModule.TWO_FA),
            Triple("New Payment Card", Routes.cardEditor(), VaultModule.CARDS),
            Triple("New Crypto Wallet", Routes.CRYPTO, VaultModule.CRYPTO),
            Triple("New Secure Note", Routes.noteEditor(), VaultModule.NOTES),
            Triple("New Contact", Routes.contactEditor(), VaultModule.CONTACTS),
            Triple("Secret Image", Routes.STEGO, VaultModule.STEGO)
        )

        val rowBg = if (palette.isLight) palette.abyssElevated else palette.abyssSurface

        options.forEach { (title, route, module) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(rowBg)
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
