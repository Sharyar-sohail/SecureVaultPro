package com.securevault.pro.presentation.screens.stego

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted half of the read-once ledger, on a bare JVM.
 *
 * The store itself (`StegoLedgerStore`) is a thin SharedPreferences wrapper; what has to be right is
 * the record: every metadata field survives a round trip, a hostile file name cannot forge a second
 * record, and neither a long history nor a tampered file can push the ledger past its cap.
 */
class StegoReadOnceLedgerTest {

    private fun copy(key: String, label: String, createdAt: Long = 0L) = OwnedCopy(
        key = key,
        label = label,
        path = "content://media/external/images/media/$key",
        createdAt = createdAt
    )

    @Test
    fun roundTripKeepsEveryFieldAndTheReadFlag() {
        val ledger = SelfDestructLedger()
            .register(copy("c1", "IMG_20260101_120000.jpg", createdAt = 1_767_225_600_000L))
            .register(copy("c2", "IMG_20260101_120100.jpg", createdAt = 1_767_225_660_000L))
            .markRead("c2")

        val restored = StegoLedgerCodec.decode(StegoLedgerCodec.encode(ledger))

        assertEquals(ledger.copies, restored.copies)
        assertFalse(restored.isDue("c1"))
        assertTrue(restored.isDue("c2"))
        assertEquals(1_767_225_600_000L, restored.copies.first().createdAt)
    }

    @Test
    fun roundTripSurvivesTabsNewlinesAndPercentSigns() {
        val awkward = OwnedCopy(
            key = "c%1",
            label = "IMG\t2026\n100% %0A photo.jpg",
            path = "content://media/external/images/media/42?x=%2525&y=1\r\n",
            vaultFileId = "vault%id",
            createdAt = 99L
        )
        val restored = StegoLedgerCodec.decode(
            StegoLedgerCodec.encode(SelfDestructLedger().register(awkward))
        )

        assertEquals(listOf(awkward), restored.copies)
        assertEquals(1, restored.copies.size)
    }

    @Test
    fun registerKeepsOnlyTheNewestEntries() {
        var ledger = SelfDestructLedger()
        for (i in 0 until SelfDestructLedger.MAX_ENTRIES + 7) {
            ledger = ledger.register(copy("c$i", "IMG_$i.jpg", createdAt = i.toLong()))
        }

        assertEquals(SelfDestructLedger.MAX_ENTRIES, ledger.copies.size)
        assertEquals("c7", ledger.copies.first().key)
        assertEquals("c56", ledger.copies.last().key)
    }

    @Test
    fun registerReplacesTheSameKeyWithoutGrowing() {
        val ledger = SelfDestructLedger()
            .register(copy("c1", "IMG_first.jpg"))
            .register(copy("c1", "IMG_second.jpg"))

        assertEquals(1, ledger.copies.size)
        assertEquals("IMG_second.jpg", ledger.copies.single().label)
    }

    @Test
    fun decodeAppliesTheCapToAnOversizedFile() {
        val raw = buildString {
            append("stego-ledger/1")
            for (i in 0 until 60) {
                append('\n').append("k$i\tIMG_$i.jpg\t/pictures/$i\t\t$i\t0")
            }
        }

        val restored = StegoLedgerCodec.decode(raw)

        assertEquals(50, restored.copies.size)
        assertEquals("k10", restored.copies.first().key)
        assertEquals("k59", restored.copies.last().key)
    }

    @Test
    fun decodeRefusesGarbageAndForeignVersions() {
        assertTrue(StegoLedgerCodec.decode(null).isEmpty())
        assertTrue(StegoLedgerCodec.decode("").isEmpty())
        assertTrue(StegoLedgerCodec.decode("not a ledger").isEmpty())
        assertTrue(StegoLedgerCodec.decode("stego-ledger/2\nk\tl\tp\t\t1\t0").isEmpty())
        assertTrue(StegoLedgerCodec.decode("stego-ledger/1\nk\tl\tp").isEmpty())
    }

    @Test
    fun markReadNamedOnlyMarksTheFileNameItWasGiven() {
        val ledger = SelfDestructLedger()
            .register(copy("c1", "IMG_20260101_120000.jpg"))
            .register(copy("c2", "IMG_20260101_120100.jpg"))

        val opened = ledger.markReadNamed(listOf("IMG_20260101_120000.jpg", "some other photo.jpg"))

        assertTrue(opened.isDue("c1"))
        assertFalse(opened.isDue("c2"))
        assertSame("no change must be reported as no change", opened, opened.markReadNamed(listOf("IMG_20260101_120000.jpg")))
        assertSame(ledger, ledger.markReadNamed(listOf("not on the ledger.jpg")))
    }

    @Test
    fun readFlagsOfForgottenCopiesDoNotSurvive() {
        val ledger = SelfDestructLedger()
            .register(copy("c1", "IMG_1.jpg"))
            .register(copy("c2", "IMG_2.jpg"))
            .markRead("c1")
            .forget(listOf("c1"))

        val restored = StegoLedgerCodec.decode(StegoLedgerCodec.encode(ledger))

        assertEquals(listOf("c2"), restored.copies.map { it.key })
        assertFalse(restored.isDue("c1"))
    }
}
