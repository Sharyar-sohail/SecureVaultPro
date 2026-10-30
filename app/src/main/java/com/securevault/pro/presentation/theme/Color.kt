package com.securevault.pro.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit

/**
 * Compatibility layer, and nothing else.
 *
 * Every name the app used before the token split still resolves here, so no screen had to
 * change for colour to become centralised; each one now forwards to a semantic role in
 * [VaultColor] (or to the palette for raw brand fills). The `ReplaceWith` on each entry is
 * the migration map used when the call sites are swept in the next pass — new code should
 * never import from this file.
 *
 * Two aliases deliberately stop being verbatim copies of the old brand value, because the
 * old value failed WCAG AA everywhere it was used as text:
 *   `TextMuted`    now carries the AA-checked caption ink ([VaultColor.textFaint]).
 *   `OnAccent`     now picks black or white ink by measured contrast ([VaultColor.onAccent]).
 */

@Deprecated("Use VaultColor.textStrong", ReplaceWith("VaultColor.textStrong", "com.securevault.pro.presentation.theme.VaultColor"))
val TextPrimary: Color get() = VaultColor.textStrong

@Deprecated("Use VaultColor.textBody", ReplaceWith("VaultColor.textBody", "com.securevault.pro.presentation.theme.VaultColor"))
val TextSecondary: Color get() = VaultColor.textBody

@Deprecated("Use VaultColor.textFaint", ReplaceWith("VaultColor.textFaint", "com.securevault.pro.presentation.theme.VaultColor"))
val TextMuted: Color get() = VaultColor.textFaint

@Deprecated("Use VaultColor.background", ReplaceWith("VaultColor.background", "com.securevault.pro.presentation.theme.VaultColor"))
val AbyssDark: Color get() = VaultColor.background

@Deprecated("Use VaultColor.surface", ReplaceWith("VaultColor.surface", "com.securevault.pro.presentation.theme.VaultColor"))
val AbyssSurface: Color get() = VaultColor.surface

@Deprecated("Use VaultColor.elevated", ReplaceWith("VaultColor.elevated", "com.securevault.pro.presentation.theme.VaultColor"))
val AbyssElevated: Color get() = VaultColor.elevated

@Deprecated("Use VaultColor.glass", ReplaceWith("VaultColor.glass", "com.securevault.pro.presentation.theme.VaultColor"))
val AbyssGlass: Color get() = VaultColor.glass

@Deprecated("Use VaultColor.accent (fill) or VaultColor.accentInk (text)", ReplaceWith("VaultColor.accent", "com.securevault.pro.presentation.theme.VaultColor"))
val NeonCyan: Color get() = VaultColor.accent

@Deprecated("Use VaultColor.secondary (fill) or VaultColor.secondaryInk (text)", ReplaceWith("VaultColor.secondary", "com.securevault.pro.presentation.theme.VaultColor"))
val NeonIndigo: Color get() = VaultColor.secondary

@Deprecated("Use VaultColor.warning (fill) or VaultColor.warningInk (text)", ReplaceWith("VaultColor.warning", "com.securevault.pro.presentation.theme.VaultColor"))
val NeonAmber: Color get() = VaultColor.warning

@Deprecated("Use VaultColor.credit (fill) or VaultColor.creditInk (text)", ReplaceWith("VaultColor.credit", "com.securevault.pro.presentation.theme.VaultColor"))
val NeonMint: Color get() = VaultColor.credit

@Deprecated("Use VaultColor.debit (fill) or VaultColor.debitInk (text)", ReplaceWith("VaultColor.debit", "com.securevault.pro.presentation.theme.VaultColor"))
val NeonCoral: Color get() = VaultColor.debit

@Deprecated("Use VaultColor.decorative", ReplaceWith("VaultColor.decorative", "com.securevault.pro.presentation.theme.VaultColor"))
val NeonLavender: Color get() = VaultColor.decorative

@Deprecated("Use VaultColor.hairline", ReplaceWith("VaultColor.hairline", "com.securevault.pro.presentation.theme.VaultColor"))
val GlassBorder: Color get() = VaultColor.hairline

@Deprecated("Use VaultColor.glass", ReplaceWith("VaultColor.glass", "com.securevault.pro.presentation.theme.VaultColor"))
val GlassHighlight: Color get() = VaultPaletteController.current.glassHighlight

@Deprecated("Use VaultColor.dangerFill", ReplaceWith("VaultColor.dangerFill", "com.securevault.pro.presentation.theme.VaultColor"))
val DangerDim: Color get() = VaultColor.dangerFill

@Deprecated("Use VaultColor.onAccent", ReplaceWith("VaultColor.onAccent", "com.securevault.pro.presentation.theme.VaultColor"))
val OnAccent: Color get() = VaultColor.onAccent

@Deprecated("Use VaultColor.accentRamp", ReplaceWith("VaultColor.accentRamp", "com.securevault.pro.presentation.theme.VaultColor"))
val AccentPalette: List<Color> get() = VaultColor.accentRamp

/**
 * Text size of a module label, still owned by the palette. Kept here because it is a
 * per-theme number rather than a type role; use [VaultType.micro] for any new label.
 */
val ModuleLabelSize: TextUnit get() = VaultPaletteController.current.moduleLabelSize
