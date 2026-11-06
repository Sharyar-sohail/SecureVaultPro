package com.securevault.pro.security.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board the owner expects: a QWERTY page, a normal number/symbol page, and a phone-style pad for
 * a PIN. Each assertion is about the visible order, because "it looks like my keyboard" is the whole
 * requirement — the shuffle is the only thing allowed to move a key, and only on the owner's tap.
 */
class SecureKeyboardEngineTest {

    private fun texts(row: List<SecureKey>) = row.map { it.text }

    private fun letters(state: SecureKeyboardLayoutState = SecureKeyboardLayoutState(1L)) =
        SecureKeyboardEngine.rows(state.copy(layer = SecureKeyLayer.LETTERS))

    @Test
    fun `the letters page is qwerty`() {
        val rows = letters()
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), texts(rows[0]))
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), texts(rows[1]))
        assertEquals("shift", rows[2].first().id)
        assertEquals(listOf("z", "x", "c", "v", "b", "n", "m"), texts(rows[2].drop(1).dropLast(1)))
        assertEquals("backspace", rows[2].last().id)
    }

    @Test
    fun `the bottom row is layer, space, enter — nothing else`() {
        val bottom = letters().last()
        assertEquals(3, bottom.size)
        assertEquals(listOf(SecureKey.ToNumbers.id, SecureKey.Space.id, SecureKey.Enter.id), bottom.map { it.id })
        assertEquals(
            listOf(SecureKey.ToLetters.id, SecureKey.Space.id, SecureKey.Enter.id),
            SecureKeyboardEngine.rows(SecureKeyboardLayoutState(1L, layer = SecureKeyLayer.NUMBERS)).last().map { it.id }
        )
    }

    @Test
    fun `a shuffled board still holds every letter exactly once`() {
        val rows = letters(SecureKeyboardLayoutState(77L, reshuffleCount = 3, shuffled = true))
        val typed = rows[0].map { it.text } + rows[1].map { it.text } + rows[2].drop(1).dropLast(1).map { it.text }
        assertEquals(26, typed.size)
        assertEquals("abcdefghijklmnopqrstuvwxyz".map { it.toString() }.toSet(), typed.toSet())
        // A different session seeds a different order, so the shuffle is real.
        val other = letters(SecureKeyboardLayoutState(78L, shuffled = true))
        assertTrue(rows[0].map { it.text } != other[0].map { it.text })
    }

    @Test
    fun `shift arms the next letter and a press disarms it`() {
        val armed = SecureKeyboardEngine.press(SecureKey.Shift, lettersState())
        assertTrue(armed.second.shifted)
        val rows = SecureKeyboardEngine.rows(armed.second)
        assertEquals("Q", rows[0].first().text)
        val typed = SecureKeyboardEngine.press(rows[0].first(), armed.second)
        assertEquals(SecureKeyCommand.Insert("Q"), typed.first)
        assertFalse(typed.second.shifted)
    }

    @Test
    fun `the number page reads as digits then symbols then punctuation`() {
        val rows = SecureKeyboardEngine.rows(SecureKeyboardLayoutState(2L, layer = SecureKeyLayer.NUMBERS))
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), texts(rows[0]))
        assertEquals(SecureKeyboardEngine.SYMBOL_ROW, texts(rows[1]))
        assertTrue(texts(rows[2].drop(1).dropLast(1)).contains("."))
        val shifted = SecureKeyboardEngine.rows(
            SecureKeyboardLayoutState(2L, layer = SecureKeyLayer.NUMBERS, shifted = true)
        )
        assertEquals(SecureKeyboardEngine.ALT_SYMBOL_ROW, texts(shifted[1]))
    }

    @Test
    fun `the pin pad is three columns of digits and has no letters`() {
        val rows = SecureKeyboardEngine.rows(SecureKeyboardLayoutState(3L, layer = SecureKeyLayer.NUMERIC))
        assertEquals(4, rows.size)
        assertEquals(listOf("1", "2", "3"), texts(rows[0]))
        assertEquals(listOf("4", "5", "6"), texts(rows[1]))
        assertEquals(listOf("7", "8", "9"), texts(rows[2]))
        assertEquals(listOf(SecureKey.Backspace.id, "0", SecureKey.Enter.id), rows[3].map { it.id })
        assertFalse(rows.any { row -> row.any { it.text.length == 1 && it.text[0].isLetter() && it.text[0] in 'a'..'z' } })
        assertEquals(
            "1234567890",
            rows.flatten().filter { it.kind == SecureKeyKind.CHAR }.joinToString("") { it.text }
        )
    }

    @Test
    fun `a shuffled pin pad keeps the shape and moves the digits`() {
        val rows = SecureKeyboardEngine.rows(SecureKeyboardLayoutState(9L, reshuffleCount = 1, shuffled = true, layer = SecureKeyLayer.NUMERIC))
        assertEquals(listOf(3, 3, 3, 3), rows.map { it.size })
        assertEquals(SecureKeyboardEngine.PIN_DIGITS.toSet(), rows.take(3).flatten().map { it.text }.toSet())
    }

    private fun lettersState() = SecureKeyboardLayoutState(5L)
}
