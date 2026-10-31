package com.securevault.pro.presentation.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.SearchHit
import com.securevault.pro.domain.model.SearchKind
import com.securevault.pro.domain.model.VaultEntry
import com.securevault.pro.presentation.components.BrandIconCatalog
import com.securevault.pro.presentation.components.BrandModuleIcon
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.MonogramBadge
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.PieLegendMarker
import com.securevault.pro.presentation.components.PieSlice
import com.securevault.pro.presentation.components.SectionHeader
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultModule
import com.securevault.pro.presentation.components.VaultPieChart
import com.securevault.pro.presentation.components.VaultSearchField
import com.securevault.pro.presentation.components.chartLegendSitsBeside
import com.securevault.pro.presentation.components.formatShortDate
import com.securevault.pro.presentation.components.greetingForHour
import com.securevault.pro.presentation.components.iconPlateShape
import com.securevault.pro.presentation.components.revealEntry
import com.securevault.pro.presentation.components.securityColor
import com.securevault.pro.presentation.components.selectedBed
import com.securevault.pro.presentation.components.strengthLabel
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.navigation.Routes
import com.securevault.pro.presentation.theme.BoardRhythm
import com.securevault.pro.presentation.theme.TileKind
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultContrast
import com.securevault.pro.presentation.theme.VaultLayout
import com.securevault.pro.presentation.theme.VaultMotion
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultType
import kotlinx.coroutines.delay
import com.securevault.pro.presentation.theme.boardColumns
import com.securevault.pro.presentation.theme.boardGrid
import com.securevault.pro.presentation.theme.themeSwitchEnter
import com.securevault.pro.presentation.theme.themeSwitchExit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* =====================================================================================
 * THE HOME DASHBOARD — ZERO-SCROLL BY CONSTRUCTION
 *
 * The owner's standing rule: the dashboard itself never scrolls. Scrolling belongs to
 * data lists (the vault, the notes, the chat archives, the search results pane below).
 * Everything the dashboard keeps — the greeting row, the search field, the vault
 * composition, the 4 x 3 module board — is distributed with weights and arrangements
 * inside one fixed Column, and blocks that cannot fit legibly are *dropped or compacted*,
 * never shrunk under ~11 sp text and never allowed to run off the screen.
 *
 * Height tiers, measured off the live pane (BoxWithConstraints.maxHeight):
 *   < 428 dp    hero + search + board only (composition hidden) — the grid still gets
 *               its rows; nothing clips, nothing scrolls.
 *   >= 540 dp   + the compact composition strip (58 dp dial, two-line count chips,
 *               the sync micro-animation).
 *   >= 660 dp   + the full Vault composition card (126 dp dial + tappable legend),
 *               the "All modules" header and the grammar chip.
 *   >= 780 dp   + the security stamp footer and the sync timestamp label.
 *   >= 980 dp   + the travel-mode card and the expiry reminders card (top three).
 *   >= 1120 dp  + the favorites preview (three rows) — tablets.
 *
 * The 360 x 640 dp phone sits at roughly 544 dp of pane once the status bar and the
 * dock are subtracted (the dock is reserved by the app-level Scaffold in VaultApp,
 * so this screen must not reserve it a second time the way the old LazyColumn did).
 * At that height the board gets ~302 dp — three rows of ~96 dp — and every tile's
 * intrinsic content (~88 dp: 34 icon + 13 label + 13 count band + 2 x 11 padding)
 * clears the row without clipping.
 * =====================================================================================
 */

