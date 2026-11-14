package com.securevault.pro.presentation.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.presentation.components.BrandModuleIcon
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.components.VaultModule
import com.securevault.pro.presentation.theme.BoardRhythm
import com.securevault.pro.presentation.theme.LayoutTuning
import com.securevault.pro.presentation.theme.TileKind
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultContrast
import com.securevault.pro.presentation.theme.VaultLayout
import com.securevault.pro.presentation.theme.VaultMotion
import com.securevault.pro.presentation.theme.VaultPalette
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.fitCell
import com.securevault.pro.presentation.theme.easingFor
import com.securevault.pro.presentation.theme.motionFor
import com.securevault.pro.presentation.theme.pressedLiftFor
import com.securevault.pro.presentation.theme.pressedScaleFor
import com.securevault.pro.presentation.theme.pressedSinkFor
import com.securevault.pro.presentation.theme.pressSprings
import com.securevault.pro.presentation.theme.revealForTheme
import com.securevault.pro.presentation.theme.revealGlow
import com.securevault.pro.presentation.theme.revealSweep
import com.securevault.pro.presentation.theme.tileShape

/* =====================================================================================
 * THE HOME BOARD
 *
 * One `VaultLayout` per palette, and every one of them draws a different thing:
 *
 *   KIND            what the cell actually is
 *   PLATE           glyph above label, reserved count band — the resting grid plate
 *   FEATURE         double-width card: glyph seated in a tinted square, label + hint beside it,
 *                   count in a pill; no chevron
 *   ROW             one-line rail: bare glyph beside the label, count, trailing chevron
 *   BAR             closing bar: centred glyph + label + count, accent rule along its base
 *   MICRO           borderless tile: translucent wash, glyph above label, no card at all
 *
 * The 15 layouts are then separated on four independent axes, which `ThemeLayoutVarietyTest`
 * pins: column count, row shape (the span/kind partition), rhythm (gaps + plate heights) and
 * dock silhouette (`dockSpec`). The thirteen shipped modules resolve to these boards — read
 * `2F` as a two-column FEATURE, `4B` as a full-width BAR closing the pane:
 *
 *   CLASSIC    4c · 3 rows of 4 PLATE, then 1 BAR                · 8/8 dp  · flat bar
 *   BENTO      4c · 2F+P+P · 4P · 2P+P+P · 2P+2P · BAR           · 10/10 dp · item plates floated
 *   AGENTIC    4c · 4 rails · 2 rows of 4 MICRO · closing rail   · 6/6 dp  · edge-to-edge rail
 *   ADAPTIVE   2c · 2 rails · 5 PLATE pairs · closing feature    · 8/8 dp  · chamfered plates
 *   GENERATIVE 3c · 4 rows of 3 PLATE, then 1 BAR                · 12/12 dp · thickest float
 *   MOSAIC     3c · 6 rows of 2F+P, then 1 BAR                   · 9/7 dp  · hairline float
 *   LEDGER     2c · 6 ROW pairs, then a full-width rail          · 4/4 dp  · flush rail
 *   DUO        2c · 6 FEATURE pairs, then a full-width feature   · 14/12 dp · widest plates
 *   COMPACT    5c · 2 rows of 5 MICRO, then 2M+2M+1M             · 6/6 dp  · flush, 12% indicator
 *   DENSE      6c · 2 rows of 6 PLATE, then 1 BAR                · 4/4 dp  · flush, 10% indicator
 *   COLUMN     3c · opening rail, then 4 rows of 3 PLATE         · 10/8 dp · plates + 3 dp inset
 *   SPLIT      2c · 6 FEATURE/PLATE rows, then a wide feature    · 10/10 dp · 6 dp inset
 *   GALLERY    3c · 6 rows of P+2F, then 1 BAR                   · 8/14 dp · 5 dp inset
 *   STRIP      4c · opening rail, then 6 rows of 2F+2F           · 7/9 dp  · underline-thin indicator
 *   TRIAD      4c · 6 rows of 2F+2F, then 1 BAR                  · 12/12 dp · 7 dp float
 *
 * `boardGrid` guarantees the two things the owner's screenshots were missing: every row owns
 * exactly its pane's columns — no hole, no orphan square, and no spacer standing in for either —
 * and every cell is re-kinded for the width it ended up with, so a two-column cell draws a
 * FEATURE and never a stretched PLATE. Height is no longer demanded from inside the cell: the
 * board hands each row its share of the pane with a weight, and `fitCell` decides what that
 * height can actually pay for before anything is drawn, so nothing clips or overlaps.
 * =====================================================================================
 */

