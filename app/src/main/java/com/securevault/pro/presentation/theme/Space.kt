package com.securevault.pro.presentation.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One spacing rhythm. The fixed steps give screens a scale they can share; the named roles
 * snap each step to the active palette's own gutter, so an airy theme (One UI, 26.dp screen
 * gutter) and a tight one (Material, 16.dp) differ without any screen carrying a number.
 */
object VaultSpace {

    private val palette: VaultPalette get() = VaultPaletteController.current

    val hair: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val twoXl: Dp = 32.dp

    /** Left/right page margin. */
    val gutter: Dp get() = palette.screenGutter

    /** Space between cards, rows and grid cells. */
    val gap: Dp get() = palette.gridGutter

    /** Space between two stacked sections — one gap plus a breath. */
    val stack: Dp get() = palette.gridGutter + palette.gridGutter

    /** Inner padding of a standard card, kept square so a card never reads as lopsided. */
    val card: Dp get() = maxOf(palette.gridGutter, 12.dp)

    /** Inner padding of a compact row or tile. */
    val rowH: Dp get() = palette.moduleTilePadH
    val rowV: Dp get() = palette.moduleTilePadV

    /** Bottom padding so the last card clears the dock. */
    val dockClear: Dp get() = palette.navHeight
}