/** The four fixed floor heights the tier ladder measures against. */
private val STRIP_MIN = 540.dp
private val FULL_COMPOSITION_MIN = 660.dp
private val FOOTER_MIN = 780.dp
private val RICH_CARDS_MIN = 980.dp
private val FAVORITES_MIN = 1120.dp

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onLock: () -> Unit,
    onToggleTheme: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var quickAdd by remember { mutableStateOf(false) }
    var syncSheet by remember { mutableStateOf(false) }
    val palette = VaultPaletteController.current
    val calendar = Calendar.getInstance()
    val greeting = greetingForHour(calendar.get(Calendar.HOUR_OF_DAY))
    val today = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
    val syncPhase = when {
        state.syncRunning -> SyncPhase.RUNNING
        state.syncError != null -> SyncPhase.ERROR
        state.syncPending -> SyncPhase.PENDING
        else -> SyncPhase.DONE
    }
    val syncLabel = syncTimeLabel(state.syncLastAt)

    // NeonBackground paints the theme's backdrop; the app-level Scaffold in VaultApp
    // has already subtracted the dock, so the dashboard only clears the status bar.
    NeonBackground {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            val paneHeight = maxHeight
            val showStrip = paneHeight >= STRIP_MIN
            val fullComposition = paneHeight >= FULL_COMPOSITION_MIN
            val roomy = paneHeight >= FOOTER_MIN
            val richCards = paneHeight >= RICH_CARDS_MIN
            val showFavorites = paneHeight >= FAVORITES_MIN
            val searching = state.query.isNotBlank()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = palette.screenGutter,
                        end = palette.screenGutter,
                        top = 6.dp,
                        bottom = 12.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeHeaderRow(
                    greeting = greeting,
                    today = today,
                    state = state,
                    showCount = fullComposition,
                    showEscapeChips = !richCards,
                    stripVisible = showStrip,
                    onNavigate = onNavigate,
                    onLock = onLock,
                    onToggleTheme = onToggleTheme,
                    onQuickAdd = { quickAdd = true }
                )

                VaultSearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    placeholder = "Search the vault…"
                )

                if (searching) {
                    // The results pane is a data list, so its scroll is the sanctioned kind.
                    SearchResultsPane(
                        state = state,
                        modifier = Modifier.weight(1f),
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
                    if (fullComposition) {
                        VaultCompositionCard(
                            state = state,
                            syncPhase = syncPhase,
                            syncLabel = if (roomy) syncLabel else null,
                            onNavigate = onNavigate,
                            onSync = {
                                syncSheet = true
                                viewModel.syncNow()
                            },
                            modifier = Modifier.revealEntry(1)
                        )
                    } else if (showStrip) {
                        CompactCompositionStrip(
                            state = state,
                            syncPhase = syncPhase,
                            onNavigate = onNavigate,
                            onSync = {
                                syncSheet = true
                                viewModel.syncNow()
                            },
                            modifier = Modifier.revealEntry(1)
                        )
                    }

                    if (richCards) {
                        if (state.travelMode) {
                            TravelModeCard(Modifier.revealEntry(2))
                        }
                        if (state.expiringSoon.isNotEmpty()) {
                            ExpiryRemindersCard(
                                modifier = Modifier.revealEntry(3),
                                entries = state.expiringSoon,
                                onOpen = { id -> onNavigate(Routes.entryDetail(id)) },
                                onSeeAll = { onNavigate(Routes.vault("IDENTITY")) }
                            )
                        }
                    }

                    if (fullComposition) {
                        BoardHeaderRow(layout = palette.layout)
                    }

                    // The board owns every remaining pixel — the grid always gets its rows.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        val boardEnter = themeSwitchEnter()
                        val boardExit = themeSwitchExit()
                        AnimatedContent(
                            targetState = palette.layout,
                            transitionSpec = { boardEnter togetherWith boardExit },
                            label = "homeBoard"
                        ) { layout ->
                            HomeModuleGrid(
                                state = state,
                                layout = layout,
                                showExpiryInHeader = fullComposition && !richCards,
                                onNavigate = onNavigate,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    if (roomy) {
                        SecurityStampRow()
                    }

                    if (showFavorites && state.favorites.isNotEmpty()) {
                        SectionHeader(
                            title = "Favorites",
                            actionLabel = "Vault",
                            onAction = { onNavigate(Routes.VAULT) }
                        )
                        // Three rows maximum: a preview, not a second vault list.
                        state.favorites.take(3).forEach { entry ->
                            HomeEntryRow(
                                entry = entry,
                                onOpen = { onNavigate(Routes.entryDetail(entry.id)) },
                                onToggleFavorite = { viewModel.toggleFavorite(entry.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (quickAdd) {
        QuickAddSheet(
            onDismiss = { quickAdd = false },
            onNavigate = { route ->
                quickAdd = false
                onNavigate(route)
            }
        )
    }

    if (syncSheet) {
        SyncNowSheet(
            state = state,
            onSync = viewModel::syncNow,
            onDismiss = { syncSheet = false }
        )
    }
}

// -----------------------------------------------------------------------------------------
// The sync sheet. The orb beside the pie chart is a button, and this is what it opens: the
// live state of the push (running / queued / failed / up to date), what is waiting to leave
// the phone, how many records of each kind the archive carries, and the last few attempts.
// It is a sheet on purpose — a sync is an overlay on the dashboard, never a new screen.
// -----------------------------------------------------------------------------------------
@Composable
private fun SyncNowSheet(
    state: HomeUiState,
    onSync: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = VaultPaletteController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val syncCounts = state.syncCounts
    val localTotal = state.counts
    // A finished push should not leave the owner staring at a sheet that is already up to date.
    var wasRunning by remember { mutableStateOf(state.syncRunning) }
    LaunchedEffect(state.syncRunning) {
        if (wasRunning && !state.syncRunning && state.syncError == null) {
            delay(1400)
            onDismiss()
        }
        wasRunning = state.syncRunning
    }
    val runningFill = rememberInfiniteTransition(label = "sync-fill").animateFloat(
        initialValue = 0.18f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sync-fill"
    )
    val status = when {
        state.syncRunning -> "Pushing now"
        state.syncError != null -> "Last attempt failed"
        state.syncPending -> "Changes queued"
        state.syncLastAt > 0L -> "Up to date"
        else -> "Not synced yet"
    }
    val detail = when {
        state.syncRunning -> "Encrypting the vault and writing the cloud archive."
        state.syncError != null -> state.syncError.orEmpty()
        state.syncPending -> "Your latest edits leave on the next push."
        state.syncLastAt > 0L -> "Last push ${syncTimeLabel(state.syncLastAt)}"
        else -> "Sign in to Google to mirror the vault to Drive."
    }
    val phaseColor = when {
        state.syncError != null -> VaultColor.debit
        state.syncRunning -> VaultColor.accent
        state.syncPending -> VaultColor.warning
        else -> VaultColor.credit
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        shape = palette.cardShape,
        dragHandle = { BottomSheetDefaults.DragHandle(color = palette.outlineColor) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedIcon(
                    glyph = Glyph.sync,
                    contentDescription = null,
                    tint = phaseColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Cloud sync",
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = phaseColor
                    )
                }
                GlassIconButton(
                    icon = Glyph.close,
                    contentDescription = "Close",
                    onClick = onDismiss,
                    tint = VaultColor.textBody
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
            Spacer(Modifier.height(12.dp))
            // One bar, three readings: moving while pushing, full when settled, partial when
            // edits are still queued. Decorative, so it never claims a percentage.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(VaultColor.textFaint.copy(alpha = 0.18f))
            ) {
                val fill = when {
                    state.syncRunning -> runningFill.value
                    state.syncError != null -> 0.35f
                    state.syncPending -> 0.55f
                    state.syncLastAt > 0L -> 1f
                    else -> 0.08f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fill)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(phaseColor)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "In this vault",
                style = VaultType.micro(),
                color = VaultColor.textFaint
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncCountCell("Passwords", localTotal.passwords, Modifier.weight(1f))
                SyncCountCell("2FA", localTotal.totps, Modifier.weight(1f))
                SyncCountCell("Cards", localTotal.cards, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncCountCell("Notes", localTotal.notes, Modifier.weight(1f))
                SyncCountCell("Contacts", localTotal.contacts, Modifier.weight(1f))
                SyncCountCell("Files", localTotal.files, Modifier.weight(1f))
            }
            if (syncCounts != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Last archive · ${syncCounts.total} records".trim(),
                    style = VaultType.micro(),
                    color = VaultColor.textFaint
                )
            }
            val recent = state.syncLog.take(4)
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Recent pushes",
                    style = VaultType.micro(),
                    color = VaultColor.textFaint
                )
                Spacer(Modifier.height(6.dp))
                recent.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (entry.success) VaultColor.credit else VaultColor.debit)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColor.textBody,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = syncTimeLabel(entry.at),
                            style = VaultType.micro(),
                            color = VaultColor.textFaint
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            GradientButton(
                text = if (state.syncRunning) "Syncing…" else "Sync now",
                icon = Glyph.sync,
                enabled = !state.syncRunning,
                loading = state.syncRunning,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSync
            )
        }
    }
}

@Composable
private fun SyncCountCell(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VaultColor.elevated)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColor.textStrong,
            maxLines = 1
        )
        Text(
            text = label,
            style = VaultType.micro(),
            color = VaultColor.textFaint,
            maxLines = 1
        )
    }
}

// -----------------------------------------------------------------------------------------
// The header — one compact row that every layout and every palette can restyle.
//
// The old screen composed five different heroes (the bento cell, the clay halo, the agentic
// intent rail, the adaptive hour-rule, the classic row). On a fixed viewport only one of
// them is survivable at 360 x 640, and the intent rail duplicated the board underneath it.
// The row below keeps what the variants actually carried: the greeting, the date, the
// sealed count, the travel/expiry escape hatches, and the four controls that must always
// be reachable — quick add, theme, lock, settings.
// -----------------------------------------------------------------------------------------
@Composable
private fun HomeHeaderRow(
    greeting: String,
    today: String,
    state: HomeUiState,
    showCount: Boolean,
    showEscapeChips: Boolean,
    stripVisible: Boolean,
    onNavigate: (String) -> Unit,
    onLock: () -> Unit,
    onToggleTheme: () -> Unit,
    onQuickAdd: () -> Unit
) {
    val palette = VaultPaletteController.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShieldPlate(size = 30.dp, glyph = 15.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = VaultColor.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (showCount) "$today · ${heroCount(state)}" else today,
                style = VaultType.micro(),
                color = VaultColor.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showEscapeChips) {
            if (state.travelMode) {
                TagChip("TRAVEL", accent = VaultColor.warning)
                Spacer(Modifier.width(6.dp))
            }
            // Below the strip floor the expiry count keeps its escape hatch here.
            if (!stripVisible && state.expiringSoon.isNotEmpty()) {
                TagChip("${state.expiringSoon.size} DUE", accent = VaultColor.warning)
                Spacer(Modifier.width(6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassIconButton(
                icon = Glyph.add,
                contentDescription = "Quick add",
                onClick = onQuickAdd,
                tint = VaultColor.accent,
                size = 32.dp
            )
            GlassIconButton(
                icon = if (palette.isLight) Glyph.darkMode else Glyph.lightMode,
                contentDescription = if (palette.isLight) "Switch to dark mode" else "Switch to light mode",
                onClick = onToggleTheme,
                tint = VaultColor.warning,
                size = 32.dp
            )
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = VaultColor.textBody,
                size = 32.dp
            )
            GlassIconButton(
                icon = Glyph.settings,
                contentDescription = "Settings",
                onClick = { onNavigate(Routes.SETTINGS) },
                tint = VaultColor.textBody,
                size = 32.dp
            )
        }
    }
}

/** The vault's shield plate. */
@Composable
private fun ShieldPlate(size: Dp, glyph: Dp) {
    val palette = VaultPaletteController.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(iconPlateShape(palette, size))
            .background(VaultColor.accent.copy(alpha = 0.16f))
            .border(palette.cardBorderWidth, VaultColor.accent.copy(alpha = 0.3f), iconPlateShape(palette, size)),
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            glyph = Glyph.shield,
            contentDescription = null,
            // Guarded: the brand accent on its own tinted plate is the pair that can fall under AA.
            tint = VaultContrast.ensure(
                VaultColor.accent,
                VaultContrast.composite(VaultColor.accent.copy(alpha = 0.16f), VaultColor.surface)
            ),
            modifier = Modifier.size(glyph)
        )
    }
}

private fun heroCount(state: HomeUiState): String {
    val total = state.counts.passwords + state.counts.totps + state.counts.cards +
        state.counts.notes + state.counts.contacts + state.counts.files
    return when {
        total == 0 -> "Nothing sealed yet"
        total == 1 -> "1 item sealed"
        else -> "$total items sealed"
    }
}

private data class HomeModule(
    val module: VaultModule,
    val accent: Color,
    val route: String,
    val badge: Int = 0
) {
    val label: String get() = module.label
}

private data class CompositionSlice(
    val label: String,
    val value: Int,
    val color: Color,
    val route: String
)

/** The five vault-composition categories, inked from the theme's own module ramp. */
@Composable
private fun compositionCategories(state: HomeUiState): List<CompositionSlice> {
    val palette = VaultPaletteController.current
    return listOf(
        CompositionSlice(
            "Passwords", state.counts.passwords,
            BrandIconCatalog.accent(palette.id, VaultModule.PASSWORDS), Routes.VAULT
        ),
        CompositionSlice(
            "2FA", state.counts.totps,
            BrandIconCatalog.accent(palette.id, VaultModule.TWO_FA), Routes.AUTH
        ),
        CompositionSlice(
            "Vaults", state.counts.cards,
            BrandIconCatalog.accent(palette.id, VaultModule.CARDS), Routes.WALLET
        ),
        CompositionSlice(
            "Contacts", state.counts.contacts,
            BrandIconCatalog.accent(palette.id, VaultModule.CONTACTS), Routes.CONTACTS
        ),
        CompositionSlice(
            "Files", state.counts.files,
            BrandIconCatalog.accent(palette.id, VaultModule.FILES), Routes.FILES
        )
    )
}

// -----------------------------------------------------------------------------------------
// The compact composition strip — the pie chart, its counts and the sync micro-animation
// squeezed into ~94 dp so the board keeps its three rows at 360 x 640.
// -----------------------------------------------------------------------------------------
@Composable
private fun CompactCompositionStrip(
    state: HomeUiState,
    syncPhase: SyncPhase,
    onNavigate: (String) -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = compositionCategories(state)
    val slices = categories.map { PieSlice(it.label, it.value, it.color) }
    val total = categories.sumOf { it.value }
    var selected by rememberSaveable { mutableIntStateOf(-1) }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        accent = VaultColor.accent,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The dial is the glanceable form; the chips beside it carry the legible counts,
            // so the ring can stay small without any text dropping under the 11 sp floor.
            VaultPieChart(
                slices = slices,
                selectedIndex = selected,
                onSliceSelected = { selected = if (selected == it) -1 else it },
                diameter = 58.dp,
                strokeWidth = 10.dp,
                caption = null
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
            ) {
                // Five counts as chips in a 2 + 2 + 1 ladder; the last line shares its free
                // width with the expiry escape hatch and the running total, all at 11 sp+.
                listOf(listOf(0, 1), listOf(2, 3), listOf(4)).forEach { rowIndexes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowIndexes.forEach { index ->
                            val slice = categories[index]
                            CompositionChip(
                                slice = slice,
                                active = selected == index,
                                index = index,
                                onSelect = { selected = if (selected == it) -1 else it },
                                onOpen = onNavigate,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowIndexes.size == 1) {
                            if (state.expiringSoon.isNotEmpty()) {
                                TagChip("${state.expiringSoon.size} DUE", accent = VaultColor.warning)
                            }
                            Text(
                                text = if (total == 0) "nothing sealed" else "$total sealed",
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textBody,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            SyncOrb(phase = syncPhase, label = null, onClick = onSync)
        }
    }
}

@Composable
private fun CompositionChip(
    slice: CompositionSlice,
    active: Boolean,
    index: Int,
    onSelect: (Int) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    Row(
        modifier = modifier
            .clip(palette.chipShape)
            .selectedBed(
                selected = active,
                fillColor = slice.color.copy(alpha = 0.14f),
                ruleColor = slice.color.copy(alpha = 0.34f)
            )
            .clickable {
                onSelect(index)
                onOpen(slice.route)
            }
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PieLegendMarker(color = slice.color, size = 8.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            text = slice.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) VaultContrast.ensure(slice.color, palette.abyssSurface) else VaultColor.textBody,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = slice.value.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = VaultColor.textStrong,
            maxLines = 1
        )
    }
}

// -----------------------------------------------------------------------------------------
// The sync micro-animation: while a sync runs the upload arrow rises into the cloud; a
// pending queue pulses the cloud; a clean state rests on cloudDone; a failure shows the
// offline glyph in the warning role. Every colour is a theme role, so all 15 palettes
// restyle it and reduced motion only loses the tween, never the meaning.
// -----------------------------------------------------------------------------------------
private enum class SyncPhase { RUNNING, PENDING, DONE, ERROR }

@Composable
private fun SyncOrb(
    phase: SyncPhase,
    label: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val transition = rememberInfiniteTransition(label = "syncOrb")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "syncCycle"
    )
    val density = LocalDensity.current
    val risePx = with(density) { 14.dp.toPx() }

    Column(
        modifier = modifier.then(
            if (onClick != null) Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(4.dp)
            else Modifier
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(140))
            },
            label = "syncPhase"
        ) { target ->
            when (target) {
                SyncPhase.RUNNING -> Box(
                    modifier = Modifier.size(width = 26.dp, height = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.cloud,
                        contentDescription = "Syncing",
                        tint = VaultColor.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    ThemedIcon(
                        glyph = Glyph.arrowUpward,
                        contentDescription = null,
                        tint = VaultColor.secondary,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .size(11.dp)
                            .graphicsLayer {
                                // The arrow climbs into the cloud and fades as it arrives.
                                translationY = -cycle * risePx
                                alpha = 1f - cycle * 0.85f
                            }
                    )
                }
                SyncPhase.PENDING -> ThemedIcon(
                    glyph = Glyph.cloud,
                    contentDescription = "Sync pending",
                    tint = VaultColor.accent,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = 1f + 0.12f * cycle
                            scaleY = 1f + 0.12f * cycle
                            alpha = 0.65f + 0.35f * cycle
                        }
                )
                SyncPhase.DONE -> ThemedIcon(
                    glyph = Glyph.cloudDone,
                    contentDescription = "Synced",
                    tint = VaultColor.credit,
                    modifier = Modifier.size(20.dp)
                )
                SyncPhase.ERROR -> ThemedIcon(
                    glyph = Glyph.cloudOff,
                    contentDescription = "Sync problem",
                    tint = VaultColor.warning,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (!label.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 72.dp),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun syncTimeLabel(lastAt: Long): String = when {
    lastAt <= 0L -> "never"
    else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastAt))
}

// -----------------------------------------------------------------------------------------
// The full composition card — the established pie chart with its 126 dp dial, the tappable
// legend and the live total. Only drawn when the pane can hold it beside the board.
// -----------------------------------------------------------------------------------------
@Composable
private fun VaultCompositionCard(
    state: HomeUiState,
    syncPhase: SyncPhase,
    syncLabel: String?,
    onNavigate: (String) -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val categories = compositionCategories(state)
    val slices = categories.map { PieSlice(it.label, it.value, it.color) }
    val total = categories.sumOf { it.value }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        accent = palette.cyan,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Vault composition",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (total == 0) "Nothing sealed yet" else "$total live items across your vault",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SyncOrb(phase = syncPhase, label = syncLabel, onClick = onSync)
            Spacer(Modifier.width(10.dp))
            TagChip(
                text = if (selected >= 0) categories[selected].label.uppercase() else "LIVE",
                accent = if (selected >= 0) categories[selected].color else palette.cyan
            )
        }
        Spacer(Modifier.height(8.dp))
        val chartDiameter = 126.dp
        // Below the split point the legend moves under the dial, where the two boxes
        // cannot touch, instead of squeezing beside it until its rows ran under the ring.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val beside = chartLegendSitsBeside(maxWidth, chartDiameter)
            if (beside) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VaultPieChart(
                        slices = slices,
                        selectedIndex = selected,
                        onSliceSelected = { selected = it },
                        diameter = chartDiameter,
                        strokeWidth = 17.dp,
                        caption = if (total == 0) "nothing sealed" else "$total sealed"
                    )
                    Spacer(Modifier.width(12.dp))
                    CompositionLegend(
                        categories = categories,
                        selected = selected,
                        onSelect = { selected = it },
                        onOpen = { route -> onNavigate(route) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VaultPieChart(
                        slices = slices,
                        selectedIndex = selected,
                        onSliceSelected = { selected = it },
                        diameter = chartDiameter,
                        strokeWidth = 17.dp,
                        caption = if (total == 0) "nothing sealed" else "$total sealed"
                    )
                    CompositionLegend(
                        categories = categories,
                        selected = selected,
                        onSelect = { selected = it },
                        onOpen = { route -> onNavigate(route) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * The full card's key: one row per slice, each one a bed that grows with its own content, so
 * the selected highlight is never cut by the row's own corner (see `Modifier.selectedBed`).
 */
@Composable
private fun CompositionLegend(
    categories: List<CompositionSlice>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        categories.forEachIndexed { index, slice ->
            val active = selected == index
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectedBed(
                        selected = active,
                        fillColor = slice.color.copy(alpha = 0.14f),
                        ruleColor = slice.color.copy(alpha = 0.34f)
                    )
                    .clickable {
                        onSelect(if (active) -1 else index)
                        onOpen(slice.route)
                    }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PieLegendMarker(color = slice.color, size = 9.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) VaultContrast.ensure(slice.color, palette.abyssSurface) else VaultColor.textBody,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = slice.value.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.textStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// The board.
// -----------------------------------------------------------------------------------------

/** The row that names the composition the board below actually is. Drawn only from the
 *  full-composition tier up, where there is guaranteed room for it beside the grid. */
@Composable
private fun BoardHeaderRow(layout: VaultLayout) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title = "All modules", modifier = Modifier.weight(1f))
        val chipText = when (layout) {
            VaultLayout.CLASSIC -> "Classic grid"
            VaultLayout.BENTO -> "Bento 2.0"
            VaultLayout.AGENTIC -> "Quick actions"
            VaultLayout.ADAPTIVE -> when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> "Morning order"
                in 12..17 -> "Daytime order"
                in 18..23 -> "Evening order"
                else -> "Night order"
            }
            VaultLayout.GENERATIVE -> "Clay forms"
            VaultLayout.MOSAIC -> "Mosaic 2+1"
            VaultLayout.LEDGER -> "Ledger rails"
            VaultLayout.DUO -> "Duo cards"
            VaultLayout.COMPACT -> "Compact 5"
            VaultLayout.DENSE -> "Dense 6"
            VaultLayout.COLUMN -> "Column lead"
            VaultLayout.SPLIT -> "Split cards"
            VaultLayout.GALLERY -> "Gallery 1+2"
            VaultLayout.STRIP -> "Strip rail"
            VaultLayout.TRIAD -> "Triad pairs"
        }
        val chipAccent by animateColorAsState(
            targetValue = when (layout) {
                VaultLayout.BENTO -> VaultColor.accent
                VaultLayout.AGENTIC -> VaultColor.credit
                VaultLayout.ADAPTIVE, VaultLayout.GENERATIVE, VaultLayout.DUO,
                VaultLayout.TRIAD -> VaultColor.decorative
                VaultLayout.LEDGER, VaultLayout.STRIP -> VaultColor.credit
                VaultLayout.COMPACT, VaultLayout.DENSE -> VaultColor.warning
                else -> VaultColor.textFaint
            },
            animationSpec = VaultMotion.tweenSpec<Color>(VaultMotion.STANDARD),
            label = "boardChip"
        )
        TagChip(text = chipText, accent = chipAccent)
    }
}

/** Board order, with the module's route and stored-count badge resolved against the state. */
@Composable
private fun boardItems(state: HomeUiState, layout: VaultLayout): List<BoardItem> {
    val palette = VaultPaletteController.current
    val modules = BrandIconCatalog.board.map { module ->
        HomeModule(
            module = module,
            accent = BrandIconCatalog.accent(palette.id, module),
            route = when (module) {
                VaultModule.PASSWORDS -> Routes.VAULT
                VaultModule.TWO_FA -> Routes.AUTH
                VaultModule.CARDS -> Routes.WALLET
                VaultModule.CRYPTO -> Routes.CRYPTO
                VaultModule.NOTES -> Routes.NOTES
                VaultModule.CONTACTS -> Routes.CONTACTS
                VaultModule.FILES -> Routes.FILES
                VaultModule.SCAN -> Routes.SCAN_HUB
                VaultModule.BACKUP -> Routes.BACKUP
                VaultModule.HEALTH -> Routes.HEALTH
                VaultModule.SYNC -> Routes.SYNC_CENTER
                VaultModule.TOOLS -> Routes.SECURITY_TOOLS
                VaultModule.STEGO -> Routes.STEGO
                VaultModule.HOME -> Routes.HOME
            },
            badge = when (module) {
                VaultModule.PASSWORDS -> state.counts.passwords
                VaultModule.TWO_FA -> state.counts.totps
                VaultModule.CARDS -> state.counts.cards
                VaultModule.NOTES -> state.counts.notes
                VaultModule.CONTACTS -> state.counts.contacts
                VaultModule.FILES -> state.counts.files
                else -> 0
            }
        )
    }
    // The adaptive layout is the one that genuinely re-orders: the hour's priorities rise to
    // the leading cells, ties keep the contextual order. Every other layout keeps board order.
    val ordered = if (layout == VaultLayout.ADAPTIVE) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val priority = when (hour) {
            in 5..11 -> listOf("Passwords", "Notes", "2FA", "Scan")
            in 12..17 -> listOf("Cards", "Contacts", "Files", "Passwords")
            in 18..23 -> listOf("Crypto", "Cards", "Secret Image", "Files")
            else -> listOf("Files", "Notes", "Sync", "Passwords")
        }
        modules.sortedWith(
            compareBy<HomeModule> {
                priority.indexOf(it.label).let { index -> if (index < 0) priority.size else index }
            }.thenByDescending { it.badge }
        )
    } else {
        modules
    }
    return ordered.map { module ->
        BoardItem(
            module = module.module,
            accent = module.accent,
            route = module.route,
            badge = module.badge
        )
    }
}

/**
 * The board of the active theme. The row/column partition comes from the pure [boardGrid], so
 * the grid can never overflow the pane and never leaves a hole in it either: every row's spans
 * total exactly the layout's [boardColumns], and each cell is drawn with the [TileKind] that
 * matches the width it ended up with. Both the span *and* the kind pass straight through to
 * [BoardTile] — that pass-through is what lets a bento board draw a two-column feature and a
 * closing bar while a ledger board draws six full-width rails and a dense board a 6 x 2 plate grid.
 *
 * The rows split the caller's box evenly with weights: the board owns every pixel the upper
 * tiers do not, so the grid always gets its rows and nothing on the dashboard scrolls. Each cell
 * then reads the height it was actually given (`fitCell`) instead of demanding one, which is why
 * a short row shrinks its glyph rather than clipping its label.
 */
@Composable
private fun HomeModuleGrid(
    state: HomeUiState,
    layout: VaultLayout,
    showExpiryInHeader: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = boardItems(state, layout)
    // The board is built from the theme's own grammar: columns, spans, cell kinds and spacing
    // all come from the layout, so two themes cannot resolve to the same composition.
    val rhythm = BoardRhythm.of(layout)
    val rows = boardGrid(layout, items.size)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(rhythm.rowGap)
    ) {
        if (showExpiryInHeader && state.expiringSoon.isNotEmpty()) {
            // One quiet line, in the strip the header already owns — never a scrolling card
            // unless the pane is tall enough for the real one.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultPaletteController.current.chipShape)
                    .clickable { onNavigate(Routes.vault("IDENTITY")) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemedIcon(
                    glyph = Glyph.timer,
                    contentDescription = null,
                    tint = VaultColor.warning,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${state.expiringSoon.size} item${if (state.expiringSoon.size == 1) "" else "s"} expiring within 60 days",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.warning,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            DesignReveal(index = rowIndex, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(rhythm.cellGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { cell ->
                        val item = items.getOrNull(cell.index) ?: return@forEach
                        BoardTile(
                            item = item,
                            // The board's own grammar decides how the cell draws: ROW rails,
                            // BAR closers, FEATURE cards, MICRO tiles and PLATE grids all come
                            // from `boardGrid`, so two themes cannot collapse to one silhouette.
                            kind = cell.kind,
                            layout = layout,
                            rhythm = rhythm,
                            modifier = Modifier
                                .weight(cell.span.toFloat())
                                .fillMaxHeight()
                        ) { onNavigate(item.route) }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// The search results pane — the one region of Home that scrolls, because it is a data list.
// -----------------------------------------------------------------------------------------
@Composable
private fun SearchResultsPane(
    state: HomeUiState,
    modifier: Modifier = Modifier,
    onOpen: (SearchHit) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = Glyph.search,
                contentDescription = null,
                tint = VaultColor.accent,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${state.searchHits.size} result${if (state.searchHits.size == 1) "" else "s"} for “${state.query.trim()}”",
                style = MaterialTheme.typography.labelMedium,
                color = VaultColor.textBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.searchHits.isEmpty()) {
            GlassCard {
                Text(
                    text = "No matches. Try a site name, username, or a fragment like “git”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColor.textBody
                )
            }
        } else {
            // A list, so a list's scroll — the dashboard behind it never scrolls.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.searchHits, key = { "${it.kind}-${it.id}" }) { hit ->
                    SearchHitRow(
                        modifier = Modifier.animateItem(),
                        hit = hit
                    ) { onOpen(hit) }
                }
            }
        }
    }
}

@Composable
private fun HomeEntryRow(
    entry: VaultEntry,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        onClick = onOpen,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonogramBadge(seed = entry.title, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.hasTotp) {
                        Spacer(Modifier.width(6.dp))
                        TagChip("2FA", accent = VaultColor.credit)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.username.ifBlank { entry.email }.ifBlank { entry.displayUrl.ifBlank { "No username" } },
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = strengthLabel(entry.strengthScore),
                    style = MaterialTheme.typography.labelSmall,
                    color = securityColor(entry.strengthScore)
                )
                Crossfade(targetState = entry.isFavorite, label = "favStar") { favorite ->
                    ThemedIcon(
                        glyph = if (favorite) Glyph.star else Glyph.starBorder,
                        contentDescription = if (favorite) "Remove favorite" else "Favorite",
                        tint = if (favorite) VaultColor.warning else VaultColor.textFaint,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(19.dp)
                            .clickable(onClick = onToggleFavorite)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = VaultPaletteController.current
    val icon: Glyph
    val accent: Color
    when (hit.kind) {
        SearchKind.PASSWORD -> {
            icon = Glyph.key
            accent = VaultColor.accent
        }
        SearchKind.CARD -> {
            icon = Glyph.creditCard
            accent = VaultColor.decorative
        }
        SearchKind.NOTE -> {
            icon = Glyph.notes
            accent = VaultColor.warning
        }
        SearchKind.CONTACT -> {
            icon = Glyph.person
            accent = VaultColor.secondary
        }
        SearchKind.TOTP -> {
            icon = Glyph.timer
            accent = VaultColor.credit
        }
    }
    GlassCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(iconPlateShape(palette, 40.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .border(
                        palette.cardBorderWidth,
                        accent.copy(alpha = 0.22f),
                        iconPlateShape(palette, 40.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hit.subtitle.isNotBlank()) {
                    Text(
                        text = hit.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = hit.kind.name.lowercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.8f)
            )
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = VaultColor.textFaint,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(17.dp)
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// The rich-tier cards. Only drawn when the pane provably has room for them on top of the
// board's three rows; below the tier the same facts live as chips, so nothing duplicates.
// -----------------------------------------------------------------------------------------
@Composable
private fun TravelModeCard(modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier,
        accent = VaultColor.warning,
        borderColor = VaultColor.warning.copy(alpha = 0.3f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIcon(
                glyph = Glyph.travelExplore,
                contentDescription = null,
                tint = VaultColor.warning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Travel mode is on — flagged secrets are hidden.",
                style = MaterialTheme.typography.labelMedium,
                color = VaultColor.textBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TagChip("HIDDEN", accent = VaultColor.warning)
        }
    }
}

@Composable
private fun ExpiryRemindersCard(
    entries: List<VaultEntry>,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VaultPaletteController.current
    GlassCard(
        modifier = modifier,
        accent = VaultColor.warning,
        borderColor = VaultColor.warning.copy(alpha = 0.3f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(iconPlateShape(palette, 36.dp))
                    .background(VaultColor.warning.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.timer,
                    contentDescription = null,
                    tint = VaultColor.warning,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Expiry reminders",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "IDs, licences and vehicle papers due within 60 days",
                    style = VaultType.micro(),
                    color = VaultColor.textFaint,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "SEE ALL",
                style = VaultType.micro(),
                color = VaultColor.warning,
                modifier = Modifier
                    .clip(palette.chipShape)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 5.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        entries.take(3).forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(palette.chipShape)
                    .clickable { onOpen(entry.id) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entry.category.lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }} · expires ${
                            formatShortDate(entry.expiresAt ?: 0L)
                        }",
                        style = VaultType.micro(),
                        color = VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ThemedIcon(
                    glyph = Glyph.chevronRight,
                    contentDescription = null,
                    tint = VaultColor.warning,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun SecurityStampRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(
            glyph = Glyph.shield,
            contentDescription = null,
            tint = VaultColor.textFaint.copy(alpha = 0.6f),
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "AES-256-GCM · Keystore-backed · zero-knowledge",
            style = VaultType.micro(),
            color = VaultColor.textFaint.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// -----------------------------------------------------------------------------------------
// The quick-add sheet. Sheets are overlays, not the dashboard: its body scrolling when six
// rows exceed the collapsed sheet on a short screen is the sanctioned kind of scroll.
// -----------------------------------------------------------------------------------------
private data class QuickAction(
    val label: String,
    val subtitle: String,
    val module: VaultModule,
    val accent: Color,
    val route: String
)

@Composable
private fun QuickAddSheet(onDismiss: () -> Unit, onNavigate: (String) -> Unit) {
    val palette = VaultPaletteController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val specs = listOf(
        Triple(VaultModule.PASSWORDS, "Password", "Login or app") to Routes.entryEditor(),
        Triple(VaultModule.TWO_FA, "2FA account", "TOTP secret") to Routes.totpEditor(),
        Triple(VaultModule.CARDS, "Payment card", "Card or ID") to Routes.cardEditor(),
        Triple(VaultModule.NOTES, "Secure note", "Encrypted text") to Routes.noteEditor(),
        Triple(VaultModule.CONTACTS, "Contact", "Private address book") to Routes.contactEditor(),
        Triple(VaultModule.FILES, "File", "Encrypted document") to Routes.FILES
    )
    val actions = specs.map { (spec, route) ->
        QuickAction(spec.second, spec.third, spec.first, BrandIconCatalog.accent(palette.id, spec.first), route)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        shape = palette.cardShape,
        dragHandle = { BottomSheetDefaults.DragHandle(color = palette.outlineColor) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Six quick-add rows plus the header are taller than the collapsed
                // sheet on a short screen; scroll the body instead of clipping it.
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Quick add",
                        style = MaterialTheme.typography.headlineSmall,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = "What are you securing today?",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody
                    )
                }
                GlassIconButton(
                    icon = Glyph.close,
                    contentDescription = "Close",
                    onClick = onDismiss,
                    tint = VaultColor.textBody
                )
            }
            Spacer(Modifier.height(18.dp))
            actions.chunked(2).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowActions.forEach { action ->
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            accent = action.accent,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            onClick = { onNavigate(action.route) }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BrandModuleIcon(
                                    module = action.module,
                                    size = 30.dp,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = action.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = VaultColor.textStrong,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = action.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VaultColor.textFaint,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    if (rowActions.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
            GlassCard(
                accent = VaultColor.credit,
                borderColor = VaultColor.credit.copy(alpha = 0.28f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                onClick = { onNavigate(Routes.SCANNER) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThemedIcon(
                        glyph = Glyph.qrCodeScanner,
                        contentDescription = null,
                        tint = VaultColor.credit,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Scan a 2FA QR code",
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColor.textStrong
                        )
                        Text(
                            text = "Point the camera at an authenticator QR",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
