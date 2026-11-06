package com.securevault.pro.presentation.components

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricUtils {

    /**
     * Face and fingerprint only. Device credential is deliberately excluded so the
     * platform shows the native biometric sheet instead of the phone's lock-screen
     * PIN / pattern / password flow.
     */
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK

    /**
     * `BiometricManager` is a binder call into a service OEM builds leave in unusual states, and
     * this used to run unguarded from view-model init: a `DeadObjectException` here killed the
     * lock screen before it could paint, which strands the owner outside his own vault with no
     * way in. A refused probe reads as "no biometrics", and the PIN / master password path stays.
     */
    fun canAuthenticate(context: Context): Boolean {
        val status = runCatching {
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
        }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
        onCancel: () -> Unit = {},
        onFallback: () -> Unit = {}
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onFallback()
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> onCancel()
                        // Anything below is the platform refusing the attempt, so the sheet is
                        // already gone and nothing else will bring it back. ColourOS reports
                        // several of these with an empty or meaningless `errString`, so the code
                        // writes the message, and the manual form is opened in the same breath:
                        // a lockout that only says "try again later" is a dead end.
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            onFallback()
                            onError("Too many failed face / fingerprint tries. Unlock with your PIN or master password.")
                        }
                        BiometricPrompt.ERROR_TIMEOUT -> {
                            onFallback()
                            onError("The scan timed out. Tap Unlock to try your face again, or use your PIN.")
                        }
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> {
                            onFallback()
                            onError("No face or fingerprint is enrolled on this phone. Unlock with your PIN or master password.")
                        }
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
                        BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> {
                            onFallback()
                            onError("The biometric sensor is busy. Unlock with your PIN or master password and try again in a moment.")
                        }
                        else -> onError(
                            errString.toString().ifBlank {
                                "Biometric unlock failed. Use your PIN or master password."
                            }
                        )
                    }
                }
            }
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            // Required whenever DEVICE_CREDENTIAL is not part of the authenticator set.
            .setNegativeButtonText("Use master password")
        prompt.authenticate(builder.build())
    }
}
