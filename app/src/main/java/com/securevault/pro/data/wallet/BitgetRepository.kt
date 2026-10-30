package com.securevault.pro.data.wallet

import com.securevault.pro.domain.model.BitgetAsset
import com.securevault.pro.domain.model.BitgetCredentialState
import com.securevault.pro.domain.model.BitgetFailure
import com.securevault.pro.domain.model.BitgetFailureKind
import com.securevault.pro.domain.model.BitgetRead
import com.securevault.pro.domain.model.BitgetSecretBundle
import com.securevault.pro.domain.model.BitgetSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the read-only Bitget account panel: seal the credentials, call the two documented
 * balance endpoints, price the result with the app's existing keyless price sources, and keep the
 * last read.
 *
 * Two rules the rest of the class exists to enforce:
 *
 * 1. The plaintext credentials are read out of storage **inside one [refresh]**, used for one signed
 *    request each, and never returned from this class, stored in a state object, or written to a log.
 *    [connect] is the only other place they appear, and it hands them straight to the sealer.
 * 2. Only [BitgetSnapshot] — symbols and numbers — is persisted after a read. Nothing credential-like
 *    can be: [BitgetCredentialStore.storeSnapshot] refuses a document that names a credential field.
 */
@Singleton
class BitgetRepository @Inject constructor(
    private val client: BitgetClient,
    private val store: BitgetCredentialStore,
    private val balances: CryptoBalanceService
) {

    /** Masked, renderable state, or null when the owner has not added a key. */
    suspend fun credentialState(): BitgetCredentialState? = store.state()

    /** The last read, kept so the panel opens instantly and offline instead of showing a spinner. */
    suspend fun lastSnapshot(): BitgetSnapshot? = store.snapshot()

    /**
     * Validates, seals and saves, then performs the first read so the owner sees numbers rather than
     * a thank-you message. A rejected read keeps the credentials: the clock fix or a re-typed value
     * should not cost the owner the whole entry again.
     */
    suspend fun connect(label: String, bundle: BitgetSecretBundle): BitgetRead {
        val problem = BitgetCredentials.validate(bundle)
        if (problem != null) {
            return BitgetRead.Failed(
                BitgetFailure(kind = BitgetFailureKind.OTHER, message = problem.message)
            )
        }
        val saved = store.save(bundle, label)
        if (!saved.ok) {
            return BitgetRead.Failed(
                BitgetFailure(
                    kind = BitgetFailureKind.OTHER,
                    message = saved.message ?: "This device could not store the credentials."
                )
            )
        }
        return refresh()
    }

    /**
     * One signed pass over both account legs. Never retried: a clock-skew or rate-limit rejection is
     * handed back as words, not hammered until the key gets locked.
     */
    suspend fun refresh(): BitgetRead = withContext(Dispatchers.IO) {
        val secrets = store.secrets()
            ?: return@withContext BitgetRead.Failed(
                BitgetFailure(
                    kind = BitgetFailureKind.INVALID_KEY,
                    message = "No Bitget key is stored on this device yet. Add the three values first."
                )
            )

        val spotCall = client.fetchSpotAssets(secrets)
        val mixCall = client.fetchMixAccounts(secrets, BitgetClient.MIX_PRODUCT_TYPE)

        val spotFailure = (spotCall as? BitgetClient.Call.Rejected)?.failure
        val mixFailure = (mixCall as? BitgetClient.Call.Rejected)?.failure
        if (spotFailure != null && mixFailure != null) return@withContext BitgetRead.Failed(spotFailure)

        val spotAssets = (spotCall as? BitgetClient.Call.Accepted)
            ?.let { BitgetClient.spotAssets(it.data) }
            .orEmpty()
        val mixAssets = (mixCall as? BitgetClient.Call.Accepted)
            ?.let { BitgetClient.mixAssets(it.data) }
            .orEmpty()

        val notice = when {
            spotFailure != null -> "Spot account not read: " + spotFailure.message
            mixFailure != null -> "Futures account not read: " + mixFailure.message
            else -> null
        }

        val merged = spotAssets + mixAssets
        val prices = runCatching { balances.usdPrices(merged.map { it.symbol }) }.getOrDefault(emptyMap())
        val priced = merged.map { asset ->
            asset.copy(
                priceUsd = prices[asset.symbol.uppercase()]
                    ?: if (asset.symbol.equals("USDT", true) || asset.symbol.equals("USDC", true)) 1.0 else 0.0
            )
        }
        // A leg that answered with nothing at all is still an answer; say so instead of a blank card.
        val text = if (priced.isEmpty() && notice == null) {
            "Bitget answered: this account holds nothing in either wallet right now."
        } else {
            notice
        }
        val snapshot = BitgetSnapshot(
            assets = priced.sortedWith(compareByDescending<BitgetAsset> { it.usd }.thenBy { it.symbol }),
            fetchedAt = System.currentTimeMillis(),
            notice = text
        )
        store.storeSnapshot(snapshot)
        BitgetRead.Loaded(snapshot)
    }

    /** Immediate, total removal of everything this feature stored. */
    suspend fun disconnect(): Boolean {
        store.clear()
        return store.state() == null
    }
}
