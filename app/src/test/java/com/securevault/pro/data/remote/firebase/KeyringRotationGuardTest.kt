package com.securevault.pro.data.remote.firebase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The offline-passphrase-change strand, pinned to source.
 *
 * Changing the master passphrase while the phone has no connection used to be a one-way door: the
 * new hash was committed locally, the background re-seal of the cloud keyring failed silently, and
 * the cloud kept a wrap that only the *superseded* passphrase could open. The web companion asks for
 * the passphrase the owner actually has, so it reported "wrong passphrase" forever.
 *
 * The fix is a queue of the finished envelope plus three replay paths, and the invariants that make
 * it safe are the ones this test holds: the queue holds ciphertext only, a replay must prove itself
 * against this device's key, a newer cloud node always wins, and a wiped device drops the queue.
 *
 * Source is read rather than executed because [Keyring] needs Context, SharedPreferences, Keystore
 * and a signed-in Firebase account — none of which exist in a JVM unit test.
 */
class KeyringRotationGuardTest {

    private val keyring: File? by lazy {
        listOf(
            File("src/main/java/com/securevault/pro/data/remote/firebase/Keyring.kt"),
            File("app/src/main/java/com/securevault/pro/data/remote/firebase/Keyring.kt")
        ).firstOrNull { it.isFile }
    }

    private val engine: File? by lazy {
        listOf(
            File("src/main/java/com/securevault/pro/data/remote/realtime/RealtimeEngine.kt"),
            File("app/src/main/java/com/securevault/pro/data/remote/realtime/RealtimeEngine.kt")
        ).firstOrNull { it.isFile }
    }

    @Test
    fun `a rotation queues the sealed wrap before it tries to write it`() {
        val body = rotateBody()
        val queue = body.indexOf("storePendingRotation(uid, envelope)")
        val write = body.indexOf("writeEnvelope(path, envelope)")
        assertTrue("rotateKeyring must queue the envelope", queue >= 0)
        assertTrue("rotateKeyring must write the envelope", write >= 0)
        assertTrue(
            "Queuing after a failed write is too late - the caller has no way to retry the seal",
            queue < write
        )
        assertTrue("A landed write clears the queue", body.contains("clearPendingRotation()"))
    }

    @Test
    fun `the queue can never hold a passphrase or a key`() {
        val body = sourceFile().readText().substringAfter("private fun storePendingRotation").substringBefore("private fun clearPendingRotation")
        for (forbidden in listOf("passphrase", "deviceVaultKey", "key.bytes", "sealingSecret", "cachedKey")) {
            assertFalse("The queue writer references $forbidden", body.contains(forbidden))
        }
        assertTrue("Only the envelope's own fields may be stored", body.contains("envelope.wrap"))
        assertTrue("The uid is an identifier, not a secret, and is needed to scope the replay",
            body.contains("KEY_PENDING_UID"))
    }

    @Test
    fun `the pending envelope parses through the same contract as a cloud node`() {
        val body = sourceFile().readText().substringAfter("fun pendingRotation()").substringBefore("fun hasPendingRotation")
        assertTrue("The queue is read back as a KeyringEnvelope", body.contains("KeyringEnvelope.fromCloudMap"))
        assertTrue("The algorithm field is the contract's", body.contains("KeyringCodec.ALGORITHM"))
    }

    @Test
    fun `an unlock repairs a stranded keyring only when the wrap proves itself`() {
        val publish = sourceFile().readText().substringAfter("suspend fun publishKeyring").substringBefore("Second-phone path")
        val repair = publish.substringAfter("if (opened == null) {").substringBefore("WRONG_PASSPHRASE)\n                return Result.failure(SvpAuthException(WRONG_PASSPHRASE))")
        assertTrue("The queued wrap must be opened with the passphrase just verified",
            repair.contains("pendingOpensWith(queued, passphrase, key.bytes)"))
        assertTrue("A queue entry from another account is never replayed",
            repair.contains("pendingOwner() == uid"))
        assertTrue("Failure is still a worded wrong-passphrase, never a silent overwrite",
            publish.contains("_status.value = _status.value.copy(note = WRONG_PASSPHRASE)"))
    }

    @Test
    fun `the replay never rolls back a newer cloud node`() {
        val flush = sourceFile().readText().substringAfter("suspend fun flushPendingRotation").substringBefore("// ----------------------------- publish / rotate")
        assertTrue("A cloud wrap at least as new as the queue wins and the queue is dropped",
            Regex("current\\.updatedAt\\s*>=\\s*pending\\.updatedAt[\\s\\S]{0,120}clearPendingRotation").containsMatchIn(flush))
        assertTrue("A replay scoped to a different account is dropped, not written",
            flush.contains("if (pendingOwner() != uid)"))
        assertTrue("A decoy session never shares a keyring", flush.contains("sessionManager.isDecoy()"))
        assertTrue("Only a landed write clears the queue",
            flush.substringAfter("writeEnvelope(path, pending)").contains("clearPendingRotation()"))
    }

    @Test
    fun `a wiped or signed-out device drops the queue with the key`() {
        val clear = sourceFile().readText().substringAfter("fun clearDeviceKey").substringBefore("private suspend fun writeEnvelope")
        for (key in listOf("KEY_PENDING_WRAP", "KEY_PENDING_SALT", "KEY_PENDING_ITERS", "KEY_PENDING_AT", "KEY_PENDING_UID")) {
            assertTrue("clearDeviceKey must remove $key", clear.contains(".remove($key)"))
        }
    }

    @Test
    fun `the engine replays the queue as soon as it comes up`() {
        val file = engine
        assumeTrue("RealtimeEngine.kt is not next to this run", file != null)
        val start = file!!.readText().substringAfter("fun start()").substringBefore("fun applyPersistenceOnce")
        assertTrue("Coming up is the automatic retry point", start.contains("flushPendingRotation"))
        assertTrue("A replay failure is surfaced, never swallowed", start.contains("lastError"))
    }

    private fun rotateBody(): String =
        sourceFile().readText().substringAfter("suspend fun rotateKeyring").substringBefore("/** Forgets the local copy only")

    private fun sourceFile(): File {
        val file = keyring
        assumeTrue("Keyring.kt is not next to this run", file != null)
        return file!!
    }
}
