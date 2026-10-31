package com.securevault.pro.presentation.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.securevault.pro.presentation.screens.welcome.IntroScreen
import com.securevault.pro.presentation.screens.welcome.OnboardingScreen
import com.securevault.pro.presentation.screens.welcome.SetupScreen
import com.securevault.pro.presentation.screens.welcome.SplashScreen
import com.securevault.pro.presentation.theme.DockStyle
import com.securevault.pro.presentation.theme.NavIndicator
import com.securevault.pro.presentation.theme.SecureWalletTheme
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultRadius
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

    androidx.compose.runtime.CompositionLocalProvider(LocalSecureClipboard provides clipboard) {
        Box(Modifier.fillMaxSize()) {
            when (state.stage) {
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
            CrashReportBanner(modifier = Modifier.align(Alignment.TopCenter))
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
            enterTransition = {
                fadeIn(tween(220, delayMillis = 40)) +
                    slideInHorizontally(tween(300)) { it / 16 }
            },
            exitTransition = {
                fadeOut(tween(140)) +
                    slideOutHorizontally(tween(300)) { -it / 30 }
            },
            popEnterTransition = {
                fadeIn(tween(220, delayMillis = 40)) +
                    slideInHorizontally(tween(300)) { -it / 16 }
            },
            popExitTransition = {
                fadeOut(tween(140)) +
                    slideOutHorizontally(tween(300)) { it / 30 }
            }
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
                    onOpenHealth = { navController.navigate(Routes.HEALTH) },
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
    val dock = palette.dock
    val floats = dock == DockStyle.PILL_FROSTED ||
        dock == DockStyle.COMFORT_PANEL ||
        (dock == DockStyle.SOFT_FLOATING && palette.navFloating)
    val shape = when (dock) {
        DockStyle.FLAT_PLATE -> RoundedCornerShape(
            topStart = palette.navCorner,
            topEnd = palette.navCorner
        )
        DockStyle.MINIMAL_BAR -> RectangleShape
        else -> palette.navShape
    }
    val sideInset = when (dock) {
        DockStyle.PILL_FROSTED -> 18.dp
        DockStyle.COMFORT_PANEL -> 12.dp
        DockStyle.SOFT_FLOATING -> if (palette.navFloating) 14.dp else 0.dp
        else -> 0.dp
    }
    val bottomInset = when (dock) {
        DockStyle.PILL_FROSTED -> 12.dp
        DockStyle.COMFORT_PANEL -> 10.dp
        DockStyle.SOFT_FLOATING -> if (palette.navFloating) 10.dp else 0.dp
        else -> 0.dp
    }
    val itemShape = when (palette.navIndicator) {
        NavIndicator.PILL -> VaultRadius.pill()
        NavIndicator.UNDERLINE -> RoundedCornerShape(palette.navCorner / 2)
        else -> RectangleShape
    }
    val frostTop = palette.navFrostAlpha
    val frostBottom = (frostTop + 0.03f).coerceAtMost(1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = sideInset, end = sideInset, bottom = bottomInset)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(palette.navHeight)
                .then(
                    if (floats || palette.cardShadow > 0.dp) {
                        Modifier.shadow(
                            elevation = if (palette.glow) 20.dp else palette.cardShadow.coerceAtLeast(6.dp),
                            shape = shape,
                            ambientColor = VaultColor.accent.copy(alpha = if (palette.glow) 0.24f else 0.08f),
                            spotColor = VaultColor.accent.copy(alpha = if (palette.glow) 0.3f else 0.1f)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.abyssElevated.copy(alpha = frostTop),
                            palette.abyssSurface.copy(alpha = frostBottom)
                        )
                    )
                )
                // The PILL_FROSTED dock used to paint a second translucent capsule
                // (a glassHighlight sheen) on top of its own gradient. It read as a
                // ghost pill sitting behind the icons, so the extra layer is gone:
                // the dock is one plate, one fill.
                .border(
                    if (dock == DockStyle.MINIMAL_BAR) 0.dp
                    else palette.cardBorderWidth.coerceAtLeast(1.dp),
                    palette.outlineColor,
                    shape
                )
                .padding(horizontal = if (dock == DockStyle.COMFORT_PANEL) 8.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.pattern
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(itemShape)
                        .background(
                            if (selected && palette.navIndicator == NavIndicator.PILL) {
                                VaultColor.accent.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                            itemShape
                        )
                        .clickable { onSelect(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BrandModuleIcon(
                        module = tab.module,
                        size = palette.navIconSize * 1.34f,
                        contentDescription = tab.label,
                        modifier = Modifier.graphicsLayer {
                            alpha = if (selected) 1f else 0.58f
                        }
                    )
                    if (palette.navLabels) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (palette.uppercaseLabels || dock == DockStyle.FLAT_PLATE) {
                                tab.label.uppercase()
                            } else {
                                tab.label
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = palette.navLabelSize,
                                letterSpacing = if (dock == DockStyle.FLAT_PLATE) 0.8.sp else 0.4.sp
                            ),
                            color = if (selected) VaultColor.textStrong else VaultColor.textFaint,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (palette.navIndicator == NavIndicator.UNDERLINE) {
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(if (selected) 18.dp else 3.dp)
                                .height(2.dp)
                                .clip(VaultShapes.tag)
                                .background(if (selected) VaultColor.accent else Color.Transparent)
                        )
                    }
                    if (palette.navIndicator == NavIndicator.DOT) {
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .size(if (selected) 5.dp else 3.dp)
                                .clip(CircleShape)
                                .background(if (selected) VaultColor.accent else Color.Transparent)
                        )
                    }
                }
            }
        }
        if (dock == DockStyle.MINIMAL_BAR) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.surfaceOverlay.copy(alpha = 0.12f))
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
