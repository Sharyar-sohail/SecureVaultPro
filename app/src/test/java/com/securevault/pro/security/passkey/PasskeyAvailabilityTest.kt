package com.securevault.pro.security.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "this device cannot do passkeys" verdict, and the failure story that follows it.
 *
 * Both halves are pure functions of plain values, which is the only reason they can be tested at all:
 * the transport they describe lives inside the system credential provider and answers only on a real
 * phone running Android 14 or newer.
 *
 * The thresholds come from the pinned artifact, not from a blog post. `CredentialProviderFactory` in
 * `androidx.credentials:credentials:1.2.0-rc01` returns a transport when `SDK_INT >= 34`, tries a
 * manifest-named OEM provider at exactly 33, and returns nothing at 32 and below — at which point the
 * library throws `Get|CreateCredentialProviderConfigurationException`. This app ships that artifact
 * alone (the Play Services transport is not in the offline Gradle cache, so it cannot be a dependency)
 * and declares no provider service of its own in `AndroidManifest.xml`, so 34 really is the floor.
 *
 * The rule the whole file protects: **an unknown is never an available**. Every branch that cannot
 * decide refuses to start a ceremony.
 */
class PasskeyAvailabilityTest {

    private fun verdict(
        sdk: Int,
        providerInManifest: Boolean = false,
        primary: Boolean = true,
        keyOpens: Boolean = true
    ) = PasskeyAvailabilityJudge.verdict(sdk, providerInManifest, primary, keyOpens)

    // ---- the transport floor ---------------------------------------------------------------

    @Test
    fun `android 14 and above have a transport`() {
        listOf(34, 35, 36, 100).forEach { sdk ->
            val availability = verdict(sdk)
            assertEquals("API $sdk", PasskeyTransportState.READY, availability.transport)
            assertTrue(availability.canRunCeremony)
            assertTrue(availability.canEnrol)
            assertTrue(availability.canAuthenticate)
            assertFalse(availability.isTransportBlockedByPlatform)
            assertEquals("ready", availability.reasonLabel())
        }
        assertEquals(34, PasskeyAvailabilityJudge.MIN_SDK_FOR_FRAMEWORK_CREDENTIAL_STORE)
    }

    @Test
    fun `android 13 and below have none, and say which half is missing`() {
        listOf(26, 30, 32, 33).forEach { sdk ->
            val availability = verdict(sdk)
            assertFalse("API $sdk claimed a transport", availability.canRunCeremony)
            assertTrue(availability.isTransportBlockedByPlatform)
        }
        val thirtyThree = verdict(33)
        assertEquals(PasskeyTransportState.PROVIDER_NOT_REGISTERED, thirtyThree.transport)
        assertEquals("no provider", thirtyThree.reasonLabel())

        val older = verdict(32)
        assertEquals(PasskeyTransportState.ANDROID_TOO_OLD, older.transport)
        assertEquals("android too old", older.reasonLabel())
        // The sentence is owner-facing, so the test pins what it must accomplish rather than its
        // exact wording: say which floor is missing, and say plainly that nothing was lost.
        val transportMessage = PasskeyFailureClassifier.ownerMessage(
            PasskeyFailure(PasskeyFailureKind.TRANSPORT_UNAVAILABLE, null, null)
        )
        assertTrue(transportMessage, transportMessage.contains("Android 14"))
        assertTrue(transportMessage, transportMessage.contains("nothing was lost"))
        assertFalse(transportMessage, transportMessage.contains("transport"))
    }

    /** API 33 is the one level where an OEM provider *could* be registered; the app has not, so it must say so. */
    @Test
    fun `a manifest-registered provider is the only way API 33 becomes ready`() {
        assertEquals(
            PasskeyTransportState.READY,
            verdict(33, providerInManifest = true).transport
        )
        assertEquals(
            PasskeyTransportState.PROVIDER_NOT_REGISTERED,
            verdict(33, providerInManifest = false).transport
        )
        assertEquals(33, PasskeyAvailabilityJudge.PRE_FRAMEWORK_OEM_PROVIDER_SDK)
    }

