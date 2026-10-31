package com.securevault.pro.data.email.repository

import com.securevault.pro.data.email.AuthKind
import com.securevault.pro.data.email.EmailAccount
import com.securevault.pro.data.email.EmailPresets
import com.securevault.pro.data.email.MailEndpoint
import com.securevault.pro.data.email.MailServerConfig
import com.securevault.pro.data.email.ProviderPreset
import com.securevault.pro.data.email.ProviderTemplate
import com.securevault.pro.data.email.SecurityMode
import com.securevault.pro.data.local.dao.MailAccountDao
import com.securevault.pro.data.local.entity.MailAccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a configured mailbox lives between the setup screen and a connection attempt.
 *
 * [MailAccountStore] is the only thing the mail screens talk to, and the two implementations behind
 * it are deliberately interchangeable: [RoomMailAccountStore] writes the real rows, and
 * [InMemoryMailAccountStore] keeps them in this process only. The Room table is *not registered in
 * `VaultDatabase` yet* — that file belongs to the manager, so the DI default is the in-memory store
 * and the whole UI (add account, list, folder strip, reader, compose) is buildable and testable
 * today. `agent_notes/email-ui.md` carries the exact lines that flip the binding once
 * `mail_accounts` exists; nothing above the interface changes when they land.
 *
 * What the store guarantees, in both implementations:
 *
 * 1. **A typed password is sealed before it is kept.** [save] runs the secret through
 *    [MailCredentialVault], which uses the vault's own AES-256-GCM envelope, and refuses to write
 *    anything when sealing fails. A row therefore holds either an envelope or an empty string.
 * 2. **Nothing is deleted by the clock.** [remove] writes the owner's tombstone; the sealed
 *    credential is cleared with it because a dead account must not keep a live secret, but the row
 *    itself only disappears on the explicit [purgeDeleted] call from the "remove completely" action.
 *    No TTL, no retention window, no background cleanup.
 * 3. **A read failure is a sentence, not an exception.** [unlockSecret] returns a [MailOutcome], so
 *    a Keystore that will not open becomes "This device could not read the saved password…" on the
 *    account row instead of a crash on the mailbox screen.
 *
 * Mail accounts are device-local on purpose: they are not one of the seven realtime segments and no
 * part of this file is reachable from `RealtimeStore` — `MailAccountNeverSyncedTest` pins that.
 */

/** One account as the mail layer keeps it: [account] plus the two things the domain model cannot hold. */
data class MailAccountRecord(
    val account: EmailAccount,
    /** The login the server expects. Gmail's address; a cPanel box's `user%domain.com`. */
    val username: String,
    /** The vault envelope, or empty. Never a password. */
    val credentialSealed: String
) {
    val id: String get() = account.id
    val address: String get() = account.address
    val displayName: String get() = account.displayName
    val usesOAuth: Boolean get() = account.usesOAuth
    val loginName: String get() = username.ifBlank { account.address }
    val hasStoredCredential: Boolean get() = credentialSealed.isNotBlank()

    /** The one-line credential status the account row shows. Never the value itself. */
    val credentialSummary: String get() = MailCredentials.describe(credentialSealed, usesOAuth)

    /** True when a password account has nothing to connect with — the row the setup screen needs. */
    val needsPassword: Boolean get() = !usesOAuth && credentialSealed.isBlank()

    fun withAccount(block: (EmailAccount) -> EmailAccount): MailAccountRecord =
        copy(account = block(account))
}

/** The result of an account write: the row that was kept, or the reason it was not. */
sealed interface MailSaveResult {
    data class Saved(val record: MailAccountRecord) : MailSaveResult
    data class Rejected(val message: String) : MailSaveResult

    val isSaved: Boolean get() = this is Saved
}

interface MailAccountStore {

    /** Live list, newest write first, tombstoned rows excluded. */
    fun observeAccounts(): Flow<List<MailAccountRecord>>

