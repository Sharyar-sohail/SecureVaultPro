package com.securevault.pro.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

/**
 * WCAG 2.1 contrast maths over the palette. Every "ink" role below passes through
 * [VaultContrast.ensure], so a brand colour that is too pale for a white card — the
 * Apple and Samsung greens/ambers measured at 2.0:1 — is darkened only as far as readability
 * needs, while the same brand colour stays untouched for fills, gradients and plates.
 */
object VaultContrast {

    const val AA_TEXT = 4.5
    const val AA_LARGE = 3.0

    /**
     * The chroma every drawn colour must clear. The owner's rule is blunt: no dim colours. A
     * brand ramp darkened until it holds white ink can collapse into grey — #747479 Tools,
     * #867148 Passwords — and that reads as a washed-out theme even though it passes AA.
     */
    const val CHROMA_FLOOR = 0.55

    /** HSL saturation (0..1) of a colour, so the chroma floor can be measured, not asserted. */
    fun saturation(color: Color): Double {
        val max = maxOf(color.red, color.green, color.blue).toDouble()
        val min = minOf(color.red, color.green, color.blue).toDouble()
        val delta = max - min
        if (delta <= 0.0) return 0.0
        val light = (max + min) / 2.0
        return delta / (1.0 - kotlin.math.abs(2.0 * light - 1.0).coerceAtMost(0.9999))
    }

    /**
     * Push [color] up to the chroma floor along its own hue. Nothing is ever desaturated here:
     * the fix only adds colour, which is why a saturated plate is safe to draw even where the
     * palette already passes.
     */
    fun vibrant(color: Color, floor: Double = CHROMA_FLOOR): Color {
        if (saturation(color) >= floor) return color
        val channels = listOf(color.red, color.green, color.blue)
        val max = channels.maxOrNull() ?: return color
        val min = channels.minOrNull() ?: return color
        val delta = (max - min).toDouble()
        val hue = when (max) {
            color.red -> ((color.green - color.blue) / delta % 6.0)
            color.green -> ((color.blue - color.red) / delta + 2.0)
            else -> ((color.red - color.green) / delta + 4.0)
        } * 60.0
        val chroma = floor * (1.0 - kotlin.math.abs(2.0 * ((max + min) / 2.0).toDouble() - 1.0))
        val h = (hue + 360.0) % 360.0 / 60.0
        val x = chroma * (1.0 - kotlin.math.abs(h % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            h < 1.0 -> Triple(chroma, x, 0.0)
            h < 2.0 -> Triple(x, chroma, 0.0)
            h < 3.0 -> Triple(0.0, chroma, x)
            h < 4.0 -> Triple(0.0, x, chroma)
            h < 5.0 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        val light = ((max + min) / 2.0).toDouble()
        val m = light - chroma / 2.0
        return Color(
            red = (r1 + m).toFloat().coerceIn(0f, 1f),
            green = (g1 + m).toFloat().coerceIn(0f, 1f),
            blue = (b1 + m).toFloat().coerceIn(0f, 1f)
        )
    }

    fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    fun ratio(foreground: Color, background: Color): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** What a viewer actually sees when translucent [foreground] sits on [background]. */
    fun composite(foreground: Color, background: Color): Color {
        val t = foreground.alpha
        return Color(
            red = background.red + (foreground.red - background.red) * t,
            green = background.green + (foreground.green - background.green) * t,
            blue = background.blue + (foreground.blue - background.blue) * t,
            alpha = 1f
        )
    }

    /**
     * Blends [foreground] toward black on a light bed, white on a dark bed, until it clears
     * [minimum]. The search stops at the first blend that passes, so the brand hue survives.
     */
    fun ensure(foreground: Color, background: Color, minimum: Double = AA_TEXT): Color {
        val flat = composite(foreground, background)
        if (ratio(flat, background) >= minimum) return flat
        val target = if (luminance(background) > 0.45) BLACK else WHITE
        var low = 0f
        var high = 1f
        repeat(14) {
            val middle = (low + high) / 2f
            if (ratio(blend(flat, target, middle), background) >= minimum) high = middle else low = middle
        }
        return blend(flat, target, high)
    }

    /** The better of light/dark ink for text drawn on a solid [fill]. */
    fun inkOn(fill: Color, candidates: List<Color>): Color {
        candidates.firstOrNull { ratio(it, fill) >= AA_TEXT }?.let { return it }
        return candidates.maxByOrNull { ratio(it, fill) } ?: WHITE
    }

    private val BLACK = Color(0f, 0f, 0f)
    private val WHITE = Color(1f, 1f, 1f)

    private fun blend(from: Color, to: Color, t: Float) = Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f
    )
}

/**
 * Semantic colour roles: what a colour *means*, never which brand it borrows. Every role
 * reads the active palette, so one theme switch repaints the whole app.
 */
object VaultColor {