    @Test
    fun `an unreadable device is never counted as able`() {
        listOf(0, -1).forEach { sdk ->
            val availability = verdict(sdk)
            assertEquals(PasskeyTransportState.UNKNOWN, availability.transport)
            assertFalse(availability.canRunCeremony)
            assertFalse(availability.canEnrol)
            assertFalse(availability.canAuthenticate)
            assertEquals("unknown", availability.reasonLabel())
            // UNKNOWN is not a platform verdict: the owner cannot fix their Android version downward.
            assertFalse(availability.isTransportBlockedByPlatform)
        }
    }

    // ---- the session and the key ------------------------------------------------------------

    @Test
    fun `a transport that exists is still not a licence to write in a decoy session`() {
        val decoy = verdict(34, primary = false)
        assertEquals(PasskeyTransportState.READY, decoy.transport)
        assertFalse(decoy.canRunCeremony)
        assertFalse(decoy.canEnrol)
        assertFalse(decoy.canAuthenticate)
        assertEquals("session", decoy.reasonLabel())
        assertFalse(decoy.isTransportBlockedByPlatform)
    }

    @Test
    fun `a device key that will not open stops both directions`() {
        val broken = verdict(34, keyOpens = false)
        assertFalse(broken.canRunCeremony)
        assertFalse(broken.canEnrol)
        assertFalse(broken.canAuthenticate)
        assertEquals("device key", broken.reasonLabel())
        // The transport itself is fine, so the banner must not blame the Android version.
        assertEquals(PasskeyTransportState.READY, broken.transport)
    }

    @Test
    fun `the two gates are one rule, so they can never drift apart`() {
        listOf(26, 33, 34).forEach { sdk ->
            listOf(true, false).forEach { primary ->
                listOf(true, false).forEach { keyOpens ->
                    val availability = verdict(
                        sdk = sdk,
                        providerInManifest = false,
                        primary = primary,
                        keyOpens = keyOpens
                    )
                    assertEquals(availability.canRunCeremony, availability.canEnrol)
                    assertEquals(availability.canRunCeremony, availability.canAuthenticate)
                }
            }
        }
    }

    // ---- the failure story -------------------------------------------------------------------

    /**
     * Keyed on the exception's *type-name string* plus its `type` field, exactly as
     * [PasskeyCredentialTransport] feeds it (`e.javaClass.name`, `e.type`). The class names below are
     * the ones that exist in the pinned artifact.
     */
    @Test
    fun `each provider refusal lands in the app's own vocabulary`() {
        val cases = listOf(
            // (exception class name, its `type`, expected kind)
            Triple(
                "androidx.credentials.exceptions.GetCredentialProviderConfigurationException",
                "InvalidStateError", PasskeyFailureKind.TRANSPORT_UNAVAILABLE
            ),
            Triple(
                "androidx.credentials.exceptions.CreateCredentialProviderConfigurationException",
                null, PasskeyFailureKind.TRANSPORT_UNAVAILABLE
            ),
            Triple(
                "androidx.credentials.exceptions.GetCredentialCancellationException",
                "AbortError", PasskeyFailureKind.CANCELLED
            ),
            Triple(
                "androidx.credentials.exceptions.CreateCredentialCancellationException",
                "AbortError", PasskeyFailureKind.CANCELLED
            ),
            Triple(
                "androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException",
                "NotAllowedError", PasskeyFailureKind.CANCELLED
            ),
            Triple(
                "androidx.credentials.exceptions.GetCredentialInterruptedException",
                "AbortError", PasskeyFailureKind.CANCELLED
            ),
            Triple("androidx.credentials.exceptions.NoCredentialException", "NotFoundError",
                PasskeyFailureKind.NO_PASSKEY),
            Triple(
                "androidx.credentials.exceptions.GetCredentialUnsupportedException",
                null, PasskeyFailureKind.UNSUPPORTED
            ),
            Triple(
                "androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException",
                "NotSupportedError", PasskeyFailureKind.UNSUPPORTED
            ),
            Triple(
                "androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException",
                "ConstraintError", PasskeyFailureKind.INVALID_REQUEST
            ),
            Triple(
                "androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException",
                "SyntaxError", PasskeyFailureKind.INVALID_REQUEST
            ),
            Triple(
                "androidx.credentials.exceptions.CreateCredentialNoCreateOptionException",
                "NoCreateOptionError", PasskeyFailureKind.TRANSPORT_UNAVAILABLE
            ),
            Triple("java.lang.IllegalStateException", "UnknownError", PasskeyFailureKind.UNKNOWN),
            Triple("kotlin.Throwable", null, PasskeyFailureKind.UNKNOWN)
        )
        cases.forEach { (name, type, expected) ->
            val failure = PasskeyFailureClassifier.classify(name, type, null)
            assertEquals("$name / $type", expected, failure.kind)
            assertTrue(
                "$name produced no owner-facing sentence",
                PasskeyFailureClassifier.ownerMessage(failure).isNotBlank()
            )
        }
    }

