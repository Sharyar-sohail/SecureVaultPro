package com.securevault.pro.security.backup

import java.util.Locale

/**
 * The order in which stored envelopes are offered their keys.
 *
 * This is the one place the order lives, and it mirrors the writer's history rather than a hunch:
 *
 *  1. the passphrase the owner typed, if any;
 *  2. this device's own vault key — every keyring-era build (3.4.x) seals with it;
 *  3. `SecureVaultPro_Universal_Cloud_Key_v3` — every build up to 3.3.x seals with it;
 *  4. the account address, exact then lowercase;
 *  5. the older retired universal strings.
 *
 * Steps 2 and 3 are the ones that matter. An earlier 3.4.9 draft put the address variants first and
 * asked the keyring for a sealing secret even when it held none — which *generates* a fresh key that
 * can never open the owner's file. The real key then sat past a time budget and the restore failed
 * on files it had always been able to read. Keep this order pinned by `LegacyKeyPlanTest`.
 */
object LegacyKeyPlan {

    fun ordered(
        emails: List<String>,
        passphrase: String? = null,
        statedIterations: Int? = null,
        vaultSecret: String? = null
    ): List<OpeningSecret> {
        val stated = statedIterations?.takeIf { it > 0 }
        val universalProfiles = stated?.let { listOf(it) } ?: listOf(
            LegacyEnvelopeOpener.CURRENT_ITERATIONS,
            LegacyEnvelopeOpener.PIN_PROFILE_ITERATIONS,
            LegacyEnvelopeOpener.EARLIER_PROFILE_ITERATIONS
        )
        // The keyring era and the address era only ever wrote 600,000 (or whatever the file states).
        val singleProfile = stated?.let { listOf(it) }
            ?: listOf(LegacyEnvelopeOpener.CURRENT_ITERATIONS)

        val ordered = buildList {
            if (!passphrase.isNullOrBlank()) {
                add(OpeningSecret(passphrase.trim(), OpeningSecretKind.PASSPHRASE, universalProfiles))
            }
            if (!vaultSecret.isNullOrBlank()) {
                add(OpeningSecret(vaultSecret, OpeningSecretKind.VAULT_KEY, singleProfile))
            }
            add(OpeningSecret(PRIMARY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
            emails.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach {
                    add(OpeningSecret(it, OpeningSecretKind.ACCOUNT_EMAIL, singleProfile))
                    add(
                        OpeningSecret(
                            it.lowercase(Locale.US),
                            OpeningSecretKind.ACCOUNT_EMAIL,
                            singleProfile
                        )
                    )
                }
            add(OpeningSecret(LEGACY_CLOUD_KEY_V2, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
            add(OpeningSecret(LEGACY_CLOUD_KEY_V1, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
            add(OpeningSecret(LEGACY_CLOUD_KEY, OpeningSecretKind.RETIRED_UNIVERSAL_KEY, universalProfiles))
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