    private val palette: VaultPalette get() = VaultPaletteController.current

    // ---- Surfaces ---------------------------------------------------------------------
    val background: Color get() = palette.abyssDark
    val surface: Color get() = palette.abyssSurface
    val elevated: Color get() = palette.abyssElevated
    val glass: Color get() = palette.abyssGlass
    val shade: Color get() = palette.surfaceShade

    /** Opaque ink for dividers, chips and inset panels; the caller still picks the alpha. */
    val overlay: Color get() = palette.surfaceOverlay

    /** A translucent light-on-dark flourish: sheen on a card, ring on a plate. */
    val surfaceLift: Color get() = if (palette.isLight) Color(0x1FFFFFFF) else Color(0x2FFFFFFF)
    val hairline: Color get() = palette.outlineColor
    val hairlineStrong: Color get() = VaultContrast.ensure(hairline, surface, VaultContrast.AA_LARGE)
    val scrim: Color get() = if (palette.isLight) Color(0x66000000) else Color(0xCC000000)

    // ---- Text -------------------------------------------------------------------------
    val textStrong: Color get() = palette.textPrimary
    val textBody: Color get() = VaultContrast.ensure(palette.textSecondary, surface)
    val textFaint: Color get() = VaultContrast.ensure(
        VaultContrast.ensure(palette.textMuted, surface), palette.abyssElevated
    )

    // ---- Accents ----------------------------------------------------------------------
    val accent: Color get() = palette.cyan
    val secondary: Color get() = palette.indigo
    val decorative: Color get() = palette.lavender

    /** Accent used as text or stroke on a card — never as a fill. */
    val accentInk: Color get() = VaultContrast.ensure(accent, surface)
    val secondaryInk: Color get() = VaultContrast.ensure(secondary, surface)
    val decorativeInk: Color get() = VaultContrast.ensure(decorative, surface)

    /** Ink drawn on a solid accent fill: buttons, badges, icon plates. */
    val onAccent: Color get() = inkOn(accent)
    val onSecondary: Color get() = inkOn(secondary)
    val onWarning: Color get() = inkOn(warning)

    /**
     * Two accent roles side by side — the floating action button and hero header ramp.
     * Stops are always mid-chroma brand fills, so the ink on them is chosen, not assumed.
     */
    fun heroFill(from: Color = accent, to: Color = secondary): List<Color> = listOf(from, to)

    /** Ink that survives the *worst* stop of a gradient. */
    fun onGradient(stops: List<Color>): Color =
        inkCandidates.maxByOrNull { ink -> stops.minOf { VaultContrast.ratio(ink, it) } } ?: Color.White

    // ---- Drawn over camera, media and dialogs -----------------------------------------
    /** Off-white that reads on a black scrim or a live camera preview at any hour. */
    val inkOnScrim: Color get() = if (palette.isLight) Color(0xFFF2F4F8) else Color.White
    val hairlineOnScrim: Color get() = if (palette.isLight) Color(0x33FFFFFF) else Color(0x40FFFFFF)

    /** Always-dark bed for physical-card and camera affordances: stripe, veil, letterbox. */
    val void: Color get() = Color(0xE6000000)

