package com.securevault.pro.presentation.screens.secureinput

import com.securevault.pro.crypto.Bip39
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The word-by-word check that runs on every keystroke of a typed recovery phrase.
 *
 * Typing is the hot path: a 24-word phrase re-checked once per character has to cost lookups, not
 * list scans, and the fast path must still answer exactly what the plain list does. These two rules
 * are asserted together so nobody trades correctness for speed.
 */
class RecoverySeedTypingTest {

    @Test
    fun theIndexLookupAnswersExactlyWhatTheListAnswers() {
        val english = wordList()
        val positions = english.withIndex().associate { (index, word) -> word to index }

        val cases = listOf(
            // A real 12-word phrase with a valid checksum.
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            // Right shape, wrong checksum, and a word that is not in the list at all.
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon",
            "abandon abandon abandon abandon abandon abandon morket abandon abandon abandon abandon about",
            // Too short and too long.
            "abandon abandon abandon",
            english.subList(0, 24).joinToString(" ")
        )

        for (phrase in cases) {
            assertEquals(
                "the index answered differently for “$phrase”",
                RecoverySeedLogic.validatePhrase(phrase, english),
                RecoverySeedLogic.validatePhrase(phrase) { word -> positions[word] }
            )
        }
    }

    @Test
    fun aTypedPhraseNamesThePositionThatNeedsFixingAndNothingElse() {
        val positions = wordList().withIndex().associate { (index, word) -> word to index }
        val broken = "abandon abandon abandon abandon abandon abandon morket abandon " +
            "abandon abandon abandon about"

        val validation = RecoverySeedLogic.validatePhrase(broken) { word -> positions[word] }

        assertTrue(validation.countAccepted)
        assertFalse(validation.canContinue)
        assertFalse(validation.checksumValid)
        assertEquals(listOf("morket"), validation.unknownWords.map { it.word })
        assertEquals(6, validation.unknownWords.first().index)
        assertTrue(validation.blockingMessage!!.contains("Word 7"))
    }

    @Test
    fun aCompleteStandardPhrasePassesThroughTheIndex() {
        val english = wordList()
        val positions = english.withIndex().associate { (index, word) -> word to index }
        val phrase = RecoverySeedLogic.mnemonicFromEntropy(ByteArray(16) { it.toByte() }, english)

        val validation = RecoverySeedLogic.validatePhrase(phrase) { word -> positions[word] }

        assertEquals(12, validation.wordCount)
        assertTrue(validation.canContinue)
        assertTrue(validation.checksumValid)
        assertTrue(validation.isStandardMnemonic)
        assertTrue(Bip39.validate(phrase, english))
    }

    @Test
    fun everyPrefixOfATypedPhraseAnswersTheSameAndCountsOnlyItsOwnWords() {
        val english = wordList()
        val positions = english.withIndex().associate { (index, word) -> word to index }
        val phrase = "abandon ability able about above absorb abstract absurd abuse access accident account"

        for (length in 1..phrase.length) {
            val typed = phrase.take(length)
            assertEquals(
                "the index answered differently for “$typed”",
                RecoverySeedLogic.validatePhrase(typed, english),
                RecoverySeedLogic.validatePhrase(typed) { word -> positions[word] }
            )
        }

        // The cost of one re-check, measured on the last keystroke: every word is asked of the index,
        // twice at most (the word list and then the checksum). A list scan would be 2048 per word.
        var lookups = 0
        RecoverySeedLogic.validatePhrase(phrase) { word -> lookups++; positions[word] }
        val words = RecoverySeedLogic.wordsOf(phrase).size
        assertTrue("one keystroke cost $lookups lookups for $words words", lookups in 1..words * 2)
    }

    private fun wordList(): List<String> {
        val candidates = listOf(
            File("src/main/assets/bip39_english.txt"),
            File("app/src/main/assets/bip39_english.txt"),
            File("../app/src/main/assets/bip39_english.txt")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("bip39_english.txt not reachable from ${File(".").absolutePath}")
        val words = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        require(words.size == RecoverySeedLogic.WORDLIST_SIZE) { "asset holds ${words.size} words" }
        return words
    }
}
