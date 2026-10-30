package com.securevault.pro.presentation.screens.wallet

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.pro.domain.model.DigitalCard
import com.securevault.pro.presentation.components.BiometricUtils
import com.securevault.pro.presentation.components.ConfirmDialog
import com.securevault.pro.presentation.components.EmptyState
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.GlassCard
import com.securevault.pro.presentation.components.GlassIconButton
import com.securevault.pro.presentation.components.GlassScaffold
import com.securevault.pro.presentation.components.LocalSecureClipboard
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.ThemedIconTile
import com.securevault.pro.presentation.components.vaultGutter
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultType
import com.securevault.pro.presentation.components.Glyph

@Composable
fun WalletScreen(
    onAddCard: () -> Unit,
    onEditCard: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onLock: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalSecureClipboard.current
    val context = LocalContext.current
    var revealedCardId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DigitalCard?>(null) }
    var paySheetOpen by remember { mutableStateOf(false) }
    var payRevealedId by remember { mutableStateOf<String?>(null) }
    var payMessage by remember { mutableStateOf<String?>(null) }

    fun revealCard(card: DigitalCard, onGranted: (DigitalCard) -> Unit, onDenied: (String) -> Unit) {
        if (card.number.isBlank() && card.cvv.isBlank() && card.pin.isBlank()) {
            onDenied("This card has no secrets stored on this device.")
            return
        }
        val activity = context as? FragmentActivity
        when {
            activity == null -> onDenied("A secure activity is required to authenticate.")
            !BiometricUtils.canAuthenticate(context) ->
                onDenied("Enrol a face or fingerprint to reveal card secrets.")
            else -> BiometricUtils.authenticate(
                activity = activity,
                title = "Reveal card secrets",
                subtitle = "Use your face or fingerprint to view the CVV and PIN",
                onSuccess = { onGranted(card) },
                onError = onDenied
            )
        }
    }

    GlassScaffold(
        title = "Wallet",
        subtitle = "${state.cards.size} encrypted cards",
        actions = {
            GlassIconButton(
                icon = Glyph.lock,
                contentDescription = "Lock vault",
                onClick = onLock,
                tint = VaultColor.accent
            )
        },
        fab = {
            val heroFill = VaultColor.heroFill()
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(heroFill)),
                contentAlignment = Alignment.Center
            ) {
                GlassIconButton(
                    icon = Glyph.add,
                    contentDescription = "Add card",
                    onClick = onAddCard,
                    tint = VaultColor.onGradient(heroFill),
                    background = Color.Transparent,
                    size = 58.dp
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = vaultGutter(),
                        end = vaultGutter(),
                top = 4.dp,
                bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                WalletSections(
                    onOpen = onOpenCategory,
                    onPay = {
                        payMessage = null
                        payRevealedId = null
                        paySheetOpen = true
                    }
                )
            }
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    accent = VaultColor.credit,
                    borderColor = VaultColor.credit.copy(alpha = 0.22f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = Glyph.fingerprint,
                            contentDescription = null,
                            tint = VaultColor.credit,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Tap a card to flip it. CVV and PIN are revealed with your face or fingerprint.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VaultColor.textBody,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
            if (state.cards.isEmpty()) {
                item {
                    EmptyState(
                        icon = Glyph.creditCard,
                        title = "No payment cards yet",
                        message = "Add a card below. Numbers, CVV and PIN are sealed with AES-256-GCM and only revealed after authentication.",
                        actionLabel = "Add a card",
                        onAction = onAddCard
                    )
                }
            } else {
                items(state.cards, key = { it.id }) { card ->
                    val revealed = revealedCardId == card.id
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = card.nickname,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = VaultColor.textStrong,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${card.brand} · ${card.type.lowercase().replaceFirstChar { it.uppercase() }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VaultColor.textFaint
                                )
                            }
                            if (!state.decoy) {
                                GlassIconButton(
                                    icon = Glyph.edit,
                                    contentDescription = "Edit card",
                                    onClick = { onEditCard(card.id) },
                                    tint = VaultColor.accent,
                                    background = Color.Transparent,
                                    size = 34.dp
                                )
                                Spacer(Modifier.width(4.dp))
                                GlassIconButton(
                                    icon = Glyph.delete,
                                    contentDescription = "Delete card",
                                    onClick = { pendingDelete = card },
                                    tint = VaultColor.debit,
                                    background = Color.Transparent,
                                    size = 34.dp
                                )
                            }
                        }
                        FlipCard(card = card, revealed = revealed)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton(
                                text = "Copy number",
                                icon = Glyph.creditCard,
                                accent = VaultColor.textBody,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (card.number.isNotBlank()) {
                                        clipboard.copy(card.number, "Card number")
                                    } else {
                                        message = "This card number is sealed on another device."
                                    }
                                }
                            )
                            GhostButton(
                                text = if (revealed) "Hide secrets" else "Reveal secrets",
                                icon = if (revealed) Glyph.visibilityOff else Glyph.fingerprint,
                                accent = VaultColor.credit,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (revealed) {
                                        revealedCardId = null
                                    } else {
                                        message = null
                                        revealCard(
                                            card,
                                            onGranted = { revealedCardId = it.id },
                                            onDenied = { message = it }
                                        )
                                    }
                                }
                            )
                        }
                        if (revealed) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton(
                                    text = "Copy CVV",
                                    icon = Glyph.visibility,
                                    accent = VaultColor.warning,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (card.cvv.isNotBlank()) clipboard.copy(card.cvv, "Card CVV")
                                    }
                                )
                                GhostButton(
                                    text = "Copy PIN",
                                    icon = Glyph.lock,
                                    accent = VaultColor.warning,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (card.pin.isNotBlank()) clipboard.copy(card.pin, "Card PIN")
                                    }
                                )
                            }
                        }
                        if (message != null) {
                            Text(
                                text = message.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.warning,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            if (state.decoy) {
                item {
                    Text(
                        text = "Decoy wallet · read-only demonstration data",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.warning,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }

    ConfirmDialog(
        visible = pendingDelete != null,
        title = "Remove this card?",
        message = "The card number, CVV and PIN will be permanently deleted from this device.",
        confirmLabel = "Remove",
        onConfirm = {
            pendingDelete?.let { viewModel.delete(it.id) }
            pendingDelete = null
            revealedCardId = null
        },
        onDismiss = { pendingDelete = null }
    )

    if (paySheetOpen) {
        PaySheet(
            cards = state.cards,
            revealedId = payRevealedId,
            message = payMessage ?: if (state.decoy) {
                "Card secrets stay hidden in demonstration mode."
            } else {
                null
            },
            onSelect = { card ->
                if (state.decoy) {
                    payMessage = "Unlock your real vault to use quick pay."
                } else {
                    payMessage = null
                    revealCard(
                        card,
                        onGranted = { payRevealedId = it.id },
                        onDenied = { payMessage = it }
                    )
                }
            },
            onHide = { payRevealedId = null },
            onCopy = { value, label ->
                if (value.isBlank()) {
                    payMessage = "That value is not stored for this card."
                } else {
                    clipboard.copy(value, label)
                }
            },
            onDismiss = {
                paySheetOpen = false
                payRevealedId = null
                payMessage = null
            }
        )
    }
}

@Composable
private fun WalletSections(onOpen: (String) -> Unit, onPay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "VAULT SECTIONS",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
            color = VaultColor.textFaint
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WalletSectionTile(
                label = "Pay",
                hint = "Reveal & copy",
                icon = Glyph.contactless,
                accent = VaultColor.debit,
                modifier = Modifier.weight(1f),
                onClick = onPay
            )
            WalletSectionTile(
                label = "Banking",
                hint = "Accounts & cards",
                icon = Glyph.accountBalance,
                accent = VaultColor.credit,
                modifier = Modifier.weight(1f),
                onClick = { onOpen("BANKING") }
            )
            WalletSectionTile(
                label = "IDs",
                hint = "IDs & licences",
                icon = Glyph.badge,
                accent = VaultColor.decorative,
                modifier = Modifier.weight(1f),
                onClick = { onOpen("IDENTITY") }
            )
            WalletSectionTile(
                label = "Vehicles",
                hint = "Papers & expiry",
                icon = Glyph.directionsCar,
                accent = VaultColor.warning,
                modifier = Modifier.weight(1f),
                onClick = { onOpen("VEHICLE") }
            )
        }
    }
}

@Composable
private fun PaySheet(
    cards: List<DigitalCard>,
    revealedId: String?,
    message: String?,
    onSelect: (DigitalCard) -> Unit,
    onHide: () -> Unit,
    onCopy: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                .padding(start = 20.dp, end = 20.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Quick pay",
                style = MaterialTheme.typography.titleLarge,
                color = VaultColor.textStrong,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Unlock the card you are paying with. Number, expiry and CVV stay on screen until you hide them.",
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textBody,
                lineHeight = 18.sp
            )
            if (cards.isEmpty()) {
                Text(
                    text = "No cards in the vault yet. Add one from the Wallet screen first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textFaint
                )
            }
            cards.forEach { card ->
                val revealed = card.id == revealedId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(VaultShapes.card)
                        .background(VaultColor.background.copy(alpha = 0.55f))
                        .border(
                            1.dp,
                            if (revealed) VaultColor.debit.copy(alpha = 0.4f) else VaultColor.hairline,
                            VaultShapes.card
                        )
                        .clickable { if (revealed) onHide() else onSelect(card) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemedIcon(
                            glyph = if (revealed) Glyph.visibilityOff
                            else Glyph.fingerprint,
                            contentDescription = null,
                            tint = if (revealed) VaultColor.debit else VaultColor.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = card.nickname.ifBlank { card.brand },
                                style = MaterialTheme.typography.titleMedium,
                                color = VaultColor.textStrong,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = listOfNotNull(
                                    card.bankName.takeIf { it.isNotBlank() },
                                    "•••• ${card.last4}".takeIf { card.last4.isNotBlank() },
                                    card.holderName.takeIf { it.isNotBlank() }
                                ).joinToString(" · ").ifBlank { "No bank linked" },
                                style = MaterialTheme.typography.labelSmall,
                                color = VaultColor.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (revealed) {
                        Text(
                            text = groupCardNumber(card.number).ifBlank { "Sealed on another device" },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.5.sp
                            ),
                            color = VaultColor.textStrong
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PayDetail(
                                label = "Expiry",
                                value = card.expiryLabel,
                                modifier = Modifier.weight(1f),
                                onCopy = { onCopy(card.expiryLabel, "Expiry") }
                            )
                            PayDetail(
                                label = "CVV",
                                value = card.cvv.ifBlank { "·" },
                                modifier = Modifier.weight(1f),
                                onCopy = { onCopy(card.cvv, "CVV") }
                            )
                            PayDetail(
                                label = "PIN",
                                value = card.pin.ifBlank { "·" },
                                modifier = Modifier.weight(1f),
                                onCopy = { onCopy(card.pin, "PIN") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton(
                                text = "Copy number",
                                icon = Glyph.creditCard,
                                accent = VaultColor.accent,
                                modifier = Modifier.weight(1f),
                                onClick = { onCopy(card.number, "Card number") }
                            )
                            GhostButton(
                                text = "Hide",
                                icon = Glyph.visibilityOff,
                                accent = VaultColor.textBody,
                                modifier = Modifier.weight(1f),
                                onClick = onHide
                            )
                        }
                    }
                }
            }
            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.warning
                )
            }
        }
    }
}

