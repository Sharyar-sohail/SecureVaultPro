package com.securevault.pro.data.wallet

import com.securevault.pro.data.remote.firebase.Keyring
import com.securevault.pro.security.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place the wallet code reaches for the account's vault key.
 *
 * Deliberately tiny: [sealer] hands back the master-key seal/open pair — `CryptoManager` under the
 * key [Keyring] already holds for this account — or null when this device has no such key, which is
 * how [WalletRepository] decides to write a wallet with no key rather than with an open one. The
 * pair re-reads the key on every call, so a keyring adopted after this device signed in is used at
 * once and no key buffer is ever held here.
 */
@Singleton
class WalletKeyBackupKeySource @Inject constructor(
    private val keyring: Keyring,
    private val crypto: CryptoManager
) {

    fun sealer(): WalletKeyBackupSealer? {
        val vaultKey = runCatching { keyring.deviceVaultKey() }.getOrNull() ?: return null
        if (vaultKey.bytes.isEmpty()) return null
        return object : WalletKeyBackupSealer {
            override fun seal(plaintext: String): String? {
                if (plaintext.isBlank()) return null
                val key = currentKey() ?: return null
                return runCatching { crypto.encryptWithDerivedKey(plaintext, key) }.getOrNull()
            }

            override fun open(ciphertext: String?): String? {
                if (ciphertext.isNullOrBlank()) return null
                val key = currentKey() ?: return null
                return runCatching { crypto.decryptWithDerivedKey(ciphertext, key) }.getOrNull()
            }
        }
    }

    private fun currentKey(): ByteArray? =
        runCatching { keyring.deviceVaultKey()?.bytes }.getOrNull()?.takeIf { it.isNotEmpty() }
}
