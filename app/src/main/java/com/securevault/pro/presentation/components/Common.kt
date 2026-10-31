package com.securevault.pro.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.diagnostics.CrashLogger
import com.securevault.pro.security.keyboard.SecureIme
import com.securevault.pro.presentation.theme.HeaderStyle
import com.securevault.pro.presentation.theme.ThemeDesign
import com.securevault.pro.presentation.theme.VaultBackdrop
import com.securevault.pro.presentation.theme.VaultPalette
import com.securevault.pro.presentation.theme.VaultPaletteController
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultContrast
import com.securevault.pro.presentation.theme.VaultShapes
import com.securevault.pro.presentation.theme.VaultRadius
import com.securevault.pro.presentation.theme.VaultType


@Composable
fun NeonBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = VaultPaletteController.current
    val pulse by rememberInfiniteTransition(label = "backdrop").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "backdropPulse"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(palette.abyssDark, palette.abyssSurface, palette.abyssDark)
                )
            )
    ) {
        Canvas(
            modifier = if (palette.backdropBlur > 0.dp && !CrashLogger.safeRender) {
                Modifier
                    .fillMaxSize()
                    .blur(palette.backdropBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            } else {
                Modifier.fillMaxSize()
            }
        ) {
            val intensity = palette.meshIntensity
            val lineColor = if (palette.isLight) {
                palette.textPrimary.copy(alpha = 0.035f)
            } else {
                palette.textPrimary.copy(alpha = 0.028f)
            }
            if (palette.design == ThemeDesign.GENERATIVE) {
                // Clean adaptive gradient: no grid, no blobs — one surface that
                // drifts slowly with content instead of a busy mesh.
                val drift = sin(pulse * PI).toFloat()
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            palette.cyan.copy(alpha = 0.13f * intensity),
                            Color.Transparent,
                            palette.indigo.copy(alpha = 0.11f * intensity),
                            palette.lavender.copy(alpha = 0.07f * intensity)
                        ),
                        start = Offset(
                            size.width * (0.02f + 0.18f * drift),
                            -size.height * 0.10f
                        ),
                        end = Offset(
                            size.width * (1.02f - 0.22f * drift),
                            size.height * 1.08f
                        )
                    )
                )
                val glowCenter = Offset(
                    size.width * (0.76f - 0.12f * drift),
                    size.height * (0.10f + 0.08f * drift)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.cyan.copy(alpha = 0.12f * intensity),
                            Color.Transparent
                        ),
                        center = glowCenter,
                        radius = size.width * 0.95f
                    ),
                    radius = size.width * 0.95f,
                    center = glowCenter
                )
            } else when (palette.backdrop) {
                VaultBackdrop.PULSE_MESH -> {
                    backdropGrid(lineColor.copy(alpha = 0.6f), 64.dp.toPx())
                    val drift = sin(pulse * PI).toFloat()
                    backdropGlow(
                        color = palette.cyan,
                        alpha = 0.13f * intensity,
                        center = Offset(
                            size.width * (0.88f - drift * 0.22f),
                            size.height * (0.04f + drift * 0.14f)
                        ),
                        radius = size.width * 0.85f
                    )
                    backdropGlow(
                        color = palette.indigo,
                        alpha = 0.11f * intensity,
                        center = Offset(
                            size.width * (0.08f + drift * 0.28f),
                            size.height * (0.62f - drift * 0.12f)
                        ),
                        radius = size.width * 0.78f
                    )
                    backdropGlow(
                        color = palette.lavender,
                        alpha = 0.07f * intensity,
                        center = Offset(size.width * 0.45f, size.height * 1.02f),
                        radius = size.width * 0.7f
                    )
                }
                VaultBackdrop.AURORA_MESH -> {
                    backdropGrid(lineColor, 56.dp.toPx())
                    backdropGlow(
                        palette.cyan,
                        0.10f * intensity,
                        Offset(size.width * 0.92f, size.height * 0.02f),
                        size.width * 0.75f
                    )
                    backdropGlow(
                        palette.indigo,
                        0.09f * intensity,
                        Offset(-size.width * 0.1f, size.height * 0.7f),
                        size.width * 0.75f
                    )
                }
                VaultBackdrop.NEON_MESH -> {
                    backdropGrid(palette.cyan.copy(alpha = 0.045f * intensity), 44.dp.toPx())
                    backdropGlow(
                        palette.coral,
                        0.13f * intensity,
                        Offset(size.width * 0.9f, size.height * 0.12f),
                        size.width * 0.85f
                    )
                    backdropGlow(
                        palette.cyan,
                        0.13f * intensity,
                        Offset(size.width * 0.05f, size.height * 0.55f),
                        size.width * 0.8f
                    )
                    backdropGlow(
                        palette.indigo,
                        0.10f * intensity,
                        Offset(size.width * 0.55f, size.height * 0.95f),
                        size.width * 0.7f
                    )
                    val scanStep = 5.dp.toPx()
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            color = palette.textPrimary.copy(alpha = 0.009f * intensity),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += scanStep
                    }
                }
                VaultBackdrop.CALM -> {
                    backdropGlow(
                        palette.cyan,
                        0.10f,
                        Offset(size.width * 0.82f, size.height * 0.05f),
                        size.width * 0.9f
                    )
                    backdropGlow(
                        palette.indigo,
                        0.06f,
                        Offset(size.width * 0.1f, size.height * 0.85f),
                        size.width * 0.8f
                    )
                }
                VaultBackdrop.FLAT_GRID -> {
                    backdropGrid(lineColor, 72.dp.toPx())
                    backdropGlow(
                        palette.cyan,
                        0.05f * intensity,
                        Offset(size.width * 0.82f, size.height * 0.08f),
                        size.width * 0.9f
                    )
                }
                VaultBackdrop.HARD_GRID -> {
                    val ink = palette.textPrimary.copy(alpha = 0.075f * intensity)
                    val step = 28.dp.toPx()
                    val dot = 1.4.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        var y = 0f
                        while (y < size.height) {
                            drawCircle(ink, dot, Offset(x, y))
                            y += step
                        }
                        x += step
                    }
                }
                VaultBackdrop.SOFT_CLAY -> {
                    backdropGlow(
                        palette.lavender,
                        0.16f * intensity,
                        Offset(size.width * 0.88f, size.height * 0.08f),
                        size.width * 0.8f
                    )
                    backdropGlow(
                        palette.cyan,
                        0.12f * intensity,
                        Offset(size.width * 0.05f, size.height * 0.45f),
                        size.width * 0.85f
                    )
                    backdropGlow(
                        palette.coral,
                        0.11f * intensity,
                        Offset(size.width * 0.55f, size.height * 0.96f),
                        size.width * 0.7f
                    )
                    backdropGlow(
                        palette.glassHighlight,
                        0.18f * intensity,
                        Offset(size.width * 0.3f, size.height * 0.2f),
                        size.width * 0.5f
                    )
                }
                VaultBackdrop.TEXTURED -> {
                    val step = 4.dp.toPx()
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            color = palette.textPrimary.copy(alpha = 0.013f * intensity),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += step
                    }
                    backdropGlow(
                        palette.amber,
                        0.06f * intensity,
                        Offset(size.width * 0.85f, size.height * 0.05f),
                        size.width * 0.75f
                    )
                    backdropGlow(
                        palette.surfaceShade,
                        if (palette.isLight) 0.03f else 0.16f * intensity,
                        Offset(size.width * 0.1f, size.height * 0.95f),
                        size.width * 0.9f
                    )
                }
            }
        }
        content()
    }
}

