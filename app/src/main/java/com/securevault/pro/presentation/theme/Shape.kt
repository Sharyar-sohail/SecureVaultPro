package com.securevault.pro.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The only corner scale in the app. Steps are anchored to the active palette, so a brand
 * that draws 32.dp panes and one that draws 12.dp panes each get a full, self-consistent
 * ramp instead of the 22 ad-hoc radii screens used to hardcode.
 */
object VaultRadius {

    private val palette: VaultPalette get() = VaultPaletteController.current

    /** Tightest usable corner — tags, inline pills, progress tracks. */
    val xs get() = (palette.chipCorner.value * 0.5f).dp.coerceAtLeast(2.dp)
    val sm get() = palette.chipCorner
    val md get() = palette.inputCorner
    val lg get() = palette.buttonCorner
    val xl get() = palette.cardCorner
    val twoXl get() = palette.navCorner

    /** Perfect circle, expressed as a shape rather than a magic 50.dp. */
    val circle: Shape = CircleShape

    /** Hard corners — the one radius a brutalist frame insists on having none of. */
    val square: Shape = RoundedCornerShape(0.dp)

    fun pill(): Shape = RoundedCornerShape(percent = 50)
}

/** Named shapes for the surfaces the component library draws. */
object VaultShapes {

    val card: RoundedCornerShape get() = RoundedCornerShape(VaultRadius.xl)
    val sheet: RoundedCornerShape get() = RoundedCornerShape(topStart = VaultRadius.twoXl, topEnd = VaultRadius.twoXl, bottomStart = 0.dp, bottomEnd = 0.dp)
    val button: RoundedCornerShape get() = RoundedCornerShape(VaultRadius.lg)
    val input: RoundedCornerShape get() = RoundedCornerShape(VaultRadius.md)
    val chip: RoundedCornerShape get() = RoundedCornerShape(VaultRadius.sm)
    val tag: RoundedCornerShape get() = RoundedCornerShape(VaultRadius.xs)
    val plate: RoundedCornerShape get() = RoundedCornerShape(VaultPaletteController.current.iconTileCorner)
    val nav: RoundedCornerShape get() = RoundedCornerShape(VaultRadius.twoXl)

    /** Feeds [MaterialTheme.shapes], which existed for three releases and was read nowhere. */
    fun material(palette: VaultPalette = VaultPaletteController.current): Shapes = Shapes(
        extraSmall = RoundedCornerShape((palette.chipCorner.value * 0.5f).dp.coerceAtLeast(2.dp)),
        small = RoundedCornerShape(palette.inputCorner),
        medium = RoundedCornerShape(palette.buttonCorner),
        large = RoundedCornerShape(palette.cardCorner),
        extraLarge = RoundedCornerShape(palette.navCorner)
    )
}