    suspend fun accounts(): List<MailAccountRecord>

    suspend fun find(id: String): MailAccountRecord?

    /**
     * Insert or update.
     *
     * [plaintextSecret] is the typed password and exists only inside this call: `null` means "keep
     * whatever is already sealed for this account", an empty string means "clear it" (what a Google
     * account stores), and any other value is sealed before the row is written. When sealing fails
     * the write is refused outright — a plaintext is never substituted for a missing envelope.
     */
    suspend fun save(
        account: EmailAccount,
        username: String = account.address,
        plaintextSecret: String? = null
    ): MailSaveResult

    /** The sealed envelope for one account — what a connection attempt is handed. */
    suspend fun sealedCredential(id: String): String?

    /**
     * The plaintext for one connection attempt, opened inside the store so no screen ever holds a
     * password it read itself. A problem here is a readable sentence, never an exception name.
     */
    suspend fun unlockSecret(id: String): MailOutcome<String>

    /** The account row's own memory of the last attempt, so the list can explain itself offline. */
    suspend fun markSynced(id: String, at: Long, error: String)

    suspend fun setAutoSync(id: String, enabled: Boolean)

    /** A new readable failure sentence on an existing row, keeping everything else. */
    suspend fun recordError(id: String, error: String)

    /** The owner pressed Remove: the row is tombstoned and its sealed credential is wiped. */
    suspend fun remove(id: String)

    /** The owner pressed "Remove completely": the tombstoned rows are gone from this device. */
    suspend fun purgeDeleted()

    /** True when an address is already configured, so the setup screen can warn instead of duplicate. */
    suspend fun findByAddress(address: String): MailAccountRecord?
}

/** Fresh ids and the two account shapes the UI creates. Kept here so screens stay thin. */
object MailAccounts {

    /** Stable, sortable, collision-free enough for a phone: `mail-<uuid>`. */
    fun newId(): String = "mail-" + UUID.randomUUID().toString().take(24)

    /**
     * The Gmail row: XOAUTH2, Google's fixed endpoints, no stored secret.
     *
     * [id] is supplied by the caller so a repeat sign-in can overwrite the row that address already
     * owns instead of adding a second one beside it. A fresh id is minted only for a new address.
     */
    fun googleAccount(email: String, label: String = "", id: String = newId()): EmailAccount {
        val template = EmailPresets.ALL.first { it.preset == ProviderPreset.GMAIL }
        return built(id, email, label, template, AuthKind.OAUTH_XOAUTH2)
    }

    /** A manually configured row; the preset comes from the template the form used. */
    fun manualAccount(
        email: String,
        label: String,
        config: MailServerConfig,
        preset: ProviderPreset = presetFor(config)
    ): EmailAccount = EmailAccount(
        id = newId(),
        address = email.trim(),
        label = label.trim(),
        provider = preset,
        authKind = AuthKind.PASSWORD,
        config = config
    )

    /** The template a hand-typed host pair matches, so the row still says who it belongs to. */
    fun presetFor(config: MailServerConfig): ProviderPreset {
        val incoming = config.incoming.host.trim().lowercase()
        val match = EmailPresets.ALL.firstOrNull { template ->
            template.config.incoming.host.equals(incoming, ignoreCase = true)
        }
        return match?.preset ?: ProviderPreset.CUSTOM
    }

    /** The domain-derived template for an address typed on the setup screen. */
    fun templateFor(address: String): ProviderTemplate? = EmailPresets.suggestFor(address)

    /** True when the typed address could use the one-tap Google path instead of a password. */
    fun canUseGoogleSignIn(address: String): Boolean = EmailPresets.isGoogleMailbox(address)

    private fun built(id: String, email: String, label: String, template: ProviderTemplate, auth: AuthKind): EmailAccount =
        EmailAccount(
            id = id,
            address = email.trim(),
            label = label.trim().ifBlank { template.label },
            provider = template.preset,
            authKind = auth,
            config = template.config
        )
}

