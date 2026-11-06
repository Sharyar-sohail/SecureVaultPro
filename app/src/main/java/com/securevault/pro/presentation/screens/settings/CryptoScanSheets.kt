package com.securevault.pro.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.securevault.pro.crypto.ChainDescriptor
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.EvmCrypto
import com.securevault.pro.data.wallet.CustomToken
import com.securevault.pro.data.wallet.EvmTokenProbe
import com.securevault.pro.data.wallet.WalletPreferences
import com.securevault.pro.presentation.components.GhostButton
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.TagChip
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultTextField
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultShapes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the scanner is told to do, in the owner's hands.
 *
 * Two decisions live here: which chains the wallet walks, and which contracts it reads even though
 * no public token list publishes them. Both are plain settings — a chain name and a public contract
 * address are not secrets — so neither is ever synced, backed up or sent anywhere.
 */
data class ScanNetworkRow(
    val networkId: String,
    val displayName: String,
    val tokenStandard: String,
    val nativeSymbol: String,
    val enabled: Boolean,
    /** Whether this app can read a token on that chain at all. */
    val acceptsCustomTokens: Boolean
)

data class CryptoScanUiState(
    val networks: List<ScanNetworkRow> = emptyList(),
    val customTokens: List<CustomToken> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null
) {
    val enabledCount: Int get() = networks.count { it.enabled }
    val chainsThatTakeTokens: List<ScanNetworkRow> get() = networks.filter { it.acceptsCustomTokens }
}

@HiltViewModel
class CryptoScanViewModel @Inject constructor(
    private val preferences: WalletPreferences,
    private val probe: EvmTokenProbe
) : ViewModel() {

    private val _state = MutableStateFlow(CryptoScanUiState())
    val state: StateFlow<CryptoScanUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.enabledNetworkIds, preferences.customTokens) { enabled, tokens ->
                enabled to tokens
            }.catch { emit(emptySet<String>() to emptyList()) }
                .collect { (enabled, tokens) ->
                    _state.update { current ->
                        current.copy(
                            networks = ChainRegistry.all().map { it.toRow(enabled) },
                            customTokens = tokens
                        )
                    }
                }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(notice = null, error = null) }
    }

    /**
     * Every chain left on is scanned; switching all off is allowed but pointless, so it says so
     * instead of leaving an empty wallet with no reason attached.
     */
    fun setNetworkEnabled(networkId: String, enabled: Boolean) {
        if (!enabled && _state.value.enabledCount <= 1) {
            _state.update {
                it.copy(error = "Keep at least one chain on, or the wallet has nothing left to read.")
            }
            return
        }
        viewModelScope.launch { preferences.setNetworkEnabled(networkId, enabled) }
    }

    /**
     * Reads the contract from the chain and only then stores it.
     *
     * A pasted ticker is never trusted — a thousand tokens call themselves "GOLD" — so the symbol,
     * name and decimals shown afterwards are the ones the contract itself answered with. An address
     * that answers as nothing is refused, and the owner is told which chain it was tried on.
     */
    fun addToken(networkId: String, contractInput: String) {
        val state = _state.value
        if (state.busy) return
        val network = CryptoNetwork.entries.firstOrNull { it.id == networkId }
        val descriptor = network?.let(ChainRegistry::descriptor)
        if (descriptor == null || !descriptor.isEvm) {
            _state.update { it.copy(error = "That chain holds only its own coin, so no token can be added to it.") }
            return
        }
        val contract = EvmCrypto.normalize(contractInput)
        if (contract == null) {
            _state.update {
                it.copy(error = "A ${descriptor.tokenStandard} contract is 0x followed by 40 letters and digits. " +
                    "Copy it from the token's own page.")
            }
            return
        }
        if (state.customTokens.any { it.networkId == network.id && it.contract.equals(contract, true) }) {
            _state.update { it.copy(error = "${descriptor.displayName} already reads that contract.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, notice = null, error = null) }
            val read = runCatching { probe.describe(descriptor.rpcEndpoints, contract) }.getOrNull()
            if (read == null) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "No ${descriptor.tokenStandard} token answered at that address on " +
                            "${descriptor.displayName}. Check the contract and the chain, then try again."
                    )
                }
                return@launch
            }
            val problem = preferences.addCustomToken(
                CustomToken(
                    networkId = network.id,
                    contract = contract,
                    symbol = read.symbol,
                    name = read.name,
                    decimals = read.decimals
                )
            )
            _state.update {
                it.copy(
                    busy = false,
                    notice = problem ?: "${read.symbol} · ${read.name} added. It is read first on every " +
                        "${descriptor.displayName} scan from now on.",
                    error = problem
                )
            }
        }
    }

    fun removeToken(token: CustomToken) {
        viewModelScope.launch {
            preferences.removeCustomToken(token.networkId, token.contract)
            _state.update {
                it.copy(notice = "${token.symbol} will no longer be read on ${chainName(token.networkId)}.")
            }
        }
    }

    private fun chainName(networkId: String): String =
        ChainRegistry.descriptorOrNull(networkId)?.displayName ?: "that chain"
}

private fun ChainDescriptor.toRow(enabled: Set<String>) = ScanNetworkRow(
    networkId = network.id,
    displayName = displayName,
    tokenStandard = tokenStandard,
    nativeSymbol = nativeSymbol,
    enabled = network.id in enabled,
    acceptsCustomTokens = isEvm
)

