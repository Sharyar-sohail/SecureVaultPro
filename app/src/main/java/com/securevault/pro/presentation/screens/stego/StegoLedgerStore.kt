package com.securevault.pro.presentation.screens.stego

import android.content.Context

/**
 * Where the read-once ledger lives between launches: a private SharedPreferences file owned by the
 * stego package, named `stego_ledger`.
 *
 * Only the metadata [OwnedCopy] already holds is written — key, label, saved location string,
 * created-at and read flag — through [StegoLedgerCodec]. No pixels, no payload text, no passphrase
 * or key ever reaches this file, so a leaked backup of it names a few files and nothing more. The
 * ledger itself enforces [SelfDestructLedger.MAX_ENTRIES], and a save never deletes a saved image:
 * removal stays an explicit button in the reveal screen.
 *
 * A dedicated file rather than a Room table keeps this out of the schema and its migrations.
 */
internal class StegoLedgerStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads whatever the last session left, or an empty ledger when nothing (or nonsense) is there. */
    fun load(): SelfDestructLedger = runCatching {
        StegoLedgerCodec.decode(prefs.getString(KEY_LEDGER, null))
    }.getOrDefault(SelfDestructLedger())

    /** Writes the whole record back; `apply` keeps the caller off the disk. */
    fun save(ledger: SelfDestructLedger) {
        runCatching {
            prefs.edit().putString(KEY_LEDGER, StegoLedgerCodec.encode(ledger)).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "stego_ledger"
        const val KEY_LEDGER = "ledger_v1"
    }
}
