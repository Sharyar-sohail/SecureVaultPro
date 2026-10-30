package com.securevault.pro.presentation.components

import androidx.compose.ui.graphics.Color
import com.securevault.pro.presentation.theme.VaultColor

/**
 * A note colour tag is stored as a word, not a hex, so it re-inks with the theme instead of
 * carrying the five dark-mode values it was born with.
 */
fun noteAccent(color: String): Color = when (color.uppercase()) {
    "CYAN" -> VaultColor.accent
    "MINT" -> VaultColor.credit
    "LAVENDER" -> VaultColor.decorative
    "CORAL" -> VaultColor.debit
    else -> VaultColor.textBody
}

/** The tile a tag sits on: the accent itself, sunk into the surface so ink stays readable. */
fun noteTint(color: String): Color = VaultColor.noteTint(noteAccent(color))