    /** Lightening target for glass sheens and lit gradients, independent of theme ink. */
    val sheen: Color get() = Color.White

    /** The band between caution and loss on a security ramp. */
    val alert: Color get() = lerp(warning, debit, 0.45f)

    /** QR modules are a scanning contract, not a theme choice: pure light on pure dark, always. */
    val qrLight: Color get() = Color.White
    val qrDark: Color get() = Color(0xFF000000)

    /** Partner brand mark colour — fixed by the brand, not by the theme. */
    val whatsAppBrand: Color get() = Color(0xFF25D366)

    /** Bed for a colour tag: the tag's own accent, sunk into the card so ink survives on it. */
    fun noteTint(fill: Color): Color =
        if (palette.isLight) VaultContrast.composite(fill.copy(alpha = 0.12f), surface)
        else VaultContrast.composite(fill.copy(alpha = 0.22f), elevated)

    private fun inkOn(fill: Color) = VaultContrast.inkOn(fill, inkCandidates)
    private val inkCandidates: List<Color>
        get() = listOf(Color.White, palette.textPrimary, palette.abyssDark)

    // ---- Meaning: money in, money out, caution, loss ----------------------------------
    val credit: Color get() = palette.mint
    val creditInk: Color get() = VaultContrast.ensure(credit, surface)
    val debit: Color get() = palette.coral
    val debitInk: Color get() = VaultContrast.ensure(debit, surface)
    val warning: Color get() = palette.amber
    val warningInk: Color get() = VaultContrast.ensure(warning, surface)
    val dangerFill: Color get() = palette.dangerDim

    val accentRamp: List<Color> get() = listOf(accent, secondary, credit, warning, decorative, debit)

    // ---- Material 3 -------------------------------------------------------------------
    /**
     * Containers stay translucent so the backdrop still reads through a chip, but each
     * matching "on" colour is measured against the composited result rather than the raw
     * accent — which is how the light themes pass AA after this file exists and did not
     * before it.
     */
    fun colorScheme(p: VaultPalette = palette): ColorScheme {
        val containerAlpha = if (p.isLight) 0.14f else 0.16f
        val card = p.abyssSurface
        val accent = p.cyan
        val secondary = p.indigo
        val warning = p.amber
        val debit = p.coral

        fun onContainer(container: Color, from: Color) =
            VaultContrast.ensure(from, VaultContrast.composite(container, card))

        fun ink(fill: Color) = VaultContrast.inkOn(
            fill,
            listOf(Color.White, p.textPrimary, p.abyssDark)
        )

        val base = if (p.isLight) lightColorScheme() else darkColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = ink(accent),
            primaryContainer = accent.copy(alpha = containerAlpha),
            onPrimaryContainer = onContainer(accent.copy(alpha = containerAlpha), accent),
            secondary = secondary,
            onSecondary = ink(secondary),
            secondaryContainer = secondary.copy(alpha = containerAlpha),
            onSecondaryContainer = onContainer(secondary.copy(alpha = containerAlpha), secondary),
            tertiary = warning,
            onTertiary = ink(warning),
            tertiaryContainer = warning.copy(alpha = containerAlpha),
            onTertiaryContainer = onContainer(warning.copy(alpha = containerAlpha), warning),
            background = p.abyssDark,
            onBackground = p.textPrimary,
            surface = card,
            onSurface = p.textPrimary,
            surfaceVariant = p.abyssElevated,
            onSurfaceVariant = VaultContrast.ensure(p.textSecondary, p.abyssElevated),
            surfaceTint = accent,
            outline = p.outlineColor,
            outlineVariant = p.glassBorder,
            error = debit,
            onError = ink(debit),
            errorContainer = p.dangerDim,
            onErrorContainer = VaultContrast.ensure(debit, p.dangerDim),
            inverseSurface = p.textPrimary,
            inverseOnSurface = card,
            scrim = if (p.isLight) Color(0x66000000) else Color(0xCC000000)
        )
    }
}
