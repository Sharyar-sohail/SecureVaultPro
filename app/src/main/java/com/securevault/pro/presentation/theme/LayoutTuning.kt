package com.securevault.pro.presentation.theme

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * The owner's per-theme board tuning: how many columns the module grid takes, and how large its
 * plate, glyph and breathing room are drawn.
 *
 * The values are *multipliers on the theme's own grammar*, never fixed sizes, so the board keeps
 * filling its pane at every column count and the zero-scroll dashboard contract still holds.
 * [columns] is null while the theme draws its own column count — that is the "Theme default"
 * choice in the settings sheet, and [isDefault] is what the Home board reads to prove it can take
 * the untouched fast path.
 */
data class LayoutTuning(
    /** Null means "the palette's own `boardColumns`". Otherwise 2, 3 or 4. */
    val columns: Int? = null,
    /** Multiplier on the plate's content block. */
    val tileScale: Float = 1f,
    /** Multiplier on the module glyph only. */
    val iconScale: Float = 1f,
    /** Multiplier on row gap, cell gap, inner padding and the section rhythm. */
    val spacingScale: Float = 1f
) {
    /** True when nothing has been tuned, so the theme's own board must be drawn unchanged. */
    val isDefault: Boolean
        get() = columns == null && tileScale == 1f && iconScale == 1f && spacingScale == 1f

    /** The column count this tuning asks for, or [fallback] (the palette's own). */
    fun columnsOr(fallback: Int): Int = columns ?: fallback

    companion object {
        val DEFAULT = LayoutTuning()

        /** The column choices the settings sheet offers, in board order. */
        val COLUMN_CHOICES: List<Int?> = listOf(null, 2, 3, 4)

        /** Snaps a slider value onto its 0.05 ladder so a drag cannot persist 1.1000001. */
        fun snap(value: Float, min: Float, max: Float): Float {
            val stepped = kotlin.math.round(value * 20f) / 20f
            return stepped.coerceIn(min, max)
        }
    }
}

/**
 * The live tuning of the *active* theme, as a Compose state.
 *
 * A palette is a plain `mutableStateOf` snapshot as well ([VaultPaletteController]), because the
 * screens read the palette outside composition. This follows the same shape: Home subscribes to
 * `AppLockManager.layoutTuning(themeId)`, pushes every emission through [apply], and every board
 * that reads [current] recomposes. VaultApp can call [apply] itself instead once it owns the
 * subscription — nothing else has to change.
 */
object LayoutTuningController {
    private val tuning = mutableStateOf(LayoutTuning())

    /** The tuning Home must draw with right now. */
    val current: LayoutTuning get() = tuning.value

    /** The same value as an observable state, for callers that prefer `by`. */
    val active: State<LayoutTuning> get() = tuning

    fun apply(next: LayoutTuning) {
        if (tuning.value != next) tuning.value = next
    }

    /** Back to the theme's own board. */
    fun reset() = apply(LayoutTuning())
}
