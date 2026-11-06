package com.securevault.pro.presentation.screens.welcome

/**
 * The single timing contract for the boot screen: how long the owner stays on it and when each
 * capability chip lands.
 *
 * The gate used to be 1.25 s, which handed over while the last chips were still fading in, so the
 * infographics were never readable. They now all land inside the first two thirds of a longer hold
 * and stay on screen for the remainder.
 */
object SplashTiming {
    /** How long the boot screen holds before the stage machine moves on. */
    const val HOLD_MS = 2_400L

    /** Duration of one chip's glide-in. */
    const val CHIP_RUN_MS = 420

    /** Number of capability chips the splash lays out. */
    const val CHIP_COUNT = 6

    /** The first chip starts after the vault mark has drawn enough of itself to be recognised. */
    private const val CHIP_FIRST_DELAY_MS = 300

    /** The gap between consecutive chips landing. */
    private const val CHIP_STAGGER_MS = 170

    fun chipDelayMs(index: Int): Int = CHIP_FIRST_DELAY_MS + index * CHIP_STAGGER_MS

    /** When the last chip finishes its glide-in; must stay inside [HOLD_MS]. */
    val LAST_CHIP_SETTLED_MS: Long = chipDelayMs(CHIP_COUNT - 1).toLong() + CHIP_RUN_MS
}