/** One module of the board, as the tile draws it. */
internal data class BoardItem(
    val module: VaultModule,
    val accent: Color,
    val route: String,
    val badge: Int
)

/**
 * The bed a cell paints, composited onto the card surface, so contrast is measured against what
 * the eye actually sees rather than against a raw translucent token.
 *
 * The floors are the owner's "no dim colours" rule: a wash thin enough to disappear reads as a
 * washed-out theme even when every ink passes AA, and the accent itself is pushed to the palette's
 * chroma floor before it tints anything.
 */
internal fun tileBed(palette: VaultPalette, kind: TileKind, accent: Color): Color {
    val vivid = VaultContrast.vibrant(accent)
    val tint = when (kind) {
        TileKind.PLATE -> palette.abyssElevated.copy(alpha = 0.72f)
        TileKind.FEATURE -> vivid.copy(alpha = if (palette.isLight) 0.20f else 0.26f)
        TileKind.BAR -> palette.abyssElevated.copy(alpha = 0.88f)
        TileKind.ROW -> palette.abyssElevated.copy(alpha = 0.62f)
        TileKind.MICRO -> Color.Transparent
    }
    return VaultContrast.composite(tint, palette.abyssSurface)
}

/** Label ink for a cell. Never a raw accent: pastel brand colour on a tinted bed is the pair that read as a smudge. */
internal fun tileLabelInk(palette: VaultPalette, accent: Color, tinted: Boolean): Color =
    if (tinted) VaultContrast.ensure(accent, tileBed(palette, TileKind.FEATURE, accent))
    else palette.textPrimary

/**
 * The secondary ink inside a tile — a hint, a count, a chevron. The screen-wide faint token is
 * deliberately not used here: it is tuned for a whole page of metadata and reads as grey mush at
 * the size a badge is drawn. This is the cell's own accent, held at AA-large on its own bed.
 */
internal fun tileQuietInk(accent: Color, bed: Color): Color =
    VaultContrast.ensure(VaultContrast.vibrant(accent), bed, VaultContrast.AA_LARGE)

/** The silhouette each kind is cut to. Geometry — not colour — is what separates the layouts. */
internal fun tileShapeOf(palette: VaultPalette, layout: VaultLayout, kind: TileKind): Shape =
    tileShape(palette, layout, kind)

/**
 * The owner's tile-size dial, applied as a draw-time multiplier rather than a layout request.
 *
 * Scaling the content block instead of re-measuring it is what keeps the dashboard's promise: the
 * grid still owns every row of the pane, the plate still earns its share of the height, and a
 * larger tile simply paints past its resting edges before the parent clips it — it never pushes a
 * row off the pane or forces the board to scroll. At the default 1f this adds no layer at all.
 */
private fun Modifier.contentScale(scale: Float): Modifier =
    if (scale == 1f) this
    else this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }

/**
 * Press feedback, hoisted into one modifier so no tile carries its own magic number.
 *
 * The *shape* of the answer is the theme's: [PressResponse] says whether a held plate shrinks,
 * loses its lift, sinks into the board or springs past its resting line, and only the shared
 * ladder decides how fast. Reduced motion still snaps everything straight back to rest.
 */