private fun DrawScope.backdropGrid(color: Color, step: Float, strokeWidth: Float = 1f) {
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
        y += step
    }
}

private fun DrawScope.backdropGlow(
    color: Color,
    alpha: Float,
    center: Offset,
    radius: Float
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}


/** Horizontal gutter the active theme asks every screen to respect. */
@Composable
fun vaultGutter(): Dp = VaultPaletteController.current.screenGutter

// ---------------------------------------------------------------------------------------------
// Icon-plate geometry.
//
// The generated board (`tools/brand-icons/build.mjs`) bakes one squircle into every drawable:
// a corner of 60 units inside a 256-unit plate. `iconTileCorner` is that corner measured at the
// tile the board ships at (38.dp — see the token's doc in theme/Palette.kt). Every Kotlin tile
// has to reproduce the *same silhouette at its own size*, otherwise a 30.dp tile looks like a
// circle and a 48.dp tile looks like a square next to the vector plate. The ratio is taken from
// the palette token rather than hardcoded, so a palette that ever moves `iconTileCorner` moves
// the tiles with it, and the guard test in ThemeTokensTest keeps the token identical across every
// shipped theme.
// ---------------------------------------------------------------------------------------------

/** The tile size `iconTileCorner` is calibrated at, in dp. */
internal const val ICON_TILE_CALIBRATION_DP = 38f

/** The corner the generated vectors bake: 60 units of a 256-unit plate. */
internal const val BOARD_PLATE_CORNER_FRACTION = 60f / 256f

/**
 * The plate corner fraction a tile of any size should use, straight from the palette token.
 * Clamped to the range that still reads as the board's squircle, and to half the tile so a
 * degenerate token can never turn a plate into a lozenge.
 */
internal fun iconPlateCornerFraction(palette: VaultPalette): Float =
    (palette.iconTileCorner.value / ICON_TILE_CALIBRATION_DP).coerceIn(0.10f, 0.34f)

/** Pure, unit-free form of [iconPlateShape]: [tileDp] is whatever dp the tile is drawn at. */
internal fun iconPlateCornerDp(palette: VaultPalette, tileDp: Float): Float =
    (tileDp * iconPlateCornerFraction(palette)).coerceIn(0f, tileDp / 2f)

/**
 * The silhouette a [ThemedIconTile] and a generated plate share. Pass the tile's own size: the
 * radius tracks it, exactly like the vector's `viewportWidth`-relative corner does when the
 * drawable is scaled.
 */
fun iconPlateShape(palette: VaultPalette, size: Dp): Shape =
    RoundedCornerShape(iconPlateCornerDp(palette, size.value).dp)

@Composable
fun iconPlateShape(size: Dp): Shape = iconPlateShape(VaultPaletteController.current, size)

/**
 * The soft halo a theme's grammar asks for behind an icon plate, or 0.dp where it asks for none.
 *
 * A halo is painted *outside* the node's bounds by `Modifier.shadow`. That is exactly why it read
 * as a sliced highlight: the card around the tile clipped its own content layer to the card shape,
 * so 7.dp of clay glow was cut off at the plate's edge. `GlassCard` no longer clips the layer its
 * content rides on, so the halo has the tile's own padding to live in and stays whole. The neon
 * grammar paints its glow as an outline instead of a drop shadow, so it asks for none here.
 */
internal fun iconPlateHalo(palette: VaultPalette): Dp =
    if (palette.design == ThemeDesign.CLAYMORPHISM) 7.dp else 0.dp

// ---------------------------------------------------------------------------------------------
// Dialog fitting.
//
// A Material3 AlertDialog lays its slots out as one non-scrolling Column
// (icon / title / text / buttons). Give it more content than the window has and the WindowManager
// clips the bottom of the surface — which is the bottom button row. So each slot gets a bounded,
// scrollable height, and the two heights plus the dialog's own chrome are split out of the real
// screen height by [dialogHeightBudgetDp], a pure function the guard test pins. Nothing here
// changes what a dialog decides; it only decides how much of the screen a dialog may claim.
// ---------------------------------------------------------------------------------------------

/** Title padding, text padding and the button row an AlertDialog adds around its slots, in dp. */
internal const val DIALOG_CHROME_DP = 92f

/** Share of the screen a dialog may claim in total; the rest stays scrim so it reads as a dialog. */
internal const val DIALOG_SCREEN_BUDGET = 0.90f

/** How the claimable height splits between the scrolling body and the title. */
internal const val DIALOG_BODY_SHARE = 0.74f

