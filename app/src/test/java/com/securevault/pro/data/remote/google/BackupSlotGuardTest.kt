package com.securevault.pro.data.remote.google

import com.securevault.pro.data.backup.CloudBackupFile
import com.securevault.pro.security.KeyDerivation
import com.securevault.pro.security.backup.BackupEnvelopeFormat
import com.securevault.pro.security.backup.LegacyEnvelopeOpener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The rule that stops a reinstall from eating the owner's only backup.
 *
 * The old writer called `driveService.upload(name, bytes, existingId)` unconditionally, so a fresh
 * install — which cannot open a vault sealed with the previous device's key — replaced the real
 * vault with demo records. These tests drive the guard against an in-memory Drive and assert on the
 * bytes that survive, not on which method was called for its own sake.
 */
class BackupSlotGuardTest {

    private val derivation = KeyDerivation()
    private val opener = LegacyEnvelopeOpener(derivation)
    private val ourSecret = "dGhpc0lzT3VyVmF1bHRLZXlCeXRlc0ZvclRlc3RzMDAwMDAw"
    private val foreignSecret = "VGhpc0lzQW5vdGhlckRldmljZXNWYXVsdEtleTAwMDAwMDAw"

    private class FakeDrive {
        val files = mutableMapOf<String, Pair<CloudBackupFile, ByteArray>>()
        private var nextId = 0
        var downloads = 0
        var failDownloads = false

        fun put(name: String, bytes: ByteArray, modifiedTime: Long = 100L, isPrimary: Boolean = true): CloudBackupFile {
            val file = CloudBackupFile("id-${nextId++}", name, bytes.size.toLong(), modifiedTime, isPrimary)
            files[file.id] = file to bytes
            return file
        }

        fun bytesNamed(name: String): List<ByteArray> =
            files.values.filter { it.first.name == name }.map { it.second }

        fun latestNamed(name: String): ByteArray? = bytesNamed(name).lastOrNull()

        fun downloadsOf(id: String): suspend (CloudBackupFile) -> ByteArray = { file ->
            downloads++
            if (failDownloads) error("network down")
            files[file.id]?.second ?: error("missing ${file.id}")
        }

        fun uploadAsNew(): suspend (String, ByteArray) -> CloudBackupFile = { name, payload ->
            put(name, payload, modifiedTime = 100L + nextId, isPrimary = false)
        }

        fun uploadOver(): suspend (String, ByteArray, String) -> CloudBackupFile = { name, payload, id ->
            val existing = files[id] ?: error("no file $id")
            val replaced = existing.first.copy(size = payload.size.toLong(), modifiedTime = existing.first.modifiedTime + 1)
            files[id] = replaced to payload
            replaced
        }
    }

    private class FakeProofs : SlotProofStore {
        private val owned = mutableSetOf<String>()
        private val preserved = mutableSetOf<String>()
        override fun ownsSlot(key: String, secretFingerprint: String) = owned.contains("$key|$secretFingerprint")
        override fun markSlotOwned(key: String, secretFingerprint: String) { owned.add("$key|$secretFingerprint") }
        override fun wasPreserved(key: String) = preserved.contains(key)
        override fun markPreserved(key: String) { preserved.add(key) }
    }

    private fun guard(drive: FakeDrive, proofs: FakeProofs = FakeProofs(), secret: String = ourSecret) =
        BackupSlotGuard(
            opener = opener,
            derivation = derivation,
            ourSecret = secret,
            proofs = proofs,
            download = drive.downloadsOf(""),
            uploadAsNew = drive.uploadAsNew(),
            uploadOver = drive.uploadOver()
        )

