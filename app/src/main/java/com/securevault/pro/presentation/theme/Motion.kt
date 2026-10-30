package com.securevault.pro.presentation.theme

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/**
 * One duration and easing vocabulary. Screens used to spread 19 different `tween(...)`
 * values across 9 files; every animation now names one of these steps and inherits the
 * device's animation scale, which the app previously ignored entirely.
 */
object VaultMotion {

    const val INSTANT = 90
    const val QUICK = 140
    const val SHORT = 180
    const val STANDARD = 240
    const val LONG = 340
    const val EMPHATIC = 480
    const val LINGERING = 800

    /** Product-feel curves: a name instead of a magic number at each call site. */
    val Enter: Easing = LinearOutSlowInEasing
    val Exit: Easing = FastOutSlowInEasing
    val Emphasis: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    val SpringDamping = 0.82f
    val SpringStiffness = Spring.StiffnessLow
    val SpringBouncy = 0.55f

    /**
     * Multiplier for [scaled]. Honours the system "animation duration scale" setting, so
     * reduced motion is respected rather than animated over; 0 means "cut to the end".
     */
    @Composable
    fun durationScale(): Float {
        val context = LocalContext.current
        return remember(context) {
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                )
            }.getOrDefault(1f).coerceIn(0f, 2f)
        }
    }

    @Composable
    fun tween(durationMillis: Int, easing: Easing = Exit) = tween<Int>(
        durationMillis = scaled(durationMillis, durationScale()),
        easing = easing
    )

    /** A duration a caller can pass straight into a non-composable spec. */
    @Composable
    fun scaled(durationMillis: Int): Int = scaled(durationMillis, durationScale())

    private fun scaled(durationMillis: Int, scale: Float): Int =
        if (scale <= 0f) 0 else (durationMillis * scale).toInt().coerceAtLeast(1)

    // -------------------------------------------------------------------------------------
    // Specs.
    //
    // Screens used to sprinkle `tween(180)` and `spring(0.55f, 380f)` through the tree, so no
    // call site obeyed the device's animation-scale setting. Every animated value in the app now
    // names one of these, and every one of them collapses to instant when the scale is 0:
    // `tween(0)` and `snap()` are the "no animation" specs, so reduced motion degrades the tree
    // rather than merely shortening it.
    // -------------------------------------------------------------------------------------

    /** True when the device asks for no animation at all. Guard any per-frame driver on this. */
    @Composable
    fun reduced(): Boolean = durationScale() <= 0f

    /** A scaled, typed tween for `animate*AsState` and enter/exit transitions. */
    @Composable
    fun <T> tweenSpec(
        durationMillis: Int = STANDARD,
        easing: Easing = Exit,
        delayMillis: Int = 0
    ): FiniteAnimationSpec<T> = tween(
        durationMillis = scaled(durationMillis),
        delayMillis = if (delayMillis <= 0) 0 else scaled(delayMillis),
        easing = easing
    )

    /** A settling spring; snaps straight to the target under reduced motion. */
    @Composable
    fun <T> springSpec(
        dampingRatio: Float = SpringBouncy,
        stiffness: Float = SpringStiffness
    ): FiniteAnimationSpec<T> =
        if (durationScale() <= 0f) snap() else spring(dampingRatio = dampingRatio, stiffness = stiffness)

    /**
     * The stagger step of a cascade, already scaled: screens add `index * stagger` as a delay so a
     * board assembles itself, and under reduced motion the whole cascade lands on frame one.
     */
    @Composable
    fun stagger(index: Int, step: Int = INSTANT): Int =
        scaled((index.coerceIn(0, MAX_STAGGER) * step))

    /** Number of cascade slots worth delaying; past it everything arrives together. */
    const val MAX_STAGGER = 8

    // ---- Enter / exit vocabulary --------------------------------------------------------

    @Composable
    fun fadeEnter(durationMillis: Int = SHORT, delayMillis: Int = 0): EnterTransition =
        fadeIn(tweenSpec<Float>(durationMillis, Enter, delayMillis))

    @Composable
    fun fadeExit(durationMillis: Int = QUICK): ExitTransition =
        fadeOut(tweenSpec<Float>(durationMillis, Exit))

    @Composable
    fun scaleEnter(initialScale: Float = 0.94f, durationMillis: Int = STANDARD): EnterTransition =
        scaleIn(tweenSpec<Float>(durationMillis, Emphasis), initialScale)

    @Composable
    fun scaleExit(targetScale: Float = 0.96f, durationMillis: Int = QUICK): ExitTransition =
        scaleOut(tweenSpec<Float>(durationMillis, Exit), targetScale)

    @Composable
    fun slideEnterVertically(durationMillis: Int = STANDARD, delayMillis: Int = 0, offset: (Int) -> Int): EnterTransition =
        slideInVertically(tweenSpec<IntOffset>(durationMillis, Enter, delayMillis), offset)

    @Composable
    fun slideEnterHorizontally(durationMillis: Int = STANDARD, delayMillis: Int = 0, offset: (Int) -> Int): EnterTransition =
        slideInHorizontally(tweenSpec<IntOffset>(durationMillis, Enter, delayMillis), offset)

    @Composable
    fun slideExitVertically(durationMillis: Int = QUICK, offset: (Int) -> Int): ExitTransition =
        slideOutVertically(tweenSpec<IntOffset>(durationMillis, Exit), offset)

    @Composable
    fun slideExitHorizontally(durationMillis: Int = QUICK, offset: (Int) -> Int): ExitTransition =
        slideOutHorizontally(tweenSpec<IntOffset>(durationMillis, Exit), offset)
}
