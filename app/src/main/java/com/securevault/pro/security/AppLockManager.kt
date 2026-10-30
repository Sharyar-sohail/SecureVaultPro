package com.securevault.pro.security

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.securevault.pro.data.remote.firebase.Keyring
import com.securevault.pro.diagnostics.CrashLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.securityDataStore by preferencesDataStore(name = "vault_security_prefs")

/** Must match VaultPalettes.DEFAULT_ID and VaultFont.DEFAULT_ID. */
private const val DEFAULT_THEME = "aqua"
private const val DEFAULT_FONT = "inter"

/** Kept at file scope so the recovery screen can write them without a DI graph. */
private val THEME_ID_KEY = stringPreferencesKey("theme_id")
private val FONT_ID_KEY = stringPreferencesKey("font_id")

/**
 * Put the look back to stock from the recovery screen, which is the one surface that has to
 * survive a broken dependency graph — so this writes the two preferences [AppLockManager]
 * reads instead of asking for the manager that normally owns them.
 */
suspend fun Context.resetVaultLookToStock() {
    securityDataStore.edit {
        it[THEME_ID_KEY] = DEFAULT_THEME
        it[FONT_ID_KEY] = DEFAULT_FONT
    }
}

enum class UnlockResult { INVALID, MASTER_PASSWORD, PIN, DECOY_PIN }

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyDerivation: KeyDerivation,
    private val keyring: Keyring
) {
    private object Keys {
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val INTRO_SEEN = booleanPreferencesKey("intro_seen")
        val MASTER_HASH = stringPreferencesKey("master_hash")
        val MASTER_SALT = stringPreferencesKey("master_salt")
        val MASTER_ITERATIONS = intPreferencesKey("master_iterations")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_ITERATIONS = intPreferencesKey("pin_iterations")
        val DECOY_HASH = stringPreferencesKey("decoy_hash")
        val DECOY_SALT = stringPreferencesKey("decoy_salt")
        val DECOY_ITERATIONS = intPreferencesKey("decoy_iterations")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val SCREENSHOT_BLOCKED = booleanPreferencesKey("screenshot_blocked")
        val AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
        val CLIPBOARD_SECONDS = intPreferencesKey("clipboard_seconds")
        val TRAVEL_MODE = booleanPreferencesKey("travel_mode")
        val RANDOMIZE_KEYPAD = booleanPreferencesKey("randomize_keypad")
        val LAST_BACKUP = longPreferencesKey("last_backup")
        val CLOUD_EMAIL = stringPreferencesKey("cloud_email")
        val COMPANION_URL = stringPreferencesKey("companion_url")
        val COMPANION_ENABLED = booleanPreferencesKey("companion_enabled")
        val COMPANION_PAIR = stringPreferencesKey("companion_pair")
        val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        val LOCKOUT_UNTIL = longPreferencesKey("lockout_until")
        val PIN_LENGTH = intPreferencesKey("pin_length")
        val THEME_ID = THEME_ID_KEY
        val FONT_ID = FONT_ID_KEY
        val PANIC_SHAKE = booleanPreferencesKey("panic_shake")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val INACTIVITY_LOCK_SECONDS = intPreferencesKey("inactivity_lock_seconds")
        val DEAD_MAN_ARMED = booleanPreferencesKey("dead_man_armed")
        val DEAD_MAN_INTERVAL_DAYS = intPreferencesKey("dead_man_interval_days")
        val DEAD_MAN_LAST_CHECK_IN = longPreferencesKey("dead_man_last_check_in")
        val HIBP_API_KEY = stringPreferencesKey("hibp_api_key")
    }

    private val store = context.securityDataStore

    private val _storeDegraded = MutableStateFlow(false)

    /** True once a settings read has failed on this launch. */
    val storeDegraded: StateFlow<Boolean> = _storeDegraded.asStateFlow()

    /**
     * Every read goes through here. A damaged preference file used to throw out of the
     * flow that fed the whole stage machine, and because nothing downstream could catch
     * it the process died the moment the lock screen appeared — on every launch, since
     * the file stayed damaged. Defaults plus a recorded fault are strictly better: the
     * owner sees a working screen and the report says what happened. The vault itself is
     * sealed by a Keystore key, so a settings read that falls back never exposes or
     * loses stored secrets.
     */
    private val data: Flow<Preferences> = store.data.catch { error ->
        _storeDegraded.value = true
        CrashLogger.noteFault(context, "Local security settings could not be read", error)
        emit(emptyPreferences())
    }

    val setupComplete: Flow<Boolean> = data.map { it[Keys.SETUP_COMPLETE] ?: false }
    val onboardingDone: Flow<Boolean> = data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val introSeen: Flow<Boolean> = data.map { it[Keys.INTRO_SEEN] ?: false }
    val biometricEnabled: Flow<Boolean> = data.map { it[Keys.BIOMETRIC_ENABLED] ?: false }
    val screenshotBlocked: Flow<Boolean> = data.map { it[Keys.SCREENSHOT_BLOCKED] ?: true }
    val autoLockSeconds: Flow<Int> = data.map { it[Keys.AUTO_LOCK_SECONDS] ?: 60 }
    val clipboardSeconds: Flow<Int> = data.map { it[Keys.CLIPBOARD_SECONDS] ?: 30 }
    val travelMode: Flow<Boolean> = data.map { it[Keys.TRAVEL_MODE] ?: false }
    val randomizeKeypad: Flow<Boolean> = data.map { it[Keys.RANDOMIZE_KEYPAD] ?: true }
    val decoyEnabled: Flow<Boolean> = data.map { !(it[Keys.DECOY_HASH] ?: "").isNullOrBlank() }
    val pinLength: Flow<Int> = data.map { it[Keys.PIN_LENGTH] ?: 6 }
    val lastBackupAt: Flow<Long> = data.map { it[Keys.LAST_BACKUP] ?: 0L }
    val cloudEmail: Flow<String> = data.map { it[Keys.CLOUD_EMAIL] ?: "" }
    val companionUrl: Flow<String> = data.map { it[Keys.COMPANION_URL] ?: com.securevault.pro.BuildConfig.DEFAULT_COMPANION_URL }
    val companionEnabled: Flow<Boolean> = data.map { it[Keys.COMPANION_ENABLED] ?: false }
    /**
     * Short handshake code shown in the pairing QR. It only identifies the browser
     * session the owner is standing at; it is never a key and decrypts nothing.
     */
    val companionPairCode: Flow<String> = data.map { it[Keys.COMPANION_PAIR] ?: "" }
    val themeId: Flow<String> = data.map { it[Keys.THEME_ID] ?: DEFAULT_THEME }
    val fontId: Flow<String> = data.map { it[Keys.FONT_ID] ?: DEFAULT_FONT }
    val panicShakeEnabled: Flow<Boolean> = data.map { it[Keys.PANIC_SHAKE] ?: false }
    val autoSyncEnabled: Flow<Boolean> = data.map { it[Keys.AUTO_SYNC] ?: true }
    val inactivityLockSeconds: Flow<Int> = data.map { it[Keys.INACTIVITY_LOCK_SECONDS] ?: 120 }
    val deadManArmed: Flow<Boolean> = data.map { it[Keys.DEAD_MAN_ARMED] ?: false }
    val deadManIntervalDays: Flow<Int> = data.map { it[Keys.DEAD_MAN_INTERVAL_DAYS] ?: 7 }
    val deadManLastCheckIn: Flow<Long> = data.map { it[Keys.DEAD_MAN_LAST_CHECK_IN] ?: 0L }
    val hibpApiKey: Flow<String> = data.map { it[Keys.HIBP_API_KEY] ?: "" }

    suspend fun isSetupComplete(): Boolean = data.first()[Keys.SETUP_COMPLETE] ?: false
    suspend fun isOnboardingDone(): Boolean = data.first()[Keys.ONBOARDING_DONE] ?: false
    suspend fun isBiometricEnabled(): Boolean = data.first()[Keys.BIOMETRIC_ENABLED] ?: false

    /**
     * Writes credentials, the biometric choice and the setup flag in one edit. They used
     * to be three awaits, and the stage machine could see the setup complete while the
     * session was still locked — which flashed the lock screen between "create PIN" and
     * the dashboard.
     */
    suspend fun completeSetup(
        masterPassword: String,
        pin: String,
        biometricEnabled: Boolean
    ) = withContext(Dispatchers.Default) {
        require(masterPassword.length >= 8) { "Master password too short" }
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "PIN must be 4-8 digits" }
        val masterSalt = keyDerivation.generateSalt()
        val pinSalt = keyDerivation.generateSalt()
        val masterHash = keyDerivation.deriveKey(
            masterPassword.toCharArray(), masterSalt, KeyDerivation.PBKDF2_ITERATIONS
        )
        val pinHash = keyDerivation.deriveKey(
            pin.toCharArray(), pinSalt, KeyDerivation.PIN_ITERATIONS
        )
        store.edit { prefs ->
            prefs[Keys.MASTER_HASH] = b64(masterHash)
            prefs[Keys.MASTER_SALT] = b64(masterSalt)
            prefs[Keys.MASTER_ITERATIONS] = KeyDerivation.PBKDF2_ITERATIONS
            prefs[Keys.PIN_HASH] = b64(pinHash)
            prefs[Keys.PIN_SALT] = b64(pinSalt)
            prefs[Keys.PIN_ITERATIONS] = KeyDerivation.PIN_ITERATIONS
            prefs[Keys.PIN_LENGTH] = pin.length
            prefs[Keys.BIOMETRIC_ENABLED] = biometricEnabled
            prefs[Keys.SETUP_COMPLETE] = true
            prefs[Keys.ONBOARDING_DONE] = true
            prefs[Keys.SCREENSHOT_BLOCKED] = prefs[Keys.SCREENSHOT_BLOCKED] ?: true
            prefs[Keys.RANDOMIZE_KEYPAD] = prefs[Keys.RANDOMIZE_KEYPAD] ?: true
            prefs[Keys.FAILED_ATTEMPTS] = 0
            prefs[Keys.LOCKOUT_UNTIL] = 0L
        }
        // The master passphrase is in memory at exactly three moments: setup, a passphrase change
        // and a passphrase unlock. This is one of them, and the only one that always happens, so it
        // is where the cloud copy of the vault key gets sealed for the web companion. Fire and
        // forget: a phone with no signal still finishes setup, and the share retries on next unlock.
        runCatching { keyring.onMasterPassphrase(masterPassword) }
    }

    /**
     * Changes the master passphrase and, when [previousPassphrase] is supplied, re-seals the cloud
     * vault key with it. Without that re-seal the web companion would keep asking for a passphrase
     * that no longer exists anywhere.
     *
     * The local hash is committed first so an offline owner is never stuck on a settings screen, and
     * [Keyring.onPassphraseRotated] queues the sealed replacement before it attempts the write — the
     * commit and the cloud are therefore brought back in step automatically on the next connection.
     */
    suspend fun setMasterPassword(
        newPassword: String,
        previousPassphrase: String? = null
    ) = withContext(Dispatchers.Default) {
        require(newPassword.length >= 8)
        val salt = keyDerivation.generateSalt()
        val hash = keyDerivation.deriveKey(newPassword.toCharArray(), salt, KeyDerivation.PBKDF2_ITERATIONS)
        store.edit {
            it[Keys.MASTER_HASH] = b64(hash)
            it[Keys.MASTER_SALT] = b64(salt)
            it[Keys.MASTER_ITERATIONS] = KeyDerivation.PBKDF2_ITERATIONS
        }
        runCatching { keyring.onPassphraseRotated(previousPassphrase, newPassword) }
    }

    suspend fun setPin(newPin: String) = withContext(Dispatchers.Default) {
        require(newPin.length in 4..8 && newPin.all(Char::isDigit))
        val salt = keyDerivation.generateSalt()
        val hash = keyDerivation.deriveKey(newPin.toCharArray(), salt, KeyDerivation.PIN_ITERATIONS)
        store.edit {
            it[Keys.PIN_HASH] = b64(hash)
            it[Keys.PIN_SALT] = b64(salt)
            it[Keys.PIN_ITERATIONS] = KeyDerivation.PIN_ITERATIONS
            it[Keys.PIN_LENGTH] = newPin.length
        }
    }

    suspend fun setDecoyPin(decoyPin: String?) {
        if (decoyPin.isNullOrBlank()) {
            store.edit {
                it.remove(Keys.DECOY_HASH)
                it.remove(Keys.DECOY_SALT)
                it.remove(Keys.DECOY_ITERATIONS)
            }
        } else {
            require(decoyPin.length in 4..8 && decoyPin.all(Char::isDigit))
            val salt = withContext(Dispatchers.Default) { keyDerivation.generateSalt() }
            val hash = withContext(Dispatchers.Default) {
                keyDerivation.deriveKey(decoyPin.toCharArray(), salt, KeyDerivation.PIN_ITERATIONS)
            }
            store.edit {
                it[Keys.DECOY_HASH] = b64(hash)
                it[Keys.DECOY_SALT] = b64(salt)
                it[Keys.DECOY_ITERATIONS] = KeyDerivation.PIN_ITERATIONS
            }
        }
    }

    suspend fun verifyMasterPassword(password: String): Boolean {
        if (password.isBlank()) return false
        val prefs = data.first()
        val salt = prefs[Keys.MASTER_SALT]?.let(::unB64) ?: return false
        val expected = prefs[Keys.MASTER_HASH]?.let(::unB64) ?: return false
        val iterations = prefs[Keys.MASTER_ITERATIONS] ?: KeyDerivation.PBKDF2_ITERATIONS
        val actual = withContext(Dispatchers.Default) {
            keyDerivation.deriveKey(password.toCharArray(), salt, iterations)
        }
        val matched = keyDerivation.constantTimeEquals(actual, expected)
        if (matched) {
            // A proven passphrase is also the moment the cloud keyring can be sealed or confirmed,
            // so an install that came across with no network picks web access up on its own.
            runCatching { keyring.onMasterPassphrase(password) }
        }
        return matched
    }

    suspend fun verifyPin(pin: String): Boolean = verifyHashedPin(pin, false)

    suspend fun verifyDecoyPin(pin: String): Boolean = verifyHashedPin(pin, true)

    private suspend fun verifyHashedPin(pin: String, decoy: Boolean): Boolean {
        if (pin.isBlank()) return false
        val prefs = data.first()
        val hashKey = if (decoy) Keys.DECOY_HASH else Keys.PIN_HASH
        val saltKey = if (decoy) Keys.DECOY_SALT else Keys.PIN_SALT
        val iterKey = if (decoy) Keys.DECOY_ITERATIONS else Keys.PIN_ITERATIONS
        val expected = prefs[hashKey]?.let(::unB64) ?: return false
        val salt = prefs[saltKey]?.let(::unB64) ?: return false
        val iterations = prefs[iterKey] ?: KeyDerivation.PIN_ITERATIONS
        val actual = withContext(Dispatchers.Default) {
            keyDerivation.deriveKey(pin.toCharArray(), salt, iterations)
        }
        return keyDerivation.constantTimeEquals(actual, expected)
    }

    suspend fun verifySecret(secret: String, allowDecoy: Boolean = true): UnlockResult {
        if (secret.isBlank()) return UnlockResult.INVALID
        if (allowDecoy && verifyDecoyPin(secret)) return UnlockResult.DECOY_PIN
        if (secret.length in 4..8 && secret.all(Char::isDigit) && verifyPin(secret)) return UnlockResult.PIN
        if (verifyMasterPassword(secret)) return UnlockResult.MASTER_PASSWORD
        return UnlockResult.INVALID
    }

    suspend fun recordUnlockFailure(): Long {
        var lockoutUntil = 0L
        store.edit { prefs ->
            val attempts = (prefs[Keys.FAILED_ATTEMPTS] ?: 0) + 1
            if (attempts >= 5) {
                lockoutUntil = System.currentTimeMillis() + 30_000L
                prefs[Keys.FAILED_ATTEMPTS] = 0
                prefs[Keys.LOCKOUT_UNTIL] = lockoutUntil
            } else {
                prefs[Keys.FAILED_ATTEMPTS] = attempts
            }
        }
        return lockoutUntil
    }

    suspend fun clearUnlockFailures() {
        store.edit {
            it[Keys.FAILED_ATTEMPTS] = 0
            it[Keys.LOCKOUT_UNTIL] = 0L
        }
    }

    suspend fun lockedUntil(): Long = data.first()[Keys.LOCKOUT_UNTIL] ?: 0L

    suspend fun failedAttempts(): Int = data.first()[Keys.FAILED_ATTEMPTS] ?: 0

    suspend fun setBiometricEnabled(enabled: Boolean) = store.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    suspend fun setScreenshotBlocked(enabled: Boolean) = store.edit { it[Keys.SCREENSHOT_BLOCKED] = enabled }
    suspend fun setAutoLockSeconds(seconds: Int) = store.edit { it[Keys.AUTO_LOCK_SECONDS] = seconds.coerceIn(15, 900) }
    suspend fun setClipboardSeconds(seconds: Int) = store.edit { it[Keys.CLIPBOARD_SECONDS] = seconds.coerceIn(30, 60) }
    suspend fun setInactivityLockSeconds(seconds: Int) = store.edit { it[Keys.INACTIVITY_LOCK_SECONDS] = seconds.coerceIn(30, 900) }
    suspend fun setTravelMode(enabled: Boolean) = store.edit { it[Keys.TRAVEL_MODE] = enabled }
    suspend fun setRandomizeKeypad(enabled: Boolean) = store.edit { it[Keys.RANDOMIZE_KEYPAD] = enabled }
    suspend fun markBackupComplete() = store.edit { it[Keys.LAST_BACKUP] = System.currentTimeMillis() }
    suspend fun setOnboardingDone(done: Boolean) = store.edit { it[Keys.ONBOARDING_DONE] = done }
    suspend fun setIntroSeen(seen: Boolean) = store.edit { it[Keys.INTRO_SEEN] = seen }
    suspend fun setCloudEmail(email: String) = store.edit { it[Keys.CLOUD_EMAIL] = email }
    suspend fun setCompanionUrl(url: String) = store.edit { it[Keys.COMPANION_URL] = url.trim() }
    suspend fun setCompanionEnabled(enabled: Boolean) = store.edit { it[Keys.COMPANION_ENABLED] = enabled }
    suspend fun setCompanionPairCode(code: String) =
        store.edit { it[Keys.COMPANION_PAIR] = code.trim().uppercase() }
    suspend fun setThemeId(id: String) = store.edit {
        it[Keys.THEME_ID] = id.trim().ifBlank { DEFAULT_THEME }
    }
    suspend fun setFontId(id: String) = store.edit {
        it[Keys.FONT_ID] = id.trim().ifBlank { DEFAULT_FONT }
    }
    suspend fun setPanicShakeEnabled(enabled: Boolean) = store.edit { it[Keys.PANIC_SHAKE] = enabled }
    suspend fun setAutoSyncEnabled(enabled: Boolean) = store.edit { it[Keys.AUTO_SYNC] = enabled }

    suspend fun armDeadManSwitch(intervalDays: Int) = store.edit {
        it[Keys.DEAD_MAN_ARMED] = true
        it[Keys.DEAD_MAN_INTERVAL_DAYS] = intervalDays.coerceIn(1, 90)
        it[Keys.DEAD_MAN_LAST_CHECK_IN] = System.currentTimeMillis()
    }

    suspend fun disarmDeadManSwitch() = store.edit {
        it[Keys.DEAD_MAN_ARMED] = false
        it.remove(Keys.DEAD_MAN_LAST_CHECK_IN)
    }

    suspend fun recordDeadManCheckIn() = store.edit {
        if (it[Keys.DEAD_MAN_ARMED] == true) {
            it[Keys.DEAD_MAN_LAST_CHECK_IN] = System.currentTimeMillis()
        }
    }

    suspend fun setHibpApiKey(key: String) = store.edit {
        it[Keys.HIBP_API_KEY] = key.trim()
    }

    suspend fun deadManSnapshot(): Triple<Boolean, Int, Long> {
        val prefs = data.first()
        return Triple(
            prefs[Keys.DEAD_MAN_ARMED] ?: false,
            prefs[Keys.DEAD_MAN_INTERVAL_DAYS] ?: 7,
            prefs[Keys.DEAD_MAN_LAST_CHECK_IN] ?: 0L
        )
    }

    suspend fun wipeSecurityPrefs() {
        store.edit { it.clear() }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unB64(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: Throwable) {
        null
    }
}