/** Hard ceilings so a tablet gets a readable dialog instead of a body a metre tall. */
internal const val DIALOG_BODY_CEILING_DP = 400f
internal const val DIALOG_TITLE_CEILING_DP = 120f

data class DialogHeightBudget(
    val bodyMaxDp: Float,
    val titleMaxDp: Float,
    /** Body + title + the dialog's fixed chrome: what the surface actually costs on screen. */
    val totalMaxDp: Float
)

/**
 * Split a screen height into the parts a dialog may claim. Upper clamps only, so
 * `totalMaxDp <= screenHeightDp * DIALOG_SCREEN_BUDGET` holds for every input.
 */
internal fun dialogHeightBudgetDp(screenHeightDp: Float): DialogHeightBudget {
    val usable = (screenHeightDp * DIALOG_SCREEN_BUDGET - DIALOG_CHROME_DP).coerceAtLeast(0f)
    val body = (usable * DIALOG_BODY_SHARE).coerceAtMost(DIALOG_BODY_CEILING_DP)
    val title = (usable * (1f - DIALOG_BODY_SHARE)).coerceAtMost(DIALOG_TITLE_CEILING_DP)
    return DialogHeightBudget(body, title, body + title + DIALOG_CHROME_DP)
}

/** Widest a dialog surface may get, in dp, on a screen [screenWidthDp] wide. */
internal fun dialogWidthCapDp(screenWidthDp: Float, preferredCapDp: Float = 420f): Float =
    (screenWidthDp - 24f).coerceIn(200f, preferredCapDp)

@Composable
fun dialogHeightBudget(): DialogHeightBudget =
    dialogHeightBudgetDp(LocalConfiguration.current.screenHeightDp.toFloat())

/**
 * Bounded + scrollable body for a dialog slot. Apply it to the `text` slot of an AlertDialog (or
 * to the single child of a `Dialog`): the content can never exceed the screen, and whatever does
 * not fit is reachable by scrolling — never clipped.
 */
@Composable
fun Modifier.dialogBody(
    budget: DialogHeightBudget = dialogHeightBudget()
): Modifier = this
    .heightIn(max = budget.bodyMaxDp.dp)
    .verticalScroll(rememberScrollState())

/** The same guarantee for a dialog title, which is otherwise the second thing that pushes buttons off. */
@Composable
fun Modifier.dialogTitle(
    budget: DialogHeightBudget = dialogHeightBudget()
): Modifier = this
    .heightIn(max = budget.titleMaxDp.dp)
    .verticalScroll(rememberScrollState())

/** Keeps a dialog inside the screen width instead of letting a long message stretch it. */
@Composable
fun Modifier.dialogWidth(preferredCapDp: Dp = 420.dp): Modifier = this.widthIn(
    max = dialogWidthCapDp(LocalConfiguration.current.screenWidthDp.toFloat(), preferredCapDp.value).dp
)

/**
 * A selected-state bed that is always fully visible: the fill and its rule are painted *inside*
 * the node's own bounds (clip, then fill, then rule, and only then the caller's padding), so no
 * rounded ancestor and no corner radius can cut a selected state. The lift a selected row gets is
 * colour and a heavier rule, never a translation that leaves the bounds.
 */
@Composable
fun Modifier.selectedBed(
    selected: Boolean,
    fillColor: Color,
    ruleColor: Color,
    shape: Shape = VaultShapes.chip,
    ruleWidth: Dp = VaultPaletteController.current.cardBorderWidth
): Modifier = this.then(
    if (selected) {
        Modifier
            .clip(shape)
            .background(fillColor, shape)
            .border(ruleWidth.coerceAtLeast(1.dp), ruleColor, shape)
    } else {
        Modifier.clip(shape)
    }
)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    accent: Color = VaultColor.accent,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    revealIndex: Int = -1,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = VaultPaletteController.current
    val shape = palette.cardShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background: Brush = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> Brush.linearGradient(
            listOf(palette.abyssSurface, palette.abyssSurface)
        )
        ThemeDesign.CLAYMORPHISM -> Brush.linearGradient(
            listOf(
                palette.abyssSurface,
                palette.abyssElevated.copy(alpha = 0.92f),
                accent.copy(alpha = 0.08f)
            )
        )
        ThemeDesign.AGENTIC, ThemeDesign.BENTO -> Brush.linearGradient(
            listOf(palette.abyssSurface, palette.abyssSurface.copy(alpha = 0.97f))
        )
        ThemeDesign.NEO_SKEUOMORPHIC -> Brush.verticalGradient(
            listOf(
                palette.abyssElevated,
                palette.abyssSurface,
                palette.abyssDark.copy(alpha = 0.92f)
            )
        )
        else -> if (palette.cardGradient) {
            Brush.linearGradient(
                colors = listOf(
                    palette.abyssGlass.copy(alpha = palette.cardFillAlpha),
                    palette.abyssSurface.copy(alpha = 0.86f),
                    accent.copy(alpha = if (palette.glow) 0.07f else 0.04f)
                ),
                start = Offset.Zero,
                end = Offset(900f, 1200f)
            )
        } else {
            Brush.linearGradient(
                listOf(palette.abyssElevated.copy(alpha = palette.cardFillAlpha), palette.abyssElevated)
            )
        }
    }
    val softBorder = when {
        palette.design == ThemeDesign.CLAYMORPHISM -> palette.glassHighlight.copy(alpha = 0.5f)
        palette.design == ThemeDesign.LIQUID_GLASS ->
            (borderColor ?: palette.glassBorder).copy(alpha = 0.85f)
        else -> borderColor ?: palette.outlineColor
    }
    val pressShift by animateDpAsState(
        targetValue = if (pressed && palette.pressDepth > 0.dp) palette.pressDepth else 0.dp,
        animationSpec = tween(90),
        label = "cardPress"
    )
    val pressScale by animateFloatAsState(
        // A pressed card shrinks a hair; a selected card lifts its shadow instead of moving,
        // so a selected state never has to leave the card's own bounds to be seen.
        targetValue = when {
            selected -> 1.0f
            pressed && palette.pressDepth <= 0.dp -> 0.985f
            else -> 1f
        },
        animationSpec = tween(110),
        label = "cardScale"
    )
    val selectionTint = accent.copy(alpha = if (palette.isLight) 0.10f else 0.14f)
    val ruleColor = if (selected) accent.copy(alpha = 0.72f) else softBorder
    val ruleWidth = when {
        selected -> palette.cardBorderWidth.coerceAtLeast(1.5.dp)
        else -> palette.cardBorderWidth
    }

    val reveal = if (revealIndex >= 0) Modifier.revealEntry(revealIndex) else Modifier
    Box(
        modifier = modifier
            .then(reveal)
            .offset(y = pressShift)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        propagateMinConstraints = true
    ) {
        // The plate is the only clipped layer, and it is a sibling *behind* the content. When the
        // card itself clipped, every selection bed and tint a screen drew near a corner was cut by
        // the card's own radius — the reported "highlight drawn outside its bounds and chopped".
        // Content is now free to paint its full rectangle; the plate keeps the silhouette.
        Box(
            modifier = Modifier
                .matchParentSize()
                .vaultShadow(palette, shape, accent, selected)
                .clip(shape)
                .background(background)
        ) {
            if (selected) {
                Box(Modifier.matchParentSize().background(selectionTint))
            }
            if (palette.embossed) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    palette.surfaceOverlay.copy(alpha = if (palette.isLight) 0.45f else 0.10f),
                                    Color.Transparent,
                                    palette.surfaceShade.copy(alpha = if (palette.isLight) 0.04f else 0.10f)
                                )
                            )
                        )
                )
            }
            if (palette.sheen) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    palette.surfaceOverlay.copy(alpha = 0.09f),
                                    Color.Transparent,
                                    palette.surfaceOverlay.copy(alpha = 0.035f)
                                )
                            )
                        )
                )
            }
            if (palette.cardHighlight) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(accent.copy(alpha = 0.45f), Color.Transparent)
                            )
                        )
                )
            }
        }
        // The rule rides on top of everything, unclipped by the content layer, so a selected card's
        // outline is complete on all four sides.
        Box(
            Modifier
                .matchParentSize()
                .border(ruleWidth, ruleColor, shape)
        )
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