@Composable
private fun PayDetail(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(VaultShapes.input)
            .background(VaultColor.accent.copy(alpha = 0.07f))
            .clickable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = VaultColor.textFaint)
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            color = VaultColor.textStrong,
            maxLines = 1
        )
    }
}

private fun groupCardNumber(number: String): String {
    val digits = number.filter(Char::isDigit)
    if (digits.isEmpty()) return number.trim()
    return digits.chunked(4).joinToString(" ")
}

@Composable
private fun WalletSectionTile(
    label: String,
    hint: String,
    icon: Glyph,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    Column(
        modifier = modifier
            .clip(palette.chipShape)
            .background(accent.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        ThemedIconTile(
            icon = icon,
            contentDescription = null,
            accent = accent,
            size = 30.dp,
            iconSize = 17.dp,
            background = accent.copy(alpha = 0.14f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = VaultColor.textStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = hint,
            style = VaultType.micro(),
            color = VaultColor.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FlipCard(card: DigitalCard, revealed: Boolean) {
    var flipped by remember(card.id) { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) flipped = true
    }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(480),
        label = "cardFlip"
    )
    val start = remember(card.colorGradientStart) {
        parseHexColor(card.colorGradientStart, fallback = 0xFF1E273E)
    }
    val end = remember(card.colorGradientEnd) {
        parseHexColor(card.colorGradientEnd, fallback = 0xFF141B2D)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(212.dp)
            .shadow(
                elevation = 22.dp,
                shape = VaultShapes.card,
                ambientColor = start.copy(alpha = 0.5f),
                spotColor = start.copy(alpha = 0.6f)
            )
            .clip(VaultShapes.card)
            .clickable { flipped = !flipped }
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 18f * density
            }
    ) {
        if (rotation <= 90f) {
            CardFront(card = card, start = start, end = end)
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) {
                CardBack(card = card, revealed = revealed, start = start)
            }
        }
    }
}

@Composable
private fun CardFront(card: DigitalCard, start: Color, end: Color) {
    val palette = VaultPaletteController.current
    val ink = VaultColor.onGradient(listOf(start, end))
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(start, end)))
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .offset(x = 220.dp, y = (-70).dp)
                .background(
                    Brush.radialGradient(
                        listOf(palette.cyan.copy(alpha = 0.12f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(150.dp)
                .offset(x = (-60).dp, y = 150.dp)
                .background(
                    Brush.radialGradient(
                        listOf(palette.indigo.copy(alpha = 0.08f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.bankName.uppercase().ifBlank { card.nickname.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = ink.copy(alpha = 0.82f),
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ThemedIcon(
                    glyph = Glyph.contactless,
                    contentDescription = null,
                    tint = ink.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemedIcon(
                    glyph = Glyph.memory,
                    contentDescription = null,
                    tint = VaultColor.warning,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = maskedNumber(card),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    color = ink,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "CARD HOLDER",
                        style = VaultType.micro(),
                        color = ink.copy(alpha = 0.72f),
                    )
                    Text(
                        text = card.holderName.uppercase().ifBlank { "UNNAMED" },
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "VALID THRU",
                        style = VaultType.micro(),
                        color = ink.copy(alpha = 0.72f),
                    )
                    Text(
                        text = card.expiryLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                        color = ink
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = card.brand,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun CardBack(card: DigitalCard, revealed: Boolean, start: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(VaultColor.background, start.copy(alpha = 0.55f))))
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SECURE ELEMENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
                ThemedIcon(
                    glyph = Glyph.lock,
                    contentDescription = null,
                    tint = VaultColor.credit.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(VaultColor.void)
            )
            Row(
                modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BackSecret(
                    label = "CVV",
                    value = if (revealed) card.cvv.ifBlank { "—" } else "•••",
                    revealed = revealed,
                    modifier = Modifier.weight(1f)
                )
                BackSecret(
                    label = "PIN",
                    value = if (revealed) card.pin.ifBlank { "—" } else "••••",
                    revealed = revealed,
                    modifier = Modifier.weight(1f)
                )
                BackSecret(
                    label = "ENDING",
                    value = card.last4.ifBlank { "••••" },
                    revealed = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.padding(horizontal = vaultGutter(), vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (revealed) "Authenticated this session" else "Authenticate to reveal secrets",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (revealed) VaultColor.credit else VaultColor.textFaint,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = card.expiryLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = VaultColor.textBody
                )
            }
        }
    }
}

@Composable
private fun BackSecret(
    label: String,
    value: String,
    revealed: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(VaultShapes.input)
            .background(VaultColor.surfaceLift.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = VaultType.micro(),
            color = VaultColor.textFaint,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (revealed) VaultColor.textStrong else VaultColor.textBody,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun maskedNumber(card: DigitalCard): String {
    val last4 = card.last4.ifBlank { "••••" }
    return "••••  ••••  ••••  $last4"
}

private fun parseHexColor(value: String, fallback: Long): Color {
    return try {
        Color(android.graphics.Color.parseColor(value))
    } catch (_: Throwable) {
        Color(fallback)
    }
}