/**
 * The chain board. Every chain the app speaks is one row with a switch, and the switch says what it
 * does in wallet terms: a chain switched off is not scanned, so its coins stop refreshing while the
 * last figures known stay on screen rather than falling to zero.
 */
@Composable
fun ManageNetworksSheet(
    onDismiss: () -> Unit,
    viewModel: CryptoScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        dragHandle = { BottomSheetDefaults.DragHandle(color = VaultColor.hairline) }
    ) {
        SheetHeader(
            title = "Manage networks",
            subtitle = "${state.enabledCount} of ${state.networks.size} chains scanned · " +
                "a chain switched off stops refreshing, its last balances stay visible",
            onDismiss = onDismiss
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                state.networks.forEachIndexed { index, row ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(VaultColor.hairline.copy(alpha = 0.45f))
                        )
                    }
                    NetworkSwitchRow(
                        row = row,
                        onToggle = { enabled -> viewModel.setNetworkEnabled(row.networkId, enabled) }
                    )
                }
            }
            SheetMessage(state.notice, state.error)
        }
    }
}

@Composable
private fun NetworkSwitchRow(row: ScanNetworkRow, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (row.enabled) VaultColor.credit else VaultColor.hairline,
                    CircleShape
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColor.textStrong,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${row.nativeSymbol} · ${row.tokenStandard}",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint
            )
        }
        Switch(
            checked = row.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VaultColor.background,
                checkedTrackColor = VaultColor.credit,
                uncheckedThumbColor = VaultColor.textFaint,
                uncheckedTrackColor = VaultColor.elevated
            )
        )
    }
}

/**
 * The token list the owner wrote themselves. A contract is added by address, named by the chain, and
 * removed with one tap; what it does to the scan is said in the same words the scanner uses.
 */
@Composable
fun ManageTokensSheet(
    onDismiss: () -> Unit,
    viewModel: CryptoScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val chains = state.chainsThatTakeTokens
    var selected by remember(chains) { mutableStateOf(chains.firstOrNull()?.networkId.orEmpty()) }
    var contract by remember { mutableStateOf("") }

    // The field is cleared when the chain has answered, not when the button was pressed: a press
    // that is still reading the contract must keep the address on screen.
    LaunchedEffect(state.notice) {
        if (state.notice != null) contract = ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VaultColor.surface,
        contentColor = VaultColor.textStrong,
        dragHandle = { BottomSheetDefaults.DragHandle(color = VaultColor.hairline) }
    ) {
        SheetHeader(
            title = "Manage tokens",
            subtitle = "Add a token by its contract address · the app reads its name from the chain, " +
                "it never trusts a ticker you type",
            onDismiss = onDismiss
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chains.forEach { chain ->
                    ChainChip(
                        label = chain.displayName,
                        selected = chain.networkId == selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selected = chain.networkId
                            viewModel.clearMessage()
                        }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            VaultTextField(
                value = contract,
                onValueChange = { contract = it },
                label = "Contract address",
                placeholder = "0x…",
                singleLine = true,
                imeAction = ImeAction.Done,
                onImeSubmit = { viewModel.addToken(selected, contract) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                supportingText = "Find it on the token's own page or its chain explorer — that address is its only true name.",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            GhostButton(
                text = "Read the chain and add it",
                icon = Glyph.add,
                accent = VaultColor.accent,
                enabled = contract.isNotBlank() && selected.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.addToken(selected, contract) }
            )
            Spacer(Modifier.height(6.dp))
            SheetMessage(state.notice, state.error)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Your tokens",
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    modifier = Modifier.weight(1f)
                )
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = VaultColor.accent
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (state.customTokens.isEmpty()) {
                Text(
                    text = "None yet. Every public token list is already scanned; add a contract only " +
                        "when a token you own stays missing from the portfolio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VaultColor.textFaint,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.customTokens.forEach { token ->
                        CustomTokenRow(
                            token = token,
                            chainName = ChainRegistry.descriptorOrNull(token.networkId)?.displayName
                                ?: token.networkId,
                            onRemove = { viewModel.removeToken(token) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomTokenRow(token: CustomToken, chainName: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = token.symbol,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColor.textStrong,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${token.name} · ${token.contract}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = VaultColor.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        TagChip(chainName, accent = VaultColor.accent)
        IconButtonSlot(icon = Glyph.delete, description = "Remove ${token.symbol}", onClick = onRemove)
    }
}

@Composable
private fun IconButtonSlot(icon: Glyph, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(VaultShapes.input)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = description,
            tint = VaultColor.textFaint,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun ChainChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = if (selected) VaultColor.accent else VaultColor.textFaint
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) VaultColor.textStrong else VaultColor.textBody,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(VaultShapes.input)
            .background(
                if (selected) VaultColor.accent.copy(alpha = 0.18f) else VaultColor.elevated
            )
            .border(1.dp, if (selected) accent else VaultColor.hairline, VaultShapes.input)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    )
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = VaultColor.textStrong
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColor.textFaint,
                lineHeight = 17.sp
            )
        }
        TextButton(onClick = onDismiss) {
            Text("Done", color = VaultColor.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SheetMessage(notice: String?, error: String?) {
    if (notice == null && error == null) return
    Text(
        text = error ?: notice.orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        color = if (error != null) VaultColor.warning else VaultColor.credit,
        lineHeight = 16.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