/** Row <-> domain mapping, shared by both stores so the two can never disagree about a column. */
object MailAccountMapping {

    /** Missing or unparseable storage values fall back to the only safe choices: a password account on TLS. */
    fun toRecord(row: MailAccountEntity): MailAccountRecord = MailAccountRecord(
        account = EmailAccount(
            id = row.id,
            address = row.address,
            label = row.label,
            provider = ProviderPreset.fromStorage(row.provider),
            authKind = AuthKind.fromStorage(row.authKind) ?: AuthKind.PASSWORD,
            config = MailServerConfig(
                incoming = MailEndpoint(
                    row.imapHost, row.imapPort, SecurityMode.fromStorage(row.imapSecurity) ?: SecurityMode.SSL_TLS
                ),
                outgoing = MailEndpoint(
                    row.smtpHost, row.smtpPort, SecurityMode.fromStorage(row.smtpSecurity) ?: SecurityMode.SSL_TLS
                )
            ),
            autoSync = row.autoSync,
            lastSyncAt = row.lastSyncAt,
            lastError = row.lastError,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            deletedAt = row.deletedAt
        ),
        username = row.username,
        credentialSealed = row.credentialSealed
    )

    fun toEntity(record: MailAccountRecord): MailAccountEntity {
        val account = record.account
        val config = account.config
        return MailAccountEntity(
            id = account.id,
            address = account.address.trim(),
            label = account.label.trim(),
            provider = account.provider.storageKey,
            authKind = account.authKind.storageKey,
            username = record.username.ifBlank { account.address }.trim(),
            imapHost = config.incoming.host.trim(),
            imapPort = config.incoming.port,
            imapSecurity = config.incoming.security.storageKey,
            smtpHost = config.outgoing.host.trim(),
            smtpPort = config.outgoing.port,
            smtpSecurity = config.outgoing.security.storageKey,
            credentialSealed = record.credentialSealed,
            autoSync = account.autoSync,
            lastSyncAt = account.lastSyncAt,
            lastError = account.lastError,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
            deletedAt = account.deletedAt
        )
    }
}

/**
 * The store the app runs on today: process memory only, behind the same interface as Room.
 *
 * Accounts added here are gone when the process dies. That is a known, deliberate gap until the
 * manager registers `MailAccountEntity` in `VaultDatabase` — the UI, the ViewModel logic and these
 * tests are all written against [MailAccountStore], so wiring the real table is a binding change and
 * not a rewrite.
 */