    /** A clock running out is not the owner pressing back, even though the exception says "cancel". */
    @Test
    fun `a timeout outranks the cancellation it is named inside`() {
        val timeout = PasskeyFailureClassifier.classify(
            "kotlinx.coroutines.TimeoutCancellationException",
            null,
            "Timed out waiting for the credential sheet"
        )
        assertEquals(PasskeyFailureKind.TIMED_OUT, timeout.kind)
        assertEquals("the sheet stayed open too long",
            PasskeyFailure(PasskeyFailureKind.TIMED_OUT, "the sheet stayed open too long", null).providerMessage)
    }

    @Test
    fun `the provider's own words survive, capped`() {
        val long = "x".repeat(400)
        val failure = PasskeyFailureClassifier.classify(
            "androidx.credentials.exceptions.GetCredentialUnknownException",
            "SomeError",
            long
        )
        assertEquals(PasskeyFailureKind.UNKNOWN, failure.kind)
        assertEquals(160, failure.providerMessage?.length)
        assertEquals("SomeError", failure.providerType)
        assertTrue(PasskeyFailureClassifier.ownerMessage(failure).contains("x".repeat(90)))
        // A short type string is kept as it is; a long one is capped rather than dropped.
        val longType = PasskeyFailureClassifier.classify(null, "E".repeat(200), null)
        assertEquals(80, longType.providerType?.length)
    }

    @Test
    fun `a null or blank exception still classifies to something safe`() {
        val failure = PasskeyFailureClassifier.classify(null, null, null)
        assertEquals(PasskeyFailureKind.UNKNOWN, failure.kind)
        assertNullProviderWords(failure)
        assertFalse(failure.isCancelled)
        assertTrue(PasskeyFailureClassifier.ownerMessage(failure).length > 20)
    }

    private fun assertNullProviderWords(failure: PasskeyFailure) {
        assertEquals(null, failure.providerMessage)
        assertEquals(null, failure.providerType)
    }

    @Test
    fun `cancellation is the one refusal that is not an error`() {
        val cancelled = PasskeyFailure(PasskeyFailureKind.CANCELLED, null, "AbortError")
        assertTrue(cancelled.isCancelled)
        assertEquals(
            "Cancelled. Nothing was saved and nothing was sent to the site.",
            PasskeyFailureClassifier.ownerMessage(cancelled)
        )
        assertNotEquals(
            cancelled.kind,
            PasskeyFailure(PasskeyFailureKind.NO_PASSKEY, null, null).kind
        )
        assertTrue(PasskeyAvailabilityJudge.isTransportMissing(PasskeyFailureKind.TRANSPORT_UNAVAILABLE))
        assertFalse(PasskeyAvailabilityJudge.isTransportMissing(PasskeyFailureKind.NO_PASSKEY))
        assertFalse(PasskeyAvailabilityJudge.isTransportMissing(PasskeyFailureKind.CANCELLED))
    }

    /** The screen's four honest states must stay four distinct verdicts, not one collapsed guess. */
    @Test
    fun `every state has its own sentence on the device row`() {
        val sentences = PasskeyTransportState.entries.map { state ->
            PasskeyAvailability(state, 34, true, true).reasonLabel()
        }
        assertEquals(sentences.distinct().size, sentences.size)
        assertEquals(4, PasskeyTransportState.entries.size)
    }
}
