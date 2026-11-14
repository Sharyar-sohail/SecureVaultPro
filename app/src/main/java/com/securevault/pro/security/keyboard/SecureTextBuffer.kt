package com.securevault.pro.security.keyboard

/**
 * The text a secure field holds, and where its caret is.
 *
 * Every edit the secure board makes goes through here, and only here. There is no path from the
 * IME to a field's value: the Compose layer refuses edits from anything but this buffer, so
 * "what the owner typed" is always a function of the keys they tapped.
 */
data class SecureTextBuffer(val text: String = "", val cursor: Int = 0) {

    init {
        require(cursor in 0..text.length) { "The caret has to sit inside the text" }
    }

    val isEmpty: Boolean get() = text.isEmpty()

    fun insert(value: String): SecureTextBuffer {
        if (value.isEmpty()) return this
        val next = text.substring(0, cursor) + value + text.substring(cursor)
        return SecureTextBuffer(next, cursor + value.length)
    }

    fun backspace(): SecureTextBuffer {
        if (cursor == 0) return this
        return SecureTextBuffer(text.substring(0, cursor - 1) + text.substring(cursor), cursor - 1)
    }

    fun moveCursorLeft(): SecureTextBuffer =
        if (cursor <= 0) this else SecureTextBuffer(text, cursor - 1)

    fun moveCursorRight(): SecureTextBuffer =
        if (cursor >= text.length) this else SecureTextBuffer(text, cursor + 1)

    /** A newline only where multiple lines are wanted; single-line fields submit instead. */
    fun appendNewline(): SecureTextBuffer = insert("\n")

    fun clear(): SecureTextBuffer = SecureTextBuffer()

    companion object {
        val Empty = SecureTextBuffer()

        /** Adopts a value owned by the caller (a ViewModel field) without moving their caret. */
        fun of(text: String): SecureTextBuffer = SecureTextBuffer(text, text.length)
    }
}
