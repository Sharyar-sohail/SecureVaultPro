package com.securevault.pro.security.backup

import java.util.Locale

/**
 * The keys a stored envelope is offered, and the exact counts it is derived at.
 *
 * The derivation never changed with the seed-phrase feature: every v3 writer sealed with either
 * the retired universal v3 literal (builds up to 3.3.x) or this user's keyring vault key (3.3.4+),
 * always at 600,000 rounds. Exported files used the passphrase the owner typed. Only envelopes
 * older than v3 have extra counts in the old reader (210,000 and 120,000), and only those files
 * get them. There is no count sweep: a count no writer used cannot open a file, it can only make
 * the failure slower.
 *
 * The version decides which keys are *plausible*:
 *  - v3 and newer: the two v3 writers, the passphrase, the account ID and the account address.
 *  - older envelopes and the salt-less blob: the same plus the v2/v1/v0 strings, the shared legacy
 *    literals and the historical addresses the original reader hard-coded.
 *
 * Asking the keyring for a sealing secret when it holds none *generates* a fresh key that can never
 * open the owner's file; that regression is why [ordered] takes a nullable `vaultSecret` and why
 * `CloudSyncManager` only passes one when `hasDeviceKey()` is true.
 *
 * Everything here is a **read** candidate. `UniversalKeyGuardTest` fails the build if any of these
 * strings reaches a write path, because a shared literal in a writer is the one change that would
 * put a vault key in a browser bundle.
 */
object LegacyKeyPlan {

    /** Shared literals the original reader swept. Read-only history: never a writer's key again. */
    val SHARED_LEGACY_KEYS = listOf(
        "SecureVaultPro_Master_Encryption_Key_2026",
        "SecureVaultProKey",
        "secure_vault_pro_secret",
        "vault_default_key",
        "SecureVaultProDefaultKey"
    )

    /**
     * Addresses the original reader hard-coded, so an install that never stored its account address
     * could still open the owner's file. They stay a read candidate only.
     */
    val HISTORICAL_OWNER_ADDRESSES = listOf(
        "sharyarprince@gmail.com",
        "sharyarsohail@gmail.com",
        "sharyar1670@gmail.com"
    )

    /** Handles the original reader also tried (the local part of the addresses above, plus fallbacks). */
    val HISTORICAL_HANDLES = listOf(
        "sharyarprince",
        "sharyarsohail",
        "sharyar1670",
        "sharyar",
        "admin",
        "12345678",
        "1234"
    )

    /**
     * Every key a v3 envelope could have been written with, in the order the writers appeared.
     *
     * This is deliberately a short, closed list rather than a sweep. There have only ever been
     * three v3 writers, all at 600,000 rounds:
     *  - [PRIMARY_CLOUD_KEY], which every build up to 3.3.x (including 3.1.9) sealed Drive copies
     *    with — the reason a Google login used to open any of the owner's backups with no prompt;
     *  - this account's keyring vault key, which 3.3.4+ seals with;
     *  - the passphrase the owner typed, which seals a local export.
     *
     * A device that holds a vault key still has to offer [PRIMARY_CLOUD_KEY]: its 3.1.9-era copies
     * were written before that key existed, and dropping the literal is exactly what made an old
     * Drive backup fail on a newer install. A device with neither still gets the 3.1.9 key, so the
     * common restore needs one derivation and never more than these exact three.
     *
     * [preferPassphrase] is for a local export: the owner typed a passphrase precisely because that
     * is the file's key, so it is offered before a vault key this device happens to hold.
     */
    fun restoreChain(
        passphrase: String? = null,
        vaultSecret: String? = null,
        preferPassphrase: Boolean = false
    ): List<OpeningSecret> {
        val rounds = listOf(LegacyEnvelopeOpener.CURRENT_ITERATIONS)
        val typed = passphrase?.trim()?.takeIf { it.isNotBlank() }
        val vault = vaultSecret?.trim()?.takeIf { it.isNotBlank() }
        val chain = buildList {
            if (preferPassphrase && typed != null) {
                add(OpeningSecret(typed, OpeningSecretKind.PASSPHRASE, rounds))
            }
            if (vault != null) {
                add(OpeningSecret(vault, OpeningSecretKind.VAULT_KEY, rounds))
            }
            // The exact key builds up to 3.3.x, including 3.1.9, wrote every Drive copy with.
            add(OpeningSecret(PRIMARY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, rounds))
            if (!preferPassphrase && typed != null) {
                add(OpeningSecret(typed, OpeningSecretKind.PASSPHRASE, rounds))
            }
        }
        val seen = mutableSetOf<String>()
        return chain.filter { seen.add(it.value) }
    }

