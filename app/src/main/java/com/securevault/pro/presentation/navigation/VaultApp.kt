package com.securevault.pro.presentation.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.securevault.pro.presentation.AppStage
import com.securevault.pro.presentation.AppViewModel
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.presentation.components.BrandModuleIcon
import com.securevault.pro.presentation.components.CrashReportBanner
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.PanicShakeEffect
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultModule
import com.securevault.pro.presentation.components.secureClipboard
import kotlinx.coroutines.delay
import com.securevault.pro.presentation.screens.auth.AuthScreen
import com.securevault.pro.presentation.screens.auth.ScannerScreen
import com.securevault.pro.presentation.screens.auth.TotpEditorScreen
import com.securevault.pro.presentation.screens.backup.BackupScreen
import com.securevault.pro.presentation.screens.contacts.ContactEditorScreen
import com.securevault.pro.presentation.screens.contacts.ContactsScreen
import com.securevault.pro.presentation.screens.crypto.CryptoWalletScreen
import com.securevault.pro.presentation.screens.files.FilesScreen
import com.securevault.pro.presentation.screens.files.VoiceRecorderScreen
import com.securevault.pro.presentation.screens.files.WhatsAppChatsScreen
import com.securevault.pro.presentation.screens.health.HealthScreen
import com.securevault.pro.presentation.screens.home.HomeScreen
import com.securevault.pro.presentation.screens.lock.LockScreen
import com.securevault.pro.presentation.screens.notes.NoteEditorScreen
import com.securevault.pro.presentation.screens.notes.NotesScreen
import com.securevault.pro.presentation.screens.scanner.DocumentScannerScreen
import com.securevault.pro.presentation.screens.scanner.FileScannerScreen
import com.securevault.pro.presentation.screens.scanner.OcrScannerScreen
import com.securevault.pro.presentation.screens.scanner.ScanHubScreen
import com.securevault.pro.presentation.screens.security.SecurityToolsScreen
import com.securevault.pro.presentation.screens.settings.CompanionPairing
import com.securevault.pro.presentation.screens.settings.SettingsScreen
import com.securevault.pro.presentation.screens.sync.SyncCenterScreen
import com.securevault.pro.presentation.screens.vault.EntryDetailScreen
import com.securevault.pro.presentation.screens.vault.EntryEditorScreen
import com.securevault.pro.presentation.screens.vault.EntryEditorViewModel
import com.securevault.pro.presentation.screens.vault.VaultListScreen
import com.securevault.pro.presentation.screens.viewer.SecureViewerScreen
import com.securevault.pro.presentation.screens.wallet.CardEditorScreen
import com.securevault.pro.presentation.screens.wallet.WalletScreen
import com.securevault.pro.presentation.screens.email.EmailSection
import com.securevault.pro.presentation.screens.stego.StegoSection
import com.securevault.pro.presentation.screens.passkey.PasskeySection
import com.securevault.pro.security.passkey.PasskeyHandoff
import com.securevault.pro.security.keyboard.LocalSecureBoardHeight
import com.securevault.pro.security.keyboard.LocalSecureKeyboardHaptics
import com.securevault.pro.security.keyboard.LocalSecureKeyboardTyping
import com.securevault.pro.security.keyboard.SecureKeyboardHost
import com.securevault.pro.presentation.screens.welcome.IntroScreen
import com.securevault.pro.presentation.screens.welcome.OnboardingScreen
import com.securevault.pro.presentation.screens.welcome.SetupScreen
import com.securevault.pro.presentation.screens.welcome.SplashScreen
import com.securevault.pro.presentation.theme.DockStyle
import com.securevault.pro.presentation.theme.DockFill
import com.securevault.pro.presentation.theme.DockPlan
import com.securevault.pro.presentation.theme.MarkerAnchor
import com.securevault.pro.presentation.theme.NavIndicator
import com.securevault.pro.presentation.theme.SecureWalletTheme
import com.securevault.pro.presentation.theme.ThemeMotion
import com.securevault.pro.presentation.theme.VaultPalette
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultContrast
import com.securevault.pro.presentation.theme.VaultMotion
import com.securevault.pro.presentation.theme.DOCK_ICON_SCALE
import com.securevault.pro.presentation.theme.DOCK_LABEL_GAP
import com.securevault.pro.presentation.theme.DOCK_MARKER_GAP
import com.securevault.pro.presentation.theme.dockBarShape
import com.securevault.pro.presentation.theme.dockPlan
import com.securevault.pro.presentation.theme.dockSeatShape
import com.securevault.pro.presentation.theme.easingFor
import com.securevault.pro.presentation.theme.motionFor
import com.securevault.pro.presentation.theme.themeRouteEnter
import com.securevault.pro.presentation.theme.themeRouteExit
import com.securevault.pro.presentation.components.Glyph

