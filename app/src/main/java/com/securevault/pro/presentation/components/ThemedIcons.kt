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
 * fifteen palettes (that is the point), while the optical size follows the style so a switch still
 * reads as a change of drawing. The ink never follows it — the tint the caller asked for is drawn
 * at full strength, always.
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
        tint = tint,
        contentDescription = contentDescription,
        modifier = modifier,
        animate = animate
    )
}

/**
 * Optical size per treatment, as a fraction of the requested box.
 *
 * This used to be paired with an `alpha = 0.86f` on [IconStyle.OUTLINED], because a thin mark reads
 * as lighter than a solid one. That reasoning is wrong on this phone and the owner has said so:
 * faded ink is a *dim icon*, which is the one thing he banned along with dark plates. Alpha also
 * breaks WCAG 1.4.11, which wants at least 3:1 for a graphic that carries meaning, and five of the
 * fifteen palettes are outlined, so it was dimming the majority of the app's iconography.
 *
 * The weight difference is now carried by geometry alone, and every style sits on its own value so
 * switching icon treatment is visible.
 */
private val IconStyle.scale: Float
    get() = when (this) {
        IconStyle.OUTLINED -> 1.04f
        IconStyle.SHARP -> 1.00f
        IconStyle.FILLED -> 0.96f
        IconStyle.ROUNDED -> 0.98f
    }
