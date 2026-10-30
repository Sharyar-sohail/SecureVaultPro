package com.securevault.pro.presentation.screens.stego

import com.securevault.pro.crypto.stego.SecretKind

/**
 * Which of the two passphrases the owner typed opened what, and what the app is therefore allowed
 * to show.
 *
 * The carrier itself never says which envelope is the decoy — the key schedule in
 * `StegoKeySchedule` puts each envelope in a slot derived from the key that opens it, both blocks
 * pad to the same length, and no role bit exists anywhere on the image. That is the whole
 * deniability property, and it has a consequence the UI has to be honest about: the *role* lives in
 * the order the owner types, not in the photograph. Whoever holds one passphrase sees exactly one
 * plausible message and cannot tell whether a second one exists.
 *
 * So the decision table below is about typing order only. It is pure, because it is the part a
 * reviewer can actually check, and because the screens must not each invent their own answer.
 */
sealed class TwoPasswordChoice {

    /** Only the first passphrase opened anything: show its message and say nothing about the rest. */
    data class FirstOnly(val secret: ByteArray, val kind: SecretKind) : TwoPasswordChoice()

    /** Only the second passphrase opened: the first was wrong, or the owner mis-typed it. */
    data class SecondOnly(val secret: ByteArray, val kind: SecretKind) : TwoPasswordChoice()

    /**
     * Both opened. The first is presented as the vault the owner named; the second stays behind a
     * deliberate tap, because silently showing both would break the cover story in front of anyone
     * looking over a shoulder.
     */
    data class Both(val first: ByteArray, val firstKind: SecretKind, val second: ByteArray, val secondKind: SecretKind) :
        TwoPasswordChoice()

    /** Neither opened. The carrier may still hold a secret; the app cannot and must not guess. */
    object Neither : TwoPasswordChoice()

    val openedCount: Int
        get() = when (this) {
            is FirstOnly -> 1
            is SecondOnly -> 1
            is Both -> 2
            Neither -> 0
        }
}

/**
 * Maps one or two recovered messages onto a [TwoPasswordChoice].
 *
 * Null means "this passphrase opened nothing" — a GCM tag that does not verify, which is the only
 * answer the envelope format can give to a wrong key, and the reason a wrong passphrase produces no
 * partial plaintext anywhere in this subsystem.
 */
object StegoPayloadSelection {

    /** One passphrase's outcome: what it opened, or null when it opened nothing. */
    data class Opening(val secret: ByteArray, val kind: SecretKind, val slot: Int) {
        override fun equals(other: Any?): Boolean =
            other is Opening && other.slot == slot && other.secret.contentEquals(secret) && other.kind == kind

        override fun hashCode(): Int = slot * 31 + secret.contentHashCode()
    }

    fun choose(primary: Opening?, decoy: Opening?): TwoPasswordChoice = when {
        primary != null && decoy != null ->
            TwoPasswordChoice.Both(primary.secret, primary.kind, decoy.secret, decoy.kind)
        primary != null -> TwoPasswordChoice.FirstOnly(primary.secret, primary.kind)
        decoy != null -> TwoPasswordChoice.SecondOnly(decoy.secret, decoy.kind)
        else -> TwoPasswordChoice.Neither
    }

    /**
     * The message shown by default. With both open, the primary is the answer and the decoy is the
     * one that needs an explicit tap; with one open, that one is the answer whatever it is.
     */
    fun headline(choice: TwoPasswordChoice): ByteArray? = when (choice) {
        is TwoPasswordChoice.FirstOnly -> choice.secret
        is TwoPasswordChoice.SecondOnly -> choice.secret
        is TwoPasswordChoice.Both -> choice.first
        TwoPasswordChoice.Neither -> null
    }

    /** True when the cover story is the only thing on screen and the real vault is still hidden. */
    fun hidesTheSecond(choice: TwoPasswordChoice): Boolean = choice is TwoPasswordChoice.Both

    /**
     * Wording for the reveal screen. It refuses to call either envelope "the decoy", because on the
     * image there is no such thing — the app only knows which passphrase the owner entered first.
     */
    fun explain(choice: TwoPasswordChoice): String = when (choice) {
        is TwoPasswordChoice.FirstOnly -> "One passphrase opened one message."
        is TwoPasswordChoice.SecondOnly ->
            "Only the second passphrase opened a message. The first is wrong, or this carrier holds " +
                "nothing else."
        is TwoPasswordChoice.Both ->
            "Two passphrases opened two separate messages. Nothing on the photo marks which is " +
                "which — the order you typed them is the only thing that decides."
        TwoPasswordChoice.Neither ->
            "Neither passphrase opened this carrier. It may hold nothing, or a secret you have not " +
                "typed."
    }
}
