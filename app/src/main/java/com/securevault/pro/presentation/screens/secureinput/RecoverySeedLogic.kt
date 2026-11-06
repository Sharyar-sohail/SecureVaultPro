package com.securevault.pro.presentation.screens.secureinput

import com.securevault.pro.crypto.Bip39
import com.securevault.pro.crypto.Hash
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.Random

/**
 * Every rule the Master Recovery Seed flow runs on, in one pure object: no Compose, no Android,
 * no Keystore, so the JVM tests in `app/src/test/.../secureinput/` assert the exact behaviour the
 * screen shows — including which word the owner has to fix.
 *
 * The phrase itself is standard BIP-39 (11-bit groups over the 2048-word English list plus the
 * SHA-256 checksum prefix). The vault binding is *not* a BIP-39 account key: a recovery seed has
 * to open an existing `{v:3}` envelope, and that envelope is PBKDF2(passphrase, salt) — see
 * [recoveryPassphrase] for how the two are joined without touching either format.
 */
object RecoverySeedLogic {

    /** The English list BIP-39 defines. Anything else is a damaged asset, not a shorter wordlist. */
    const val WORDLIST_SIZE = 2048

    /** The two lengths the generator offers; both are legal BIP-39 lengths. */
    val GENERATOR_WORD_COUNTS = listOf(12, 24)

    /** Any length a *typed* phrase may have and still be a BIP-39 phrase. */
    val ACCEPTED_WORD_COUNTS: Set<Int> = Bip39.VALID_WORD_COUNTS

    /** `normalize`/`words` already live in BIP-39; these re-export them so the screen has one source. */
    fun normalize(phrase: String): String = Bip39.normalize(phrase)

    fun wordsOf(phrase: String): List<String> = Bip39.words(phrase)

    /**
     * Entropy bits behind a phrase of [wordCount] words, or null when [wordCount] is not a BIP-39
     * length. 12 → 128, 15 → 160, 18 → 192, 21 → 224, 24 → 256: every 3 words carry 32 bits of
     * entropy plus 1 checksum bit, which is why the count must be a multiple of 3.
     */
    fun entropyBits(wordCount: Int): Int? =
        if (wordCount !in ACCEPTED_WORD_COUNTS) null else wordCount / 3 * 32

    /** Checksum bits appended to that entropy — `entropyBits / 32`, i.e. one bit per 3 words. */
    fun checksumBits(wordCount: Int): Int? = entropyBits(wordCount)?.let { it / 32 }

    /**
     * The words for a fixed [entropy] (16, 20, 24, 28 or 32 bytes), checksum included.
     *
     * Split out from [generateMnemonic] so a test can feed known entropy and assert the exact
     * phrase; the CSPRNG only enters at the device call site.
     */
    fun mnemonicFromEntropy(entropy: ByteArray, wordlist: List<String>): String {
        require(wordlist.size == WORDLIST_SIZE) { "BIP-39 wordlist must hold $WORDLIST_SIZE words" }
        require(entropy.size in 16..32 && entropy.size % 4 == 0) {
            "BIP-39 entropy must be 16..32 bytes in 4-byte steps"
        }
        val entropyBits = entropy.size * 8
        val checksumBits = entropyBits / 32
        val digest = Hash.sha256(entropy)
        val bits = BooleanArray(entropyBits + checksumBits)
        for (index in 0 until entropyBits) {
            bits[index] = (entropy[index / 8].toInt() shr (7 - index % 8)) and 1 == 1
        }
        for (index in 0 until checksumBits) {
            bits[entropyBits + index] = (digest[index / 8].toInt() shr (7 - index % 8)) and 1 == 1
        }
        val out = ArrayList<String>(bits.size / 11)
        var cursor = 0
        while (cursor + 11 <= bits.size) {
            var index = 0
            for (bit in 0 until 11) index = (index shl 1) or (if (bits[cursor + bit]) 1 else 0)
            out += wordlist[index]
            cursor += 11
        }
        return out.joinToString(" ")
    }

    /**
     * A fresh phrase of [wordCount] words from the platform CSPRNG.
     *
     * `SecureRandom().nextBytes` over exactly `entropyBits/8` bytes is what makes a 24-word phrase
     * 256 bits of device entropy — not twelve or twenty-four picks from the list, which would drop
     * the checksum and weaken the phrase.
     */
    fun generateMnemonic(wordCount: Int, wordlist: List<String>): String {
        val bits = entropyBits(wordCount)
            ?: throw IllegalArgumentException(countMessage(wordCount))
        val entropy = ByteArray(bits / 8)
        SecureRandom().nextBytes(entropy)
        try {
            return mnemonicFromEntropy(entropy, wordlist)
        } finally {
            entropy.fill(0)
        }
    }

    /**
     * Everything wrong with a typed phrase, named by position.
     *
     * Deliberately not a single boolean: the owner needs "word 7 `morket` is not in the BIP-39 list"
     * and not "invalid mnemonic", and the checksum is reported apart from the wordlist so a
     * hand-made phrase can still be used with a visible warning instead of a silent lie.
     */
    fun validatePhrase(phrase: String, wordlist: List<String>): PhraseValidation =
        validatePhrase(phrase) { word -> wordlist.indexOf(word).takeIf { it >= 0 } }