private fun Modifier.vaultShadow(
    palette: VaultPalette,
    shape: Shape,
    accent: Color,
    lifted: Boolean = false
): Modifier {
    if (palette.cardShadow <= 0.dp && !lifted) return this
    val glow = palette.glow
    val base = palette.cardShadow.coerceAtLeast(0.dp)
    return shadow(
        elevation = if (lifted) (base + 4.dp).coerceAtLeast(6.dp) else base,
        shape = shape,
        // The plate layer clips itself right after this; the shadow is left free so the card's own
        // drop shadow is not stuffed inside its silhouette.
        clip = false,
        ambientColor = accent.copy(alpha = if (glow) 0.35f else 0.10f),
        spotColor = accent.copy(alpha = if (glow) 0.42f else 0.14f)
    )
}


/** Hairline that separates rows inside a card, tinted for the active palette. */
@Composable
fun VaultDivider(modifier: Modifier = Modifier, alpha: Float = 0.08f) {
    val palette = VaultPaletteController.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.surfaceOverlay.copy(alpha = alpha))
    )
}

/**
 * Inset panel modifier for the chips, logs and preview strips that used to hardcode
 * a white wash — on a light palette that wash is invisible, so it takes the ink now.
 */
@Composable
fun Modifier.overlayPanel(
    alpha: Float = 0.05f,
    shape: Shape = VaultShapes.input
): Modifier {
    val palette = VaultPaletteController.current
    return this.background(palette.surfaceOverlay.copy(alpha = alpha), shape)
}

/**
 * Staggered first-appearance reveal for cards and rows, so a screen assembles itself
 * instead of cutting in. `index` orders the cascade; keep it small per screen.
 */
