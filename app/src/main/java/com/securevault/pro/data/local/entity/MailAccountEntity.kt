package com.securevault.pro.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One configured mailbox, as it is kept on the device.
 *
 * Two rules this row is written to obey:
 *
 * 1. `credentialSealed` holds **only** an AES-256-GCM envelope — the exact
 *    `base64(iv).base64(ciphertext+tag)` container `CryptoManager` writes for a vault entry's
 *    password or a note's body. The plaintext password is never a column, never in a preference
 *    file and never in a log; an account that signs in with Google stores an empty string here,
 *    because its token is minted per connection and not kept at all.
 * 2. This table is device-local by decision. It is not one of the seven realtime segments
 *    (`notes, entries, cards, totps, contacts, cryptoWallets, files`), it is not reachable from
 *    `RealtimeStore`, and `MailAccountNeverSyncedTest` fails the build if that ever stops being
 *    true. A mail server's host, user name and sealed credential must not ride to the web clone.
 *
 * `deletedAt` is a manual tombstone: nothing in this feature expires, ages out or auto-purges a
 * row. The owner presses Delete, or the row stays.
 */
@Entity(
    tableName = "mail_accounts",
    indices = [Index(value = ["address"], unique = false)]
)
data class MailAccountEntity(
    @PrimaryKey val id: String,
    val address: String,
    val label: String,
    /** [com.securevault.pro.data.email.ProviderPreset.storageKey]. */
    val provider: String,
    /** [com.securevault.pro.data.email.AuthKind.storageKey]. */
    val authKind: String,
    /** The full address or login the server expects; often, but not always, [address]. */
    val username: String,
    val imapHost: String,
    val imapPort: Int,
    /** [com.securevault.pro.data.email.SecurityMode.storageKey] — there is no plaintext mode. */
    val imapSecurity: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String,
    /** Ciphertext envelope, or empty for an OAuth account. Never a password. */
    val credentialSealed: String,
    val autoSync: Boolean = true,
    val lastSyncAt: Long = 0L,
    /** The last readable failure sentence, so the account row can explain itself offline. */
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)