@Singleton
class InMemoryMailAccountStore @Inject constructor(
    private val sealer: MailSecretSealer
) : MailAccountStore {

    private val vault = MailCredentialVault(sealer)

    /**
     * Every row this process holds, tombstoned ones included: [remove] marks, [purgeDeleted] drops,
     * and only the reads that the UI uses filter the tombstones out. Keeping the deleted rows here is
     * what makes the in-memory store obey the same "nothing disappears on a clock" rule as Room.
     */
    private val rows = MutableStateFlow<List<MailAccountRecord>>(emptyList())

    override fun observeAccounts(): Flow<List<MailAccountRecord>> = rows.map { it.visible() }

    override suspend fun accounts(): List<MailAccountRecord> = rows.value.visible()

    override suspend fun find(id: String): MailAccountRecord? = rows.value.visible().firstOrNull { it.id == id }

    override suspend fun findByAddress(address: String): MailAccountRecord? {
        val needle = address.trim().lowercase()
        return rows.value.visible().firstOrNull { it.address.equals(needle, ignoreCase = true) }
    }

    override suspend fun save(
        account: EmailAccount,
        username: String,
        plaintextSecret: String?
    ): MailSaveResult {
        val previous = find(account.id)
        val alreadySealed = previous?.credentialSealed.orEmpty()
        val sealed = if (plaintextSecret == null) {
            alreadySealed
        } else {
            val outcome = vault.sealForStorage(plaintextSecret)
            if (!outcome.ok) return MailSaveResult.Rejected(outcome.message)
            outcome.sealed
        }
        val now = System.currentTimeMillis()
        val record = MailAccountRecord(
            account = account.copy(
                address = account.address.trim(),
                label = account.label.trim(),
                createdAt = previous?.account?.createdAt ?: account.createdAt,
                updatedAt = now,
                deletedAt = null
            ),
            username = username.ifBlank { account.address }.trim(),
            credentialSealed = sealed
        )
        // The row that is about to be written must not contain the secret it is meant to hide.
        if (plaintextSecret != null && vault.rowLeaksSecret(record.credentialSealed, plaintextSecret)) {
            return MailSaveResult.Rejected(
                "The password was not stored because it appears inside the saved record unchanged. " +
                    "Nothing was changed; try again."
            )
        }
        rows.update { current ->
            current.filterNot { it.id == record.id } + record
        }
        return MailSaveResult.Saved(record)
    }

    override suspend fun sealedCredential(id: String): String? = find(id)?.credentialSealed

    override suspend fun unlockSecret(id: String): MailOutcome<String> {
        val record = find(id)
            ?: return MailOutcome.problem("That account is not configured on this device.", MailFailureKind.CONFIG)
        return unlockFrom(record, vault)
    }

    override suspend fun markSynced(id: String, at: Long, error: String) {
        rows.update { current ->
            current.map { if (it.id == id) it.withAccount { account -> account.copy(lastSyncAt = at, lastError = error, updatedAt = at) } else it }
        }
    }

    override suspend fun recordError(id: String, error: String) {
        rows.update { current ->
            current.map { if (it.id == id) it.withAccount { account -> account.copy(lastError = error) } else it }
        }
    }

    override suspend fun setAutoSync(id: String, enabled: Boolean) {
        rows.update { current ->
            current.map { if (it.id == id) it.withAccount { account -> account.copy(autoSync = enabled) } else it }
        }
    }

    override suspend fun remove(id: String) {
        val now = System.currentTimeMillis()
        // Same rule as Room: a removed account stops holding a credential the moment the owner asks.
        rows.update { current ->
            current.map {
                if (it.id == id) {
                    it.copy(
                        credentialSealed = MailCredentials.NO_SEALED_CREDENTIAL,
                        account = it.account.copy(deletedAt = now, lastError = "")
                    )
                } else it
            }
        }
    }

    override suspend fun purgeDeleted() {
        rows.update { current -> current.filter { it.account.deletedAt == null } }
    }

    /** Every row this process holds, tombstones included, seals redacted. For tests and diagnostics. */
    fun snapshot(): List<MailAccountRecord> = rows.value.map { it.copy(credentialSealed = if (it.hasStoredCredential) "sealed" else "") }

    private fun List<MailAccountRecord>.visible(): List<MailAccountRecord> =
        filter { it.account.deletedAt == null }.sortedByDescending { it.account.updatedAt }
}

/**
 * The real store, over the `mail_accounts` table.
 *
 * Not constructed by Hilt yet — asking for [MailAccountDao] before `VaultDatabase` declares it would
 * fail the build, which is exactly the mistake this file is written to avoid. When the migration
 * lands, `provideMailAccountStore` in `MailDataModule.kt` returns
 * `RoomMailAccountStore(vaultDatabase.mailAccountDao(), sealer)` instead.
 */
