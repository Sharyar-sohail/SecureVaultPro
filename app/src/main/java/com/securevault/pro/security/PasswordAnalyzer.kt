package com.securevault.pro.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

@Singleton
class PasswordAnalyzer @Inject constructor() {

    companion object {
        private val BREACH_DICTIONARY = setOf(
            "123456", "123456789", "12345678", "password", "qwerty", "12345", "1234567",
            "111111", "1234567890", "123123", "abc123", "1234", "password1", "iloveyou",
            "1q2w3e4r", "000000", "qwerty123", "zaq12wsx", "dragon", "sunshine",
            "princess", "letmein", "654321", "monkey", "27653", "1qaz2wsx", "123321",
            "qwertyuiop", "superman", "asdfghjkl", "welcome", "admin", "login",
            "passw0rd", "starwars", "football", "baseball", "whatever", "trustno1",
            "sharyar", "securevault", "vault123", "changeme", "master", "secret"
        )
        private val DICTIONARY_WORDS = listOf(
            "password", "welcome", "admin", "login", "secret", "master", "vault",
            "qwerty", "letmein", "dragon", "monkey", "football", "baseball", "sunshine",
            "princess", "shadow", "superman", "batman", "trustno", "hello", "freedom"
        )

        /**
         * Substitution table applied before any dictionary test, so the usual
         * "p@ssw0rd" / "L3tm31n" camouflage no longer hides a common word.
         */
        private val LEET_MAP = mapOf(
            '@' to 'a', '4' to 'a', '3' to 'e', '0' to 'o', '1' to 'i',
            '!' to 'i', '7' to 't', '+' to 't', '5' to 's', '$' to 's',
            '8' to 'b', '6' to 'g', '2' to 'z', '|' to 'l'
        )

        /** Layout walks beyond rows: columns and diagonals of a QWERTY board. */
        private val KEYBOARD_WALKS = listOf(
            "abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnopqrstuvwxyz".reversed(),
            "0123456789", "0987654321",
            "qwertyuiop", "asdfghjkl", "zxcvbnm",
            "qazwsx", "wsxedc", "edcrfv", "rfvtgb", "tgbyhn", "yhnujm", "ujmik",
            "1qaz2wsx", "zaq1xsw2", "qweasdzxc"
        )

        /**
         * Years and dates are the most common human tail on an otherwise good password.
         * Both sides must be non-digits, so a random 22-character secret that merely happens
         * to contain "1920" is never read as a date.
         */
        private val DATE_PATTERN = Regex(
            "(?<!\\d)(?:19[5-9]\\d|20[0-4]\\d)(?!\\d)" +
                "|(?<!\\d)\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?(?!\\d)"
        )

        /** "word" + "1234!" — a dictionary core with decoration on either side. */
        private val AFFIX_PATTERN = Regex("^[^a-z]*([a-z]{3,})[^a-z]*$")
    }

    data class Result(
        val score: Int,
        val entropyBits: Double,
        val label: String,
        val compromised: Boolean,
        val feedback: List<String>
    )

    fun analyze(password: String): Result {
        if (password.isEmpty()) return Result(0, 0.0, "Empty", false, listOf("Enter a password"))
        val normalized = password.lowercase()
        // Substitutions are camouflage, not entropy, so the text tests run on the folded form
        // while the date tests stay on the raw one, where the digits are still digits.
        val folded = leetFold(normalized)
        val compromised = BREACH_DICTIONARY.contains(normalized) || BREACH_DICTIONARY.contains(folded)
        val classes = listOf(
            password.any(Char::isLowerCase),
            password.any(Char::isUpperCase),
            password.any(Char::isDigit),
            password.any { !it.isLetterOrDigit() }
        ).count { it }
        var pool = 0
        if (password.any(Char::isLowerCase)) pool += 26
        if (password.any(Char::isUpperCase)) pool += 26
        if (password.any(Char::isDigit)) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        if (pool == 0) pool = 26

        var entropy = password.length * (ln(pool.toDouble()) / ln(2.0))
        if (password.length >= 12) entropy += 8
        if (password.length >= 16) entropy += 10

        // ---- structure penalties, zxcvbn-style: a recognisable pattern costs bits ----
        val sequence = hasSequence(password)
        val repeated = hasRepeat(password) || hasRepeatedBlock(folded)
        val dated = DATE_PATTERN.containsMatchIn(normalized)
        val core = if (compromised) null else dictionaryCore(normalized) ?: dictionaryCore(folded)
        when {
            compromised -> entropy = min(entropy, 12.0)
            core != null -> entropy *= if (normalized == core || folded == core) 0.45 else 0.55
            DICTIONARY_WORDS.any { folded.contains(it) } -> entropy *= 0.65
        }
        if (sequence) entropy *= 0.7
        if (repeated) entropy *= 0.8
        if (dated) entropy *= 0.85
        if (classes == 1) entropy *= 0.9
        entropy = max(0.0, entropy)

        var score = ((entropy / 90.0) * 100).toInt().coerceIn(0, 100)
        if (password.length < 8) score = min(score, 35)
        if (compromised) score = min(score, 8)

        val label = when {
            score >= 80 -> "Fortress"
            score >= 60 -> "Strong"
            score >= 40 -> "Fair"
            score >= 20 -> "Weak"
            else -> "Critical"
        }
        val feedback = buildList {
            if (compromised) add("Found in known breach dictionaries")
            if (core != null) add("“$core” with decoration is guessable")
            if (password.length < 12) add("Use at least 12 characters")
            if (dated) add("Avoid dates and years")
            if (sequence) add("Avoid keyboard sequences")
            if (repeated) add("Avoid repeated characters or blocks")
            // NIST SP 800-63B: composition rules buy little, so they are only nagged while the
            // password is still short enough for the character mix to be the limiting factor.
            if (password.length < 14) {
                if (!password.any(Char::isUpperCase)) add("Add uppercase letters")
                if (!password.any(Char::isDigit)) add("Add numbers")
                if (!password.any { !it.isLetterOrDigit() }) add("Add a symbol")
            }
        }
        return Result(score, entropy, label, compromised, feedback.take(4))
    }