@Composable
fun VaultApp(viewModel: AppViewModel = hiltViewModel()) {
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()
    val fontId by viewModel.fontId.collectAsStateWithLifecycle()
    SecureWalletTheme(themeId = themeId, fontId = fontId) {
        VaultAppScaffold(viewModel = viewModel)
    }
}

@Composable
private fun VaultAppScaffold(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val screenshotBlocked by viewModel.screenshotBlocked.collectAsStateWithLifecycle()
    val clipboardSeconds by viewModel.clipboardSeconds.collectAsStateWithLifecycle()
    val autoLockSeconds by viewModel.autoLockSeconds.collectAsStateWithLifecycle()
    val inactivityLockSeconds by viewModel.inactivityLockSeconds.collectAsStateWithLifecycle()
    val panicShakeEnabled by viewModel.panicShakeEnabled.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val secureKeyboard by viewModel.secureKeyboardEnabled.collectAsStateWithLifecycle()
    val secureBoardHeight by viewModel.secureBoardHeight.collectAsStateWithLifecycle()
    val secureKeyboardHaptics by viewModel.secureKeyboardHaptics.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val view = LocalView.current
    val clipboard = remember(context) { secureClipboard(context.applicationContext) }

    androidx.compose.runtime.LaunchedEffect(clipboardSeconds) {
        clipboard.clearAfterSeconds = clipboardSeconds
    }

    androidx.compose.runtime.LaunchedEffect(state.stage) {
        if (state.stage == AppStage.LOCKED || state.stage == AppStage.SETUP) {
            clipboard.clear()
        }
    }

    val activePalette = VaultPaletteController.active.value
    androidx.compose.runtime.SideEffect {
        // The theme belongs in the marker: a defect that only shows on one palette is
        // otherwise indistinguishable from a general crash once the owner reports it.
        CrashLogger.noteStage(state.stage.name.lowercase() + "/" + activePalette.id)
    }
    androidx.compose.runtime.SideEffect {
        if (view.isInEditMode) return@SideEffect
        val activity = context.findActivity() ?: return@SideEffect
        // The vault is the secret surface, and only the vault: welcome, setup, lock and rescue all
        // share this window and must stay photographable so a failing login can be reported.
        // Safe render mode wins over the preference: being readable beats being unphotographable.
        val protect = state.stage == AppStage.UNLOCKED && screenshotBlocked && !CrashLogger.safeRender
        if (protect) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = activePalette.isLight
            isAppearanceLightNavigationBars = activePalette.isLight
        }
    }

    PanicShakeEffect(
        enabled = state.stage == AppStage.UNLOCKED && panicShakeEnabled,
        onShake = viewModel::lockNow
    )

    androidx.compose.runtime.CompositionLocalProvider(
        LocalSecureClipboard provides clipboard,
        LocalSecureKeyboardTyping provides secureKeyboard,
        LocalSecureBoardHeight provides secureBoardHeight,
        LocalSecureKeyboardHaptics provides secureKeyboardHaptics
    ) {
        Box(Modifier.fillMaxSize()) {
            // One secure board for the whole app, docked at the bottom of the window instead of
            // inside whichever field is focused. Engaging a field therefore costs the form no
            // layout height where the field sits — the screen gives up room at the bottom exactly
            // as it does for the system keyboard, which is what stops the input from jumping.
            SecureKeyboardHost(modifier = Modifier.fillMaxSize()) {
                // A short fade + lift as the app moves between major stages (boot, onboarding, lock,
                // vault). Navigation, routes and back handling are untouched: only the stage body
                // is wrapped, and the outgoing screen is disposed as soon as the 180-240 ms hop ends.
                val stageEnter = VaultMotion.fadeEnter(durationMillis = 240) +
                    VaultMotion.slideEnterVertically(durationMillis = 240) { it / 30 }
                val stageExit = VaultMotion.fadeExit(durationMillis = VaultMotion.SHORT)
                AnimatedContent(
                    targetState = state.stage,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { stageEnter togetherWith stageExit },
                    label = "appStage"
                ) { stage ->
                    when (stage) {
                        AppStage.LOADING -> SplashScreen(
                            status = cloudState.detail.takeIf { it.isNotBlank() },
                            title = cloudState.title.takeIf { cloudState.active && it.isNotBlank() },
                            progress = cloudState.percent.takeIf { cloudState.active }
                        )
                        AppStage.ONBOARDING -> OnboardingScreen(onFinished = viewModel::completeOnboarding)
                        AppStage.INTRO -> IntroScreen(onFinished = viewModel::completeIntro)
                        AppStage.SETUP -> SetupScreen()
                        AppStage.LOCKED -> LockScreen()
                        AppStage.UNLOCKED -> UnlockedApp(
                            autoLockSeconds = autoLockSeconds,
                            inactivityLockSeconds = inactivityLockSeconds,
                            onLock = viewModel::lockNow,
                            onToggleTheme = viewModel::toggleLightMode
                        )
                    }
                }
            }
            // App-level notices share one column so a crash report never sits on top of
            // anything else the root has to say.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                CrashReportBanner()
            }
        }
    }
}

