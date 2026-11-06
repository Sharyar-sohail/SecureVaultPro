package com.securevault.pro.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Semantic type roles. Screens name the job a line of text has; only this file names a
 * size. The 15 Material roles below stay defined because third-party composables read them,
 * but app code should reach for [VaultType] instead.
 */
val VaultTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 54.sp,
        lineHeight = 58.sp,
        letterSpacing = (-1.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1).sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 30.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.sp
    )
)

/**
 * The roles a wallet actually draws: headings, body, captions, and money. Money gets its
 * own ramp because tabular figures are what let a balance column hold its rhythm — 12
 * rows of amounts line up only when digits share a width.
 *
 * [micro] is the floor: nothing in this app renders below 11sp any more, which retires the
 * 8.sp and 9.sp labels measured on ten screens.
 */
object VaultType {

    /** Portfolio total — the single biggest number in the app. */
    @Composable
    fun hero(): TextStyle = MaterialTheme.typography.displaySmall.tabular(weight = FontWeight.Bold)

    /** A balance or transaction amount, sized to its container. */
    @Composable
    fun amount(): TextStyle = MaterialTheme.typography.titleLarge.tabular(weight = FontWeight.SemiBold)

    @Composable
    fun amountCompact(): TextStyle = MaterialTheme.typography.titleMedium.tabular(weight = FontWeight.SemiBold)

    @Composable
    fun screenTitle(): TextStyle = MaterialTheme.typography.headlineMedium

    @Composable
    fun sectionTitle(): TextStyle = MaterialTheme.typography.titleMedium

    @Composable
    fun cardTitle(): TextStyle = MaterialTheme.typography.titleSmall

    @Composable
    fun body(): TextStyle = MaterialTheme.typography.bodyMedium

    @Composable
    fun caption(): TextStyle = MaterialTheme.typography.bodySmall

    /** The smallest type the app allows, for chip and dock labels. */
    @Composable
    fun micro(): TextStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp
    )

    @Composable
    fun mono(): TextStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp
    )

    private fun TextStyle.tabular(weight: FontWeight): TextStyle = copy(
        fontWeight = weight,
        fontFeatureSettings = "tnum",
        letterSpacing = (-0.4).sp
    )

    /** Alignment used beside an amount so units and signs never jump. */
    val amountAlignment = TextAlign.End
}

/**
 * The ceiling the app renders text at, whatever the owner's system accessibility slider says.
 *
 * Every ramp in this file is sized for the screen it lands on, and the auth surfaces are the tightest:
 * a 32.sp heading multiplied by a 1.4 system scale pushed the master-password form, its action button
 * and the secure board past the bottom of the window, which is how a login screen ends up unusable.
 * `MainActivity` clamps this onto the activity's configuration, so dp measurements, hit targets and the
 * keyboard keep their real size and only point-sized text stops inflating. Below the cap nothing
 * changes: an owner on 0.85 keeps their smaller text.
 */
const val MAX_FONT_SCALE = 1.0f
