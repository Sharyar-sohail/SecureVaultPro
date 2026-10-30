package com.securevault.pro.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore path must never turn one record into two. These guard the content
 * matching that catches a re-import of the same logical row under a foreign id,
 * while still allowing a real edit of a known id through as an update.
 */
class DuplicateIndexTest {

    private val existing = listOf(
        "id-1" to DuplicateIndex.key("Login", "Gmail", "sam@example.com", "", "gmail.com")
    )

    @Test
    fun `known id is an update, not a duplicate`() {
        val index = DuplicateIndex(existing)
        assertFalse(
            index.isDuplicate(
                "id-1",
                DuplicateIndex.key("Login", "Gmail", "sam@example.com", "", "gmail.com")
            )
        )
    }

    @Test
    fun `same content under a new id is dropped`() {
        val index = DuplicateIndex(existing)
        assertTrue(
            index.isDuplicate(
                "id-9",
                DuplicateIndex.key("Login", "Gmail", "sam@example.com", "", "gmail.com")
            )
        )
    }

    @Test
    fun `unseen content is kept and then recognised within the same payload`() {
        val index = DuplicateIndex(existing)
        val signature = DuplicateIndex.key("Login", "GitHub", "sam@example.com", "", "")
        assertFalse(index.isDuplicate("id-2", signature))
        assertTrue(index.isDuplicate("id-3", signature))
    }

    @Test
    fun `key normalises spacing and case`() {
        assertEquals(
            DuplicateIndex.key(" Login ", "GitHub"),
            DuplicateIndex.key("login", "github")
        )
    }

    @Test
    fun `empty index keeps the first row it sees`() {
        val index = DuplicateIndex(emptyList())
        assertFalse(index.isDuplicate("id-1", DuplicateIndex.key("Login", "Gmail", "", "", "")))
    }
}