    /** Seals [json] the way every release has: PBKDF2 over a per-file salt, AES-256-GCM, `iv.body`. */
    private fun seal(json: String, secret: String): ByteArray {
        val salt = ByteArray(32) { (it + 11).toByte() }
        val key = derivation.deriveKey(secret.toCharArray(), salt, KeyDerivation.PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val blob = cipher.doFinal(json.toByteArray())
        val data = Base64.getEncoder().encodeToString(cipher.iv) + "." + Base64.getEncoder().encodeToString(blob)
        return BackupEnvelopeFormat.envelopeJson(3, Base64.getEncoder().encodeToString(salt), data).toByteArray()
    }

    private val ourVault = """{"passwords":[{"id":"p-1","title":"Ours"}]}""".toByteArray()
    private val theirVault = """{"passwords":[{"id":"p-9","title":"Theirs"}]}""".toByteArray()

    @Test
    fun `an empty slot is created`() = runBlocking {
        val drive = FakeDrive()
        val outcome = guard(drive).write("vault_backup.enc", ourVault, null)
        assertTrue(outcome is BackupSlotGuard.Outcome.Written)
        assertEquals(1, drive.files.size)
        assertEquals(0, drive.downloads)
    }

    @Test
    fun `a slot sealed with our own key is replaced without a download of the whole vault`() = runBlocking {
        val drive = FakeDrive()
        val proofs = FakeProofs()
        val first = drive.put("vault_backup.enc", seal("""{"passwords":[]}""", ourSecret))
        guard(drive, proofs).write("vault_backup.enc", ourVault, first)
        val downloadsAfterFirst = drive.downloads
        val current = drive.files[first.id]!!.first
        guard(drive, proofs).write("vault_backup.enc", ourVault, current)
        assertEquals("a proven slot must not be downloaded again", downloadsAfterFirst, drive.downloads)
        assertEquals(1, drive.files.size)
    }

    @Test
    fun `a slot sealed with another device's key is preserved before it is replaced`() = runBlocking {
        val drive = FakeDrive()
        val foreignBytes = seal("""{"passwords":[{"id":"p-9","title":"Theirs"}]}""", foreignSecret)
        val slot = drive.put("vault_backup.enc", foreignBytes)

        val outcome = guard(drive).write("vault_backup.enc", ourVault, slot) as BackupSlotGuard.Outcome.Written

        assertNotNull("the guard must report the preserved copy by name", outcome.preservedName)
        assertTrue(outcome.preservedName!!.startsWith(BackupSlotGuard.PRESERVED_PREFIX))
        assertTrue(outcome.preservedName.endsWith(BackupSlotGuard.PRESERVED_SUFFIX))
        val preserved = drive.latestNamed(outcome.preservedName)
        assertNotNull("the original bytes must still be retrievable", preserved)
        assertTrue("the preserved copy must be byte-identical", preserved!!.contentEquals(foreignBytes))

        val slack = drive.files[slot.id]!!.second
        assertTrue("the slot now holds the new vault", slack.contentEquals(ourVault))
    }

    @Test
    fun `a slot that cannot be downloaded is not written at all`() = runBlocking {
        val drive = FakeDrive()
        val slot = drive.put("vault_backup.enc", seal("""{"passwords":[]}""", foreignSecret))
        val before = drive.files[slot.id]!!.second
        drive.failDownloads = true

        val outcome = guard(drive).write("vault_backup.enc", ourVault, slot)

        assertTrue(outcome is BackupSlotGuard.Outcome.Held)
        assertTrue(drive.files[slot.id]!!.second.contentEquals(before))
        assertEquals("nothing may be uploaded when the slot cannot be read", 1, drive.files.size)
    }

    @Test
    fun `after a preserve the next sync costs no download`() = runBlocking {
        val drive = FakeDrive()
        val proofs = FakeProofs()
        val slot = drive.put("vault_backup.enc", seal("""{"passwords":[]}""", foreignSecret))
        val g = guard(drive, proofs)
        g.write("vault_backup.enc", ourVault, slot)
        val downloads = drive.downloads
        val current = drive.files[slot.id]!!.first
        g.write("vault_backup.enc", ourVault, current)
        assertEquals(downloads, drive.downloads)
    }

    @Test
    fun `a foreign slot that was already preserved is replaced without a second copy`() = runBlocking {
        val drive = FakeDrive()
        val proofs = FakeProofs()
        val slot = drive.put("vault_backup.enc", seal("""{"passwords":[]}""", foreignSecret))
        proofs.markPreserved("${slot.id}:${slot.size}:${slot.modifiedTime}")
        guard(drive, proofs).write("vault_backup.enc", ourVault, slot)
        assertEquals("no duplicate safety copy for the same revision", 1, drive.files.size)
    }

    @Test
    fun `an unreadable slot with a held reason names the file`() = runBlocking {
        val drive = FakeDrive()
        val slot = drive.put("vault_backup.xml.enc", seal("""{"passwords":[]}""", foreignSecret))
        drive.failDownloads = true
        val outcome = guard(drive).write("vault_backup.xml.enc", ourVault, slot) as BackupSlotGuard.Outcome.Held
        assertTrue(outcome.reason.contains("vault_backup.xml.enc"))
    }

    @Test
    fun `opensWithOurKey is honest about foreign and plain files`() {
        val g = guard(FakeDrive())
        assertTrue(g.opensWithOurKey(seal("""{"passwords":[]}""", ourSecret)))
        assertFalse(g.opensWithOurKey(seal("""{"passwords":[]}""", foreignSecret)))
        assertFalse("a plaintext file is not ours", g.opensWithOurKey("""{"passwords":[]}""".toByteArray()))
        assertFalse("an empty file is not ours", g.opensWithOurKey(ByteArray(0)))
    }

    @Test
    fun `the policy table answers every state the writer can be in`() {
        assertEquals(SlotWriteAction.WRITE_FRESH, BackupSlotPolicy.decide(false, true, false, false))
        assertEquals(SlotWriteAction.OVERWRITE, BackupSlotPolicy.decide(true, true, true, false))
        assertEquals(SlotWriteAction.PRESERVE_THEN_OVERWRITE, BackupSlotPolicy.decide(true, true, false, false))
        assertEquals(SlotWriteAction.OVERWRITE_AFTER_PRESERVED, BackupSlotPolicy.decide(true, true, false, true))
        assertEquals(SlotWriteAction.HOLD_UNKNOWN, BackupSlotPolicy.decide(true, false, false, false))
        assertEquals("unreadable wins over every other consideration", SlotWriteAction.HOLD_UNKNOWN, BackupSlotPolicy.decide(true, false, true, true))
    }
}
