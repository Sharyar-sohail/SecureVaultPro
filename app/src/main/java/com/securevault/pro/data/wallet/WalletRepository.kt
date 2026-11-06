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

/** Whether one backed-up wallet landed on this device, and the one plain sentence why it did not. */
data class WalletRestoreResult(val restored: Boolean, val note: String? = null)

@Singleton
class WalletRepository @Inject constructor(
    private val dao: CryptoWalletDao,
    private val crypto: CryptoManager,
    private val balances: CryptoBalanceService,
    private val keyStore: WalletKeyStore,
    private val keyBackupKeys: WalletKeyBackupKeySource,
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
     * a key imported through the Violet path keeps only a pointer there — the key itself is in
     * [WalletKeyStore], a file no realtime segment reads. A wallet restored from a backup is stored
     * the same way it was made, so signing needs to know nothing about where it came from.
     */
    suspend fun signingKey(id: String, network: CryptoNetwork): BigInteger? =
        withContext(Dispatchers.IO) {
            val entity = dao.getById(id) ?: return@withContext null
            val payload = openSecretColumn(entity.secret) ?: return@withContext null
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

            // The synced row carries a pointer only: no key, no phrase, nothing spendable.
            val entity = buildEntity(
                id = id,
                label = label.trim().ifBlank { "Imported key" },
                kind = WalletImportKind.PRIVATE_KEY,
                secretJson = referenceJson(),
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
         * A row carrying it holds no secret of any kind, so realtime syncing moves nothing
         * spendable. A Drive backup does carry that wallet's key — never as this column, only as
         * [BackupCryptoWalletDto.keyBackup], sealed with the account's own vault key.
         */
        const val SECRET_REF_FIELD = "keyRef"
        const val KEY_STORE_REFERENCE = "device_key_store"

        /** What the key store says about a wallet it has no address for; never a real address. */
        private const val MASKED_AS_ABSENT = "not shown"

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
     * Rows for the Drive document, with the spending material of each one sealed alongside them.
     *
     * A wallet whose key lives in [WalletKeyStore] used to be left out whole, because the backup
     * would have carried an address list with no way to spend it. It now travels: the key goes into
     * [BackupCryptoWalletDto.keyBackup], sealed with this account's own vault key — the same master
     * key the document itself is sealed with — so a phone that signs in to the same account gets a
     * wallet it can actually spend from, with nothing for the owner to type in again. The row's
     * `secret` stays the harmless pointer it already was.
     *
     * When this device holds no account key there is nothing to seal with, and the wallet is written
     * without one: an address list whose key did not travel, never an open key.
     */
    suspend fun buildBackup(): List<BackupCryptoWalletDto> = withContext(Dispatchers.IO) {
        val sealer = keyBackupKeys.sealer()
        dao.getAll().mapNotNull { entity ->
            val payload = openSecretColumn(entity.secret) ?: return@mapNotNull null
            BackupCryptoWalletDto(
                id = entity.id,
                label = entity.label,
                kind = entity.kind,
                secret = payload.toString(),
                keyBackup = sealedMaterialFor(entity.id, payload, sealer),
                balancesJson = entity.balancesJson,
                totalUsd = entity.totalUsd,
                lastSyncedAt = entity.lastSyncedAt,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
    }

    /**
     * The sealed spending material for one row, or null when there is no account key to seal with,
     * no key on this device, or a value no wallet can be rebuilt from. Sealing itself refuses to
     * return anything that still shows the key, so an open key never reaches the document.
     */
    private suspend fun sealedMaterialFor(
        walletId: String,
        payload: JSONObject,
        sealer: WalletKeyBackupSealer?
    ): String? {
        if (sealer == null) return null
        if (isReferencePayload(payload)) {
            return WalletKeyBackup.seal(
                sealer,
                WalletKeyBackupKind.PRIVATE_KEY,
                keyStore.key(walletId).orEmpty()
            )
        }
        val secret = payload.optString("secret").trim()
        if (secret.isBlank()) return null
        val kind = WalletKeyBackup.kindOf(payload.optString("kind"), secret) ?: return null
        return WalletKeyBackup.seal(sealer, kind, secret, payload.optString("passphrase"))
    }

    /**
     * Puts one backed-up wallet back on this device.
     *
     * The sealed blob is what the owner's key comes back as: an imported key is stored exactly the
     * way a local import stores it — in [WalletKeyStore], with the row keeping its pointer — and a
     * recovery phrase comes back the way a local phrase is kept, so nobody has to type anything
     * again. Addresses are derived from the material rather than copied, and a wallet whose restored
     * key owns a different address than the one on record is refused outright instead of quietly
     * given someone else's funds. A document with no blob — every backup written before this —
     * restores exactly as it always did.
     */
    suspend fun restore(dto: BackupCryptoWalletDto): WalletRestoreResult = withContext(Dispatchers.IO) {
        runCatching { restoreWallet(dto) }.getOrElse {
            WalletRestoreResult(false, "'${walletName(dto)}' could not be restored.")
        }
    }

    private fun walletName(dto: BackupCryptoWalletDto): String =
        dto.label?.trim().orEmpty().ifBlank { "Wallet" }

    private suspend fun restoreWallet(dto: BackupCryptoWalletDto): WalletRestoreResult {
        val name = walletName(dto)
        val id = dto.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val payload = dto.secret?.let(::parseSecretPayload)
        val sealed = openSealedMaterial(dto.keyBackup)
        val material = sealed ?: materialOf(payload)
            ?: return WalletRestoreResult(
                restored = false,
                note = if (payload != null && isReferencePayload(payload)) {
                    "'$name' came back without its key, so it holds addresses only. " +
                        "Add its private key again on this phone to spend from it."
                } else {
                    "'$name' had no key or recovery phrase in the backup."
                }
            )
        val addresses = deriveAddresses(material)
            ?: return WalletRestoreResult(false, "'$name' has a key this phone cannot use, so it was skipped.")
        val primary = addresses.firstOrNull { it.network == CryptoNetwork.BSC }?.address
            ?: addresses.first().address
        WalletKeyBackup.addressMismatchReason(claimedAddressFor(id), primary)?.let {
            return WalletRestoreResult(false, it)
        }
        val label = dto.label?.trim().orEmpty().ifBlank {
            if (material.kind == WalletKeyBackupKind.SEED_PHRASE) "Seed wallet" else "Imported key"
        }
        // A key that arrived sealed belongs in the device store, not in a synced row; a phrase is
        // kept the way a locally entered phrase is kept.
        if (sealed != null && material.kind == WalletKeyBackupKind.PRIVATE_KEY) {
            val stored = keyStore.store(
                walletId = id,
                canonicalKeyHex = canonicalKeyHex(material.secret),
                primaryAddress = primary,
                label = label,
                source = WalletKeySource.IMPORTED_PRIVATE_KEY
            )
            if (!stored.ok) {
                return WalletRestoreResult(
                    false,
                    stored.message ?: "'$name' could not keep its key on this phone, so it was skipped."
                )
            }
        }
        val kind = when (material.kind) {
            WalletKeyBackupKind.SEED_PHRASE -> WalletImportKind.SEED_PHRASE
            WalletKeyBackupKind.PRIVATE_KEY -> WalletImportKind.PRIVATE_KEY
        }
        val secretJson = when {
            kind == WalletImportKind.SEED_PHRASE -> JSONObject()
                .put("kind", kind.name)
                .put("secret", material.secret)
                .put("passphrase", material.passphrase)
                .toString()
            sealed != null -> referenceJson()
            else -> JSONObject()
                .put("kind", kind.name)
                .put("secret", material.secret)
                .put("passphrase", "")
                .toString()
        }
        val entity = buildEntity(
            id = id,
            label = label,
            kind = kind,
            secretJson = secretJson,
            addresses = addresses
        ).copy(
            balancesJson = dto.balancesJson ?: "[]",
            totalUsd = dto.totalUsd ?: 0.0,
            lastSyncedAt = dto.lastSyncedAt ?: 0L,
            createdAt = dto.createdAt ?: System.currentTimeMillis(),
            updatedAt = dto.updatedAt ?: System.currentTimeMillis()
        )
        dao.upsert(entity)
        return WalletRestoreResult(true)
    }

    /** The blob opened with this account's key, or null when there is none or it will not open. */
    private fun openSealedMaterial(blob: String?): WalletKeyMaterial? {
        if (blob.isNullOrBlank()) return null
        val sealer = keyBackupKeys.sealer() ?: return null
        return WalletKeyBackup.open(sealer, blob)
    }

    /** The material a row carries in the clear of the document; null for a pointer or nothing. */
    private fun materialOf(payload: JSONObject?): WalletKeyMaterial? {
        if (payload == null || isReferencePayload(payload)) return null
        val secret = payload.optString("secret").trim()
        if (secret.isBlank()) return null
        val kind = WalletKeyBackup.kindOf(payload.optString("kind"), secret) ?: return null
        return WalletKeyMaterial(kind, secret, payload.optString("passphrase"))
    }

    private fun deriveAddresses(material: WalletKeyMaterial): List<WalletAddress>? = runCatching {
        when (material.kind) {
            WalletKeyBackupKind.SEED_PHRASE ->
                WalletDeriver.fromSeedPhrase(material.secret, material.passphrase)

            WalletKeyBackupKind.PRIVATE_KEY -> {
                val bytes = Hash.bytesFromHex(material.secret) ?: return null
                val scalar = BigInteger(1, bytes)
                if (!Secp256k1.isValidPrivateKey(scalar)) return null
                WalletDeriver.fromPrivateKey(scalar)
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun canonicalKeyHex(secret: String): String {
        val bytes = Hash.bytesFromHex(secret) ?: return secret.trim()
        return "0x" + Hash.hex(bytes)
    }

    /**
     * What this device already says the address of a wallet id is: the row's own address if a row
     * with that id is here, otherwise the masked address the key store kept for it. Null when this
     * phone has never seen the id, which is a fresh restore with nothing to contradict.
     */
    private suspend fun claimedAddressFor(walletId: String): String? {
        dao.getById(walletId)?.let { entity ->
            val addresses = decodeAddresses(entity.addressesJson)
            val primary = addresses.firstOrNull { it.network == CryptoNetwork.BSC } ?: addresses.firstOrNull()
            if (primary != null) return primary.address
        }
        return keyStore.record(walletId)?.maskedAddress
            ?.takeIf { it.isNotBlank() && it != MASKED_AS_ABSENT }
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

    private fun parseSecretPayload(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
        val kind = if (trimmed.contains(' ')) WalletImportKind.SEED_PHRASE else WalletImportKind.PRIVATE_KEY
        return JSONObject().put("kind", kind.name).put("secret", trimmed).put("passphrase", "")
    }

    /**
     * The row's sealed secret column, opened.
     *
     * One build wrote that column sealed twice, so a value that is still an envelope after the first
     * unwrap is opened once more. Without that those wallets would neither sign nor back up, and
     * their keys are on the device and safe — only the pointer around them was misshapen.
     */
    private fun openSecretColumn(stored: String): JSONObject? {
        var plain = crypto.decrypt(stored) ?: return null
        if (!plain.trim().startsWith("{") && crypto.isEncryptedBlob(plain)) {
            plain = crypto.decrypt(plain) ?: return null
        }
        return parseSecretPayload(plain)
    }

    /** The pointer a Violet-imported row carries in place of a key. */
    private fun referenceJson(): String = JSONObject()
        .put("kind", WalletImportKind.PRIVATE_KEY.name)
        .put(SECRET_REF_FIELD, KEY_STORE_REFERENCE)
        .put("secret", "")
        .put("passphrase", "")
        .toString()

    /**
     * The stored rows plus this scan, in one list.
     *
     * A chain the scan walked is rebuilt from what the chain just answered — that is how a token the
     * owner sold disappears, and how a token they just received appears, without a stale row surviving
     * either way. A chain it did not walk is left exactly as it was: switching a chain off in Settings
     * must not make its balances look stale, and a chain whose nodes were silent keeps its last known
     * figures with the reason written on the row.
     */
    private fun merge(cached: List<TokenBalance>, scan: BalanceScan): List<TokenBalance> {
        val out = ArrayList<TokenBalance>(cached.size + scan.tokens.size)
        out += scan.tokens
        out += cached.filter { it.networkId !in scan.scannedNetworkIds }.map { row ->
            if (scan.errors.containsKey(row.networkId)) {
                row.copy(available = false, error = "Offline · showing last known balance")
            } else {
                row
            }
        }
        return out.distinctBy { it.networkId to it.symbol }
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
                    .put("contract", entry.contract ?: JSONObject.NULL)
                    .put("decimals", entry.decimals)
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
                            },
                            // Rows written before token discovery carry neither of these; a native
                            // coin never had a contract, so its absence is the normal case.
                            contract = obj.optString("contract").takeIf {
                                it.isNotBlank() && it != "null"
                            },
                            decimals = obj.optInt("decimals", 0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
