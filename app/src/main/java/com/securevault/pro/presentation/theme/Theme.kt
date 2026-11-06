package com.securevault.pro.presentation.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The theme's typographic voice, laid over the family the owner picked in Settings.
 *
 * [family] is applied to the *base scale first*, so a design block's own `fontFamily` wins.
 * It used to be applied last, which quietly deleted the only thing several themes had to say
 * about type: every one of the five arcade palettes declares Monospace for its headings and
 * tags, and re-homing all fifteen roles onto the picked family after the copy() chain threw
 * that away on every real device — `monoTags` then only survived inside the chart. A theme's
 * typeface is part of its layout language, so it is now the theme's to keep.
 *
 * The exception is `labelSmall`, which deliberately stays on the picked family: that style is
 * what the dock captions and the board tiles draw at the palette's own 10-11.sp, and their
 * advance widths are what [fitCell] and `HomeBoardFitTest` budget for. A mono face is roughly
 * 15% wider per glyph than the sans, and a clipped module name is the one defect that rule
 * cannot pay for. Tracking on the mono roles is pulled in by the same margin.
 */
private fun vaultTypography(palette: VaultPalette, family: FontFamily = FontFamily.SansSerif): Typography {
    val base = VaultTypography.withFamily(family)
    return when (palette.design) {
        ThemeDesign.NEUTRAL -> base
        ThemeDesign.NEO_BRUTALIST -> base.copy(
            displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 0.sp),
            displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 0.sp),
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = 0.sp),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Black),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Black),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
            labelLarge = base.labelLarge.copy(letterSpacing = 0.8.sp),
            labelMedium = base.labelMedium.copy(letterSpacing = 0.9.sp),
            labelSmall = base.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        )
        ThemeDesign.AGENTIC -> base.copy(
            displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.8).sp),
            displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold),
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
            labelMedium = base.labelMedium.copy(letterSpacing = 0.2.sp),
            labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp)
        )
        ThemeDesign.CLAYMORPHISM -> base.copy(
            displayLarge = base.displayLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
            displayMedium = base.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
            labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Bold),
            labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
        )
        ThemeDesign.CYBERPUNK -> base.copy(
            displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-2).sp),
            displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black),
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Black),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
            labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp),
            labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp),
            // Stays on the picked family — see the kdoc. Tracked, but not past what a 10.sp
            // dock caption has room for inside its share of the bar.
            labelSmall = base.labelSmall.copy(letterSpacing = 1.2.sp)
        )
        ThemeDesign.NEO_SKEUOMORPHIC -> base.copy(
            displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = base.labelLarge.copy(letterSpacing = 0.4.sp),
            labelMedium = base.labelMedium.copy(letterSpacing = 0.6.sp),
            labelSmall = base.labelSmall.copy(letterSpacing = 1.2.sp)
        )
        ThemeDesign.LIQUID_GLASS -> base
        ThemeDesign.GENERATIVE -> base.copy(
            displaySmall = base.displaySmall.copy(letterSpacing = (-0.9).sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
            labelSmall = base.labelSmall.copy(letterSpacing = 1.2.sp)
        )
        ThemeDesign.BENTO -> base.copy(
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            labelSmall = base.labelSmall.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
fun SecureWalletTheme(
    themeId: String = VaultPalettes.DEFAULT_ID,
    fontId: String = VaultFonts.DEFAULT_ID,
    content: @Composable () -> Unit
) {
    // Resolved during this composition, before any child reads a colour role. Publishing it
    // through a LaunchedEffect meant the first frame painted the previous palette, which is
    // the flash people saw on cold start and on every switch.
    val palette = remember(themeId) { VaultPalettes.find(themeId).also(VaultPaletteController::apply) }
    val font = remember(fontId) { VaultFonts.find(fontId).also { VaultFontController.apply(it) } }
    MaterialTheme(
        colorScheme = VaultColor.colorScheme(palette),
        typography = vaultTypography(palette, font.family),
        shapes = VaultShapes.material(palette)
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            ThemeSwapHalo(palette, Modifier.matchParentSize())
        }
    }
}

/**
 * Re-homes every text role onto the chosen family. Sizes, weights and tracking stay
 * exactly as the theme wrote them, so a font swap changes the typeface, not the layout.
 */
private fun Typography.withFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family)
)

/**
 * A short halo in the incoming palette's accent, played only when the theme actually
 * changes. It has no pointer input, so taps keep reaching the screen underneath.
 */
@Composable
private fun ThemeSwapHalo(palette: VaultPalette, modifier: Modifier) {
    val halo = remember { Animatable(0f) }
    var seenThemeId by remember { mutableStateOf(palette.id) }
    LaunchedEffect(palette.id) {
        if (palette.id == seenThemeId) return@LaunchedEffect
        seenThemeId = palette.id
        halo.snapTo(0f)
        halo.animateTo(0.30f, tween(130))
        halo.animateTo(0f, tween(340))
    }
    val strength = halo.value
    if (strength <= 0.002f) return
    Box(
        modifier.drawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.cyan.copy(alpha = strength),
                        palette.indigo.copy(alpha = strength * 0.55f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.86f, size.height * 0.12f),
                    radius = size.maxDimension * 1.15f
                )
            )
        }
    )
}