@Composable
fun Modifier.revealEntry(index: Int = 0, travel: Dp = 18.dp): Modifier {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val delay = (index * 55).coerceAtMost(385)
    val fade by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(300, delayMillis = delay),
        label = "revealAlpha"
    )
    val lift by animateDpAsState(
        if (shown) 0.dp else travel,
        tween(360, delayMillis = delay, easing = FastOutSlowInEasing),
        label = "revealLift"
    )
    return this.alpha(fade).offset(y = lift)
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val palette = VaultPaletteController.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (palette.uppercaseLabels) title.uppercase() else title,
            style = MaterialTheme.typography.titleMedium,
            color = VaultColor.textStrong,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = if (palette.uppercaseLabels) actionLabel.uppercase() else actionLabel,
                style = MaterialTheme.typography.labelLarge,
                // The action is readable text, so it uses the AA-guarded accent ink.
                color = VaultColor.accentInk,
                modifier = Modifier
                    .clip(palette.chipShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: Glyph? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    val shape = palette.buttonShape
    val brutal = palette.design == ThemeDesign.NEO_BRUTALIST
    val background: Brush = when {
        !enabled -> Brush.horizontalGradient(
            listOf(
                palette.abyssElevated.copy(alpha = 0.9f),
                palette.abyssElevated.copy(alpha = 0.6f)
            )
        )
        brutal -> Brush.horizontalGradient(listOf(palette.amber, palette.amber))
        palette.gradientButtons -> Brush.horizontalGradient(listOf(palette.cyan, palette.indigo))
        else -> Brush.horizontalGradient(listOf(palette.cyan, palette.cyan))
    }
    val contentColor = when {
        !enabled -> VaultColor.textFaint
        brutal -> palette.textPrimary
        palette.design == ThemeDesign.AGENTIC -> VaultColor.onAccent
        else -> VaultColor.onAccent
    }
    val borderWidth = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> 2.dp
        ThemeDesign.LIQUID_GLASS, ThemeDesign.CYBERPUNK -> 1.dp
        else -> 0.dp
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressShift by animateDpAsState(
        targetValue = if (pressed && palette.pressDepth > 0.dp) palette.pressDepth else 0.dp,
        animationSpec = tween(90),
        label = "buttonPress"
    )
    // A held button sinks a hair under the finger: the plate scales down and dims slightly, so the
    // press reads on every theme, including the flat ones where the shadow is invisible.
    val held = pressed && enabled && !loading
    val buttonScale by animateFloatAsState(
        targetValue = if (held) 0.97f else 1f,
        animationSpec = tween(120),
        label = "buttonScale"
    )
    val buttonAlpha by animateFloatAsState(
        targetValue = if (held) 0.94f else 1f,
        animationSpec = tween(120),
        label = "buttonAlpha"
    )
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                    alpha = buttonAlpha
                }
                .offset(y = pressShift)
                .vaultShadow(palette, shape, palette.cyan)
                .clip(shape)
                .background(background)
                .border(borderWidth, palette.outlineColor, shape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled && !loading,
                    onClick = onClick
                )
                .heightIn(min = if (brutal) 56.dp else 54.dp)
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                ThemedIcon(
                    glyph = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = if (palette.uppercaseLabels) text.uppercase() else text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: Glyph? = null,
    enabled: Boolean = true,
    accent: Color = VaultColor.textBody,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    val shape = palette.buttonShape
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .vaultShadow(palette, shape, accent)
            .clip(shape)
            .background(
                if (palette.design == ThemeDesign.NEO_BRUTALIST) {
                    palette.abyssSurface
                } else {
                    palette.abyssElevated.copy(alpha = 0.55f)
                }
            )
            .border(palette.cardBorderWidth, palette.outlineColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            ThemedIcon(
                glyph = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (palette.uppercaseLabels) text.uppercase() else text,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassIconButton(
    icon: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = VaultColor.textBody,
    background: Color = VaultColor.elevated.copy(alpha = 0.7f),
    size: Dp = 40.dp
) {
    val palette = VaultPaletteController.current
    val shape = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> palette.chipShape
        ThemeDesign.BENTO -> VaultShapes.input
        ThemeDesign.NEO_SKEUOMORPHIC -> VaultShapes.input
        else -> CircleShape
    }
    Box(
        modifier = modifier
            .size(size)
            .vaultShadow(palette, shape, tint)
            .clip(shape)
            .background(background)
            .border(palette.cardBorderWidth.coerceAtLeast(1.dp), palette.outlineColor, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            glyph = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.48f)
        )
    }
}


/**
 * The plate a [ThemedIconTile] actually paints, composited onto the card it sits on.
 *
 * A gradient across a 38.dp tile is one flat swatch to the eye, so the two stops are averaged
 * after compositing. This is the colour every icon-ink measurement is taken against — in the
 * component and in `ThemeTokensTest` — which is why the tile and the test cannot drift apart.
 */
internal fun iconPlateColor(palette: VaultPalette, accent: Color, background: Color): Color {
    val card = VaultColor.surface
    val lift = VaultColor.surfaceLift
    val stops: Pair<Color, Color> = when (palette.design) {
        ThemeDesign.GENERATIVE ->
            accent.copy(alpha = 0.30f) to palette.indigo.copy(alpha = 0.12f)
        ThemeDesign.NEO_BRUTALIST -> accent to accent
        ThemeDesign.CLAYMORPHISM -> lift.copy(alpha = 0.42f) to background
        ThemeDesign.NEO_SKEUOMORPHIC -> lift.copy(alpha = 0.20f) to background.copy(alpha = 0.30f)
        ThemeDesign.LIQUID_GLASS -> lift.copy(alpha = 0.22f) to accent.copy(alpha = 0.10f)
        else -> background to background
    }
    val first = VaultContrast.composite(stops.first, card)
    val second = VaultContrast.composite(stops.second, card)
    return Color(
        red = (first.red + second.red) / 2f,
        green = (first.green + second.green) / 2f,
        blue = (first.blue + second.blue) / 2f,
        alpha = 1f
    )
}

@Composable
fun ThemedIconTile(
    icon: Glyph,
    contentDescription: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = size * 0.5f,
    background: Color = accent.copy(alpha = 0.14f)
) {
    val palette = VaultPaletteController.current
    // One silhouette, every theme, at every tile size. The plate corner is the palette token
    // (iconTileCorner — the same 9.dp in every shipped palette, derived from the board's
    // baked 60/256 squircle at the 38.dp tile the board ships at), scaled by the size this
    // tile is actually drawn at. A fixed 9.dp made a 30.dp plate look round and a 48.dp plate
    // look square next to the generated vector, whose corner tracks its own viewport. No
    // per-design geometry is allowed here: theme identity lives in the colours below, never in
    // the shape of the tile.
    val shape: Shape = iconPlateShape(palette, size)
    val fill: Brush = when (palette.design) {
        ThemeDesign.GENERATIVE -> Brush.linearGradient(
            listOf(accent.copy(alpha = 0.30f), palette.indigo.copy(alpha = 0.12f))
        )
        ThemeDesign.NEO_BRUTALIST -> Brush.linearGradient(listOf(accent, accent))
        ThemeDesign.CLAYMORPHISM -> Brush.verticalGradient(
            listOf(VaultColor.surfaceLift.copy(alpha = 0.42f), background)
        )
        ThemeDesign.NEO_SKEUOMORPHIC -> Brush.verticalGradient(
            listOf(VaultColor.surfaceLift.copy(alpha = 0.20f), background.copy(alpha = 0.30f))
        )
        ThemeDesign.LIQUID_GLASS -> Brush.linearGradient(
            listOf(VaultColor.surfaceLift.copy(alpha = 0.22f), accent.copy(alpha = 0.10f))
        )
        else -> Brush.linearGradient(listOf(background, background))
    }
    val borderColor = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> palette.textPrimary
        ThemeDesign.CLAYMORPHISM -> VaultColor.surfaceLift.copy(alpha = 0.55f)
        ThemeDesign.CYBERPUNK -> accent.copy(alpha = 0.85f)
        else -> if (palette.hardOutline) palette.textPrimary else accent.copy(alpha = 0.26f)
    }
    val borderWidth = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> 2.dp
        ThemeDesign.CLAYMORPHISM -> 2.dp
        ThemeDesign.CYBERPUNK -> 1.dp
        else -> if (palette.hardOutline) 2.dp else palette.cardBorderWidth
    }
    val tint = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> palette.abyssDark
        ThemeDesign.AGENTIC -> palette.textSecondary
        else -> accent
    }
    // The one contrast promise the tile makes: the glyph clears AA on the plate it actually
    // paints, not on the raw brand fill. A translucent accent over a white card is a pale plate,
    // and a vivid accent on it measured 2.7:1 on Piano Glass — legible as a swatch, muddy as a
    // symbol. `ensure` moves the ink only as far as that needs, so the fills stay vivid.
    val glyphInk = VaultContrast.ensure(tint, iconPlateColor(palette, accent, background))
    val halo = iconPlateHalo(palette)
    val boxModifier = modifier
        .size(size)
        .then(
            if (halo > 0.dp) {
                Modifier.shadow(
                    elevation = halo,
                    shape = shape,
                    clip = false,
                    ambientColor = accent.copy(alpha = 0.45f),
                    spotColor = accent.copy(alpha = 0.55f)
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(fill)
        .border(borderWidth, borderColor, shape)
    Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
        ThemedIcon(
            glyph = icon,
            contentDescription = contentDescription,
            tint = glyphInk,
            modifier = Modifier.size(iconSize)
        )
        // Per-design icon grammar so the same module never looks identical
        // across two themes.
        when (palette.design) {
            // Plain Material themes wear no badge; the glyph stands on its own.
            ThemeDesign.NEUTRAL -> Unit
            ThemeDesign.CLAYMORPHISM -> Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(size * 0.16f)
                    .size(size * 0.15f)
                    .background(VaultColor.surfaceLift.copy(alpha = 0.7f), CircleShape)
            )
            ThemeDesign.NEO_BRUTALIST -> Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.24f)
                    .background(accent.copy(alpha = 0.55f), shape)
            )
            ThemeDesign.CYBERPUNK -> Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = size * 0.10f)
                    .fillMaxWidth(0.46f)
                    .height(1.5.dp)
                    .background(accent.copy(alpha = 0.95f))
            )
            ThemeDesign.NEO_SKEUOMORPHIC -> Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = size * 0.09f)
                    .fillMaxWidth(0.52f)
                    .height(1.dp)
                    .background(VaultColor.surfaceLift.copy(alpha = 0.38f))
            )
            ThemeDesign.LIQUID_GLASS -> Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = size * 0.10f)
                    .fillMaxWidth(0.44f)
                    .height(1.dp)
                    .background(VaultColor.surfaceLift.copy(alpha = 0.65f))
            )
            ThemeDesign.BENTO -> Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(size * 0.14f)
                    .size(size * 0.12f)
                    .background(accent, CircleShape)
            )
            ThemeDesign.AGENTIC -> Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.30f)
                    .background(accent.copy(alpha = 0.16f), CircleShape)
            )
            ThemeDesign.GENERATIVE -> Unit
        }
    }
}


