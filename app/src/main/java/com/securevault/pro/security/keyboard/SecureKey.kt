package com.securevault.pro.security.keyboard

/**
 * What a keycap is on the secure keyboard.
 *
 * The grid is drawn by the app, so a key is data first and a picture second: [label] is what the
 * keycap shows for the current layout state and [text] is the exact string it puts into the field.
 * Modifier keys carry no [text] — they change the layout instead.
 */
enum class SecureKeyKind {
    /** Prints [SecureKey.text] into the field. */
    CHAR,

    /** Prints a space; drawn as a wide key with a bar instead of a letter. */
    SPACE,

    /** Fires the submit action, or a newline when the field accepts multiple lines. */
    ENTER,

    /** Deletes the character before the caret. */
    BACKSPACE,

    /** Arms the case/symbol shift for the next character. */
    SHIFT,

    /** Swaps the letter grid for the digits and symbols grid. */
    LAYER,

    /** Re-seats the tiles in a new order for the rest of this session. */
    SHUFFLE,
}

/** Which half of the board is showing. */
enum class SecureKeyLayer {
    LETTERS,
    NUMBERS,
}

/**
 * One pressable key.
 *
 * [id] is stable across shuffles (it names the *character*, not its position) so a11y labels and
 * tests can point at a key without caring where it landed.
 */
data class SecureKey(
    val id: String,
    val label: String,
    val kind: SecureKeyKind,
    val text: String = if (kind == SecureKeyKind.CHAR) label else "",
) {
    companion object {
        fun char(symbol: String, insert: String = symbol): SecureKey =
            SecureKey(id = symbol, label = symbol, kind = SecureKeyKind.CHAR, text = insert)

        val Shift = SecureKey(id = "shift", label = "shift", kind = SecureKeyKind.SHIFT)
        val Backspace = SecureKey(id = "backspace", label = "backspace", kind = SecureKeyKind.BACKSPACE)
        val Enter = SecureKey(id = "enter", label = "enter", kind = SecureKeyKind.ENTER)
        val Space = SecureKey(id = "space", label = "space", kind = SecureKeyKind.SPACE)
        val Shuffle = SecureKey(id = "shuffle", label = "shuffle", kind = SecureKeyKind.SHUFFLE)
        val ToLetters = SecureKey(id = "letters", label = "ABC", kind = SecureKeyKind.LAYER)
        val ToNumbers = SecureKey(id = "numbers", label = "123", kind = SecureKeyKind.LAYER)
    }
}

/** What the field has to do about a press. Everything else about a press is layout. */
sealed interface SecureKeyCommand {
    data class Insert(val text: String) : SecureKeyCommand
    data object Backspace : SecureKeyCommand
    data object Enter : SecureKeyCommand

    /** A layout-only key: the value did not change. */
    data object Ignored : SecureKeyCommand
}
