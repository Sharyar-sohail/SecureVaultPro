package com.securevault.pro.presentation.screens.crypto

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.TrustReport
import com.securevault.pro.crypto.WalletAddress
import com.securevault.pro.crypto.WalletImportKind
import com.securevault.pro.domain.model.CryptoWallet
import com.securevault.pro.domain.model.TransferAsset
import com.securevault.pro.domain.model.TransferQuote
import com.securevault.pro.domain.model.TransferReceipt
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.QrCodeImage
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.Pill
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.components.vaultGutter
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.components.Glyph

@Composable
fun CryptoWalletScreen(
    onBack: () -> Unit,
    onLock: () -> Unit,
    viewModel: CryptoWalletViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalSecureClipboard.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var receiveWallet by remember { mutableStateOf<CryptoWallet?>(null) }
    var pendingDelete by remember { mutableStateOf<CryptoWallet?>(null) }

    fun toast(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    GlassScaffold(
        title = "Crypto wallet",
        subtitle = when {
            state.decoy -> "Demonstration mode"
            state.wallets.isEmpty() -> "Self-custody · keys stay on-device"
            else -> buildString {
                append(state.wallets.size)
                append(if (state.wallets.size == 1) " wallet · " else " wallets · ")
                append(state.fundedCount)
                append(if (state.fundedCount == 1) " funded asset" else " funded assets")
            }
        },
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            GlassIconButton(
                icon = Glyph.refresh,
                contentDescription = "Refresh balances",
                onClick = viewModel::refreshAll,
                tint = VaultColor.accent
            )
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = VaultColor.decorative
            )
        },
        fab = {
            if (!state.decoy) {
                val heroFill = VaultColor.heroFill(VaultColor.decorative, VaultColor.secondary)
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(heroFill))
                        .clickable { showAdd = true },
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.add,
                        contentDescription = "Import wallet",
                        tint = VaultColor.onGradient(heroFill),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = vaultGutter(), end = vaultGutter(), top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PortfolioCard(
                    totalUsd = state.totalUsd,
                    lastSyncedAt = state.lastSyncedAt,
                    refreshing = state.refreshing.isNotEmpty(),
                    fundedCount = state.fundedCount
                )
            }

            state.error?.let { message ->
                item { NoticeCard(message, VaultColor.debit, Glyph.warningAmber) }
            }
            state.scanNotice?.let { message ->
                item { NoticeCard(message = message, accent = VaultColor.warning, icon = Glyph.info) }
            }
            if (state.scanErrors.isNotEmpty()) {
                item {
                    NoticeCard(
                        message = state.scanErrors.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                        accent = VaultColor.warning,
                        icon = Glyph.cloudOff,
                        actionLabel = "Retry",
                        onAction = viewModel::refreshAll
                    )
                }
            }

            if (state.decoy) {
                item {
                    NoticeCard(
                        message = "Wallet balances are hidden in demonstration mode. Unlock with your real PIN to see your portfolio.",
                        accent = VaultColor.warning,
                        icon = Glyph.shield
                    )
                }
            }

            when {
                state.loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = VaultColor.accent, strokeWidth = 2.dp)
                    }
                }
                state.wallets.isEmpty() && !state.decoy -> item {
                    EmptyState(
                        icon = Glyph.currencyBitcoin,
                        title = "No wallets yet",
                        message = "Import a seed phrase or private key to track Bitcoin, Ethereum, BNB Chain, Polygon and Tron balances.",
                        actionLabel = "Import wallet",
                        onAction = { showAdd = true }
                    )
                }
                else -> items(state.wallets, key = { it.id }) { wallet ->
                    WalletCard(
                        wallet = wallet,
                        refreshing = wallet.id in state.refreshing,
                        onRefresh = { viewModel.refresh(wallet.id) },
                        onDelete = { pendingDelete = wallet },
                        onCopy = { address ->
                            clipboard.copy(address, "Public address")
                            toast("Address copied · clipboard clears automatically")
                        },
                        onSend = { asset -> viewModel.openSend(wallet, asset) },
                        onReceive = { receiveWallet = wallet }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemedIcon(
                        glyph = Glyph.shield,
                        contentDescription = null,
                        tint = VaultColor.textFaint.copy(alpha = 0.7f),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Signed on this device. Only public addresses and signed transactions reach the network.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddWalletSheet(
            busy = state.busy,
            error = state.error,
            onDismiss = {
                showAdd = false
                viewModel.clearError()
            },
            onImport = { label, input, kind ->
                viewModel.importWallet(label, input, kind) { error ->
                    if (error == null) {
                        showAdd = false
                        toast("Wallet secured · scanning balances")
                    }
                }
            }
        )
    }

    receiveWallet?.let { wallet ->
        ReceiveSheet(wallet = wallet, onDismiss = { receiveWallet = null })
    }

    val transfer = state.transfer
    val transferWallet = state.wallets.firstOrNull { it.id == transfer.walletId }
    if (transfer.open && transferWallet != null) {
        SendSheet(
            wallet = transferWallet,
            transfer = transfer,
            onDismiss = viewModel::closeTransfer,
            onSelectAsset = viewModel::selectAsset,
            onRecipient = viewModel::setRecipient,
            onAmount = viewModel::setAmount,
            onMax = { viewModel.useMax(transferWallet) },
            onReview = { viewModel.review(transferWallet) },
            onEdit = viewModel::clearQuote,
            onConfirm = { viewModel.confirmSend(transferWallet) },
            onCopy = { value ->
                clipboard.copy(value, "Transaction hash")
                toast("Copied · clipboard clears automatically")
            }
        )
    }

    val deleting = pendingDelete
    ConfirmDialog(
        visible = deleting != null,
        title = "Remove wallet?",
        message = "The encrypted secret for \"${deleting?.shortLabel ?: "this wallet"}\" will be deleted. Make sure you have your recovery phrase before continuing.",
        confirmLabel = "Remove",
        onConfirm = {
            deleting?.let { viewModel.delete(it.id) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null }
    )
}

@Composable
private fun PortfolioCard(
    totalUsd: Double,
    lastSyncedAt: Long,
    refreshing: Boolean,
    fundedCount: Int
) {
    GlassCard(accent = VaultColor.decorative) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "ESTIMATED PORTFOLIO",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = VaultColor.decorative
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatUsd(totalUsd),
                    style = MaterialTheme.typography.displaySmall,
                    color = VaultColor.textStrong
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        refreshing -> "Scanning public ledgers…"
                        lastSyncedAt > 0 -> "$fundedCount funded · updated ${relativeTime(lastSyncedAt)}"
                        else -> "Balances are fetched on demand"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody
                )
            }
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(VaultShapes.button)
                    .background(VaultColor.decorative.copy(alpha = 0.14f))
                    .border(1.dp, VaultColor.decorative.copy(alpha = 0.28f), VaultShapes.button),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.currencyBitcoin,
                    contentDescription = null,
                    tint = VaultColor.decorative,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(
    message: String,
    accent: Color,
    icon: Glyph,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VaultShapes.button)
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.25f), VaultShapes.button)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThemedIcon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(VaultShapes.input)
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun WalletCard(
    wallet: CryptoWallet,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String) -> Unit,
    onSend: (TransferAsset?) -> Unit,
    onReceive: () -> Unit
) {
    val accent = if (wallet.isSeedPhrase) VaultColor.decorative else VaultColor.accent
    val funded = wallet.fundedAssets()
    GlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThemedIconTile(
                icon = if (wallet.isSeedPhrase) Glyph.shield else Glyph.key,
                contentDescription = null,
                accent = accent,
                size = 44.dp,
                iconSize = 21.dp,
                background = accent.copy(alpha = 0.14f)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = wallet.shortLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    TagChip(if (wallet.isSeedPhrase) "SEED" else "KEY", accent = accent)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (wallet.lastSyncedAt > 0) {
                        "Updated ${relativeTime(wallet.lastSyncedAt)}"
                    } else {
                        "Not scanned yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatUsd(wallet.totalUsd),
                    style = MaterialTheme.typography.titleMedium,
                    color = VaultColor.textStrong
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = accent
                        )
                    } else {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                            ThemedIcon(
                                glyph = Glyph.refresh,
                                contentDescription = "Refresh wallet",
                                tint = VaultColor.textBody,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        ThemedIcon(
                            glyph = Glyph.delete,
                            contentDescription = "Remove wallet",
                            tint = VaultColor.textFaint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(VaultColor.hairline.copy(alpha = 0.55f))
        )
        Spacer(Modifier.height(6.dp))

        if (funded.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemedIcon(
                    glyph = Glyph.currencyBitcoin,
                    contentDescription = null,
                    tint = VaultColor.textFaint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "No funded coins discovered. Receive any asset below and it will appear after the next scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody,
                    lineHeight = 18.sp
                )
            }
        } else {
            wallet.addresses.forEach { address ->
                val assets = funded.filter { it.networkId == address.network.id }
                if (assets.isEmpty()) return@forEach
                NetworkBlock(
                    address = address,
                    assets = assets,
                    onCopy = onCopy,
                    onSend = onSend
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GhostButton(
                text = "Send",
                icon = Glyph.arrowUpward,
                accent = VaultColor.credit,
                modifier = Modifier.weight(1f),
                enabled = funded.isNotEmpty(),
                onClick = { onSend(funded.firstOrNull()) }
            )
            GhostButton(
                text = "Receive",
                icon = Glyph.qrCodeScanner,
                accent = VaultColor.accent,
                modifier = Modifier.weight(1f),
                onClick = onReceive
            )
        }
    }
}

@Composable
private fun NetworkBlock(
    address: WalletAddress,
    assets: List<TransferAsset>,
    onCopy: (String) -> Unit,
    onSend: (TransferAsset) -> Unit
) {
    val accent = networkAccent(address.network)
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(accent, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = address.network.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.textStrong
                )
                Text(
                    text = shortAddress(address.address),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = VaultColor.textFaint,
                    maxLines = 1
                )
            }
            IconButton(onClick = { onCopy(address.address) }, modifier = Modifier.size(30.dp)) {
                ThemedIcon(
                    glyph = Glyph.contentCopy,
                    contentDescription = "Copy ${address.network.displayName} address",
                    tint = accent.copy(alpha = 0.8f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        assets.forEach { asset ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShapes.input)
                    .clickable { onSend(asset) }
                    .padding(start = 15.dp, top = 7.dp, bottom = 5.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${asset.symbol} · ${formatAmount(asset.amount)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = asset.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatUsd(asset.usd),
                        style = MaterialTheme.typography.labelMedium,
                        color = VaultColor.textStrong
                    )
                    Text(
                        text = "Send",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiveSheet(
    wallet: CryptoWallet,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalSecureClipboard.current
    var selected by remember(wallet.id) {
        mutableStateOf(wallet.addresses.firstOrNull()?.network ?: CryptoNetwork.BITCOIN)
    }
    val address = wallet.addresses.firstOrNull { it.network == selected }?.address.orEmpty()
    val accent = networkAccent(selected)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        dragHandle = { BottomSheetDefaults.DragHandle(color = VaultColor.hairline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Receive crypto",
                style = MaterialTheme.typography.headlineSmall,
                color = VaultColor.textStrong,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "This address is public. Share it or scan the code — never share a key or phrase.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                wallet.addresses.forEach { item ->
                    Pill(
                        label = item.network.symbol,
                        selected = selected == item.network,
                        onClick = { selected = item.network }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))

            if (address.isNotBlank()) {
                QrCodeImage(value = address, contentDescription = "Address QR code", size = 216.dp)
                Spacer(Modifier.height(18.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShapes.button)
                    .background(accent.copy(alpha = 0.08f))
                    .border(1.dp, accent.copy(alpha = 0.25f), VaultShapes.button)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${selected.displayName.uppercase()} · ${selected.addressLabel.uppercase()}",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    color = accent
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = VaultColor.textStrong,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )
            }
            Spacer(Modifier.height(14.dp))

            GradientButton(
                text = "Copy address",
                icon = Glyph.contentCopy,
                modifier = Modifier.fillMaxWidth(),
                enabled = address.isNotBlank(),
                onClick = { clipboard.copy(address, "${selected.displayName} address") }
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShapes.button)
                    .background(VaultColor.warning.copy(alpha = 0.07f))
                    .border(1.dp, VaultColor.warning.copy(alpha = 0.22f), VaultShapes.button)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemedIcon(
                    glyph = Glyph.warningAmber,
                    contentDescription = null,
                    tint = VaultColor.warning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Only send ${selected.symbol} on ${selected.displayName}. Coins sent over another network may be unrecoverable.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textBody,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun SendSheet(
    wallet: CryptoWallet,
    transfer: TransferUiState,
    onDismiss: () -> Unit,
    onSelectAsset: (TransferAsset) -> Unit,
    onRecipient: (String) -> Unit,
    onAmount: (String) -> Unit,
    onMax: () -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onCopy: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val funded = wallet.fundedAssets()
    val asset = transfer.asset
    val quote = transfer.quote
    val receipt = transfer.receipt

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        dragHandle = { BottomSheetDefaults.DragHandle(color = VaultColor.hairline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            if (receipt != null) {
                ReceiptContent(
                    receipt = receipt,
                    onCopy = onCopy,
                    onOpen = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(receipt.explorerUrl))
                            )
                        }
                    },
                    onDone = onDismiss
                )
            } else {
                Text(
                    text = if (asset != null) "Send ${asset.symbol}" else "Send crypto",
                    style = MaterialTheme.typography.headlineSmall,
                    color = VaultColor.textStrong
                )
                Text(
                    text = "Signed locally · ${wallet.shortLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textBody
                )
                Spacer(Modifier.height(16.dp))

                if (funded.isEmpty()) {
                    NoticeCard(
                        message = "This wallet has no funded assets yet. Receive a coin first, then it becomes available to send.",
                        accent = VaultColor.warning,
                        icon = Glyph.warningAmber
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        funded.forEach { item ->
                            Pill(
                                label = "${item.symbol} · ${networkShort(item.networkId)}",
                                selected = item.key == asset?.key,
                                onClick = { onSelectAsset(item) }
                            )
                        }
                    }
                }

                if (asset != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Available",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${formatAmount(asset.amount)} ${asset.symbol} · ${formatUsd(asset.usd)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = VaultColor.textBody
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    if (quote == null) {
                        VaultTextField(
                            value = transfer.recipient,
                            onValueChange = onRecipient,
                            label = "${asset.networkName} recipient",
                            placeholder = addressPlaceholder(asset.networkId),
                            singleLine = true,
                            isError = transfer.recipientError != null,
                            supportingText = transfer.recipientError
                                ?: if (transfer.recipient.isNotBlank()) "Address format verified" else null
                        )
                        Spacer(Modifier.height(12.dp))

                        VaultTextField(
                            value = transfer.amount,
                            onValueChange = onAmount,
                            label = "Amount (${asset.symbol})",
                            placeholder = "0.0",
                            keyboardType = KeyboardType.Decimal,
                            trailingIcon = {
                                Text(
                                    text = "MAX",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VaultColor.accent,
                                    modifier = Modifier
                                        .clickable(onClick = onMax)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (asset.isNative) {
                                "The network fee comes out of this balance."
                            } else {
                                "The network fee is paid in ${feeSymbol(asset.networkId)}."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint
                        )

                        transfer.error?.let { message ->
                            Spacer(Modifier.height(12.dp))
                            NoticeCard(message, VaultColor.debit, Glyph.warningAmber)
                        }

                        Spacer(Modifier.height(16.dp))
                        GradientButton(
                            text = "Review transfer",
                            icon = Glyph.arrowUpward,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = transfer.recipient.isNotBlank() &&
                                transfer.amount.isNotBlank() &&
                                transfer.recipientError == null,
                            loading = transfer.quoting,
                            onClick = onReview
                        )
                    } else {
                        QuoteSummary(quote)
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GhostButton(
                                text = "Edit",
                                modifier = Modifier.weight(1f),
                                enabled = !transfer.sending,
                                onClick = onEdit
                            )
                            GradientButton(
                                text = "Confirm & send",
                                icon = Glyph.arrowUpward,
                                modifier = Modifier.weight(1f),
                                enabled = !transfer.sending && quote.trust.accepted,
                                loading = transfer.sending,
                                onClick = onConfirm
                            )
                        }
                        if (!quote.trust.accepted) {
                            Spacer(Modifier.height(12.dp))
                            NoticeCard(
                                quote.trust.refusalReason(),
                                VaultColor.debit,
                                Glyph.shield
                            )
                        }
                        transfer.error?.let { message ->
                            Spacer(Modifier.height(12.dp))
                            NoticeCard(message, VaultColor.debit, Glyph.warningAmber)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Your key never leaves this device. The signed transaction is broadcast to the network.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textFaint,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteSummary(quote: TransferQuote) {
    GlassCard(accent = VaultColor.decorative) {
        Text(
            text = "TRANSFER SUMMARY",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
            color = VaultColor.decorative
        )
        Spacer(Modifier.height(10.dp))
        SummaryRow("Network", quote.chainLabel.ifBlank { quote.networkName })
        SummaryRow("From", shortAddress(quote.fromAddress))
        SummaryRow("To", shortAddress(quote.toAddress))
        SummaryRow("Amount", "${plain(quote.amount)} ${quote.symbol}")
        SummaryRow(
            "Network fee",
            buildString {
                append(plain(quote.fee))
                append(' ')
                append(quote.feeSymbol)
                if (quote.feeUsd > 0.0) {
                    append(" · ")
                    append(formatUsd(quote.feeUsd))
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(VaultColor.hairline.copy(alpha = 0.55f))
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = quote.totalLabel,
            style = MaterialTheme.typography.titleSmall,
            color = VaultColor.textStrong
        )
        Text(
            text = quote.feeNote,
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint
        )
        quote.warning?.let { warning ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.warning
            )
        }
        TrustChecklist(quote.trust)
    }
}

/**
 * The verdict the send path tested, on screen.
 *
 * Every line [com.securevault.pro.data.wallet.TransactionTrust] produced is drawn — the address
 * shape, the chain the signature is for, the token contract, the balance and the fee — with the
 * same pass mark the engine used to decide whether to sign at all. A refusal therefore never
 * arrives as a bare error after a tap: the owner sees which rule failed *before* confirming, and
 * the button stays shut until every check is green.
 */
@Composable
private fun TrustChecklist(report: TrustReport) {
    if (report.checks.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        text = if (report.accepted) "TRUST CHECKS · ALL PASSED" else "TRUST CHECKS · ${report.blocking.size} FAILED",
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
        color = if (report.accepted) VaultColor.credit else VaultColor.debit
    )
    Spacer(Modifier.height(8.dp))
    report.checks.forEach { check ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            ThemedIcon(
                glyph = if (check.passed) Glyph.checkCircle else Glyph.errorOutline,
                contentDescription = if (check.passed) "Passed" else "Failed",
                tint = if (check.passed) VaultColor.credit else VaultColor.debit,
                modifier = Modifier.size(15.dp),
                animate = false
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = check.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.textStrong
                )
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = VaultColor.textStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ReceiptContent(
    receipt: TransferReceipt,
    onCopy: (String) -> Unit,
    onOpen: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(VaultColor.credit.copy(alpha = 0.14f))
                .border(1.dp, VaultColor.credit.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ThemedIcon(
                glyph = Glyph.check,
                contentDescription = null,
                tint = VaultColor.credit,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Transaction broadcast",
            style = MaterialTheme.typography.headlineSmall,
            color = VaultColor.textStrong
        )
        Text(
            text = "${plain(receipt.amount)} ${receipt.symbol} on ${receipt.networkName}",
            style = MaterialTheme.typography.bodySmall,
            color = VaultColor.textBody,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(VaultShapes.button)
                .background(VaultColor.credit.copy(alpha = 0.07f))
                .border(1.dp, VaultColor.credit.copy(alpha = 0.24f), VaultShapes.button)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = "TX HASH",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                color = VaultColor.credit
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = receipt.hash,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = VaultColor.textStrong,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tap copy to verify it in any explorer.",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
        Spacer(Modifier.height(16.dp))

        GradientButton(
            text = "View on explorer",
            icon = Glyph.openInNew,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpen
        )
        Spacer(Modifier.height(8.dp))
        GhostButton(
            text = "Copy hash",
            icon = Glyph.contentCopy,
            modifier = Modifier.fillMaxWidth(),
            accent = VaultColor.accent,
            onClick = { onCopy(receipt.hash) }
        )
        Spacer(Modifier.height(8.dp))
        GhostButton(
            text = "Done",
            modifier = Modifier.fillMaxWidth(),
            onClick = onDone
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Confirmation can take a few seconds. Balances refresh automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.textFaint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddWalletSheet(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: (String, String, WalletImportKind) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var label by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(WalletImportKind.SEED_PHRASE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        dragHandle = { BottomSheetDefaults.DragHandle(color = VaultColor.hairline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Text(
                text = "Import wallet",
                style = MaterialTheme.typography.headlineSmall,
                color = VaultColor.textStrong
            )
            Text(
                text = "Addresses are derived on-device. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody
            )
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    label = "Seed phrase",
                    selected = kind == WalletImportKind.SEED_PHRASE,
                    onClick = { kind = WalletImportKind.SEED_PHRASE }
                )
                Pill(
                    label = "Private key",
                    selected = kind == WalletImportKind.PRIVATE_KEY,
                    onClick = { kind = WalletImportKind.PRIVATE_KEY }
                )
            }
            Spacer(Modifier.height(14.dp))

            VaultTextField(
                value = label,
                onValueChange = { label = it },
                label = "Label (optional)",
                placeholder = if (kind == WalletImportKind.SEED_PHRASE) "Savings" else "Trading key",
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))

            if (kind == WalletImportKind.SEED_PHRASE) {
                VaultTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "Recovery phrase",
                    placeholder = "12, 15, 18, 21 or 24 BIP39 words",
                    isPassword = true,
                    singleLine = false,
                    minLines = 3
                )
            } else {
                VaultTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "Private key",
                    placeholder = "64 hex characters (0x optional)",
                    isPassword = true,
                    singleLine = false,
                    minLines = 2
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(VaultShapes.button)
                    .background(VaultColor.warning.copy(alpha = 0.08f))
                    .border(1.dp, VaultColor.warning.copy(alpha = 0.24f), VaultShapes.button)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemedIcon(
                    glyph = Glyph.warningAmber,
                    contentDescription = null,
                    tint = VaultColor.warning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Anyone with this phrase or key controls the funds. It is sealed with your vault key and never leaves this device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textBody,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(16.dp))

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColor.debit,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
            }

            GradientButton(
                text = if (kind == WalletImportKind.SEED_PHRASE) "Import seed phrase" else "Import private key",
                modifier = Modifier.fillMaxWidth(),
                icon = Glyph.add,
                enabled = input.isNotBlank() && !busy,
                loading = busy,
                onClick = { onImport(label, input, kind) }
            )
        }
    }
}

private fun plain(value: BigDecimal): String {
    val stripped = value.stripTrailingZeros()
    return stripped.toPlainString().ifBlank { "0" }
}

/**
 * Chain labels come from [ChainRegistry], so a chain added there appears here, in the network
 * switcher and in Settings' Manage networks with its own name, fee coin and address hint — no
 * screen keeps a list of chains any more.
 */
private fun networkShort(networkId: String): String = ChainRegistry.shortName(networkId)

private fun feeSymbol(networkId: String): String = ChainRegistry.feeSymbol(networkId)

private fun addressPlaceholder(networkId: String): String =
    ChainRegistry.descriptorOrNull(networkId)?.let { ChainRegistry.addressHint(it.network) } ?: "Address"

/**
 * The chain's colour is its slot in the theme's own accent ramp, so every chain reads distinctly and
 * no chain can ever be drawn in a colour this theme does not allow.
 */
private fun networkAccent(network: CryptoNetwork): Color {
    val ramp = VaultColor.accentRamp
    val index = ChainRegistry.all().indexOfFirst { it.network == network }
    return if (index < 0 || ramp.isEmpty()) VaultColor.accent else ramp[index % ramp.size]
}

private fun shortAddress(address: String): String {
    if (address.length <= 20) return address
    return address.take(10) + "…" + address.takeLast(8)
}

private val amountFormat = DecimalFormat(
    "#,##0.########",
    DecimalFormatSymbols(Locale.US)
)

private val usdFormat = DecimalFormat(
    "#,##0.00",
    DecimalFormatSymbols(Locale.US)
)

private val smallUsdFormat = DecimalFormat(
    "0.0000",
    DecimalFormatSymbols(Locale.US)
)

private fun formatAmount(value: Double): String {
    if (!value.isFinite()) return "—"
    return when {
        value == 0.0 -> "0"
        value < 0.00000001 -> "<0.00000001"
        else -> amountFormat.format(value)
    }
}

private fun formatUsd(value: Double): String {
    if (!value.isFinite() || value <= 0.0) return "$0.00"
    return if (value < 1.0) "$" + smallUsdFormat.format(value) else "$" + usdFormat.format(value)
}

private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return "never"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> "${diff / 86_400_000L}d ago"
    }
}