@Composable
private fun Modifier.tilePress(
    source: MutableInteractionSource,
    rest: Dp,
    palette: VaultPalette,
    shape: Shape = RoundedCornerShape(16.dp),
    accent: Color = palette.cyan
): Modifier {
    val pressed = source.collectIsPressedAsState().value
    val motion = motionFor(palette.id)
    val spring = pressSprings(motion.press)
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScaleFor(motion.press) else 1f,
        animationSpec = if (spring) {
            VaultMotion.springSpec<Float>()
        } else {
            VaultMotion.tweenSpec<Float>(VaultMotion.QUICK, easingFor(motion.curve))
        },
        label = "tilePressScale"
    )
    val lift by animateDpAsState(
        targetValue = if (pressed) rest * pressedLiftFor(motion.press) else rest,
        animationSpec = if (spring) {
            VaultMotion.springSpec<Dp>()
        } else {
            VaultMotion.tweenSpec<Dp>(VaultMotion.QUICK, easingFor(motion.curve))
        },
        label = "tilePressLift"
    )
    val sink by animateFloatAsState(
        targetValue = if (pressed) pressedSinkFor(motion.press) else 0f,
        animationSpec = VaultMotion.tweenSpec<Float>(VaultMotion.QUICK, easingFor(motion.curve)),
        label = "tilePressSink"
    )
    val isLight = palette.isLight
    val ambient = if (isLight) Color.Black.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.45f)
    val spot = accent.copy(alpha = if (isLight) 0.18f else 0.32f)

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = sink * density
        }
        .shadow(
            elevation = lift.coerceAtLeast(0.dp),
            shape = shape,
            clip = false,
            ambientColor = ambient,
            spotColor = spot
        )
}

@Composable
internal fun BoardTile(
    item: BoardItem,
    kind: TileKind,
    layout: VaultLayout,
    rhythm: BoardRhythm,
    tuning: LayoutTuning = LayoutTuning.DEFAULT,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (kind == TileKind.MICRO) {
        MicroCell(item, layout, rhythm, tuning, modifier, onClick)
        return
    }
    val palette = VaultPaletteController.current
    val source = remember { MutableInteractionSource() }
    val shape = tileShapeOf(palette, layout, kind)
    val bed = tileBed(palette, kind, item.accent)
    val labelInk = tileLabelInk(palette, item.accent, tinted = kind == TileKind.FEATURE)
    val countInk = tileQuietInk(item.accent, bed)
    val rest = restingElevation(layout, palette)

    Box(
        modifier = modifier
            // The bloom lives outside the clip so it can halo the plate; the sheen lives inside it
            // so it cannot cross the corner. Both are no-ops for a theme whose motion does not use them.
            .revealGlow(palette, shape)
            .tilePress(source, rest, palette, shape, item.accent)
            .clip(shape)
            .revealSweep(palette)
            // One flat bed per kind, hoisted into a two-stop brush so the tile cannot shimmer.
            .background(Brush.linearGradient(listOf(bed, bed)))
            .tileRule(palette, layout, kind, item.accent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) {
        // Antigravity top-edge specular glass reflection
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            if (palette.isLight) Color.White.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.22f),
                            item.accent.copy(alpha = if (palette.isLight) 0.40f else 0.25f),
                            if (palette.isLight) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Agentic rails read from their leading edge, adaptive plates from their top rule.
        if (layout == VaultLayout.AGENTIC || layout == VaultLayout.ADAPTIVE) {
            val ruleWidth = if (layout == VaultLayout.AGENTIC) 3.dp else 2.dp
            Box(
                Modifier
                    .align(if (layout == VaultLayout.AGENTIC) Alignment.CenterStart else Alignment.TopStart)
                    .then(
                        if (layout == VaultLayout.AGENTIC) {
                            Modifier.fillMaxHeight().width(ruleWidth)
                        } else {
                            Modifier.fillMaxWidth().height(ruleWidth)
                        }
                    )
                    .background(item.accent.copy(alpha = 0.8f))
            )
        }
        // One composition per kind. The silhouette changes with the kind, and so does the
        // internal arrangement, so a rail never renders as a plate with a different corner
        // radius — which is exactly the defect the owner rejected.
        when (kind) {
            TileKind.ROW -> RailCell(item, palette, rhythm, tuning, labelInk, countInk)
            TileKind.BAR -> ClosingBarCell(item, palette, rhythm, tuning, labelInk, countInk)
            TileKind.FEATURE -> FeatureCell(item, layout, palette, rhythm, tuning, labelInk, countInk)
            else -> PlateCell(item, layout, palette, rhythm, tuning, labelInk, countInk)
        }
    }
}

/** The cell label, one line, ellipsised rather than clipped — never shrunk per layout. */
private fun cellLabel(item: BoardItem, palette: VaultPalette): String =
    if (palette.uppercaseLabels) item.module.label.uppercase() else item.module.label

