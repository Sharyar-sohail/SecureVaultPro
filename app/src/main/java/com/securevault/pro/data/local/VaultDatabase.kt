package com.securevault.pro.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.securevault.pro.data.local.dao.CryptoWalletDao
import com.securevault.pro.data.local.dao.DigitalCardDao
import com.securevault.pro.data.local.dao.MailAccountDao
import com.securevault.pro.data.local.dao.SecureContactDao
import com.securevault.pro.data.local.dao.SecureNoteDao
import com.securevault.pro.data.local.dao.TotpDao
import com.securevault.pro.data.local.dao.VaultDao
import com.securevault.pro.data.local.dao.WhatsAppChatDao
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import com.securevault.pro.data.local.entity.DigitalCardEntity
import com.securevault.pro.data.local.entity.MailAccountEntity
import com.securevault.pro.data.local.entity.SecureContactEntity
import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.TotpEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import com.securevault.pro.data.local.entity.WhatsAppChatEntity
import com.securevault.pro.data.local.entity.WhatsAppChatMessageEntity

@Database(
    entities = [
        VaultEntryEntity::class,
        DigitalCardEntity::class,
        TotpEntity::class,
        SecureNoteEntity::class,
        SecureContactEntity::class,
        CryptoWalletEntity::class,
        MailAccountEntity::class,
        WhatsAppChatEntity::class,
        WhatsAppChatMessageEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun digitalCardDao(): DigitalCardDao
    abstract fun totpDao(): TotpDao
    abstract fun secureNoteDao(): SecureNoteDao
    abstract fun secureContactDao(): SecureContactDao
    abstract fun cryptoWalletDao(): CryptoWalletDao
    abstract fun mailAccountDao(): MailAccountDao
    abstract fun whatsAppChatDao(): WhatsAppChatDao

    companion object {
        const val NAME = "vault_database"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crypto_wallets` (" +
                        "`id` TEXT NOT NULL, `label` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`secret` TEXT NOT NULL, `addressesJson` TEXT NOT NULL, " +
                        "`balancesJson` TEXT NOT NULL, `totalUsd` REAL NOT NULL, " +
                        "`lastSyncedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * Columns added after version 2 shipped never got their own version bump, so a
         * database written by an older release can report version 2 while missing them.
         * Room then fails its integrity check on the first query. This pass repairs the
         * shapes it can; every statement is optional, and whatever is left over is
         * caught by the destructive fallback in the database provider.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crypto_wallets` (" +
                        "`id` TEXT NOT NULL, `label` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`secret` TEXT NOT NULL, `addressesJson` TEXT NOT NULL, " +
                        "`balancesJson` TEXT NOT NULL, `totalUsd` REAL NOT NULL, " +
                        "`lastSyncedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                ensureColumn(db, "vault_entries", "expiresAt", "INTEGER")
                ensureColumn(db, "vault_entries", "deletedAt", "INTEGER")
                ensureColumn(db, "vault_entries", "lastAccessedAt", "INTEGER")
            }
        }

        /**
         * `isHiddenInTravel` joined the entity without a version bump, so a database that
         * was already stamped version 3 is missing the column and no migration will ever
         * run for it again. Home's very first query filters on that column, so the missing
         * table read is what took the process down on upgrade installs. Version 4 exists
         * only to let that repair run.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureColumn(db, "vault_entries", "isHiddenInTravel", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(db, "vault_entries", "expiresAt", "INTEGER")
                ensureColumn(db, "vault_entries", "deletedAt", "INTEGER")
                ensureColumn(db, "vault_entries", "lastAccessedAt", "INTEGER")
            }
        }

        /**
         * The built-in mail client's account table.
         *
         * Device-local and never a realtime segment: a mail host, a login and a sealed credential
         * do not ride to the web clone. The `mail_accounts` table is created empty, so an upgrade
         * install keeps every pre-existing row of every other table; without this migration Room's
         * destructive fallback would rebuild the whole database and the owner's vault would only
         * come back from a Drive restore.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mail_accounts` (" +
                        "`id` TEXT NOT NULL, `address` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`provider` TEXT NOT NULL, `authKind` TEXT NOT NULL, `username` TEXT NOT NULL, " +
                        "`imapHost` TEXT NOT NULL, `imapPort` INTEGER NOT NULL, " +
                        "`imapSecurity` TEXT NOT NULL, `smtpHost` TEXT NOT NULL, " +
                        "`smtpPort` INTEGER NOT NULL, `smtpSecurity` TEXT NOT NULL, " +
                        "`credentialSealed` TEXT NOT NULL, `autoSync` INTEGER NOT NULL, " +
                        "`lastSyncAt` INTEGER NOT NULL, `lastError` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`deletedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mail_accounts_address` " +
                        "ON `mail_accounts` (`address`)"
                )
            }
        }

        /**
         * The conversation tables behind "any export, any size, into the vault".
         *
         * `whatsapp_chats` is the index of a conversation and `whatsapp_messages` the bubbles, one
         * row each, keyed on (`chatId`, `ordinal`) so a resumed import rewrites instead of
         * duplicating. Both are created empty with `IF NOT EXISTS`: an upgrade install keeps every
         * row of every other table, which is the whole reason this is a migration and not a
         * destructive rebuild.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `whatsapp_chats` (" +
                        "`chatId` TEXT NOT NULL, `title` TEXT NOT NULL, `contactName` TEXT NOT NULL, " +
                        "`sourceName` TEXT NOT NULL, `container` TEXT NOT NULL, " +
                        "`participantsJson` TEXT NOT NULL, `messageCount` INTEGER NOT NULL, " +
                        "`mediaCount` INTEGER NOT NULL, `firstStamp` TEXT NOT NULL, " +
                        "`lastStamp` TEXT NOT NULL, `transcriptSha` TEXT NOT NULL, " +
                        "`noteId` TEXT NOT NULL, `importState` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`lastImportedAt` INTEGER NOT NULL, `mirroredToNote` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `whatsapp_messages` (" +
                        "`chatId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, " +
                        "`day` TEXT NOT NULL, `clock` TEXT NOT NULL, `sender` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, `notice` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, `attachmentsJson` TEXT NOT NULL, " +
                        "`mediaCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`, `ordinal`), " +
                        "FOREIGN KEY(`chatId`) REFERENCES `whatsapp_chats`(`chatId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_whatsapp_messages_chatId` " +
                        "ON `whatsapp_messages` (`chatId`)"
                )
            }
        }

        /** Adds [definition] as [column] when the table somehow lacks it. Never throws. */
        private fun ensureColumn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            val existing = mutableListOf<String>()
            runCatching {
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) existing.add(cursor.getString(1))
                }
            }
            if (existing.isEmpty() || existing.contains(column)) return
            runCatching { db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition") }
        }
    }
}