@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val palette = VaultPaletteController.current
    val gutter = palette.screenGutter
    when (palette.header) {
        // iOS: the title owns the centre of the bar, controls sit at the edges.
        HeaderStyle.CENTERED -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = gutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(44.dp)) {
                if (onBack != null) {
                    GlassIconButton(
                        icon = Glyph.arrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        tint = VaultColor.textStrong
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Row(
                modifier = Modifier.widthIn(min = 44.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
        // One UI / Funtouch: an oversized headline with air above and below it.
        HeaderStyle.LARGE_COMFORT -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = gutter, end = gutter, top = 22.dp, bottom = 14.dp)
        ) {
            if (onBack != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(
                        icon = Glyph.arrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        tint = VaultColor.textStrong
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = VaultType.screenTitle(),
                        color = VaultColor.textStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColor.textBody,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                actions()
            }
        }
        // EMUI / XOS: a tracked uppercase masthead ruled off from the content.
        HeaderStyle.TAB_UPPER -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = gutter, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    GlassIconButton(
                        icon = Glyph.arrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        tint = VaultColor.textStrong
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.6.sp,
                        color = VaultColor.textStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            color = VaultColor.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                actions()
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(palette.surfaceOverlay.copy(alpha = 0.12f))
            )
        }
        // MIUI / ColorOS-clean: compact bold title over its subtitle.
        HeaderStyle.INLINE_BOLD -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                GlassIconButton(
                    icon = Glyph.arrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    tint = VaultColor.textStrong
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (palette.uppercaseLabels) title.uppercase() else title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = if (palette.uppercaseLabels) subtitle.uppercase() else subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textBody,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            actions()
        }
    }
}

@Composable
fun GlassScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit
) {
    NeonBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                AppTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    actions = actions
                )
            },
            floatingActionButton = { fab?.invoke() }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxHeight()
                ) {
                    content(padding)
                }
            }
        }
    }
}

@Composable
fun MonogramBadge(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    textOverride: String? = null
) {
    val palette = remember(seed) {
        val index = (seed.hashCode() and 0x7FFFFFFF) % VaultColor.accentRamp.size
        val first = VaultColor.accentRamp[index]
        val second = VaultColor.accentRamp[(index + 2) % VaultColor.accentRamp.size]
        listOf(first, second)
    }
    val label = remember(seed, textOverride) {
        textOverride ?: seed.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "•" }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size.value * 0.32f))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        palette[0].copy(alpha = 0.92f),
                        palette[1].copy(alpha = 0.7f)
                    )
                )
            )
            .border(1.dp, VaultColor.surfaceLift.copy(alpha = 0.18f), RoundedCornerShape(size.value * 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(VaultColor.surfaceLift.copy(alpha = 0.18f), Color.Transparent)
                    )
                )
        )
        Text(
            text = label.take(2).uppercase(),
            color = VaultColor.background,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.34f).sp,
            letterSpacing = 0.sp
        )
    }
}

fun securityColor(score: Int): Color = when {
    score >= 80 -> VaultColor.credit
    score >= 60 -> VaultColor.accent
    score >= 40 -> VaultColor.warning
    score >= 20 -> VaultColor.alert
    else -> VaultColor.debit
}

