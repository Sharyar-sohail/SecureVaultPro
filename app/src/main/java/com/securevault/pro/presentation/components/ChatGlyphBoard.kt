package com.securevault.pro.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.securevault.pro.data.imports.WhatsAppChatUnit
import com.securevault.pro.presentation.theme.VaultColor

/**
 * The conversation board: every chat in the vault is drawn with a glyph of its own.
 *
 * [WhatsAppChatUnit] already guarantees each conversation its own directory; this is the other
 * half of "one chat, one module" — the owner must be able to *see* which chat a row is without
 * reading the title, the same way the media folders differ by type. The slot is a pure function of
 * the chat id, so a chat wears the glyph it wore before: re-exporting "Ali" does not shuffle the
 * board, and two chats opened in the same list never land on the same mark unless the board wraps.
 *
 * The board holds only house glyphs, and the tint is left to [ThemedIcon]'s default
 * [com.securevault.pro.presentation.theme.VaultColor.textBody] — identity is carried by the shape,
 * so no chat can be given an ink that fails the theme's contrast rule.
 */
object ChatGlyphBoard {

    /** The conversation-shaped members of the house family, in the order chats take turns over them. */
    val glyphs: List<Glyph> = listOf(
        Glyph.chat, Glyph.message, Glyph.contacts, Glyph.person,
        Glyph.phone, Glyph.call, Glyph.mic, Glyph.send,
        Glyph.notes, Glyph.email, Glyph.image, Glyph.videocam,
        Glyph.musicNote, Glyph.star, Glyph.pin, Glyph.shield
    )

    /** The glyph a chat of this id is drawn with. */
    fun glyphFor(chatId: String): Glyph = glyphs[WhatsAppChatUnit.iconSlot(chatId, glyphs.size)]

    /** The same, from the two facts the importer keys on — so a list of *not yet imported* exports
     *  shows the glyph the chat will keep once it is sealed. */
    fun glyphFor(chatTitle: String, sourceName: String): Glyph =
        glyphFor(WhatsAppChatUnit.idFor(chatTitle, sourceName))
}

/** One row's worth of chat identity: the board glyph, sized to sit in a line of body copy. */
@Composable
fun ChatGlyph(
    chatId: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = VaultColor.textBody,
    contentDescription: String? = null
) {
    ThemedIcon(
        glyph = ChatGlyphBoard.glyphFor(chatId),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
        size = size,
        animate = false
    )
}