/**
 * ROW — a full-width intent rail. A bare glyph at the leading edge, one line of label, the
 * count (if any) and a trailing chevron. No stacked hint and no seated glyph plate: this cell
 * is a line of intent, not a card, which is what separates LEDGER and AGENTIC from the plate
 * boards. Its shape is the flat leading edge from `tileShape`.
 *
 * It measures the height it was given instead of asking for one: the board hands each row its
 * share of the pane with a weight, so a rail that insisted on 40 dp would be clipped on a short
 * row rather than growing it.
 */
@Composable
private fun RailCell(
    item: BoardItem,
    palette: VaultPalette,
    rhythm: BoardRhythm,
    tuning: LayoutTuning,
    labelInk: Color,
    countInk: Color
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val labelStyle = MaterialTheme.typography.titleSmall
        val fit = fitCell(
            kind = TileKind.ROW,
            height = maxHeight,
            icon = palette.moduleIconSize * tuning.iconScale,
            labelSize = labelStyle.fontSize,
            padV = palette.moduleTilePadV
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .contentScale(tuning.tileScale)
                .padding(
                    start = 8.dp,
                    end = 6.dp,
                    top = fit.padV,
                    bottom = fit.padV
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandModuleIcon(
                module = item.module,
                size = fit.iconSize,
                contentDescription = null
            )
            Spacer(Modifier.width(6.dp))
            val effectiveFontSize = if (fit.labelSize.value > 13.5f) 13.5.sp else fit.labelSize
            Text(
                text = cellLabel(item, palette),
                style = labelStyle.copy(fontSize = effectiveFontSize),
                color = labelInk,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.badge > 0) {
                Text(
                    text = item.badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = countInk,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            ThemedIcon(
                glyph = Glyph.chevronRight,
                contentDescription = null,
                tint = countInk,
                size = 14.dp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * BAR — the closing bar of a bento board. The content is centred (glyph, label, count) and an
 * accent rule runs along the cell's base, so it reads as the board's full stop rather than as
 * another plate. No chevron: a closing bar ends the board, it does not point onward.
 */
@Composable
private fun ClosingBarCell(
    item: BoardItem,
    palette: VaultPalette,
    rhythm: BoardRhythm,
    tuning: LayoutTuning,
    labelInk: Color,
    countInk: Color
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val labelStyle = MaterialTheme.typography.titleSmall
        val fit = fitCell(
            kind = TileKind.BAR,
            height = maxHeight,
            icon = palette.moduleIconSize * tuning.iconScale,
            labelSize = labelStyle.fontSize,
            padV = palette.moduleTilePadV
        )
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .contentScale(tuning.tileScale)
                .padding(horizontal = rhythm.tilePadH + 8.dp, vertical = fit.padV),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandModuleIcon(
                module = item.module,
                size = fit.iconSize,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = cellLabel(item, palette),
                style = labelStyle.copy(fontSize = fit.labelSize),
                color = labelInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (item.badge > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = countInk,
                    maxLines = 1
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.82f)
                .height(2.dp)
                .background(VaultContrast.vibrant(item.accent).copy(alpha = 0.75f), RoundedCornerShape(50))
        )
    }
}

/**
 * FEATURE — the double-width card. The glyph is seated in a tinted square of its own, the label
 * and its hint sit beside it, and the count rides in a pill at the trailing edge. The seated
 * glyph is what distinguishes it from a ROW, whose glyph is bare, and the side-by-side text is
 * what distinguishes it from a PLATE, whose text sits under the glyph.
 *
 * Both axes are measured, not assumed. A short row drops the hint line before it clips the label;
 * a narrow cell drops the count pill before it ellipsises the module name into noise.
 */
@Composable
private fun FeatureCell(
    item: BoardItem,
    layout: VaultLayout,
    palette: VaultPalette,
    rhythm: BoardRhythm,
    tuning: LayoutTuning,
    labelInk: Color,
    countInk: Color
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val labelStyle = MaterialTheme.typography.titleSmall
        val fit = fitCell(
            kind = TileKind.FEATURE,
            height = maxHeight,
            icon = palette.moduleIconSize * tuning.iconScale,
            labelSize = labelStyle.fontSize,
            padV = palette.moduleTilePadV
        )
        val roomForPill = maxWidth >= 150.dp
        val seat = fit.iconSize
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .contentScale(tuning.tileScale)
                .padding(horizontal = rhythm.tilePadH + 4.dp, vertical = fit.padV),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(seat)
                    .clip(RoundedCornerShape(14.dp))
                    .background(VaultContrast.vibrant(item.accent).copy(alpha = if (palette.isLight) 0.22f else 0.30f)),
                contentAlignment = Alignment.Center
            ) {
                BrandModuleIcon(
                    module = item.module,
                    size = (seat - 6.dp).coerceAtLeast(12.dp),
                    contentDescription = null
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = cellLabel(item, palette),
                    style = labelStyle.copy(fontSize = fit.labelSize),
                    color = labelInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (fit.hint) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = cellHint(layout, item),
                        style = MaterialTheme.typography.labelSmall,
                        color = countInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (item.badge > 0 && roomForPill) {
                Text(
                    text = item.badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (palette.hardOutline) FontWeight.Black else FontWeight.Bold,
                    color = countInk,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(VaultContrast.vibrant(item.accent).copy(alpha = if (palette.isLight) 0.22f else 0.28f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * PLATE — the resting grid plate: glyph above label. The count band under the label is only
 * reserved when the row is tall enough to pay for it; on a short row the badge moves to the
 * corner instead, because a reserved band the plate cannot afford is exactly the clipping the
 * owner saw. A label takes a second line when the height affords it, so a module name is never
 * ellipsised for no reason, and the glyph shrinks before the text ever does.
 */
@Composable
private fun PlateCell(
    item: BoardItem,
    layout: VaultLayout,
    palette: VaultPalette,
    rhythm: BoardRhythm,
    tuning: LayoutTuning,
    labelInk: Color,
    countInk: Color
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val fit = fitCell(
            kind = TileKind.PLATE,
            height = maxHeight,
            icon = palette.moduleIconSize * tuning.iconScale,
            labelSize = palette.moduleLabelSize,
            padV = palette.moduleTilePadV
        )
        val startAligned = layout == VaultLayout.BENTO
        Column(
            modifier = Modifier
                .fillMaxSize()
                .contentScale(tuning.tileScale)
                .padding(horizontal = rhythm.tilePadH, vertical = fit.padV),
            horizontalAlignment = if (startAligned) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandModuleIcon(
                module = item.module,
                size = fit.iconSize,
                contentDescription = item.module.label
            )
            Spacer(Modifier.height(fit.gap))
            Text(
                text = cellLabel(item, palette),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = fit.labelSize),
                color = labelInk,
                maxLines = fit.labelLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (startAligned) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (fit.countBand && item.badge > 0) {
                Box(
                    modifier = Modifier.height((fit.labelSize.value + 3f).dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.badge.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = (fit.labelSize.value - 1).sp
                        ),
                        color = countInk,
                        maxLines = 1
                    )
                }
            }
        }
        if (!fit.countBand && item.badge > 0) {
            Text(
                text = item.badge.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = countInk,
                maxLines = 1,
                modifier = Modifier
                    .align(if (startAligned) Alignment.BottomStart else Alignment.TopEnd)
                    .padding(
                        start = rhythm.tilePadH + 2.dp,
                        end = rhythm.tilePadH + 2.dp,
                        top = fit.padV,
                        bottom = fit.padV
                    )
            )
        }
    }
}

/** The resting elevation each layout holds: clay floats highest, agentic and adaptive sit flat. */
private fun restingElevation(layout: VaultLayout, palette: VaultPalette): Dp =
    when (layout) {
        VaultLayout.CLASSIC -> palette.cardShadow * 0.30f
        VaultLayout.BENTO -> palette.cardShadow * 0.45f
        VaultLayout.AGENTIC, VaultLayout.ADAPTIVE, VaultLayout.LEDGER,
        VaultLayout.COMPACT, VaultLayout.DENSE, VaultLayout.STRIP -> 0.dp
        VaultLayout.GENERATIVE -> palette.cardShadow * 0.8f
        VaultLayout.DUO -> palette.cardShadow * 0.55f
        VaultLayout.TRIAD -> palette.cardShadow * 0.5f
        VaultLayout.MOSAIC, VaultLayout.GALLERY, VaultLayout.COLUMN -> palette.cardShadow * 0.35f
        VaultLayout.SPLIT -> palette.cardShadow * 0.4f
    }.coerceIn(0.dp, 8.dp)

/** Clay is borderless; adaptive and bento rule with the palette's own outline, never with the accent. */
@Composable
private fun Modifier.tileRule(
    palette: VaultPalette,
    layout: VaultLayout,
    kind: TileKind,
    accent: Color,
    shape: Shape
): Modifier {
    val width = when {
        layout == VaultLayout.GENERATIVE -> 0.dp
        layout == VaultLayout.BENTO && kind != TileKind.PLATE -> palette.cardBorderWidth.coerceAtLeast(1.5.dp)
        else -> palette.cardBorderWidth
    }
    if (width <= 0.dp) return this
    val ink = when (layout) {
        VaultLayout.ADAPTIVE, VaultLayout.BENTO -> palette.outlineColor
        else -> accent.copy(alpha = 0.3f)
    }
    return this.then(Modifier.border(width, ink, shape))
}

/** The second line a wide cell carries. Never a repeat of the label itself. */
private fun cellHint(layout: VaultLayout, item: BoardItem): String = when (layout) {
    VaultLayout.AGENTIC -> "Open ${item.module.label.lowercase()}"
    VaultLayout.BENTO -> if (item.badge > 0) "${item.badge} stored" else "Nothing stored"
    VaultLayout.ADAPTIVE -> if (item.badge > 0) "${item.badge} in ${item.module.label.lowercase()}" else "Empty"
    else -> if (item.badge > 0) "${item.badge} stored" else "Open ${item.module.label.lowercase()}"
}

/**
 * MICRO — a cell that carries no card of its own: a wash of the module's own colour, the glyph,
 * and the label under it. It is the quiet grid AGENTIC hangs under its rails and the whole board
 * for COMPACT, which is why it must never grow a badge band or a hint — but the wash is deep
 * enough to read as a deliberate tile, not as a smudge.
 */
@Composable
private fun MicroCell(
    item: BoardItem,
    layout: VaultLayout,
    rhythm: BoardRhythm,
    tuning: LayoutTuning,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val palette = VaultPaletteController.current
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState().value
    val wash by animateFloatAsState(
        targetValue = if (pressed) 0.34f else 0.18f,
        animationSpec = VaultMotion.springSpec<Float>(),
        label = "microWash"
    )
    val shape = tileShapeOf(palette, layout, TileKind.MICRO)
    val vivid = VaultContrast.vibrant(item.accent)
    BoxWithConstraints(
        modifier = modifier
            .revealGlow(palette, shape)
            .tilePress(source, 0.dp, palette, shape, item.accent)
            .clip(shape)
            .revealSweep(palette)
            .background(vivid.copy(alpha = wash), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) {
        val fit = fitCell(
            kind = TileKind.MICRO,
            height = maxHeight,
            icon = palette.moduleIconSize * tuning.iconScale,
            labelSize = palette.moduleLabelSize,
            padV = palette.moduleTilePadV
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .contentScale(tuning.tileScale)
                .padding(horizontal = rhythm.tilePadH, vertical = fit.padV),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandModuleIcon(module = item.module, size = fit.iconSize, contentDescription = item.module.label)
            Spacer(Modifier.height(fit.gap))
            Text(
                text = cellLabel(item, palette),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = fit.labelSize),
                color = VaultColor.textStrong,
                maxLines = fit.labelLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The cascade each board row arrives with.
 *
 * The entrance is the *theme's* own grammar — [revealForTheme] composes it from the palette id's
 * [ThemeMotion]: axis, order, curve, size, duration and stagger. Every number still comes off the
 * shared ladder, so reduced motion lands the whole board on one frame instead of queuing eight
 * delayed tweens, and no plate ever waits on a delay long enough to read as missing content.
 */
@Composable
internal fun DesignReveal(
    index: Int,
    count: Int = 6,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = VaultPaletteController.current
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = revealForTheme(palette.id, index, count),
        modifier = modifier
    ) {
        content()
    }
}