fun strengthLabel(score: Int): String = when {
    score >= 80 -> "Fortress"
    score >= 60 -> "Strong"
    score >= 40 -> "Fair"
    score >= 20 -> "Weak"
    else -> "Critical"
}

@Composable
fun SecurityGauge(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 126.dp,
    stroke: Dp = 11.dp,
    caption: String = strengthLabel(score).uppercase()
) {
    val animated by animateFloatAsState(
        targetValue = (score / 100f).coerceIn(0f, 1f),
        animationSpec = tween(800),
        label = "gauge"
    )
    val color = securityColor(score)
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f + 3.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(
                color = VaultColor.textStrong.copy(alpha = 0.08f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round)
            )
            val sweep = 270f * animated
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(color.copy(alpha = 0.35f), color, color.copy(alpha = 0.9f))
                ),
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = color.copy(alpha = 0.14f),
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(strokePx * 2.1f, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = color,
                fontWeight = FontWeight.Black
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textBody
            )
        }
    }
}

@Composable
fun StrengthMeter(
    score: Int,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false
) {
    val active = when {
        score <= 0 -> 0
        score < 40 -> 1
        score < 60 -> 2
        score < 80 -> 3
        else -> 4
    }
    val color = securityColor(score)
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(VaultShapes.tag)
                        .background(
                            if (index < active) color else VaultColor.textStrong.copy(alpha = 0.12f)
                        )
                )
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${strengthLabel(score)} · $score/100",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun VaultTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: Glyph? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    textStyle: TextStyle? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onImeSubmit: (() -> Unit)? = null
) {
    // A submit handler turns the key into "Done" and fires on Enter/Done/Go, so the
    // keyboard can always complete the action the on-screen button would.
    val effectiveImeAction = if (onImeSubmit != null) ImeAction.Done else imeAction
    val effectiveKeyboardActions = if (onImeSubmit != null) {
        KeyboardActions(
            onDone = { onImeSubmit() },
            onGo = { onImeSubmit() },
            onSearch = { onImeSubmit() }
        )
    } else {
        keyboardActions
    }
    // A hardware Enter never raises an IME action, so consume the key event here;
    // swallowing it also keeps the field from submitting twice.
    val submitKeyModifier = if (onImeSubmit != null) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.Enter || event.key == Key.NumPadEnter)
            ) {
                onImeSubmit()
                true
            } else {
                false
            }
        }
    } else {
        Modifier
    }
    var visible by remember { mutableStateOf(false) }
    val palette = VaultPaletteController.current
    val effectiveTransformation = if (isPassword && !visible) {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    }
    val effectiveTrailing = if (isPassword) {
        {
            IconButton(onClick = { visible = !visible }) {
                ThemedIcon(
                    glyph = if (visible) Glyph.visibilityOff else Glyph.visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                    tint = VaultColor.textBody,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    } else {
        trailingIcon
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().then(submitKeyModifier),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = VaultColor.textFaint) } },
        leadingIcon = leadingIcon?.let {
            {
                ThemedIcon(
                    glyph = it,
                    contentDescription = null,
                    tint = if (isError) VaultColor.debit else VaultColor.textBody,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingIcon = effectiveTrailing,
        visualTransformation = effectiveTransformation,
        keyboardOptions = if (isPassword && keyboardType == KeyboardType.Text) {
            // Secrets get the hardened password options: no autocorrect, no candidate dictionary,
            // and the platform's password variation (which is also not-learned and not-suggested).
            SecureIme.secureKeyboardOptions(
                secret = true,
                imeAction = effectiveImeAction,
                multiLine = !singleLine
            )
        } else {
            KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = effectiveImeAction,
                // A secret must not reach the keyboard dictionary or candidate bar.
                autoCorrectEnabled = if (isPassword) false else true
            )
        },
        keyboardActions = effectiveKeyboardActions,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let {
            { Text(it, color = if (isError) VaultColor.debit else VaultColor.textFaint, style = MaterialTheme.typography.bodySmall) }
        },
        textStyle = textStyle ?: MaterialTheme.typography.bodyLarge,
        shape = palette.inputShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VaultColor.accent.copy(alpha = 0.7f),
            unfocusedBorderColor = VaultColor.hairline,
            disabledBorderColor = VaultColor.hairline.copy(alpha = 0.35f),
            errorBorderColor = VaultColor.debit,
            focusedContainerColor = VaultColor.elevated.copy(alpha = 0.8f),
            unfocusedContainerColor = VaultColor.elevated.copy(alpha = 0.45f),
            disabledContainerColor = VaultColor.surface.copy(alpha = 0.4f),
            cursorColor = VaultColor.accent,
            focusedLabelColor = VaultColor.accent,
            unfocusedLabelColor = VaultColor.textBody,
            errorLabelColor = VaultColor.debit,
            focusedTextColor = VaultColor.textStrong,
            unfocusedTextColor = VaultColor.textStrong,
            disabledTextColor = VaultColor.textFaint,
            focusedLeadingIconColor = VaultColor.accent,
            unfocusedLeadingIconColor = VaultColor.textBody,
            focusedTrailingIconColor = VaultColor.accent,
            unfocusedTrailingIconColor = VaultColor.textBody
        )
    )
}

@Composable
fun VaultSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search your vault"
) {
    val palette = VaultPaletteController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                color = VaultColor.textFaint,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            ThemedIcon(
                glyph = Glyph.search,
                contentDescription = null,
                tint = if (query.isNotEmpty()) VaultColor.accent else VaultColor.textBody,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    ThemedIcon(
                        glyph = Glyph.close,
                        contentDescription = "Clear search",
                        tint = VaultColor.textBody,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = palette.inputShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VaultColor.accent.copy(alpha = 0.6f),
            unfocusedBorderColor = VaultColor.hairline,
            focusedContainerColor = VaultColor.elevated.copy(alpha = 0.75f),
            unfocusedContainerColor = VaultColor.elevated.copy(alpha = 0.45f),
            cursorColor = VaultColor.accent,
            focusedTextColor = VaultColor.textStrong,
            unfocusedTextColor = VaultColor.textStrong
        )
    )
}

