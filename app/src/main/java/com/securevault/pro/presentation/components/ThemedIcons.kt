package com.securevault.pro.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.theme.IconStyle
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultPaletteController

/**
 * The app's one icon entry point.
 *
 * Material Icons are banned outright: every mark drawn here comes from the house vector family in
 * `app/src/main/res/drawable/ic_a_*`, generated from `tools/brand-icons` with per-glyph provenance.
 * [MaterialIconsAreBannedTest] fails the build if the Material icon package reappears in
 * either the sources or the dependency graph, so a future edit cannot quietly bring them back.
 *
 * A theme still has an [IconStyle], but it now selects a *treatment* of the same geometry rather
 * than one of four Material languages: the family keeps one consistent silhouette across all
 * fifteen palettes (that is the point), while weight and ink follow the style so a switch still
 * reads as a change of drawing.
 */
@Composable
fun ThemedIcon(
    glyph: Glyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = VaultColor.textBody,
    size: Dp = 24.dp,
    animate: Boolean = true
) {
    val style = VaultPaletteController.current.iconStyle
    VaultGlyph(
        glyph = glyph,
        size = size * style.scale,
        tint = if (style == IconStyle.OUTLINED) tint.copy(alpha = 0.86f) else tint,
        contentDescription = contentDescription,
        modifier = modifier,
        animate = animate
    )
}

private val IconStyle.scale: Float
    get() = when (this) {
        IconStyle.OUTLINED -> 1.04f
        IconStyle.SHARP -> 1.00f
        IconStyle.FILLED -> 0.96f
        IconStyle.ROUNDED -> 1.00f
    }