@Composable
private fun UnlockedApp(
    autoLockSeconds: Int,
    inactivityLockSeconds: Int,
    onLock: () -> Unit,
    onToggleTheme: () -> Unit
) {
    AutoLockEffect(autoLockSeconds = autoLockSeconds, onLock = onLock)
    val lastInteraction = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    InactivityLockEffect(
        inactivityLockSeconds = inactivityLockSeconds,
        lastInteraction = lastInteraction,
        onLock = onLock
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.topLevelRoutes

    // The place, not just the state: `noteStage` can only say "unlocked", so a trace-less death
    // inside the note editor and one inside the card wallet produced the same report. `noteScreen`
    // writes to disk and to the trail only when the name actually changes, so a recomposition of the
    // host costs nothing.
    androidx.compose.runtime.SideEffect { CrashLogger.noteScreen(Routes.screenNameFor(currentRoute)) }

    // A route change is part of the theme now: the direction, the curve and the length all come
    // from the palette's own motion signature, and the device's animation scale is read once here
    // so a reduced-motion phone simply swaps screens on one frame.
    val routeMotion = motionFor(VaultPaletteController.current.id)
    val routeScale = VaultMotion.durationScale()

    // A browser passkey sheet can open this app before the vault is unlocked. The request waits in
    // PasskeyHandoff until this graph exists, then lands on the Passkeys section exactly once.
    val passkeyRequest by PasskeyHandoff.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(passkeyRequest) {
        if (PasskeyHandoff.consume() != null && currentRoute != Routes.PASSKEY) {
            runCatching { navController.navigate(Routes.PASSKEY) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction.longValue = SystemClock.elapsedRealtime()
                    }
                }
            }
    ) {
    Scaffold(
        containerColor = VaultColor.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                VaultBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = { themeRouteEnter(routeMotion, routeScale) },
            exitTransition = { themeRouteExit(routeMotion, routeScale) },
            popEnterTransition = { themeRouteEnter(routeMotion, routeScale, reverse = true) },
            popExitTransition = { themeRouteExit(routeMotion, routeScale, reverse = true) }
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    onLock = onLock,
                    onToggleTheme = onToggleTheme
                )
            }

            composable(
                route = Routes.VAULT,
                arguments = listOf(
                    navArgument("category") {
                        type = NavType.StringType
                        defaultValue = "ALL"
                    }
                )
            ) {
                VaultListScreen(
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                    onAddEntry = { navController.navigate(Routes.entryEditor()) },
                    onLock = onLock
                )
            }

            composable(
                route = Routes.ENTRY_DETAIL,
                arguments = listOf(navArgument("entryId") { type = NavType.StringType })
            ) {
                EntryDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Routes.entryEditor(id)) }
                )
            }

            composable(
                route = Routes.ENTRY_EDITOR,
                arguments = listOf(
                    navArgument("entryId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val editorViewModel: EntryEditorViewModel = hiltViewModel()
                val scannedUri by backStackEntry.savedStateHandle
                    .getStateFlow<String?>("entryTotpUri", null)
                    .collectAsStateWithLifecycle()
                LaunchedEffect(scannedUri) {
                    val uri = scannedUri ?: return@LaunchedEffect
                    editorViewModel.applyScannedTotp(uri)
                    backStackEntry.savedStateHandle["entryTotpUri"] = null
                }
                EntryEditorScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onScanQr = { navController.navigate(Routes.ENTRY_SCANNER) },
                    viewModel = editorViewModel
                )
            }

            composable(Routes.ENTRY_SCANNER) {
                ScannerScreen(
                    onBack = { navController.popBackStack() },
                    onScanned = { uri ->
                        navController.previousBackStackEntry?.savedStateHandle
                            ?.set("entryTotpUri", uri)
                        navController.popBackStack()
                    }
                )
            }

            composable(Routes.AUTH) {
                AuthScreen(
                    onEdit = { id -> navController.navigate(Routes.totpEditor(accountId = id)) },
                    onAdd = { navController.navigate(Routes.totpEditor()) },
                    onScan = { navController.navigate(Routes.SCANNER) },
                    onLock = onLock
                )
            }

            composable(
                route = Routes.TOTP_EDITOR,
                arguments = listOf(
                    navArgument("accountId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("uri") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                TotpEditorScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onScan = { navController.navigate(Routes.SCANNER) }
                )
            }

            composable(Routes.SCANNER) {
                ScannerScreen(
                    onBack = { navController.popBackStack() },
                    onScanned = { uri ->
                        navController.navigate(Routes.totpEditor(uri = uri)) {
                            popUpTo(Routes.SCANNER) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.WALLET) {
                WalletScreen(
                    onAddCard = { navController.navigate(Routes.cardEditor()) },
                    onEditCard = { id -> navController.navigate(Routes.cardEditor(id)) },
                    onOpenCategory = { category -> navController.navigate(Routes.vault(category)) },
                    onLock = onLock
                )
            }

            composable(Routes.CRYPTO) {
                CryptoWalletScreen(
                    onBack = { navController.popBackStack() },
                    onLock = onLock
                )
            }

            composable(
                route = Routes.CARD_EDITOR,
                arguments = listOf(
                    navArgument("cardId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                CardEditorScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }

            composable(Routes.NOTES) {
                NotesScreen(
                    onOpenNote = { id -> navController.navigate(Routes.noteEditor(id)) },
                    onAddNote = { navController.navigate(Routes.noteEditor()) },
                    onLock = onLock
                )
            }

            composable(
                route = Routes.NOTE_EDITOR,
                arguments = listOf(
                    navArgument("noteId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                NoteEditorScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }

            composable(Routes.CONTACTS) {
                ContactsScreen(
                    onEdit = { id -> navController.navigate(Routes.contactEditor(id)) },
                    onAdd = { navController.navigate(Routes.contactEditor()) },
                    onLock = onLock
                )
            }

            composable(
                route = Routes.CONTACT_EDITOR,
                arguments = listOf(
                    navArgument("contactId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                ContactEditorScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }

            composable(Routes.FILES) {
                FilesScreen(
                    onBack = { navController.popBackStack() },
                    onLock = onLock,
                    onOpenFile = { id -> navController.navigate(Routes.viewer(id)) },
                    onOpenVoice = { navController.navigate(Routes.VOICE_MEMOS) },
                    onOpenNote = { id -> navController.navigate(Routes.noteEditor(id)) },
                    onOpenChat = { id -> navController.navigate(Routes.whatsAppChats(id)) }
                )
            }

            composable(
                route = Routes.WHATSAPP_CHATS,
                arguments = listOf(
                    navArgument("chatId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                WhatsAppChatsScreen(
                    initialChatId = backStackEntry.arguments?.getString("chatId"),
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.VOICE_MEMOS) {
                VoiceRecorderScreen(
                    onBack = { navController.popBackStack() },
                    onOpenFile = { id -> navController.navigate(Routes.viewer(id)) },
                    onOpenFiles = { navController.navigate(Routes.FILES) },
                    onLock = onLock
                )
            }

            composable(
                route = Routes.VIEWER,
                arguments = listOf(navArgument("fileId") { type = NavType.StringType })
            ) { backStackEntry ->
                SecureViewerScreen(
                    fileId = backStackEntry.arguments?.getString("fileId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onLock = onLock
                )
            }

            composable(Routes.SCAN_HUB) {
                ScanHubScreen(
                    onBack = { navController.popBackStack() },
                    onOpenOcr = { navController.navigate(Routes.OCR_SCANNER) },
                    onOpenDocument = { navController.navigate(Routes.DOC_SCANNER) },
                    onOpenFile = { navController.navigate(Routes.FILE_SCANNER) },
                    onOpenFiles = { navController.navigate(Routes.FILES) }
                )
            }

            composable(Routes.OCR_SCANNER) {
                OcrScannerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.DOC_SCANNER) {
                DocumentScannerScreen(
                    onBack = { navController.popBackStack() },
                    onOpenFiles = { navController.navigate(Routes.FILES) }
                )
            }

            composable(Routes.FILE_SCANNER) {
                FileScannerScreen(
                    onBack = { navController.popBackStack() },
                    onOpenFiles = { navController.navigate(Routes.FILES) }
                )
            }

            composable(Routes.SECURITY_TOOLS) {
                SecurityToolsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenHealth = { navController.navigate(Routes.HEALTH) },
                    onOpenSync = { navController.navigate(Routes.SYNC_CENTER) },
                    onOpenPasskeys = { navController.navigate(Routes.PASSKEY) },
                    onOpenEmail = { navController.navigate(Routes.EMAIL) },
                    onOpenStego = { navController.navigate(Routes.STEGO) }
                )
            }

            composable(Routes.EMAIL) {
                EmailSection(onBack = { navController.popBackStack() })
            }

            composable(Routes.STEGO) {
                StegoSection(onBack = { navController.popBackStack() })
            }

            composable(Routes.PASSKEY) {
                PasskeySection(onBack = { navController.popBackStack() })
            }

            composable(Routes.SYNC_CENTER) {
                SyncCenterScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) }
                )
            }

            composable(Routes.BACKUP) {
                BackupScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.HEALTH) {
                HealthScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) }
                )
            }

            composable(Routes.SETTINGS) { entry ->
                val pairingRaw by entry.savedStateHandle
                    .getStateFlow<String?>("pairingPayload", null)
                    .collectAsStateWithLifecycle()
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onVaultWiped = {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) },
                    onScanPairing = { navController.navigate(Routes.PAIR_SCANNER) },
                    pairingPayload = pairingRaw,
                    onPairingConsumed = { entry.savedStateHandle["pairingPayload"] = null }
                )
            }

            composable(Routes.PAIR_SCANNER) {
                ScannerScreen(
                    title = "Scan the pairing code",
                    subtitle = "Point at the QR shown by your companion site",
                    accepts = { raw -> CompanionPairing.parse(raw) != null },
                    acceptedLabel = "Pairing code captured",
                    rejectedLabel = "That QR is not a pairing code from your companion site",
                    guidance = listOf(
                        Triple(
                            Glyph.link,
                            "On the companion page open “Pair with the phone” and hold the phone " +
                                "over the square code it shows.",
                            VaultColor.accent
                        ),
                        Triple(
                            Glyph.qrCodeScanner,
                            "If the page shows no code, it is still the old theme: paste the " +
                                "new XML into Blogger first.",
                            VaultColor.warning
                        )
                    ),
                    onBack = { navController.popBackStack() },
                    onScanned = { raw ->
                        navController.previousBackStackEntry?.savedStateHandle
                            ?.set("pairingPayload", raw)
                        navController.popBackStack()
                    }
                )
            }
        }
    }
    }
}

@Composable
private fun AutoLockEffect(
    autoLockSeconds: Int,
    onLock: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSeconds by rememberUpdatedState(autoLockSeconds)
    val currentOnLock by rememberUpdatedState(onLock)
    var backgroundedAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val stoppedAt = backgroundedAt
                    if (stoppedAt > 0L) {
                        val elapsed = SystemClock.elapsedRealtime() - stoppedAt
                        if (elapsed >= currentSeconds * 1000L) currentOnLock()
                        backgroundedAt = 0L
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Locks the vault after a period without any pointer interaction while the
 * screen is visible. Pointer events are observed on the initial pass and never
 * consumed, so taps and scrolling behave exactly as before.
 */
@Composable
private fun InactivityLockEffect(
    inactivityLockSeconds: Int,
    lastInteraction: MutableLongState,
    onLock: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSeconds by rememberUpdatedState(inactivityLockSeconds)
    val currentOnLock by rememberUpdatedState(onLock)

    LaunchedEffect(currentSeconds) {
        while (true) {
            delay(1_000)
            val idle = SystemClock.elapsedRealtime() - lastInteraction.longValue
            if (idle >= currentSeconds * 1000L) {
                currentOnLock()
                break
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                lastInteraction.longValue = SystemClock.elapsedRealtime()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private data class BottomTab(
    val route: String,
    val pattern: String,
    val label: String,
    val module: VaultModule
)

@Composable
private fun VaultBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit
) {
    val tabs = remember {
        listOf(
            BottomTab(Routes.HOME, Routes.HOME, "Home", VaultModule.HOME),
            BottomTab(Routes.vault(), Routes.VAULT, "Vault", VaultModule.PASSWORDS),
            BottomTab(Routes.AUTH, Routes.AUTH, "Auth", VaultModule.TWO_FA),
            BottomTab(Routes.CRYPTO, Routes.CRYPTO, "Crypto", VaultModule.CRYPTO),
            BottomTab(Routes.WALLET, Routes.WALLET, "Cards", VaultModule.CARDS)
        )
    }

    val palette = VaultPaletteController.current
    // The bar is a *rendering* of the theme's dock spec, not a re-reading of DockStyle: shape,
    // seat, fill, how the destinations share the width and where the marker lives all come from
    // `dockPlan`, so fifteen themes draw fifteen different bars instead of four.
    val plan = dockPlan(palette, tabs.size)
    val barShape = dockBarShape(plan.corner, palette.navCorner)
    val seatShape = dockSeatShape(plan.seat, palette.navCorner)
    val motion = motionFor(palette.id)
    val lit = VaultContrast.vibrant(palette.cyan)
    val barBed = dockBed(palette, plan)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = plan.sideInset, end = plan.sideInset, bottom = plan.bottomInset)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(plan.barHeight)
                .then(
                    if (plan.floats || palette.cardShadow > 0.dp) {
                        Modifier.shadow(
                            elevation = if (palette.glow) 20.dp else palette.cardShadow.coerceAtLeast(6.dp),
                            shape = barShape,
                            ambientColor = VaultColor.accent.copy(alpha = if (palette.glow) 0.24f else 0.08f),
                            spotColor = VaultColor.accent.copy(alpha = if (palette.glow) 0.3f else 0.1f)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(barShape)
                .background(dockFillBrush(palette, plan), barShape)
                .border(
                    if (plan.edgeRule) 0.dp
                    else palette.cardBorderWidth.coerceAtLeast(1.dp),
                    palette.outlineColor,
                    barShape
                )
                // The item plates are cut *inside* the bar, so the plate inset can never eat the
                // touch target: `dockPlan` has already given way on it if the bar is too short.
                .padding(
                    start = plan.innerInset,
                    end = plan.innerInset,
                    top = plan.plateInset,
                    bottom = plan.plateInset
                ),
            horizontalArrangement = Arrangement.spacedBy(plan.itemGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                DockItem(
                    tab = tab,
                    index = index,
                    selected = currentRoute == tab.pattern,
                    plan = plan,
                    palette = palette,
                    motion = motion,
                    seatShape = seatShape,
                    barBed = barBed,
                    lit = lit,
                    onSelect = { onSelect(tab.route) }
                )
            }
        }
        // A two-tone bar's break and a minimal bar's hairline are both rules across the bar itself,
        // drawn after the row so nothing the items do can cover them.
        if (plan.edgeRule) {
            Rule(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(1.dp),
                ink = palette.surfaceOverlay.copy(alpha = 0.12f)
            )
        }
        if (plan.fill == DockFill.SHEEN_TOP) {
            Rule(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = plan.innerInset * 2)
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .clip(RoundedCornerShape(percent = 50)),
                ink = palette.glassHighlight.copy(alpha = 0.55f)
            )
        }
    }
}

/** A single lit or hairline rule across the bar. */
@Composable
private fun Rule(modifier: Modifier, ink: Color) {
    Box(modifier.background(ink))
}

@Composable
private fun RowScope.DockItem(
    tab: BottomTab,
    index: Int,
    selected: Boolean,
    plan: DockPlan,
    palette: VaultPalette,
    motion: ThemeMotion,
    seatShape: Shape,
    barBed: Color,
    lit: Color,
    onSelect: () -> Unit
) {
    // A plate is only lit when the marker *is* the plate; elsewhere the accent stays on its own
    // rule or spot so the item never turns into a slab of brand colour.
    val seatLit = selected && plan.marker == MarkerAnchor.SEAT && plan.indicator != NavIndicator.NONE
    val wash = if (seatLit) lit.copy(alpha = if (palette.isLight) 0.16f else 0.22f) else Color.Transparent
    val bed = VaultContrast.composite(wash, barBed)
    // Ink over an accent is measured, never assumed: the label rides the composited bed it sits on.
    val labelInk = if (seatLit) {
        VaultContrast.ensure(palette.textPrimary, bed)
    } else if (selected) {
        VaultColor.textStrong
    } else {
        VaultColor.textFaint
    }
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (plan.indicator == NavIndicator.NONE) {
            VaultMotion.tweenSpec<Float>(VaultMotion.INSTANT, easingFor(motion.curve))
        } else {
            VaultMotion.tweenSpec<Float>(motion.duration, easingFor(motion.curve))
        },
        label = "dockMarker"
    )
    // The label is the last thing to go: the plan already traded plate inset for it, and this is
    // the final guard for a bar shorter than one line of the theme's own label size.
    val showLabel = plan.showLabels && plan.contentHeight <= plan.itemHeight

    Box(
        modifier = Modifier
            .weight(plan.itemWeights[index.coerceAtLeast(0)])
            .fillMaxHeight()
            .clip(seatShape)
            .background(wash, seatShape)
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (plan.marker == MarkerAnchor.ICON) {
                Box(contentAlignment = Alignment.Center) {
                    DockMarker(
                        plan = plan,
                        lit = lit,
                        progress = progress,
                        kind = MarkerKind.SPOT
                    )
                    BrandModuleIcon(
                        module = tab.module,
                        size = plan.iconSize * DOCK_ICON_SCALE,
                        contentDescription = tab.label,
                        modifier = Modifier.graphicsLayer {
                            alpha = if (selected) 1f else 0.58f
                        }
                    )
                }
            } else {
                BrandModuleIcon(
                    module = tab.module,
                    size = plan.iconSize * DOCK_ICON_SCALE,
                    contentDescription = tab.label,
                    modifier = Modifier.graphicsLayer {
                        alpha = if (selected) 1f else 0.58f
                    }
                )
            }
            if (showLabel) {
                Spacer(Modifier.height(DOCK_LABEL_GAP))
                Text(
                    text = if (palette.uppercaseLabels || palette.dock == DockStyle.FLAT_PLATE) {
                        tab.label.uppercase()
                    } else {
                        tab.label
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = plan.labelSize,
                        letterSpacing = if (palette.dock == DockStyle.FLAT_PLATE) 0.8.sp else 0.4.sp
                    ),
                    color = labelInk,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (plan.markerInFlow) {
                Spacer(Modifier.height(DOCK_MARKER_GAP))
                DockMarker(plan = plan, lit = lit, progress = progress, kind = MarkerKind.RAIL)
            }
        }
        // Markers that ride the item's own edge rather than its flow.
        when {
            plan.indicator == NavIndicator.NONE ||
                (plan.marker == MarkerAnchor.SEAT) ||
                (plan.marker == MarkerAnchor.BELOW && plan.markerInFlow) ||
                plan.marker == MarkerAnchor.ICON -> Unit
            plan.marker == MarkerAnchor.BESIDE -> DockMarker(
                plan = plan,
                lit = lit,
                progress = progress,
                kind = MarkerKind.TAB,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            plan.marker == MarkerAnchor.ABOVE -> DockMarker(
                plan = plan,
                lit = lit,
                progress = progress,
                kind = MarkerKind.RAIL,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = plan.markerThickness)
            )
            else -> DockMarker(
                plan = plan,
                lit = lit,
                progress = progress,
                kind = MarkerKind.RAIL,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * The selected marker. Its *anchor* comes from the theme's dock spec and its *shape* from the
 * palette's own [NavIndicator], so the same dot is a rail under the label in one theme, a spot
 * behind the glyph in another and a tab on the item's leading edge in a third.
 */
private enum class MarkerKind {
    /** A rail across the item, or a dot at its centre when the palette marks with a dot. */
    RAIL,
    /** A vertical tab on the item's leading edge. */
    TAB,
    /** A disc behind the glyph, cut from the glyph's own seat. */
    SPOT
}

@Composable
private fun DockMarker(
    plan: DockPlan,
    lit: Color,
    progress: Float,
    kind: MarkerKind,
    modifier: Modifier = Modifier
) {
    if (plan.indicator == NavIndicator.NONE) return
    val dot = plan.indicator == NavIndicator.DOT
    val sized = when (kind) {
        MarkerKind.RAIL -> if (dot) {
            modifier.size(plan.markerDotSize)
        } else {
            modifier.fillMaxWidth(plan.markerWidthFraction).height(plan.markerThickness)
        }
        MarkerKind.TAB -> modifier
            .fillMaxHeight(if (dot) plan.markerDotSize / plan.itemHeight else 0.58f)
            .width(if (dot) plan.markerDotSize else plan.markerThickness)
        MarkerKind.SPOT -> modifier.size(plan.iconSize * DOCK_ICON_SCALE + plan.iconSpotPad * 2)
    }
    Box(
        sized
            .graphicsLayer {
                alpha = progress
                val grow = 0.55f + 0.45f * progress
                when {
                    dot || kind == MarkerKind.SPOT -> {
                        scaleX = grow
                        scaleY = grow
                    }
                    kind == MarkerKind.TAB -> scaleY = grow
                    else -> scaleX = grow
                }
            }
            // A spot is a wash the glyph sits in; a rail or a tab is the accent itself.
            .background(
                if (kind == MarkerKind.SPOT) lit.copy(alpha = if (plan.indicator == NavIndicator.DOT) 0.4f else 0.26f) else lit,
                if (dot || kind == MarkerKind.SPOT) CircleShape else RoundedCornerShape(percent = 50)
            )
    )
}

/**
 * The bar's own fill, per [DockFill]. Every stop is a palette surface token at the palette's own
 * `navFrostAlpha`, so the theme decides how much wallpaper reads through whichever silhouette it is
 * cut to — the flat sheet, the lit footer, the frosted glass or the two-tone break.
 */
private fun dockFillBrush(palette: VaultPalette, plan: DockPlan): Brush {
    val top = plan.frostTop
    val bottom = plan.frostBottom
    val surface = palette.abyssSurface
    val elevated = palette.abyssElevated
    val glass = palette.abyssGlass
    return when (plan.fill) {
        DockFill.FLAT -> Brush.verticalGradient(
            listOf(surface.copy(alpha = top), surface.copy(alpha = bottom))
        )
        DockFill.GRADIENT -> Brush.verticalGradient(
            listOf(elevated.copy(alpha = top), surface.copy(alpha = bottom))
        )
        DockFill.GLASS -> Brush.verticalGradient(
            listOf(glass.copy(alpha = top), surface.copy(alpha = bottom))
        )
        DockFill.TWO_TONE -> Brush.verticalGradient(
            0f to elevated.copy(alpha = top),
            0.56f to elevated.copy(alpha = top),
            0.56f to surface.copy(alpha = bottom),
            1f to surface.copy(alpha = bottom)
        )
        DockFill.SHEEN_TOP -> Brush.verticalGradient(
            0f to elevated.copy(alpha = top),
            0.34f to elevated.copy(alpha = bottom),
            1f to surface.copy(alpha = bottom)
        )
    }
}

/**
 * The colour the bar's items actually sit on, composited at the theme's frost alpha over the
 * screen behind it. Label ink is measured against this, which is why a 0.55 frost theme can still
 * promise readable captions.
 */
private fun dockBed(palette: VaultPalette, plan: DockPlan): Color {
    val top = palette.abyssSurface.copy(alpha = plan.frostTop)
    return VaultContrast.composite(
        when (plan.fill) {
            DockFill.FLAT, DockFill.GLASS -> top
            DockFill.GRADIENT, DockFill.SHEEN_TOP, DockFill.TWO_TONE ->
                palette.abyssElevated.copy(alpha = plan.frostTop)
        },
        palette.abyssDark
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
