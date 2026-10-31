package com.securevault.pro.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The restore walk must reach a readable snapshot before it spends its budget elsewhere.
 *
 * `secure_wallet_web.enc` is the browser companion's copy of the same vault, sealed with the
 * passphrase typed into the page; no device key can open it. When it sorted ahead of the app's own
 * snapshots it consumed a full decrypt budget and the restore reported that nothing could be
 * opened, even though a readable copy was sitting one entry later.
 */
class RestoreWalkOrderTest {

    private fun file(name: String, modified: Long, primary: Boolean = false) =
        CloudBackupFile(id = name, name = name, size = 1, modifiedTime = modified, isPrimary = primary)

    @Test
    fun `the primary slot is always first`() {
        val ordered = RestoreWalkOrder.order(
            listOf(
                file("secure_wallet_web.enc", 5_000),
                file("vault_backup.xml.enc", 4_000),
                file("vault_backup.enc", 1_000, primary = true)
            )
        )
        assertEquals("vault_backup.enc", ordered.first().name)
    }

    @Test
    fun `an older app snapshot outranks a newer browser companion copy`() {
        val ordered = RestoreWalkOrder.order(
            listOf(
                file("secure_wallet_web.enc", 9_000),
                file("vault_backup.xml.enc", 2_000)
            )
        )
        assertEquals(listOf("vault_backup.xml.enc", "secure_wallet_web.enc"), ordered.map { it.name })
    }

    @Test
    fun `app snapshots stay newest first among themselves`() {
        val ordered = RestoreWalkOrder.order(
            listOf(
                file("vault_backup_2026-01-01.enc", 1_000),
                file("vault_backup_2026-03-01.enc", 3_000),
                file("vault_backup_2026-02-01.enc", 2_000)
            )
        )
        assertEquals(
            listOf(
                "vault_backup_2026-03-01.enc",
                "vault_backup_2026-02-01.enc",
                "vault_backup_2026-01-01.enc"
            ),
            ordered.map { it.name }
        )
    }
}