    /**
     * Same rule, but through whatever word → position lookup the caller already has.
     *
     * The screen passes a pre-built map: on a 24-word phrase being typed one letter at a time, a
     * list scan costs roughly 2048 comparisons per word per keystroke, which is what made the
     * keyboard feel like it was dropping characters.
     */
    fun validatePhrase(phrase: String, positionOf: (String) -> Int?): PhraseValidation {
        val parts = wordsOf(phrase)
        val countAccepted = parts.size in ACCEPTED_WORD_COUNTS
        val unknown = ArrayList<UnknownWord>()
        parts.forEachIndexed { index, word ->
            if (positionOf(word) == null) unknown += UnknownWord(index, word)
        }
        val checksumValid = countAccepted && unknown.isEmpty() &&
            Bip39.validate(Bip39.normalize(phrase), positionOf)
        return PhraseValidation(
            words = parts,
            countAccepted = countAccepted,
            unknownWords = unknown,
            checksumValid = checksumValid
        )
    }

    fun countMessage(wordCount: Int): String =
        "A recovery phrase is 12, 15, 18, 21 or 24 words — $wordCount is not one of them."

    /**
     * The secret that seals and opens a recovery archive for this phrase.
     *
     * `Bip39.toSeed` is the standard PBKDF2-HMAC-SHA512(phrase, "mnemonic", 2048) → 64 bytes,
     * base64'd so it survives being handed to a passphrase field (no leading/trailing whitespace,
     * so the `trim()` the existing envelope code applies cannot change it). The archive stays an
     * ordinary `{v:3}` envelope: the recovery path adds a *passphrase*, never a new key format.
     */
    fun recoveryPassphrase(mnemonic: String): String {
        val seed = Bip39.toSeed(normalize(mnemonic))
        try {
            return Base64.getEncoder().encodeToString(seed)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * The first position whose answer is missing or wrong, or null when every position matches.
     *
     * The verify step asks one position at a time; this is the same rule for the whole sheet, so a
     * test can prove the screen cannot wave through a phrase with a wrong word in the middle.
     */
    fun firstMismatch(expected: List<String>, answers: List<String?>): Int? {
        for (index in expected.indices) {
            val answer = answers.getOrNull(index)?.trim()?.lowercase()
            if (answer.isNullOrEmpty() || answer != expected[index]) return index
        }
        return null
    }

    /**
     * The four caps offered for position [position]: the real word plus three list neighbours,
     * deterministic in [seed] so the same session always shows the same choices and a test can pin
     * the order. Decoys are drawn from the list rather than invented, so nothing on screen looks
     * obviously wrong just by spelling.
     */
    fun verifyOptions(
        expected: List<String>,
        position: Int,
        wordlist: List<String>,
        seed: Long,
        optionCount: Int = 4
    ): List<String> {
        val correct = expected[position]
        val random = Random(seed + position)
        val decoys = ArrayList<String>(optionCount - 1)
        var guard = 0
        while (decoys.size < optionCount - 1 && guard < optionCount * 60) {
            guard++
            val candidate = wordlist[random.nextInt(wordlist.size)]
            if (candidate != correct && candidate !in decoys) decoys += candidate
        }
        val out = ArrayList<String>(optionCount)
        out += correct
        out += decoys
        Collections.shuffle(out, Random(seed + position + 1))
        return out
    }

    /** Positions asked during verify. All of them: a seed is only proved when every word is named. */
    fun verifyPositions(wordCount: Int): List<Int> = (0 until wordCount).toList()
}

/** One word the owner typed that the BIP-39 English list does not contain, with its position. */
data class UnknownWord(val index: Int, val word: String) {
    val label: String get() = "Word ${index + 1} · “$word” is not in the BIP-39 word list."
}

/**
 * The result of checking a typed phrase. [canContinue] is the gate the UI buttons off of;
 * [blockingMessage] and [warningMessage] are what the owner reads, in that order of urgency.
 */
data class PhraseValidation(
    val words: List<String>,
    val countAccepted: Boolean,
    val unknownWords: List<UnknownWord>,
    val checksumValid: Boolean
) {
    val wordCount: Int get() = words.size

    /** A phrase can be used at all only when the count is legal and every word is a real BIP-39 word. */
    val canContinue: Boolean get() = countAccepted && unknownWords.isEmpty()

    /** True when the phrase is a complete, standard BIP-39 mnemonic: words, count and checksum. */
    val isStandardMnemonic: Boolean get() = canContinue && checksumValid

    val blockingMessage: String?
        get() = when {
            words.isEmpty() -> "Type or paste the words of your recovery phrase."
            !countAccepted -> RecoverySeedLogic.countMessage(words.size)
            unknownWords.isNotEmpty() -> unknownWords.first().label
            else -> null
        }

    /**
     * The checksum warning is a warning, never a block: a custom phrase a person invented will not
     * satisfy a hash they cannot compute by hand, and refusing it would lock them out of the very
     * feature the phrase exists for. What it does mean is that a mistyped *generated* seed usually
     * lands here too — so the message says how to tell the two apart.
     */
    val warningMessage: String?
        get() = when {
            !canContinue -> null
            checksumValid -> null
            else -> "Every word is a BIP-39 word, but the checksum does not fit. Fine for a phrase " +
                "you wrote yourself; if this was a seed the app generated, one word is wrong — " +
                "check the order and the highlighted positions."
        }
}
