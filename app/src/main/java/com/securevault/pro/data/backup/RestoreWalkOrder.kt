package com.securevault.pro.data.backup

/**
 * The order a restore visits the files in the Drive folder.
 *
 * The primary slot comes first, then this app's own snapshots newest-first, then anything else.
 * The browser companion's `secure_wallet_web.enc` is a real copy of the same vault but it is sealed
 * with the passphrase typed into the browser, which no device key can reproduce. Ranking it with
 * the app slots let it take a full decrypt budget ahead of a readable snapshot and turn a
 * recoverable vault into "none of the backups could be opened", so it goes last.
 */
object RestoreWalkOrder {

    const val WEB_COMPANION_FILE = "secure_wallet_web.enc"

    fun order(files: List<CloudBackupFile>): List<CloudBackupFile> =
        files.sortedWith(
            compareByDescending<CloudBackupFile> { it.isPrimary }
                .thenByDescending { !it.name.equals(WEB_COMPANION_FILE, ignoreCase = true) }
                .thenByDescending { it.modifiedTime }
        )
}
