package com.securevault.pro.security.backup

/**
 * The one key that opens a stored copy, read off the copy itself.
 *
 * A v3 envelope states how it was sealed: writers since 3.5.4 record a `kid` — `vault` for this
 * account's own keyring key, `passphrase` for an export the owner sealed by hand. Copies written
 * before that field exists carry no marker, and their key is decided by one fact about this device
 * rather than by trying a list: a device that has already been handed the account's vault key is
 * reading its own account's current copies, and a device that has not can only be reading the
 * string every Drive copy was sealed with up to 3.3.x, including the 28 October 2026 build.
 *
 * Either way a restore runs a single PBKDF2 at 600,000 — the count every writer this product has
 * shipped uses — and never iterates over candidate keys. If that one key is rejected, the file is
 * reported as needing a key this device does not hold; no prompt, no sweep, no retry.
 *
 * Everything here is a **read** candidate. `UniversalKeyGuardTest` fails the build if any of these
 * strings reaches a write path, because a shared literal in a writer is the one change that would
 * put a vault key in a browser bundle.
 */
object LegacyKeyPlan {

    /** The envelope `kid` this product's own writer stamps on a cloud copy. */
    const val KEY_ID_VAULT = "vault"

    /** The `kid` on a file the owner sealed with a passphrase they chose. */
    const val KEY_ID_PASSPHRASE = "passphrase"

    /**
     * The single secret that opens a copy, or null when this device cannot name it.
     *
     * [vaultSecret] must be the keyring's own key and only when the device already holds one: a
     * freshly generated key can never open the owner's file, and offering one turns a clean failure
     * into a slow one.
     */
    fun exactSecret(
        keyId: String?,
        passphrase: String? = null,
        vaultSecret: String? = null
    ): OpeningSecret? {
        val rounds = listOf(LegacyEnvelopeOpener.CURRENT_ITERATIONS)
        val typed = passphrase?.trim()?.takeIf { it.isNotBlank() }
        val vault = vaultSecret?.trim()?.takeIf { it.isNotBlank() }
        return when (keyId?.trim()?.lowercase()) {
            KEY_ID_VAULT -> vault?.let { OpeningSecret(it, OpeningSecretKind.VAULT_KEY, rounds) }
            KEY_ID_PASSPHRASE ->
                typed?.let { OpeningSecret(it, OpeningSecretKind.PASSPHRASE, rounds) }
                    ?: vault?.let { OpeningSecret(it, OpeningSecretKind.VAULT_KEY, rounds) }
            // A copy that predates the marker: a passphrase typed for this very file is its key;
            // otherwise the account's own key when this device holds it, and the one string this
            // account's older builds sealed every Drive copy with when it does not.
            else ->
                typed?.let { OpeningSecret(it, OpeningSecretKind.PASSPHRASE, rounds) }
                    ?: vault?.let { OpeningSecret(it, OpeningSecretKind.VAULT_KEY, rounds) }
                    ?: OpeningSecret(PRIMARY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, rounds)
        }
    }

    /**
     * The one key for the shape that carried no salt at all: a bare `iv.ciphertext` body whose key
     * salt lived in a separate `key.salt` file.
     *
     * Only two writers ever produced that shape and both sealed it with an account identifier — the
     * address, or the numeric id when no address is known — never a shared literal. A passphrase
     * typed for the file outranks both, because the owner typed it for that file.
     */
    fun bareBlobSecret(
        passphrase: String? = null,
        email: String? = null,
        accountId: String? = null
    ): OpeningSecret? {
        val rounds = listOf(LegacyEnvelopeOpener.CURRENT_ITERATIONS)
        val typed = passphrase?.trim()?.takeIf { it.isNotBlank() }
        val address = email?.trim()?.takeIf { it.isNotBlank() }
        val id = accountId?.trim()?.takeIf { it.isNotBlank() }
        return when {
            typed != null -> OpeningSecret(typed, OpeningSecretKind.PASSPHRASE, rounds)
            address != null -> OpeningSecret(address, OpeningSecretKind.ACCOUNT_EMAIL, rounds)
            id != null -> OpeningSecret(id, OpeningSecretKind.ACCOUNT_ID, rounds)
            else -> null
        }
    }
}

/**
 * The string every Drive copy was sealed with up to 3.3.x — the 3.1.9 writer's own key, read out of
 * that build. Read-only history: never a writer's key again.
 */
const val PRIMARY_CLOUD_KEY = "SecureVaultPro_Universal_Cloud_Key_v3"
