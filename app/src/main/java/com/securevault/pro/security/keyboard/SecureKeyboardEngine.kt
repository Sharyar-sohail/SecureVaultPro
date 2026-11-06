package com.securevault.pro.security.keyboard

import java.security.SecureRandom
import java.util.Collections
import java.util.Random

/**
 * The layout of one session, as a value.
 *
 * Being a data class is what lets Compose hold it in `mutableStateOf`: a press returns a *new*
 * state, so the board recomposes, and the same state always renders the same board. [sessionSeed]
 * is fixed when the session starts, which is what makes a shuffled board usable — the tiles move
 * once per session (or once per explicit reshuffle), never under the finger.
 */
data class SecureKeyboardLayoutState(
    val sessionSeed: Long,
    val reshuffleCount: Int = 0,
    val layer: SecureKeyLayer = SecureKeyLayer.LETTERS,
    val shifted: Boolean = false,
    val shuffled: Boolean = false,
) {
    /** The seed the tiles are ordered by: the session seed plus any reshuffles the user asked for. */
    val effectiveSeed: Long get() = sessionSeed + reshuffleCount * PRIME_STEP

    companion object {
        /** Not a multiple of any row length, so consecutive reshuffles cannot land on the same board. */
        private const val PRIME_STEP = 1_000_003L

        /**
         * A seed for a brand new session, from the CSPRNG. Callers keep it for the session only;
         * it is layout entropy, not a secret, and is never persisted or logged.
         */
        fun newSessionSeed(): Long = SecureRandom().nextLong()
    }
}

/**
 * The board, and what a press means. Pure Kotlin: no Compose, no Android, so the whole anti-keylogger
 * layout rule is testable on the JVM (see `SecureKeyboardEngineTest`).
 */
object SecureKeyboardEngine {

    /** The canonical QWERTY rows, in order — this is what the letters page draws. */
    val LETTER_ROWS: List<String> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** The letters pool, in QWERTY order; shuffling permutes this and re-slices the rows. */
    private val LETTER_POOL: List<String> = LETTER_ROWS.flatMap { it.map { c -> c.toString() } }

    val DIGIT_ROW: String = "1234567890"