    /**
     * An empty field is not a weak password, it is no password at all: identity, vehicle and
     * banking records that carry none must not be reported as weak logins.
     */
    fun isWeak(password: String): Boolean {
        if (password.isBlank()) return false
        val result = analyze(password)
        return result.entropyBits < 60 || password.length < 10 || result.compromised
    }

    private fun leetFold(input: String): String =
        buildString(input.length) { input.forEach { append(LEET_MAP[it] ?: it) } }

    /** The dictionary word hiding behind capitals, leet or a digit tail — or null. */
    private fun dictionaryCore(candidate: String): String? {
        AFFIX_PATTERN.matchEntire(candidate)?.groupValues?.getOrNull(1)?.let { core ->
            if (DICTIONARY_WORDS.any { it == core } || BREACH_DICTIONARY.contains(core)) return core
        }
        val letters = candidate.filter(Char::isLetter)
        if (letters != candidate && letters.length >= 4 &&
            (DICTIONARY_WORDS.any { it == letters } || BREACH_DICTIONARY.contains(letters))
        ) {
            return letters
        }
        return null
    }

    private fun hasSequence(input: String): Boolean {
        val lower = input.lowercase()
        // A long row needs a four-key run (so "power" is never read as a walk); the short column
        // and diagonal walks only ever appear as three consecutive keys.
        return KEYBOARD_WALKS.any { walk ->
            val size = if (walk.length >= 8) 4 else 3
            walk.windowed(size).any { window ->
                lower.contains(window) || lower.contains(window.reversed())
            }
        }
    }

    /** "abcabc", "xyxyxy" — a block repeated instead of length being spent on new characters. */
    private fun hasRepeatedBlock(input: String): Boolean {
        if (input.length < 4) return false
        for (unit in 2..input.length / 2) {
            if (input.length % unit == 0 && input.chunked(unit).distinct().size == 1) return true
            val block = input.substring(0, unit)
            if (input.contains(block + block)) return true
        }
        return false
    }

    private fun hasRepeat(input: String): Boolean {
        if (input.length < 3) return false
        var run = 1
        for (i in 1 until input.length) {
            if (input[i] == input[i - 1]) {
                run++
                if (run >= 3) return true
            } else run = 1
        }
        return false
    }

    fun entropyFor(password: String): Double = analyze(password).entropyBits

    fun scoreFor(password: String): Int = analyze(password).score

    fun generate(length: Int = 20, symbols: Boolean = true, digits: Boolean = true, upper: Boolean = true): String {
        val lowers = "abcdefghijkmnopqrstuvwxyz"
        val uppers = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val numbers = "23456789"
        val symbolSet = "!@#$%^&*()-_=+[]{}:,.?"
        val pools = buildList {
            add(lowers)
            if (upper) add(uppers)
            if (digits) add(numbers)
            if (symbols) add(symbolSet)
        }
        val random = java.security.SecureRandom()
        val chars = mutableListOf<Char>()
        val all = pools.joinToString("")
        // One character from every selected class first — but only when the requested length can
        // hold them all, otherwise the result would be longer than the caller asked for.
        if (length >= pools.size) {
            pools.forEach { pool -> chars.add(pool[random.nextInt(pool.length)]) }
        }
        while (chars.size < length) chars.add(all[random.nextInt(all.length)])
        chars.shuffle(random)
        return chars.joinToString("")
    }

    fun entropyLabel(bits: Double): String = when {
        bits >= 80 -> "Excellent entropy"
        bits >= 60 -> "Good entropy"
        bits >= 40 -> "Moderate entropy"
        else -> "Low entropy"
    }
}
