package com.securevault.pro.presentation.screens.crypto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.WalletAddress
import com.securevault.pro.crypto.WalletImportKind
import com.securevault.pro.data.repository.VaultRepository
import com.securevault.pro.data.wallet.CryptoBalanceService
import com.securevault.pro.data.wallet.CryptoTransferService
import com.securevault.pro.data.wallet.WalletKeyRecord
import com.securevault.pro.data.wallet.WalletRepository
import com.securevault.pro.domain.model.CryptoWallet
import com.securevault.pro.domain.model.TokenBalance
import com.securevault.pro.domain.model.TransferAsset
import com.securevault.pro.domain.model.TransferQuote
import com.securevault.pro.domain.model.TransferReceipt
import com.securevault.pro.security.AppLockManager
import com.securevault.pro.security.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

data class TransferUiState(
    val open: Boolean = false,
    val walletId: String? = null,
    val asset: TransferAsset? = null,
    val recipient: String = "",
    val amount: String = "",
    val recipientError: String? = null,
    val quote: TransferQuote? = null,
    val quoting: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    val receipt: TransferReceipt? = null
)

data class CryptoUiState(
    val wallets: List<CryptoWallet> = emptyList(),
    val decoy: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val refreshing: Set<String> = emptySet(),
    val scanErrors: Map<String, String> = emptyMap(),
    val error: String? = null,
    val transfer: TransferUiState = TransferUiState(),
    val keys: KeyUiState = KeyUiState()
) {
    val totalUsd: Double get() = wallets.sumOf { it.totalUsd }
    val lastSyncedAt: Long get() = wallets.maxOfOrNull { it.lastSyncedAt } ?: 0L
    val fundedCount: Int get() = wallets.sumOf { wallet -> wallet.fundedAssets().size }
}

/**
 * State of the private-key store screens.
 *
 * [revealedKey] is the only place an unsealed key exists outside a signing call, it is set for one
 * wallet at a time behind a fresh authorisation, and it is dropped by [hideKey] when the sheet
 * closes. It is never written to a log, to CrashLogger, to a file, or into a test fixture — the
 * value exists so the owner can read it once, and nothing else may read it.
 */
data class KeyUiState(
    val records: List<WalletKeyRecord> = emptyList(),
    val preview: List<WalletAddress> = emptyList(),
    val previewError: String? = null,
    val revealTargetId: String? = null,
    val revealedKey: String? = null,
    val revealedAddress: String = "",
    val authError: String? = null,
    val busy: Boolean = false,
    val notice: String? = null
) {
    val revealing: Boolean get() = revealedKey != null
}

/** Only funded coins are surfaced; empty derivation addresses stay hidden. */
fun CryptoWallet.fundedAssets(): List<TransferAsset> = balances
    .filter { it.amount > 0.0 }
    .mapNotNull { balance ->
        val network = CryptoNetwork.entries.firstOrNull { it.id == balance.networkId }
            ?: return@mapNotNull null
        val native = balance.symbol == network.symbol
        val spec = if (native) null else CryptoTransferService.TOKEN_SPECS.firstOrNull {
            it.network == network && it.symbol.equals(balance.symbol, true)
        }
        if (!native && spec == null) return@mapNotNull null
        TransferAsset(
            networkId = network.id,
            networkName = network.displayName,
            symbol = balance.symbol,
            name = balance.name,
            contract = spec?.contract,
            decimals = spec?.decimals ?: network.decimals,
            isNative = native,
            amount = balance.amount,
            priceUsd = balance.priceUsd
        )
    }
    .distinctBy { it.key }