class RoomMailAccountStore(
    private val dao: MailAccountDao,
    sealer: MailSecretSealer
) : MailAccountStore {

    private val vault = MailCredentialVault(sealer)

    override fun observeAccounts(): Flow<List<MailAccountRecord>> =
        dao.observeAll().mapRows()

    override suspend fun accounts(): List<MailAccountRecord> = dao.getAll().mapRows()

    override suspend fun find(id: String): MailAccountRecord? = dao.getById(id)?.takeIf { it.deletedAt == null }?.toRecord()

    override suspend fun findByAddress(address: String): MailAccountRecord? {
        val needle = address.trim().lowercase()
        return accounts().firstOrNull { it.address.equals(needle, ignoreCase = true) }
    }

    override suspend fun save(
        account: EmailAccount,
        username: String,
        plaintextSecret: String?
    ): MailSaveResult {
        val previous = dao.getById(account.id)?.toRecord()
        val sealed = if (plaintextSecret == null) {
            previous?.credentialSealed.orEmpty()
        } else {
            val outcome = vault.sealForStorage(plaintextSecret)
            if (!outcome.ok) return MailSaveResult.Rejected(outcome.message)
            outcome.sealed
        }
        val now = System.currentTimeMillis()
        val record = MailAccountRecord(
            account = account.copy(
                address = account.address.trim(),
                label = account.label.trim(),
                createdAt = previous?.account?.createdAt ?: account.createdAt,
                updatedAt = now,
                deletedAt = null
            ),
            username = username.ifBlank { account.address }.trim(),
            credentialSealed = sealed
        )
        if (plaintextSecret != null && vault.rowLeaksSecret(record.credentialSealed, plaintextSecret)) {
            return MailSaveResult.Rejected(
                "The password was not stored because it appears inside the saved record unchanged. " +
                    "Nothing was changed; try again."
            )
        }
        dao.upsert(MailAccountMapping.toEntity(record))
        return MailSaveResult.Saved(record)
    }

    override suspend fun sealedCredential(id: String): String? = dao.sealedCredential(id)

    override suspend fun unlockSecret(id: String): MailOutcome<String> {
        val sealed = dao.sealedCredential(id)
            ?: return MailOutcome.problem("That account is not configured on this device.", MailFailureKind.CONFIG)
        val record = find(id)
            ?: return MailOutcome.problem("That account is not configured on this device.", MailFailureKind.CONFIG)
        return unlockFrom(record.copy(credentialSealed = sealed), vault)
    }

    override suspend fun markSynced(id: String, at: Long, error: String) = dao.markSynced(id, at, error)

    override suspend fun recordError(id: String, error: String) {
        val row = dao.getById(id) ?: return
        dao.markSynced(id, row.updatedAt.coerceAtLeast(1L), error)
    }

    override suspend fun setAutoSync(id: String, enabled: Boolean) {
        val row = dao.getById(id) ?: return
        dao.upsert(row.copy(autoSync = enabled, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun remove(id: String) = dao.markDeleted(id, System.currentTimeMillis())

    override suspend fun purgeDeleted() = dao.purgeDeletedForever()
}

/** The single place both stores turn "read the secret" into a sentence. */
private fun unlockFrom(record: MailAccountRecord, vault: MailCredentialVault): MailOutcome<String> = when {
    record.usesOAuth -> MailOutcome.problem(
        "This account signs in with Google, so it has no stored password.",
        MailFailureKind.CONFIG
    )
    record.credentialSealed.isBlank() -> MailOutcome.problem(
        "No password is saved for ${record.displayName} yet. Open the account and enter it once.",
        MailFailureKind.CONFIG
    )
    else -> {
        val plain = vault.openForConnection(record.credentialSealed)
        if (plain.isNullOrBlank()) {
            MailOutcome.problem(
                "This device could not decrypt the saved password — it was sealed by another " +
                    "install or the security key changed. Enter the password again.",
                MailFailureKind.CONFIG
            )
        } else {
            MailOutcome.ready(plain)
        }
    }
}

private fun List<MailAccountEntity>.mapRows(): List<MailAccountRecord> =
    filter { it.deletedAt == null }.map(MailAccountMapping::toRecord)

private fun MailAccountEntity.toRecord(): MailAccountRecord = MailAccountMapping.toRecord(this)

private fun Flow<List<MailAccountEntity>>.mapRows(): Flow<List<MailAccountRecord>> =
    map { rows -> rows.mapRows() }