@Composable
fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null
) {
    val palette = VaultPaletteController.current
    val shape = when (palette.design) {
        ThemeDesign.NEO_BRUTALIST -> palette.chipShape
        ThemeDesign.BENTO -> VaultShapes.input
        ThemeDesign.CLAYMORPHISM -> VaultShapes.card
        else -> VaultRadius.pill()
    }
    val background by animateColorAsState(
        targetValue = if (selected) VaultColor.accent.copy(alpha = 0.16f) else VaultColor.elevated.copy(alpha = 0.55f),
        label = "pillBackground"
    )
    val borderColor = if (selected) VaultColor.accent.copy(alpha = 0.65f) else VaultColor.hairline
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(palette.cardBorderWidth.coerceAtLeast(1.dp), borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            ThemedIcon(
                glyph = Glyph.check,
                contentDescription = null,
                tint = VaultColor.accentInk,
                modifier = Modifier
                    .size(13.dp)
                    .padding(end = 4.dp)
            )
        }
        Text(
            text = if (palette.uppercaseLabels) label.uppercase() else label,
            style = MaterialTheme.typography.labelMedium,
            // Selected pills tint the surface with accent; raw accent text can sit
            // below AA there, so the ink always goes through the contrast guard.
            color = if (selected) VaultColor.accentInk else VaultColor.textBody,
            maxLines = 1
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) VaultColor.accentInk else VaultColor.textFaint
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: Glyph,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(VaultColor.accent.copy(alpha = 0.07f))
                .border(1.dp, VaultColor.hairline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ThemedIcon(
                glyph = icon,
                contentDescription = null,
                tint = VaultColor.accent,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = VaultColor.textStrong,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColor.textBody,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            GhostButton(text = actionLabel, onClick = onAction, accent = VaultColor.accent)
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: Int,
    icon: Glyph,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    GlassCard(
        modifier = modifier,
        accent = accent,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // An icon plate, so it takes the board's silhouette from the palette token instead of
            // the input-field radius it used to borrow (a 20.dp corner on a 34.dp tile).
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(iconPlateShape(VaultPaletteController.current, 34.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColor.textStrong,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun OperationProgressCard(
    title: String,
    detail: String,
    percent: Int,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    success: String? = null,
    error: String? = null
) {
    val accent = when {
        error != null -> VaultColor.debit
        success != null && !active -> VaultColor.credit
        else -> VaultColor.accent
    }
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        borderColor = accent.copy(alpha = 0.35f),
        accent = accent
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.4.dp,
                    color = accent
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = error ?: success ?: detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) VaultColor.debit else VaultColor.textBody,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { percent.coerceIn(0, 100) / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(VaultShapes.chip),
            color = accent,
            trackColor = VaultColor.textStrong.copy(alpha = 0.1f)
        )
    }
}

@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    altLabel: String? = null,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onAlt: () -> Unit = {},
    onDismiss: () -> Unit
) {
    if (!visible) return
    val palette = VaultPaletteController.current
    // Material3 composes an AlertDialog as one non-scrolling Column of slots, so a long message
    // pushes the confirm/dismiss row under the window edge and the WindowManager clips it: the
    // user sees a dialog they cannot answer. Title and body each get a bounded, scrollable slot
    // carved out of the real screen height, and the dialog's width is capped to the screen too.
    // What the dialog decides is untouched — only how it fits.
    val budget = dialogHeightBudget()
    // Every ink here is measured against the elevated bed the dialog really paints.
    val buttonInkBed = palette.abyssElevated
    val titleInk = VaultContrast.ensure(palette.textPrimary, buttonInkBed)
    val bodyInk = VaultContrast.ensure(palette.textSecondary, buttonInkBed)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dialogWidth(),
        containerColor = palette.abyssElevated,
        shape = palette.cardShape,
        titleContentColor = titleInk,
        textContentColor = bodyInk,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = titleInk,
                modifier = Modifier.dialogTitle(budget)
            )
        },
        text = {
            Column(modifier = Modifier.dialogBody(budget)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bodyInk,
                    lineHeight = 21.sp
                )
            }
        },
        confirmButton = {
            // Buttons sit on the dialog's elevated bed; their accent labels are
            // readable text, so each one is contrast-checked against that bed.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (altLabel != null) {
                    androidx.compose.material3.TextButton(onClick = onAlt) {
                        Text(
                            text = altLabel,
                            color = VaultContrast.ensure(palette.mint, buttonInkBed),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                androidx.compose.material3.TextButton(onClick = onConfirm) {
                    Text(
                        text = confirmLabel,
                        color = VaultContrast.ensure(
                            if (destructive) palette.coral else palette.cyan,
                            buttonInkBed
                        ),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = bodyInk)
            }
        }
    )
}

@Composable
fun ScrollableContent(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

fun formatShortDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Never"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 604_800_000L -> "${diff / 86_400_000L}d ago"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.roundToInt()} KB"
    val mb = kb / 1024.0
    return if (mb < 1024) String.format(java.util.Locale.US, "%.1f MB", mb)
    else String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}

fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Working late"
}

@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 360

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = VaultColor.textStrong,
    fontSize: Int = 14,
    letterSpacing: Float = 1f,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = fontSize.sp,
        letterSpacing = letterSpacing.sp,
        maxLines = maxLines,
        overflow = overflow
    )
}

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, accent: Color = VaultColor.accent) {
    val palette = VaultPaletteController.current
    // A tag's accent is decorative fill/border only; the text always goes through the
    // contrast guard so pastel accents never drop below AA on the card behind them.
    val ink = VaultContrast.ensure(accent, VaultColor.surface)
    Text(
        text = text,
        modifier = modifier
            .clip(palette.chipShape)
            .background(accent.copy(alpha = 0.10f))
            .border(palette.cardBorderWidth.coerceAtLeast(1.dp), accent.copy(alpha = 0.22f), palette.chipShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