@HiltViewModel
class CryptoWalletViewModel @Inject constructor(
    private val repository: WalletRepository,
    private val vaultRepository: VaultRepository,
    private val transfers: CryptoTransferService,
    private val appLockManager: AppLockManager,
    sessionManager: SessionManager
) : ViewModel() {

    companion object {
        private const val STALE_AFTER_MS = 5 * 60 * 1000L
    }

    private val decoy = sessionManager.isDecoy()
    private val _state = MutableStateFlow(CryptoUiState(decoy = decoy, loading = !decoy))
    val state = _state.asStateFlow()

    init {
        loadKeys()
        viewModelScope.launch {
            var autoRefreshed = false
            repository.observeWallets().collect { wallets ->
                val visible = if (decoy) emptyList() else wallets
                _state.update { it.copy(wallets = visible, loading = false) }
                if (!decoy && !autoRefreshed) {
                    autoRefreshed = true
                    val now = System.currentTimeMillis()
                    visible.filter { now - it.lastSyncedAt > STALE_AFTER_MS }
                        .forEach { refresh(it.id) }
                }
            }
        }
    }

    fun importWallet(
        label: String,
        input: String,
        kind: WalletImportKind,
        expectedAddress: String? = null,
        onResult: (String?) -> Unit
    ) {
        if (decoy) {
            onResult("Demonstration mode cannot import wallets. Unlock with your real PIN.")
            return
        }
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = when (kind) {
                WalletImportKind.SEED_PHRASE -> repository.addFromMnemonic(label, input)
                WalletImportKind.PRIVATE_KEY ->
                    repository.addFromPrivateKey(label, input, expectedAddress)
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { wallet ->
                vaultRepository.notifyMutation()
                loadKeys()
                onResult(null)
                refresh(wallet.id)
            }.onFailure { throwable ->
                val message = throwable.message ?: "Could not import this wallet"
                _state.update { it.copy(error = message) }
                onResult(message)
            }
        }
    }

    fun refresh(id: String) {
        if (decoy || id in _state.value.refreshing) return
        viewModelScope.launch {
            _state.update { current ->
                current.copy(
                    refreshing = current.refreshing + id,
                    // A refreshAll batch starts with an empty set; later parallel
                    // refreshes must not wipe the notices collected so far.
                    scanErrors = if (current.refreshing.isEmpty()) emptyMap() else current.scanErrors,
                    error = null
                )
            }
            val result = repository.refresh(id)
            // Balances, the portfolio total and lastSyncedAt are Room state, so a scan
            // is a vault mutation: without this the Drive mirror keeps advertising the
            // figures from before the refresh.
            if (result.isSuccess) vaultRepository.notifyMutation()
            _state.update { current ->
                val errors = result.getOrNull()?.errors.orEmpty()
                    .mapKeys { (networkId, _) ->
                        CryptoNetwork.entries.firstOrNull { it.id == networkId }?.displayName ?: networkId
                    }
                current.copy(
                    refreshing = current.refreshing - id,
                    scanErrors = errors,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun refreshAll() {
        if (decoy) return
        _state.value.wallets.forEach { refresh(it.id) }
    }

    fun delete(id: String) {
        if (decoy) return
        viewModelScope.launch {
            repository.delete(id)
            vaultRepository.notifyMutation()
            loadKeys()
        }
    }

    // ------------------------------------------------------------ private keys

    /**
     * Derives the addresses a candidate key would own, without storing anything. The import sheet
     * cannot proceed until the owner confirms one of these is the address they expect.
     */
    fun previewPrivateKey(rawKey: String) {
        if (decoy) return
        val result = repository.previewPrivateKey(rawKey)
        _state.update {
            it.copy(
                keys = it.keys.copy(
                    preview = result.getOrDefault(emptyList()),
                    previewError = result.exceptionOrNull()?.message
                )
            )
        }
    }

    fun clearKeyPreview() =
        _state.update { it.copy(keys = it.keys.copy(preview = emptyList(), previewError = null)) }

    /** Masked list of what this device holds. Decrypts nothing. */
    fun loadKeys() {
        if (decoy) return
        viewModelScope.launch {
            _state.update { it.copy(keys = it.keys.copy(records = repository.storedKeys())) }
        }
    }

    /** Opens the reveal sheet for one wallet. Nothing is unsealed until an authorisation lands. */
    fun beginReveal(walletId: String) {
        if (decoy) return
        viewModelScope.launch {
            val record = repository.keyRecord(walletId)
            if (record == null) {
                _state.update {
                    it.copy(keys = it.keys.copy(authError = "This device holds no key for that wallet."))
                }
                return@launch
            }
            _state.update {
                it.copy(
                    keys = it.keys.copy(
                        revealTargetId = walletId,
                        revealedKey = null,
                        revealedAddress = record.maskedAddress,
                        authError = null,
                        notice = null
                    )
                )
            }
        }
    }

    /** Called by the sheet after a successful biometric prompt: the fresh authorisation is the key. */
    fun revealAfterBiometric(walletId: String) {
        if (decoy) return
        unsealForReveal(walletId)
    }

    /** The master-password route, for a device with no enrolled biometric. */
    fun revealAfterPassword(walletId: String, password: String) {
        if (decoy) return
        viewModelScope.launch {
            val ok = runCatching { appLockManager.verifyMasterPassword(password) }.getOrDefault(false)
            if (!ok) {
                _state.update {
                    it.copy(keys = it.keys.copy(authError = "That master password does not match this vault."))
                }
                return@launch
            }
            unsealForReveal(walletId)
        }
    }

    private fun unsealForReveal(walletId: String) {
        viewModelScope.launch {
            _state.update { it.copy(keys = it.keys.copy(busy = true, authError = null)) }
            val key = repository.exportedKeyHex(walletId)
            _state.update { current ->
                current.copy(
                    busy = false,
                    keys = current.keys.copy(
                        busy = false,
                        revealedKey = key,
                        authError = if (key == null) {
                            "This device could not unseal the key. The vault may need unlocking again."
                        } else {
                            null
                        }
                    )
                )
            }
        }
    }

    /** Drops the unsealed value from memory. Called when the sheet closes or the copy is done. */
    fun hideKey() {
        _state.update { it.copy(keys = it.keys.copy(revealedKey = null, revealTargetId = null)) }
    }

    /** Removes the sealed key and confirms by read-back, so "Disconnected" is a checked claim. */
    fun disconnectKey(walletId: String, onResult: (String) -> Unit) {
        if (decoy) return
        viewModelScope.launch {
            _state.update { it.copy(keys = it.keys.copy(busy = true)) }
            val removed = repository.delete(walletId)
            val stillThere = repository.keyRecord(walletId) != null
            _state.update { current ->
                current.copy(
                    keys = current.keys.copy(
                        busy = false,
                        revealedKey = null,
                        revealTargetId = null,
                        records = repository.storedKeys()
                    )
                )
            }
            onResult(
                when {
                    !removed -> "That wallet was not on this device."
                    stillThere -> "The wallet row is gone but the sealed key is still on disk. Try again."
                    else -> "Disconnected. The sealed key was wiped and read back as absent."
                }
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null, scanErrors = emptyMap()) }
    }

    // -------------------------------------------------------------- transfers

    fun openSend(wallet: CryptoWallet, asset: TransferAsset?) {
        if (decoy) return
        val funded = wallet.fundedAssets()
        _state.update {
            it.copy(
                transfer = TransferUiState(
                    open = true,
                    walletId = wallet.id,
                    asset = asset ?: funded.firstOrNull()
                )
            )
        }
    }

    fun selectAsset(asset: TransferAsset) {
        _state.update {
            it.copy(
                transfer = it.transfer.copy(
                    asset = asset,
                    quote = null,
                    error = null,
                    recipientError = null
                )
            )
        }
        revalidateRecipient()
    }

    fun clearQuote() {
        _state.update { it.copy(transfer = it.transfer.copy(quote = null, error = null)) }
    }

    fun setRecipient(value: String) {
        _state.update { it.copy(transfer = it.transfer.copy(recipient = value, quote = null, error = null)) }
        revalidateRecipient()
    }

    fun setAmount(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' }.take(24)
        _state.update { it.copy(transfer = it.transfer.copy(amount = filtered, quote = null, error = null)) }
    }

    fun useMax(wallet: CryptoWallet) {
        val transfer = _state.value.transfer
        val asset = transfer.asset ?: return
        val balance = asset.amount
        val max = when {
            !asset.isNative -> balance
            asset.networkId == CryptoNetwork.BITCOIN.id -> (balance - 0.00003).coerceAtLeast(0.0)
            asset.networkId == CryptoNetwork.TRON.id -> (balance - 1.1).coerceAtLeast(0.0)
            else -> (balance - 0.002).coerceAtLeast(0.0)
        }
        val text = BigDecimal.valueOf(max)
            .setScale(asset.decimals.coerceAtMost(8), RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        _state.update { it.copy(transfer = it.transfer.copy(amount = text, quote = null, error = null)) }
    }

    private fun revalidateRecipient() {
        val transfer = _state.value.transfer
        val asset = transfer.asset ?: return
        val network = CryptoNetwork.entries.firstOrNull { it.id == asset.networkId } ?: return
        val error = if (transfer.recipient.isBlank()) null
        else transfers.validateAddress(network, transfer.recipient)
        _state.update { it.copy(transfer = it.transfer.copy(recipientError = error)) }
    }

    fun review(wallet: CryptoWallet) {
        val transfer = _state.value.transfer
        val asset = transfer.asset ?: return
        if (transfer.quoting || transfer.sending) return
        if (transfer.recipientError != null) return
        if (transfer.recipient.isBlank() || transfer.amount.isBlank()) {
            _state.update {
                it.copy(transfer = it.transfer.copy(error = "Enter a recipient and an amount first."))
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(transfer = it.transfer.copy(quoting = true, error = null, quote = null))
            }
            val result = transfers.quote(wallet, asset, transfer.recipient, transfer.amount)
            _state.update { current ->
                val transfer = current.transfer
                result.fold(
                    onSuccess = { quote ->
                        current.copy(transfer = transfer.copy(quoting = false, quote = quote))
                    },
                    onFailure = { error ->
                        current.copy(
                            transfer = transfer.copy(
                                quoting = false,
                                error = error.message?.take(180) ?: "The transfer could not be prepared."
                            )
                        )
                    }
                )
            }
        }
    }

    fun confirmSend(wallet: CryptoWallet) {
        val transfer = _state.value.transfer
        val quote = transfer.quote ?: return
        if (transfer.sending) return
        viewModelScope.launch {
            _state.update { it.copy(transfer = it.transfer.copy(sending = true, error = null)) }
            val result = transfers.send(wallet, quote)
            result.fold(
                onSuccess = { receipt ->
                    _state.update {
                        it.copy(
                            transfer = it.transfer.copy(
                                sending = false,
                                receipt = receipt,
                                quote = null
                            )
                        )
                    }
                    vaultRepository.notifyMutation()
                    refresh(wallet.id)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            transfer = it.transfer.copy(
                                sending = false,
                                error = error.message?.take(240) ?: "The transfer could not be broadcast."
                            )
                        )
                    }
                }
            )
        }
    }

    fun closeTransfer() {
        if (_state.value.transfer.sending) return
        _state.update { it.copy(transfer = TransferUiState()) }
    }
}
