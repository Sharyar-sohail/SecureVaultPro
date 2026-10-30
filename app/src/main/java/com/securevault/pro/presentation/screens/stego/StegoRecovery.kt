package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.SecretKind
import com.securevault.pro.crypto.stego.StegoLimits
import java.nio.charset.CodingErrorAction
import kotlin.math.min

/**
 * What a recovered payload's bytes actually are, decided from the bytes rather than from a label.
 *
 * The carrier stores a [SecretKind] inside the encrypted envelope, so the app is *told* what it is
 * holding — but a kind is a claim made by whoever sealed the image, and this app seals images for
 * people who may be handed someone else's photograph. So the text/binary decision is re-derived
 * here from the recovered bytes: strict UTF-8 (no replacement characters, no silent truncation) plus
 * a printable-ratio gate, so a key file that happens to decode as UTF-8 is still shown as bytes
 * instead of being handed to the owner as if it were a message they could read.
 *
 * The other half of this file is the reverse direction, choosing the kind when a secret is sealed.
 * [kindFor] recognises a BIP-39-shaped recovery phrase because the product brief is a wallet: a
 * seed phrase sealed as `MESSAGE` reads back as prose and the reveal screen cannot warn the owner
 * that what they are looking at is spendable money.
 *
 * Pure, no Android, unit-tested on the JVM.
 */
sealed class Recovered {

    /** Readable text: the reveal screen can show it, copy it, and treat a seed phrase as secret. */
    data class Text(val text: String, val kind: SecretKind) : Recovered()

    /** Not text. Shown as a hex head and offered as a file, because pasting bytes would corrupt them. */
    data class Binary(val bytes: ByteArray, val hexHead: String) : Recovered() {
        val size: Int get() = bytes.size
        override fun equals(other: Any?): Boolean =
            other is Binary && other.hexHead == hexHead && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = hexHead.hashCode() * 31 + bytes.contentHashCode()
    }

    /** True when the owner is looking at money rather than at a note. */
    val isSeedMaterial: Boolean
        get() = when (this) {
            is Text -> kind == SecretKind.SEED_PHRASE || kind == SecretKind.KEYFILE
            is Binary -> true
        }
}

object StegoRecovery {

    /**
     * Fraction of characters that must be ordinary writing for bytes to be called text.
     *
     * Deliberately strict: control characters arriving through a decoder are how a wrong-but-tag-valid
     * payload usually looks, and showing them as a "message" is worse than showing hex.
     */
    const val MIN_PRINTABLE_RATIO = 0.85f

    /** Hex shown before the payload is called "and N more bytes". */
    const val HEX_PREVIEW_BYTES = 64

    /** Word counts a BIP-39 recovery phrase can legally have, in ascending order. */
    val MNEMONIC_LENGTHS: List<Int> = listOf(12, 15, 18, 21, 24)

    /**
     * The kind to seal a typed secret as.
     *
     * Checked from the text itself, not from a toggle the owner has to understand: a 12/18/24-word
     * lowercase phrase is a seed phrase, anything else is a message. A file payload always arrives
     * through [com.securevault.pro.crypto.stego.SecretKind.KEYFILE] at the caller instead.
     */
    fun kindFor(text: String): SecretKind =
        if (isMnemonic(text)) SecretKind.SEED_PHRASE else SecretKind.MESSAGE

    /** True for exactly the shape of a recovery phrase: whole words, lowercase, a legal count. */
    fun isMnemonic(text: String): Boolean {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.firstOrNull()?.isEmpty() == true) return false
        if (words.size !in MNEMONIC_LENGTHS) return false
        return words.all { word -> word.length in 3..14 && word.all { it in 'a'..'z' } }
    }

    /**
     * Bytes back into something a screen can show.
     *
     * The declared [kind] is honoured only as a label: the gate below is what decides, so a
     * `MESSAGE` carrying a zip and a `KEYFILE` carrying prose both end up displayed for what they
     * are. Empty bytes are text — an empty secret is a legitimate, if odd, message, not a binary.
     */
    fun recover(kind: SecretKind, bytes: ByteArray): Recovered {
        if (bytes.isEmpty()) return Recovered.Text("", kind)
        val decoded = strictUtf8(bytes) ?: return Recovered.Binary(bytes, hexDump(bytes))
        if (printableRatio(decoded) < MIN_PRINTABLE_RATIO) {
            return Recovered.Binary(bytes, hexDump(bytes))
        }
        return Recovered.Text(decoded, kind)
    }

    /** Decodes as UTF-8 or returns null; no replacement characters ever reach the screen. */
    fun strictUtf8(bytes: ByteArray): String? = runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /** Letters, digits, punctuation and whitespace only — control characters do not count. */
    fun printableRatio(text: String): Float {
        if (text.isEmpty()) return 1f
        var printable = 0
        for (character in text) {
            if (character == '\n' || character == '\r' || character == '\t') printable += 1
            else if (Character.isLetterOrDigit(character) || character.isWhitespace() ||
                character.code in 0x20..0x7E
            ) printable += 1
        }
        return printable.toFloat() / text.length.toFloat()
    }

    /** Upper-case hex in 16-byte rows, capped at [limit] bytes so a card never runs off screen. */
    fun hexDump(bytes: ByteArray, limit: Int = HEX_PREVIEW_BYTES): String {
        if (bytes.isEmpty()) return ""
        val take = min(bytes.size, limit)
        val builder = StringBuilder()
        var index = 0
        while (index < take) {
            val rowEnd = min(index + 16, take)
            for (position in index until rowEnd) {
                builder.append(HEX[(bytes[position].toInt() shr 4) and 0xF])
                builder.append(HEX[bytes[position].toInt() and 0xF])
                if (position + 1 < rowEnd) builder.append(' ')
            }
            index = rowEnd
            if (index < take) builder.append('\n')
        }
        if (bytes.size > take) builder.append("\n+ ${bytes.size - take} more bytes")
        return builder.toString()
    }

    /** One-line summary of a payload the screen is about to show, for the confirmation cards. */
    fun preview(bytes: ByteArray): String =
        "${bytes.size} bytes · ${StegoLimits.MAX_SECRET_BYTES} byte ceiling per envelope"

    private val HEX = "0123456789ABCDEF".toCharArray()
}
