package com.securevault.pro.data.wallet

import android.content.Context
import com.securevault.pro.crypto.Bip32
import com.securevault.pro.crypto.Bip39
import com.securevault.pro.crypto.ChainRegistry
import com.securevault.pro.crypto.CryptoNetwork
import com.securevault.pro.crypto.EvmCrypto
import com.securevault.pro.crypto.Hash
import com.securevault.pro.crypto.Secp256k1
import com.securevault.pro.crypto.WalletAddress
import com.securevault.pro.crypto.WalletDeriver
import com.securevault.pro.crypto.WalletImportKind
import com.securevault.pro.data.backup.BackupCryptoWalletDto
import com.securevault.pro.data.local.dao.CryptoWalletDao
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import com.securevault.pro.domain.model.BalanceScan
import com.securevault.pro.domain.model.CryptoWallet
import com.securevault.pro.domain.model.TokenBalance
import com.securevault.pro.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletRepository @Inject constructor(
    private val dao: CryptoWalletDao,
    private val crypto: CryptoManager,
    private val balances: CryptoBalanceService,
    private val keyStore: WalletKeyStore,
    @ApplicationContext private val context: Context
) {

    private val englishWords: List<String> by lazy {
        context.assets.open("bip39_english.txt").bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun observeWallets(): Flow<List<CryptoWallet>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    /**
     * Derives or unseals the signing scalar for one chain. The decrypted secret lives only in this
     * call's stack; callers must never persist or log it.
     *
     * Two shapes of stored secret: a seed phrase wallet keeps its sealed phrase in the Room row, and
     * a key imported through the Violet path keeps only a reference there — the key itself is in
     * [WalletKeyStore], a file no sync segment reads.
     */
    suspend fun signingKey(id: String, network: CryptoNetwork): BigInteger? =
        withContext(Dispatchers.IO) {
            val entity = dao.getById(id) ?: return@withContext null
            val payload = crypto.decrypt(entity.secret)?.let(::parseSecretPayload)
                ?: return@withContext null
            if (payload.optString(SECRET_REF_FIELD).isNotBlank()) {
                return@withContext keyStore.key(id)?.let { hex ->
                    EvmCrypto.parsePrivateKeyHex(hex)
                        .let { parse -> if (parse is EvmCrypto.PrivateKeyParse.Accepted) parse.scalar else null }
                }
            }
            val secret = payload.optString("secret").trim()
            if (secret.isBlank()) return@withContext null
            val isSeed = payload.optString("kind")
                .equals(WalletImportKind.SEED_PHRASE.name, ignoreCase = true) ||
                secret.contains(' ')
            if (isSeed) {
                val seed = Bip39.toSeed(Bip39.normalize(secret), payload.optString("passphrase"))
                Bip32.derivePath(seed, network.derivationPath).key
                    .takeIf(Secp256k1::isValidPrivateKey)
            } else {
                Hash.bytesFromHex(secret)
                    ?.takeIf { it.size == 32 }
                    ?.let { BigInteger(1, it) }
                    ?.takeIf(Secp256k1::isValidPrivateKey)
            }
        }

    suspend fun addFromMnemonic(
        label: String,
        mnemonic: String,
        passphrase: String = ""
    ): Result<CryptoWallet> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = Bip39.normalize(mnemonic)
            val parts = Bip39.words(normalized)
            if (parts.size !in Bip39.VALID_WORD_COUNTS) {
                error("Enter a 12, 15, 18, 21 or 24 word recovery phrase.")
            }
            val unknown = parts.firstOrNull { it !in englishWords }
            if (unknown != null) error("\"$unknown\" is not a BIP39 English word.")
            if (!Bip39.validate(normalized, englishWords)) {
                error("That recovery phrase failed its checksum. Check the words and their order.")
            }
            val addresses = WalletDeriver.fromSeedPhrase(normalized, passphrase)
            val secretJson = JSONObject()
                .put("kind", WalletImportKind.SEED_PHRASE.name)
                .put("secret", normalized)
                .put("passphrase", passphrase)
                .toString()
            val entity = buildEntity(
                id = UUID.randomUUID().toString(),
                label = label.trim().ifBlank { "Seed wallet" },
                kind = WalletImportKind.SEED_PHRASE,
                secretJson = secretJson,
                addresses = addresses
            )
            dao.upsert(entity)
            entity.toDomain()
        }
    }

    /**
     * What a candidate private key would give, before anything is stored. The import sheet shows
     * these and the owner has to confirm the address they expect; an import without that agreement
     * is refused, because "the key parsed" is not the same claim as "the key is the one you meant".
     */
    fun previewPrivateKey(rawKey: String): Result<List<WalletAddress>> = runCatching {
        val key = parsePrivateKey(rawKey)
        WalletDeriver.fromPrivateKey(key)
    }

    /**
     * Imports a WIF-less 32-byte key. Validation order, each with a named reason: hex shape, length,
     * non-zero, below the curve order, the address the owner expects must be one of the derived ones,
     * and only then is the key sealed — into [WalletKeyStore], never into the synced Room row.
     */
    suspend fun addFromPrivateKey(
        label: String,
        rawKey: String,
        expectedAddress: String? = null
    ): Result<CryptoWallet> = withContext(Dispatchers.IO) {
        runCatching {
            val key = parsePrivateKey(rawKey)
            val canonicalHex = "0x" + Hash.hex(Secp256k1.toFixed32(key))
            val addresses = WalletDeriver.fromPrivateKey(key)
            val wanted = expectedAddress?.trim().orEmpty()
            if (wanted.isNotEmpty()) {
                val matched = addresses.any {
                    it.address.equals(wanted, ignoreCase = true) ||
                        (it.network.let { n -> ChainRegistry.descriptor(n)?.isEvm == true } &&
                            EvmCrypto.toChecksum(it.address).equals(wanted, ignoreCase = true))
                }
                if (!matched) {
                    error(
                        "That key does not own $wanted. It derives " +
                            "${addresses.firstOrNull { it.network == CryptoNetwork.BSC }?.address ?: "no BNB address"} " +
                            "on BNB Smart Chain. Nothing was stored."
                    )
                }
            }
            val id = UUID.randomUUID().toString()
            val primary = addresses.firstOrNull { it.network == CryptoNetwork.BSC }?.address
                ?: addresses.first().address
            val sealed = keyStore.store(
                walletId = id,
                canonicalKeyHex = canonicalHex,
                primaryAddress = primary,
                label = label,
                source = WalletKeySource.IMPORTED_PRIVATE_KEY
            )
            if (!sealed.ok) error(sealed.message ?: "The key could not be sealed on this device.")

            // The synced row carries a reference only: no key, no phrase, nothing spendable.
            val referenceJson = JSONObject()
                .put("kind", WalletImportKind.PRIVATE_KEY.name)
                .put(SECRET_REF_FIELD, KEY_STORE_REFERENCE)
                .put("secret", "")
                .put("passphrase", "")
                .toString()
            val entity = buildEntity(
                id = id,
                label = label.trim().ifBlank { "Imported key" },
                kind = WalletImportKind.PRIVATE_KEY,
                secretJson = crypto.encrypt(referenceJson),
                addresses = addresses
            )
            dao.upsert(entity)
            entity.toDomain()
        }
    }

    /** Hex shape, length, non-zero and curve-order checks, each with its own reason. */
    private fun parsePrivateKey(rawKey: String): BigInteger {
        val clean = rawKey.filterNot { it.isWhitespace() }.removePrefix("0x").removePrefix("0X")
        val bytes = Hash.bytesFromHex(clean) ?: error("Private key must be hexadecimal.")
        if (bytes.size != 32) error("A private key must be 32 bytes (64 hex characters).")
        val parsed = EvmCrypto.parsePrivateKeyHex(clean)
        return when (parsed) {
            is EvmCrypto.PrivateKeyParse.Accepted -> parsed.scalar
            is EvmCrypto.PrivateKeyParse.Rejected -> error(parsed.reason)
        }
    }

    /** The unsealed key for export, only ever called behind a fresh authorisation. */
    suspend fun exportedKeyHex(id: String): String? = withContext(Dispatchers.IO) {
        keyStore.key(id)
    }

    suspend fun keyRecord(id: String): WalletKeyRecord? = withContext(Dispatchers.IO) {
        keyStore.record(id)
    }

    suspend fun storedKeys(): List<WalletKeyRecord> = withContext(Dispatchers.IO) { keyStore.records() }

    suspend fun refresh(id: String): Result<BalanceScan> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = dao.getById(id) ?: error("Wallet not found")
            val cached = decodeBalances(entity.balancesJson)
            val scan = balances.scan(entity.toDomain().addresses, cached)
            val merged = merge(cached, scan)
            // Offline rows keep their last known USD value so a failed scan never
            // resets the portfolio to $0.00.
            val total = merged.sumOf { it.usd }
            dao.upsert(
                entity.copy(
                    balancesJson = encodeBalances(merged),
                    totalUsd = total,
                    lastSyncedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            scan
        }
    }

    suspend fun refreshAll(): Map<String, Result<BalanceScan>> = withContext(Dispatchers.IO) {
        dao.getAll().associate { entity -> entity.id to refresh(entity.id) }
    }

    companion object {
        /**
         * Field name that marks a row whose key lives in [WalletKeyStore] instead of in the row.
         * A row carrying it holds no secret of any kind, so syncing or backing it up moves nothing
         * spendable.
         */
        const val SECRET_REF_FIELD = "keyRef"
        const val KEY_STORE_REFERENCE = "device_key_store"

        /** True when a decrypted secret payload is a reference rather than a phrase or key. */
        fun isReferencePayload(payload: JSONObject): Boolean =
            payload.optString(SECRET_REF_FIELD).isNotBlank()
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val existed = dao.getById(id) != null
        if (existed) {
            dao.delete(id)
            // The sealed key goes with the row, and the wipe is confirmed by reading it back —
            // a delete that left a key behind would have to be reported as a failure.
            keyStore.wipe(id)
        }
        existed
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        keyStore.walletIds().forEach { keyStore.wipe(it) }
        dao.deleteAll()
    }

    /**
     * Rows for the Drive document. A wallet whose key lives in [WalletKeyStore] is left out whole:
     * the backup would carry an address list with no way to spend it, and saying "backed up" about
     * that would be a lie. The panel tells the owner to re-import such a key after a restore.
     */
    suspend fun buildBackup(): List<BackupCryptoWalletDto> = withContext(Dispatchers.IO) {
        dao.getAll().mapNotNull { entity ->
            val secretJson = crypto.decrypt(entity.secret) ?: return@mapNotNull null
            if (isReferencePayload(parseSecretPayload(secretJson))) return@mapNotNull null
            BackupCryptoWalletDto(
                id = entity.id,
                label = entity.label,
                kind = entity.kind,
                secret = secretJson,
                balancesJson = entity.balancesJson,
                totalUsd = entity.totalUsd,
                lastSyncedAt = entity.lastSyncedAt,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
    }

    suspend fun restore(dto: BackupCryptoWalletDto): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = dto.secret?.let { parseSecretPayload(it) } ?: error("Wallet has no secret")
            if (isReferencePayload(payload)) {
                error("Imported keys are never carried by a backup; re-import the key on this device.")
            }
            val kind = when {
                payload.optString("kind").equals(WalletImportKind.PRIVATE_KEY.name, true) ->
                    WalletImportKind.PRIVATE_KEY
                payload.optString("kind").equals(WalletImportKind.SEED_PHRASE.name, true) ->
                    WalletImportKind.SEED_PHRASE
                payload.optString("secret").contains(' ') -> WalletImportKind.SEED_PHRASE
                else -> WalletImportKind.PRIVATE_KEY
            }
            val secret = payload.optString("secret").trim()
            if (secret.isBlank()) error("Wallet secret is empty")
            val passphrase = payload.optString("passphrase")
            val addresses = when (kind) {
                WalletImportKind.SEED_PHRASE -> WalletDeriver.fromSeedPhrase(secret, passphrase)
                WalletImportKind.PRIVATE_KEY -> {
                    val bytes = Hash.bytesFromHex(secret) ?: error("Invalid stored key")
                    WalletDeriver.fromPrivateKey(BigInteger(1, bytes))
                }
            }
            val entity = buildEntity(
                id = dto.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                label = dto.label?.trim().orEmpty().ifBlank {
                    if (kind == WalletImportKind.SEED_PHRASE) "Seed wallet" else "Imported key"
                },
                kind = kind,
                secretJson = JSONObject()
                    .put("kind", kind.name)
                    .put("secret", secret)
                    .put("passphrase", passphrase)
                    .toString(),
                addresses = addresses
            ).copy(
                balancesJson = dto.balancesJson ?: "[]",
                totalUsd = dto.totalUsd ?: 0.0,
                lastSyncedAt = dto.lastSyncedAt ?: 0L,
                createdAt = dto.createdAt ?: System.currentTimeMillis(),
                updatedAt = dto.updatedAt ?: System.currentTimeMillis()
            )
            dao.upsert(entity)
            true
        }.getOrDefault(false)
    }

    private fun buildEntity(
        id: String,
        label: String,
        kind: WalletImportKind,
        secretJson: String,
        addresses: List<WalletAddress>
    ): CryptoWalletEntity {
        val now = System.currentTimeMillis()
        return CryptoWalletEntity(
            id = id,
            label = label,
            kind = kind.name,
            secret = crypto.encrypt(secretJson),
            addressesJson = encodeAddresses(addresses),
            createdAt = now,
            updatedAt = now
        )
    }

    private fun parseSecretPayload(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrElse { error("Wallet secret is malformed") }
        }
        val kind = if (trimmed.contains(' ')) WalletImportKind.SEED_PHRASE else WalletImportKind.PRIVATE_KEY
        return JSONObject().put("kind", kind.name).put("secret", trimmed).put("passphrase", "")
    }

    private fun merge(cached: List<TokenBalance>, scan: BalanceScan): List<TokenBalance> {
        val fresh = scan.tokens.associateBy { it.networkId to it.symbol }
        val merged = ArrayList<TokenBalance>(cached.size + scan.tokens.size)
        cached.forEach { old ->
            val updated = fresh[old.networkId to old.symbol]
            merged += updated ?: old.copy(
                available = false,
                error = "Offline · showing last known balance"
            )
        }
        val known = cached.map { it.networkId to it.symbol }.toSet()
        scan.tokens.filter { (it.networkId to it.symbol) !in known }.forEach { merged += it }
        return merged
    }

    private fun CryptoWalletEntity.toDomain(): CryptoWallet = CryptoWallet(
        id = id,
        label = label,
        kind = kind,
        addresses = decodeAddresses(addressesJson),
        balances = decodeBalances(balancesJson),
        totalUsd = totalUsd,
        lastSyncedAt = lastSyncedAt,
        createdAt = createdAt
    )

    private fun encodeAddresses(addresses: List<WalletAddress>): String {
        val array = JSONArray()
        addresses.forEach { entry ->
            array.put(
                JSONObject()
                    .put("network", entry.network.id)
                    .put("address", entry.address)
                    .put("path", entry.path)
            )
        }
        return array.toString()
    }

    private fun decodeAddresses(json: String): List<WalletAddress> {
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val network = CryptoNetwork.entries.firstOrNull {
                        it.id == obj.optString("network")
                    } ?: continue
                    val address = obj.optString("address")
                    if (address.isNotBlank()) {
                        add(WalletAddress(network, address, obj.optString("path")))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeBalances(balances: List<TokenBalance>): String {
        val array = JSONArray()
        balances.forEach { entry ->
            array.put(
                JSONObject()
                    .put("networkId", entry.networkId)
                    .put("networkName", entry.networkName)
                    .put("symbol", entry.symbol)
                    .put("name", entry.name)
                    .put("amount", entry.amount)
                    .put("priceUsd", entry.priceUsd)
                    .put("available", entry.available)
                    .put("error", entry.error ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    private fun decodeBalances(json: String): List<TokenBalance> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    add(
                        TokenBalance(
                            networkId = obj.optString("networkId"),
                            networkName = obj.optString("networkName"),
                            symbol = obj.optString("symbol"),
                            name = obj.optString("name"),
                            amount = obj.optDouble("amount", 0.0),
                            priceUsd = obj.optDouble("priceUsd", 0.0),
                            available = obj.optBoolean("available", true),
                            error = obj.optString("error").takeIf {
                                it.isNotBlank() && it != "null"
                            }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
