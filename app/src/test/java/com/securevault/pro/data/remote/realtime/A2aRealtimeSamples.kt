package com.securevault.pro.data.remote.realtime

import com.securevault.pro.data.file.VaultFile
import com.securevault.pro.data.local.entity.CryptoWalletEntity
import com.securevault.pro.data.local.entity.DigitalCardEntity
import com.securevault.pro.data.local.entity.SecureContactEntity
import com.securevault.pro.data.local.entity.SecureNoteEntity
import com.securevault.pro.data.local.entity.TotpEntity
import com.securevault.pro.data.local.entity.VaultEntryEntity
import java.io.File

/**
 * Shared JVM-only test material for the realtime engine (agent A2a).
 *
 * Every value here is synthetic and non-secret: the ids are `fix-<module>-0001`, the "secrets" are
 * literal `SAMPLE-...-NOT-REAL` markers, the card number is all zeroes and the clocks are a fixed
 * 2024 epoch floor. The same objects are the source of truth for
 * `sync_engine/fixtures/<module>/record.json`, and [RealtimeFixturesTest] fails if the two ever
 * drift apart, so a fixture edit cannot quietly change the contract.
 */
object A2aRealtimeSamples {

    /** Synthetic epoch-ms floor (2024-10-27T06:13:20Z). Not any real user's timestamp. */
    const val BASE: Long = 1730000000000L
    const val MINUTE: Long = 60_000L

    val NOTE = SecureNoteEntity(
        id = "fix-note-0001",
        title = "Sample note title",
        content = "Synthetic sample body. Not a secret.",
        isPinned = false,
        color = "DEFAULT",
        tagsJson = """["sample"]""",
        createdAt = BASE,
        updatedAt = BASE + MINUTE
    )

    val ENTRY = VaultEntryEntity(
        id = "fix-entry-0001",
        title = "Sample login",
        username = "sample-user",
        email = "sample@example.com",
        password = "sample-not-a-real-password",
        website = "example.com",
        url = "https://example.com",
        notes = "Synthetic notes",
        category = "WEBSITES",
        customFieldsJson = "[]",
        tagsJson = """["sample"]""",
        isFavorite = false,
        hasTotp = false,
        totpSecret = "",
        createdAt = BASE,
        updatedAt = BASE + MINUTE,
        lastAccessedAt = null,
        expiresAt = null,
        strengthScore = 0,
        deletedAt = null,
        isHiddenInTravel = false
    )

    val CARD = DigitalCardEntity(
        id = "fix-card-0001",
        type = "CREDIT",
        nickname = "Sample card",
        holderName = "Sample Holder",
        number = "0000000000000000",
        expiryMonth = 12,
        expiryYear = 2099,
        cvv = "000",
        pin = "0000",
        bankName = "Sample Bank",
        notes = "Synthetic notes",
        colorGradientStart = "#1E273E",
        colorGradientEnd = "#141B2D",
        createdAt = BASE
    )

    val TOTP = TotpEntity(
        id = "fix-totp-0001",
        issuer = "Sample Issuer",
        accountName = "sample-account",
        secret = "SAMPLE-TOTP-SECRET-NOT-REAL",
        algorithm = "SHA1",
        digits = 6,
        period = 30,
        createdAt = BASE
    )

    val CONTACT = SecureContactEntity(
        id = "fix-contact-0001",
        name = "Sample Contact",
        phone = "000-000-0000",
        email = "sample@example.com",
        address = "1 Sample Street",
        notes = "Synthetic notes",
        category = "Personal",
        createdAt = BASE,
        updatedAt = BASE + MINUTE
    )

    val WALLET = CryptoWalletEntity(
        id = "fix-wallet-0001",
        label = "Sample wallet",
        kind = "SOLANA",
        secret = "SAMPLE-WALLET-SECRET-NOT-REAL",
        addressesJson = """["sample-address"]""",
        balancesJson = "[]",
        totalUsd = 1234.5,
        lastSyncedAt = BASE + 1_000L,
        createdAt = BASE,
        updatedAt = BASE + MINUTE
    )

    val FILE = VaultFile(
        id = "fix-file-0001",
        name = "sample-document.pdf",
        size = 10_240L,
        mimeType = "application/pdf",
        category = "DOCUMENTS",
        createdAt = BASE,
        uploadedAt = BASE + 1_000L,
        noteId = null,
        driveId = "synthetic-drive-id-not-real"
    )

    /** The (module, entity) pair for every one of the seven segments. */
    fun all(): List<Pair<RealtimeModule, Any>> = listOf(
        RealtimeModule.NOTES to NOTE,
        RealtimeModule.ENTRIES to ENTRY,
        RealtimeModule.CARDS to CARD,
        RealtimeModule.TOTPS to TOTP,
        RealtimeModule.CONTACTS to CONTACT,
        RealtimeModule.CRYPTO_WALLETS to WALLET,
        RealtimeModule.FILES to FILE
    )

    /** The `u` each sample publishes, i.e. what `envelope.json` carries. */
    fun clockOf(module: RealtimeModule): Long = when (module) {
        RealtimeModule.NOTES, RealtimeModule.ENTRIES, RealtimeModule.CONTACTS,
        RealtimeModule.CRYPTO_WALLETS -> BASE + MINUTE
        RealtimeModule.CARDS, RealtimeModule.TOTPS -> BASE
        RealtimeModule.FILES -> BASE + 1_000L
    }

    /** The `r` each sample's `envelope.json` carries; distinct per module on purpose. */
    fun revisionOf(module: RealtimeModule): Long = when (module) {
        RealtimeModule.NOTES -> 11L
        RealtimeModule.ENTRIES -> 12L
        RealtimeModule.CARDS -> 13L
        RealtimeModule.TOTPS -> 14L
        RealtimeModule.CONTACTS -> 15L
        RealtimeModule.CRYPTO_WALLETS -> 16L
        RealtimeModule.FILES -> 17L
    }

    /** The placeholder ciphertext marker used by every fixture envelope; never a real seal. */
    const val FAKE_SEAL = "AAA=.placeholder-not-a-real-seal"

    /**
     * The fixtures directory, found by walking up from the test working directory so the test runs
     * from Gradle (`app/`), from the IDE, or from the repo root without a hard-coded machine path.
     */
    fun fixturesRoot(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            val candidate = File(cursor, "sync_engine/fixtures")
            if (candidate.isDirectory) return candidate
            cursor = cursor.parentFile
        }
        throw IllegalStateException("sync_engine/fixtures not found from ${File("").absolutePath}")
    }

    fun fixture(module: RealtimeModule, name: String): File =
        File(File(fixturesRoot(), module.segment), name)

    fun readFixture(module: RealtimeModule, name: String): String =
        fixture(module, name).readText(Charsets.UTF_8)
}