    /** The first symbol row on the number page. */
    val SYMBOL_ROW: List<String> = listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"")

    /** What those same keys become while shift is armed on the number page. */
    val ALT_SYMBOL_ROW: List<String> = listOf("_", "+", "=", "<", ">", "[", "]", "{", "}", "|")

    /** The short row between shift and delete on the number page. */
    val MISC_ROW: List<String> = listOf(".", ",", "?", "!", "'", "#", "%")

    /** Its shifted counterpart, so the punctuation a password needs is two taps from any key. */
    val ALT_MISC_ROW: List<String> = listOf("*", "`", "~", "\\", "^", "\"", "'")

    /** A phone-style pad: three columns of digits, then delete, zero and done. */
    val PIN_DIGITS: List<String> = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")

    /**
     * Deterministic seeded shuffle: the same (pool, seed) always gives the same order, on every
     * device and every run. `java.util.Random` is used rather than `kotlin.random.Random` because
     * its sequence is specified by the platform, which is what makes the board testable.
     */
    fun <T> shuffle(pool: List<T>, seed: Long): List<T> {
        val copy = ArrayList(pool)
        Collections.shuffle(copy, Random(seed))
        return copy
    }

    /**
     * The key grid for [state], top row first.
     *
     * Shuffling permutes the whole letter pool and re-slices it, so every letter moves but the row
     * widths — and therefore the reach — stay the same. Modifier keys keep their fixed corners.
     */
    fun rows(state: SecureKeyboardLayoutState): List<List<SecureKey>> = when (state.layer) {
        SecureKeyLayer.LETTERS -> letterRows(state)
        SecureKeyLayer.NUMBERS -> numberRows(state)
        SecureKeyLayer.NUMERIC -> pinRows(state)
    }

    private fun letterRows(state: SecureKeyboardLayoutState): List<List<SecureKey>> {
        val ordered = if (state.shuffled) shuffle(LETTER_POOL, state.effectiveSeed) else LETTER_POOL
        val rows = ordered.chunkedRows(10, 9, 7)
        val caseOf = { symbol: String -> if (state.shifted) symbol.uppercase() else symbol }
        return listOf(
            rows[0].map { SecureKey.char(caseOf(it)) },
            rows[1].map { SecureKey.char(caseOf(it)) },
            listOf(SecureKey.Shift) + rows[2].map { SecureKey.char(caseOf(it)) } +
                listOf(SecureKey.Backspace),
            bottomRow(SecureKey.ToNumbers),
        )
    }

    private fun numberRows(state: SecureKeyboardLayoutState): List<List<SecureKey>> {
        val digits = DIGIT_ROW.map { it.toString() }
        val orderedDigits = if (state.shuffled) shuffle(digits, state.effectiveSeed) else digits
        val symbols = if (state.shifted) ALT_SYMBOL_ROW else SYMBOL_ROW
        val misc = if (state.shifted) ALT_MISC_ROW else MISC_ROW
        val orderedSymbols = if (state.shuffled) shuffle(symbols, state.effectiveSeed + 1) else symbols
        val orderedMisc = if (state.shuffled) shuffle(misc, state.effectiveSeed + 2) else misc
        return listOf(
            orderedDigits.map { SecureKey.char(it) },
            orderedSymbols.map { SecureKey.char(it) },
            listOf(SecureKey.Shift) + orderedMisc.map { SecureKey.char(it) } +
                listOf(SecureKey.Backspace),
            bottomRow(SecureKey.ToLetters),
        )
    }

    /**
     * The pad a PIN or an amount is typed on: the same three columns a phone dialer uses, with no
     * letters anywhere. Shuffling still applies — it moves the digits between the nine cells while
     * the shape of the pad stays, which is what keeps a shoulder-surfner from reading positions.
     */
    private fun pinRows(state: SecureKeyboardLayoutState): List<List<SecureKey>> {
        val digits = if (state.shuffled) shuffle(PIN_DIGITS, state.effectiveSeed) else PIN_DIGITS
        return digits.chunked(3).map { row -> row.map { SecureKey.char(it) } } +
            listOf(listOf(SecureKey.Backspace, SecureKey.char("0"), SecureKey.Enter))
    }

    private fun bottomRow(layerKey: SecureKey): List<SecureKey> =
        listOf(layerKey, SecureKey.Space, SecureKey.Enter)

    /**
     * Resolves [key] against [state].
     *
     * Shift is one-shot: it arms the next character and then disarms, which is the behaviour that
     * keeps a long password from needing the shift key pressed twice as often as it is used.
     * Letters always type a space; the number layer's space key does too, so a passphrase with
     * spaces is enterable on either layer.
     */
    fun press(key: SecureKey, state: SecureKeyboardLayoutState): Pair<SecureKeyCommand, SecureKeyboardLayoutState> =
        when (key.kind) {
            SecureKeyKind.CHAR ->
                SecureKeyCommand.Insert(key.text) to state.copy(shifted = false)

            SecureKeyKind.SPACE ->
                SecureKeyCommand.Insert(" ") to state.copy(shifted = false)

            SecureKeyKind.ENTER ->
                SecureKeyCommand.Enter to state

            SecureKeyKind.BACKSPACE ->
                SecureKeyCommand.Backspace to state

            SecureKeyKind.SHIFT ->
                SecureKeyCommand.Ignored to state.copy(shifted = !state.shifted)

            SecureKeyKind.LAYER -> {
                val next = if (key.id == SecureKey.ToNumbers.id) SecureKeyLayer.NUMBERS else SecureKeyLayer.LETTERS
                SecureKeyCommand.Ignored to state.copy(layer = next, shifted = false)
            }

            SecureKeyKind.SHUFFLE ->
                SecureKeyCommand.Ignored to state.copy(
                    shuffled = !state.shuffled,
                    // A reshuffle is asked for once, deliberately, so the new order is drawn from
                    // a fresh seed; typing a character never re-seats the board.
                    reshuffleCount = if (state.shuffled) state.reshuffleCount + 1 else state.reshuffleCount,
                )
        }

    /** True when the key is one of the fixed modifiers, i.e. drawn as a glyph rather than a letter. */
    fun isModifier(key: SecureKey): Boolean = key.kind != SecureKeyKind.CHAR

    private fun List<String>.chunkedRows(vararg widths: Int): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var index = 0
        for (width in widths) {
            out += slice(index until (index + width).coerceAtMost(size))
            index += width
        }
        return out
    }
}