    /**
     * The original reader's full candidate list. Only a pre-v3 envelope, or a deliberate last
     * resort after the owner has supplied a secret, is ever offered this.
     *
     * @param version the envelope's `v` field, or null for the salt-less `iv.ciphertext` blob that
     *   predates envelopes.
     */
    fun ordered(
        emails: List<String>,
        passphrase: String? = null,
        vaultSecret: String? = null,
        accountIds: List<String> = emptyList(),
        displayName: String? = null,
        version: Int? = null
    ): List<OpeningSecret> {
        val profiles = LegacyEnvelopeOpener.exactProfiles(version)
        // The salt-less blob is older than v3 even though it carries no version field.
        val olderThanV3 = version == null || version < 3

        fun secret(value: String, kind: OpeningSecretKind) =
            OpeningSecret(value, kind, profiles)

        val ordered = buildList {
            // Every v3 writer up to 3.3.x sealed with this literal; it is the old derivation.
            add(secret(PRIMARY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY))
            // 3.3.4+ seals with the keyring's own key.
            if (!vaultSecret.isNullOrBlank()) {
                add(secret(vaultSecret, OpeningSecretKind.VAULT_KEY))
            }
            // An exported file is sealed with the passphrase the owner chose.
            if (!passphrase.isNullOrBlank()) {
                add(secret(passphrase.trim(), OpeningSecretKind.PASSPHRASE))
            }
            accountIds.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { add(secret(it, OpeningSecretKind.ACCOUNT_ID)) }
            // The original reader offered v2 and v1 to every file it found, not only to envelopes
            // stamped older than v3. A v3 envelope sealed by a build that still used an older
            // literal is precisely the "it used to open" case, so they stay on the list.
            if (!olderThanV3) {
                add(secret(LEGACY_CLOUD_KEY_V2, OpeningSecretKind.RETIRED_UNIVERSAL_KEY))
                add(secret(LEGACY_CLOUD_KEY_V1, OpeningSecretKind.RETIRED_UNIVERSAL_KEY))
            }
            if (olderThanV3) {
                add(secret(LEGACY_CLOUD_KEY_V2, OpeningSecretKind.RETIRED_UNIVERSAL_KEY))
                add(secret(LEGACY_CLOUD_KEY_V1, OpeningSecretKind.RETIRED_UNIVERSAL_KEY))
                add(secret(LEGACY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY))
                SHARED_LEGACY_KEYS.forEach {
                    add(secret(it, OpeningSecretKind.SHARED_LEGACY_KEY))
                }
            }
            emails.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach {
                    add(secret(it, OpeningSecretKind.ACCOUNT_EMAIL))
                    add(secret(it.lowercase(Locale.US), OpeningSecretKind.ACCOUNT_EMAIL))
                    val handle = it.substringBefore('@').trim()
                    if (handle.isNotBlank()) {
                        add(secret(handle, OpeningSecretKind.ACCOUNT_EMAIL))
                    }
                }
            displayName?.trim()?.takeIf { it.isNotBlank() }?.let {
                add(secret(it, OpeningSecretKind.ACCOUNT_EMAIL))
            }
            // The original reader's candidate list was version-blind: it offered the account
            // address, the display name and its hard-coded historical addresses to every file it
            // found, including v3 envelopes. They stay last so a wrong guess cannot delay the
            // common keys, but a file sealed that way still opens.
            (HISTORICAL_OWNER_ADDRESSES + HISTORICAL_HANDLES).forEach {
                add(secret(it, OpeningSecretKind.ACCOUNT_EMAIL))
            }
        }
        val seen = mutableSetOf<String>()
        return ordered.filter { seen.add(it.value) }
    }
}

/** The universal strings past builds sealed with. Read-only history: never a writer's key again. */
const val PRIMARY_CLOUD_KEY = "SecureVaultPro_Universal_Cloud_Key_v3"
const val LEGACY_CLOUD_KEY_V2 = "SecureVaultPro_Universal_Cloud_Key_v2"
const val LEGACY_CLOUD_KEY_V1 = "SecureVaultPro_Universal_Cloud_Key_v1"
const val LEGACY_CLOUD_KEY = "SecureVaultPro_Universal_Cloud_Key"
