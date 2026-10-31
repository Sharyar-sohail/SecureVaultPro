package com.securevault.pro.data.remote.google

import android.content.Context
import com.securevault.pro.data.backup.CloudBackupFile
import com.securevault.pro.security.backup.BackupEnvelopeFormat
import com.securevault.pro.security.backup.LegacyEnvelopeOpener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What may be done to the backup slot, decided before any byte is written.
 *
 * The slot (`vault_backup.enc`) is the one file every past build has overwritten in place, and an
 * in-place overwrite of a file this device cannot open destroys the only copy of a vault. So the
 * rule is: replace it only when it is provably ours.
 */
enum class SlotWriteAction {
    /** Nothing in the slot yet: create it. */
    WRITE_FRESH,

    /** The slot opens with this device's own vault key — the ordinary case, replace it. */
    OVERWRITE,

    /** The slot holds bytes this device cannot open. Copy them out first, then replace the slot. */
    PRESERVE_THEN_OVERWRITE,

    /** The slot holds bytes we have already copied out. Replace the slot; the copy stays forever. */
    OVERWRITE_AFTER_PRESERVED,

    /** The slot holds bytes that could not be read, so their contents are unknown. Write nothing. */
    HOLD_UNKNOWN
}

/**
 * The whole overwrite rule, with no Drive and no crypto in it, so it can be proven by test.
 *
 * [slotOpensWithOurKey] is false when the stored envelope exists but this device's vault key does
 * not authenticate it — the state a reinstall lands in, and the exact state that used to turn an
 * owner's real vault into demo records. [alreadyPreserved] stops a repeated background sync from
 * copying the same bytes out once per minute.
 */
object BackupSlotPolicy {

    fun decide(
        slotExists: Boolean,
        slotReadable: Boolean,
        slotOpensWithOurKey: Boolean,
        alreadyPreserved: Boolean
    ): SlotWriteAction = when {
        !slotExists -> SlotWriteAction.WRITE_FRESH
        !slotReadable -> SlotWriteAction.HOLD_UNKNOWN
        slotOpensWithOurKey -> SlotWriteAction.OVERWRITE
        !alreadyPreserved -> SlotWriteAction.PRESERVE_THEN_OVERWRITE
        else -> SlotWriteAction.OVERWRITE_AFTER_PRESERVED
    }
}

/**
 * Remembers which slot contents this device already proved it owns, and which unreadable originals
 * already have a preserved copy. Both are keyed by file id + size + modified time, so a slot that
 * changed content is judged again from scratch.
 */
interface SlotProofStore {
    fun ownsSlot(key: String, secretFingerprint: String): Boolean
    fun markSlotOwned(key: String, secretFingerprint: String)
    fun wasPreserved(key: String): Boolean
    fun markPreserved(key: String)
}

/**
 * Writes a backup into the Drive slot without ever destroying a vault it cannot read.
 *
 * Everything destructive about the old path lived in one line — upload over the existing file id.
 * This wraps that line: before a replacement it asks whether the slot's contents authenticate under
 * this device's vault key, and if they do not, it stores those bytes under a `vault_backup_legacy_…`
 * name that no code path deletes. The original is replaced only after that copy exists. A slot that
 * cannot even be downloaded is left alone.
 *
 * The answer is remembered per slot revision, so a normal steady-state sync costs one small read at
 * most and usually none.
 */
