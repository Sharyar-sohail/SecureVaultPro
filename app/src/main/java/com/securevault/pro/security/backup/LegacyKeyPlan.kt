package com.securevault.pro.security.backup

import java.util.Locale

/**
 * The order in which stored envelopes are offered their keys.
 *
 * This is the one place the order lives, and it mirrors the writers this product has actually
 * shipped rather than a hunch:
 *
 *  1. the passphrase the owner typed, if any;
 *  2. the Google **account ID** — the same-package original app (`com.securevault.app`) sealed its
 *     Drive backup with it, and it is not derivable from the address;
 *  3. `SecureVaultPro_Universal_Cloud_Key_v3` — every build up to 3.3.x seals with it;
 *  4. this device's own vault key, when the keyring already holds one;
 *  5. the older retired universal strings and the shared legacy literals;
 *  6. the account address (exact, lowercase, local part), the display name, and the addresses the
 *     original reader hard-coded.
 *
 * Asking the keyring for a sealing secret when it holds none *generates* a fresh key that can never
 * open the owner's file; that regression is why [ordered] takes a nullable `vaultSecret` and why
 * `CloudSyncManager` only passes one when `hasDeviceKey()` is true. Keep this order pinned by
 * `LegacyKeyPlanTest`.
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

    fun ordered(
        emails: List<String>,
        passphrase: String? = null,
        statedIterations: Int? = null,
        vaultSecret: String? = null,
        accountIds: List<String> = emptyList(),
        displayName: String? = null
    ): List<OpeningSecret> {
        val stated = statedIterations?.takeIf { it > 0 }

        // A count stated in the file leads but never replaces the sweep: the original reader did not
        // read a count at all, it tried all four every time, and a file whose field disagrees with
        // the rounds it was actually sealed at must still open.
        fun withStated(defaults: List<Int>): List<Int> =
            stated?.let { listOf(it) + defaults }?.distinct() ?: defaults

        val universalProfiles = withStated(LegacyEnvelopeOpener.ITERATION_PROFILES)
        // Everything the address-era and literal-era writers could have paired with their key:
        // the original reader's exact four counts. 210k/120k stay off these because those counts
        // were only ever paired with the universal keys.
        val legacyProfiles = withStated(LegacyEnvelopeOpener.ADDRESS_ERA_PROFILES)
        // The keyring era only ever wrote 600,000, plus whatever the file states.
        val vaultProfiles = withStated(listOf(LegacyEnvelopeOpener.CURRENT_ITERATIONS))

        val ordered = buildList {
            if (!passphrase.isNullOrBlank()) {
                add(OpeningSecret(passphrase.trim(), OpeningSecretKind.PASSPHRASE, legacyProfiles))
            }
            accountIds.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { add(OpeningSecret(it, OpeningSecretKind.ACCOUNT_ID, legacyProfiles)) }
            add(OpeningSecret(PRIMARY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
            if (!vaultSecret.isNullOrBlank()) {
                add(OpeningSecret(vaultSecret, OpeningSecretKind.VAULT_KEY, vaultProfiles))
            }
            add(OpeningSecret(LEGACY_CLOUD_KEY_V2, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
            add(OpeningSecret(LEGACY_CLOUD_KEY_V1, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
            add(OpeningSecret(LEGACY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
            SHARED_LEGACY_KEYS.forEach {
                add(OpeningSecret(it, OpeningSecretKind.SHARED_LEGACY_KEY, legacyProfiles))
            }
            emails.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach {
                    add(OpeningSecret(it, OpeningSecretKind.ACCOUNT_EMAIL, legacyProfiles))
                    add(
                        OpeningSecret(
                            it.lowercase(Locale.US),
                            OpeningSecretKind.ACCOUNT_EMAIL,
                            legacyProfiles
                        )
                    )
                    val handle = it.substringBefore('@').trim()
                    if (handle.isNotBlank()) {
                        add(OpeningSecret(handle, OpeningSecretKind.ACCOUNT_EMAIL, legacyProfiles))
                    }
                }
            displayName?.trim()?.takeIf { it.isNotBlank() }?.let {
                add(OpeningSecret(it, OpeningSecretKind.ACCOUNT_EMAIL, legacyProfiles))
            }
            (HISTORICAL_OWNER_ADDRESSES + HISTORICAL_HANDLES).forEach {
                add(OpeningSecret(it, OpeningSecretKind.ACCOUNT_EMAIL, legacyProfiles))
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
