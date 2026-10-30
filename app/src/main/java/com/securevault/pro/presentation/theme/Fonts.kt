package com.securevault.pro.presentation.theme

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.DeviceFontFamilyName

/** A typeface the user can pick in Settings → Appearance. */
data class VaultFont(
    val id: String,
    val name: String,
    val tagline: String,
    val family: FontFamily
)

object VaultFonts {
    const val DEFAULT_ID = "inter"

    /**
     * Deliberately a member, not a top-level helper. A top-level function lives in the
     * file's own class (`FontsKt`), so calling one from here runs that class's initialiser in
     * the middle of this object's — and that initialiser reads [all] while it is still null.
     * The JVM turned it into an `ExceptionInInitializerError` on the very first composition,
     * which is what stopped the app opening at all. Keep font construction inside this
     * object, and keep anything from this file out of [all].
     */
    private fun deviceFamily(name: String): FontFamily =
        FontFamily(Font(DeviceFontFamilyName(name)))

    /** Also lazy, so no ordering between these classes can ever be load-bearing. */
    val all: List<VaultFont> by lazy {
        listOf(
            VaultFont(
                id = "inter",
                name = "Inter Sans",
                tagline = "Neutral system grotesque — the default on every theme",
                family = FontFamily.SansSerif
            ),
            VaultFont(
                id = "medium",
                name = "Medium Grotesk",
                tagline = "Heavier strokes, the HyperOS and ColorOS label feel",
                family = deviceFamily("sans-serif-medium")
            ),
            VaultFont(
                id = "compact",
                name = "Compact Condensed",
                tagline = "Narrow set that fits more rows on screen",
                family = deviceFamily("sans-serif-condensed")
            ),
            VaultFont(
                id = "editorial",
                name = "Editorial Serif",
                tagline = "Bookish serif for headings and reading lists",
                family = FontFamily.Serif
            ),
            VaultFont(
                id = "terminal",
                name = "Terminal Mono",
                tagline = "Fixed-width glyphs — codes and hashes line up",
                family = FontFamily.Monospace
            ),
            VaultFont(
                id = "script",
                name = "Soft Script",
                tagline = "Rounded humanist script for a lighter feel",
                family = FontFamily.Cursive
            )
        )
    }

    val default: VaultFont get() = all.first { it.id == DEFAULT_ID }

    fun find(id: String?): VaultFont {
        val wanted = id?.trim()?.lowercase()
        return all.firstOrNull { it.id == wanted } ?: default
    }
}

private val activeFont by lazy { mutableStateOf(VaultFonts.default) }

object VaultFontController {
    val active: State<VaultFont> get() = activeFont
    val current: VaultFont get() = activeFont.value

    fun apply(id: String?) {
        apply(VaultFonts.find(id))
    }

    fun apply(font: VaultFont) {
        if (font.id != activeFont.value.id) activeFont.value = font
    }
}
