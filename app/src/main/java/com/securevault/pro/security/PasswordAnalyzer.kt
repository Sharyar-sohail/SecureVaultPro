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
        val compromised = BREACH_DICTIONARY.contains(normalized)
        var pool = 0
        if (password.any(Char::isLowerCase)) pool += 26
        if (password.any(Char::isUpperCase)) pool += 26
        if (password.any(Char::isDigit)) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        if (pool == 0) pool = 26

        var entropy = password.length * (ln(pool.toDouble()) / ln(2.0))
        if (password.length >= 12) entropy += 8
        if (password.length >= 16) entropy += 10
        if (DICTIONARY_WORDS.any { normalized.contains(it) }) entropy *= 0.65
        if (hasSequence(password)) entropy *= 0.75
        if (hasRepeat(password)) entropy *= 0.8
        if (compromised) entropy = min(entropy, 12.0)
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
            if (password.length < 10) add("Use at least 12 characters")
            if (!password.any(Char::isUpperCase)) add("Add uppercase letters")
            if (!password.any(Char::isDigit)) add("Add numbers")
            if (!password.any { !it.isLetterOrDigit() }) add("Add a symbol")
            if (hasSequence(password)) add("Avoid keyboard sequences")
            if (hasRepeat(password)) add("Avoid repeated characters")
            if (compromised) add("Found in known breach dictionaries")
        }
        return Result(score, entropy, label, compromised, feedback.take(4))
    }

    fun isWeak(password: String): Boolean {
        val result = analyze(password)
        return result.entropyBits < 60 || password.length < 10 || result.compromised
    }

    private fun hasSequence(input: String): Boolean {
        val lower = input.lowercase()
        val sequences = listOf(
            "abcdefghijklmnopqrstuvwxyz",
            "0123456789",
            "qwertyuiop", "asdfghjkl", "zxcvbnm"
        )
        return sequences.any { seq ->
            seq.windowed(4).any { window -> lower.contains(window) || lower.contains(window.reversed()) }
        }
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
        pools.forEach { pool -> chars.add(pool[random.nextInt(pool.length)]) }
        val all = pools.joinToString("")
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
