package com.securevault.pro.di

import android.content.Context
import androidx.room.Room
import com.securevault.pro.data.imports.ChatFieldCipher
import com.securevault.pro.data.imports.VaultWhatsAppMediaSealer
import com.securevault.pro.data.imports.WhatsAppChatStore
import com.securevault.pro.data.imports.WhatsAppStreamingImporter
import com.securevault.pro.data.local.VaultDatabase
import com.securevault.pro.data.local.dao.CryptoWalletDao
import com.securevault.pro.data.local.dao.DigitalCardDao
import com.securevault.pro.data.local.dao.MailAccountDao
import com.securevault.pro.data.local.dao.SecureContactDao
import com.securevault.pro.data.local.dao.SecureNoteDao
import com.securevault.pro.data.local.dao.TotpDao
import com.securevault.pro.data.local.dao.VaultDao
import com.securevault.pro.data.local.dao.WhatsAppChatDao
import com.securevault.pro.security.CryptoManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VaultDatabase {
        // A database left by an older release can outlive the schema it was written
        // with. Room used to abort the first query in that case, which took the whole
        // process down the moment setup handed over to Home. Repair through the
        // migrations where possible and recreate the tables otherwise: the encrypted
        // Drive snapshot and the embedded vault refill what is left behind.
        return Room.databaseBuilder(context, VaultDatabase::class.java, VaultDatabase.NAME)
            .addMigrations(
                VaultDatabase.MIGRATION_1_2,
                VaultDatabase.MIGRATION_2_3,
                VaultDatabase.MIGRATION_3_4,
                VaultDatabase.MIGRATION_4_5,
                VaultDatabase.MIGRATION_5_6
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideVaultDao(database: VaultDatabase): VaultDao = database.vaultDao()

    @Provides
    fun provideDigitalCardDao(database: VaultDatabase): DigitalCardDao = database.digitalCardDao()

    @Provides
    fun provideTotpDao(database: VaultDatabase): TotpDao = database.totpDao()

    @Provides
    fun provideSecureNoteDao(database: VaultDatabase): SecureNoteDao = database.secureNoteDao()

    @Provides
    fun provideSecureContactDao(database: VaultDatabase): SecureContactDao = database.secureContactDao()

    @Provides
    fun provideCryptoWalletDao(database: VaultDatabase): CryptoWalletDao = database.cryptoWalletDao()

    @Provides
    fun provideMailAccountDao(database: VaultDatabase): MailAccountDao = database.mailAccountDao()

    @Provides
    fun provideWhatsAppChatDao(database: VaultDatabase): WhatsAppChatDao = database.whatsAppChatDao()

    /**
     * The field cipher behind the conversation tables.
     *
     * Sealed with the same AES-256-GCM data key the notes module uses, so a chat bubble and a note
     * body are protected by one root and one backup restore opens both. Opening never throws: a
     * value this device cannot decrypt comes back as it was stored, which shows the owner a sealed
     * string instead of taking the screen down.
     */
    @Provides
    @Singleton
    fun provideChatFieldCipher(crypto: CryptoManager): ChatFieldCipher = object : ChatFieldCipher {
        override fun seal(value: String): String =
            if (value.isBlank()) value else crypto.encrypt(value)

        override fun open(sealedValue: String): String =
            if (sealedValue.isBlank()) sealedValue else crypto.decrypt(sealedValue) ?: sealedValue
    }

    /**
     * The any-size import engine. The store is the Room sink, the sealer is the file vault, and the
     * cache directory is where an archive's entries are staged one at a time — never a file-sized
     * byte array in heap.
     */
    @Provides
    @Singleton
    fun provideWhatsAppStreamingImporter(
        store: WhatsAppChatStore,
        media: VaultWhatsAppMediaSealer,
        @ApplicationContext context: Context
    ): WhatsAppStreamingImporter = WhatsAppStreamingImporter(
        sink = store,
        media = media,
        cacheDir = File(context.cacheDir, "wa_stream")
    )
}