class BackupSlotGuard(
    private val opener: LegacyEnvelopeOpener,
    private val derivation: com.securevault.pro.security.KeyDerivation,
    private val ourSecret: String,
    private val proofs: SlotProofStore,
    private val download: suspend (CloudBackupFile) -> ByteArray,
    private val uploadAsNew: suspend (name: String, bytes: ByteArray) -> CloudBackupFile,
    private val uploadOver: suspend (name: String, bytes: ByteArray, existingId: String) -> CloudBackupFile
) {

    companion object {
        const val PRESERVED_PREFIX = "vault_backup_legacy_"
        const val PRESERVED_SUFFIX = ".enc"
        private const val PREFS = "svp_backup_slot_prefs"
        private const val KEY_OURS = "slot_owned_proof"
        private const val KEY_PRESERVED = "slot_preserved_proof"
    }

    sealed class Outcome {
        /** The slot was written; [preservedName] is set when an unreadable original was copied out. */
        data class Written(val file: CloudBackupFile, val preservedName: String?) : Outcome()

        /** Nothing was written and nothing was lost: the slot held bytes we could not open or read. */
        data class Held(val reason: String) : Outcome()
    }

    /**
     * Replace [slot] with [bytes] under [slotName], preserving whatever the slot held first when this
     * device cannot open it. A null [slot] means the slot is empty and this is a plain create.
     */
    suspend fun write(slotName: String, bytes: ByteArray, slot: CloudBackupFile?): Outcome {
        if (slot == null) return finish(uploadAsNew(slotName, bytes), null)
        val key = proofKey(slot)
        val fingerprint = secretFingerprint()

        if (proofs.ownsSlot(key, fingerprint)) {
            return finish(uploadOver(slotName, bytes, slot.id), null)
        }

        val original = runCatching { download(slot) }.getOrNull()
        val action = BackupSlotPolicy.decide(
            slotExists = true,
            slotReadable = original != null,
            slotOpensWithOurKey = original != null && opensWithOurKey(original),
            alreadyPreserved = proofs.wasPreserved(key)
        )
        return when (action) {
            SlotWriteAction.OVERWRITE, SlotWriteAction.OVERWRITE_AFTER_PRESERVED ->
                finish(uploadOver(slotName, bytes, slot.id), null)

            SlotWriteAction.PRESERVE_THEN_OVERWRITE -> {
                val preservedName = preservedSlotName()
                val stored = runCatching { uploadAsNew(preservedName, original!!) }
                if (stored.isFailure) {
                    return Outcome.Held(
                        "The backup in Drive is sealed with a key this device cannot open, and the " +
                            "safety copy of it could not be stored. Nothing was overwritten."
                    )
                }
                proofs.markPreserved(key)
                val replaced = runCatching { uploadOver(slotName, bytes, slot.id) }
                if (replaced.isFailure) {
                    return Outcome.Held(
                        "Your previous backup is safe in Drive as $preservedName. " +
                            "The new copy could not be written: " +
                            replaced.exceptionOrNull()?.message?.take(140).orEmpty()
                    )
                }
                finish(replaced.getOrThrow(), preservedName)
            }

            SlotWriteAction.HOLD_UNKNOWN -> Outcome.Held(
                "Google Drive would not return the backup already stored under $slotName, so nothing " +
                    "was uploaded and the vault in Drive is untouched."
            )

            SlotWriteAction.WRITE_FRESH -> finish(uploadAsNew(slotName, bytes), null)
        }
    }

    /**
     * Records the freshly written slot as ours before handing it back, so the next sync of the same
     * bytes is judged from that note instead of downloading the whole vault again to ask it.
     */
    private fun finish(file: CloudBackupFile, preservedName: String?): Outcome {
        proofs.markSlotOwned(proofKey(file), secretFingerprint())
        return Outcome.Written(file, preservedName)
    }

    /**
     * True when any envelope shape in [bytes] authenticates under this device's vault key.
     *
     * A file that is not an envelope at all counts as "not ours" — the safe reading, because those
     * bytes then get preserved instead of replaced.
     */
    fun opensWithOurKey(bytes: ByteArray): Boolean {
        val envelope = BackupEnvelopeFormat.parse(bytes) ?: return false
        if (!envelope.recognised) return false
        return LegacyEnvelopeOpener.exactProfiles(envelope.version)
            .any { opener.opensWith(envelope.salt, envelope.data, ourSecret, it) }
    }

    private fun proofKey(slot: CloudBackupFile): String = "${slot.id}:${slot.size}:${slot.modifiedTime}"

    private fun secretFingerprint(): String =
        java.util.Base64.getEncoder().encodeToString(
            derivation.sha256(ourSecret.toByteArray(Charsets.UTF_8))
        )

    private fun preservedSlotName(): String =
        PRESERVED_PREFIX +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            PRESERVED_SUFFIX

    /** The prefs-backed store the manager uses; kept here so the shape is not reimplemented twice. */
    class Prefs(private val context: Context) : SlotProofStore {
        private val prefs by lazy {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        override fun ownsSlot(key: String, secretFingerprint: String): Boolean =
            prefs.getString(KEY_OURS, null) == "$key|$secretFingerprint"

        override fun markSlotOwned(key: String, secretFingerprint: String) {
            runCatching { prefs.edit().putString(KEY_OURS, "$key|$secretFingerprint").commit() }
        }

        override fun wasPreserved(key: String): Boolean =
            prefs.getString(KEY_PRESERVED, null) == key

        override fun markPreserved(key: String) {
            runCatching { prefs.edit().putString(KEY_PRESERVED, key).commit() }
        }
    }
}
