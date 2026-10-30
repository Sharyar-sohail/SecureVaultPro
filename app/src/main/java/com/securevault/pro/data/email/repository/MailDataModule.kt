package com.securevault.pro.data.email.repository

import com.securevault.pro.data.local.VaultDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The mail module's own DI, kept inside `data/email/repository/` on purpose.
 *
 * Hilt discovers an `@InstallIn(SingletonComponent::class)` module wherever it lives, so the email
 * feature wires itself without needing a shared file.
 *
 * `mail_accounts` is a real table now: `VaultDatabase` version 5 registers [MailAccountEntity] and
 * `MIGRATION_4_5` creates the table, so the store is bound to [RoomMailAccountStore] and an
 * configured account survives a process death. `InMemoryMailAccountStore` stays in the source tree
 * for the unit tests that were written against it, and nothing in the app binds it.
 *
 * The account table is device-local on purpose. It is not one of the seven realtime segments and
 * `MailAccountNeverSyncedTest` fails the build if that ever stops being true — a mail host, a login
 * and a sealed credential must not ride to the web clone.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MailDataModule {

    /** The vault's Keystore data key, behind the sealing interface the store is written against. */
    @Binds
    @Singleton
    abstract fun bindMailSecretSealer(impl: CryptoManagerMailSealer): MailSecretSealer

    @Binds
    @Singleton
    abstract fun bindMailboxService(impl: DefaultMailboxService): MailboxService

    companion object {
        @Provides
        @Singleton
        fun provideMailAccountStore(
            database: VaultDatabase,
            sealer: MailSecretSealer
        ): MailAccountStore = RoomMailAccountStore(database.mailAccountDao(), sealer)
    }
}
