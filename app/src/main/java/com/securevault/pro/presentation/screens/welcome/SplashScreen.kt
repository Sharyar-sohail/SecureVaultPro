package com.securevault.pro.presentation.screens.welcome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.BuildConfig
import com.securevault.pro.presentation.components.Glyph
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.theme.VaultMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One boot-sequence capability chip. Labels stay a single short word so three fit anywhere. */
private data class SplashFeature(val glyph: Glyph, val label: String, val tint: Color)

/**
 * The boot screen: a drawn vault mark (rings, shield, lock badge) over a staggered row of
 * capability chips.
 *
 * Everything animated here is presentation only. The intro flourish is one ~800 ms spec on a
 * boolean that is set from the first frame, so no real work waits on it; the vault mark's motion
 * is an infinite transition that is simply pinned to its resting values when the device asks for
 * reduced motion. The public signature is unchanged so every call site keeps compiling.
 */
@Composable
fun SplashScreen(
    status: String? = null,
    title: String? = null,
    progress: Int? = null
) {
    val reduced = VaultMotion.reduced()
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    val transition = rememberInfiniteTransition(label = "splash")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing)),
        label = "spin"
    )
    val wobble by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(17_000, easing = LinearEasing)),
        label = "wobble"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            tween(1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "sweep"
    )

    val glow = if (reduced) 0.62f else pulse
    val ringSpin = if (reduced) 0f else spin
    val ringWobble = if (reduced) 0f else wobble
    val barSweep = if (reduced) 0.5f else sweep

    // A single intro driver: fade/rise for the plates, draw-in for the shield. Under reduced
    // motion VaultMotion.tweenSpec collapses to 1 ms, so the mark simply appears.
    val intro by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = VaultMotion.tweenSpec(VaultMotion.LINGERING, VaultMotion.Emphasis),
        label = "intro"
    )
    val badge by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = VaultMotion.tweenSpec(VaultMotion.STANDARD, VaultMotion.Emphasis, 420),
        label = "lockBadge"
    )

    val barFill by animateFloatAsState(
        targetValue = (progress ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = VaultMotion.tweenSpec(VaultMotion.LONG, VaultMotion.Enter),
        label = "barFill"
    )

    val features = listOf(
        SplashFeature(Glyph.lock, "Encrypted", VaultColor.accent),
        SplashFeature(Glyph.sync, "Sync", VaultColor.secondary),
        SplashFeature(Glyph.currencyBitcoin, "Crypto", VaultColor.credit),
        SplashFeature(Glyph.documentScanner, "Scan", VaultColor.accent),
        SplashFeature(Glyph.chat, "WhatsApp", VaultColor.credit),
        SplashFeature(Glyph.image, "Stego", VaultColor.secondary)
    )

    NeonBackground {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val compact = maxHeight < 700.dp || maxWidth < 380.dp
            val minimal = maxHeight < 580.dp
            val markSize = if (minimal) 124.dp else if (compact) 152.dp else 196.dp
            val plateSize = if (minimal) 70.dp else if (compact) 86.dp else 108.dp
            val plateHeight = if (compact) 44.dp else 48.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (compact) 16.dp else 24.dp,
                        vertical = if (compact) 12.dp else 26.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                VaultMark(
                    size = markSize,
                    plate = plateSize,
                    spin = ringSpin,
                    wobble = ringWobble,
                    pulse = glow,
                    intro = intro,
                    badge = badge
                )

                Spacer(Modifier.height(if (compact) 16.dp else 28.dp))
                Text(
                    text = "SECURE WALLET",
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = if (minimal) 0.sp else if (compact) 2.sp else 6.sp
                    ),
                    color = VaultColor.textFaint,
                    maxLines = 1
                )
                Text(
                    text = "PRO",
                    style = (if (compact) {
                        MaterialTheme.typography.headlineLarge
                    } else {
                        MaterialTheme.typography.displayMedium
                    }).copy(
                        letterSpacing = if (minimal) 1.sp else if (compact) 3.sp else 10.sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = VaultColor.textStrong,
                    maxLines = 1
                )
                if (!minimal) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your secrets live behind\nmilitary-grade encryption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColor.textFaint,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        maxLines = 2
                    )
                }

                Spacer(Modifier.height(if (compact) 16.dp else 26.dp))

                // Two rows of three: the cheapest layout that still fits a 320 dp phone.
                FeaturePlateRow(
                    features = features.subList(0, 3),
                    firstIndex = 0,
                    revealed = revealed,
                    compact = compact,
                    plateHeight = plateHeight
                )
                Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
                FeaturePlateRow(
                    features = features.subList(3, 6),
                    firstIndex = 3,
                    revealed = revealed,
                    compact = compact,
                    plateHeight = plateHeight
                )

                Spacer(Modifier.height(if (compact) 18.dp else 32.dp))

                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColor.textStrong,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                VaultColor.accent.copy(alpha = 0.35f + 0.6f * glow),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = status?.takeIf { it.isNotBlank() } ?: "Establishing secure enclave",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                        color = VaultColor.textFaint,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (compact) 0.86f else 0.68f)
                        .height(5.dp)
                        .background(VaultColor.surfaceLift.copy(alpha = 0.09f), CircleShape)
                        .border(
                            0.8.dp,
                            VaultColor.hairline.copy(alpha = 0.18f),
                            CircleShape
                        )
                ) {
                    if (progress != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(barFill)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            VaultColor.accent,
                                            VaultColor.secondary,
                                            VaultColor.credit
                                        )
                                    ),
                                    CircleShape
                                )
                        )
                    } else {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val band = size.width * 0.42f
                            val left = -band + (size.width + band) * barSweep
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        VaultColor.accent,
                                        VaultColor.secondary,
                                        Color.Transparent
                                    ),
                                    startX = left,
                                    endX = left + band
                                ),
                                topLeft = Offset(left, 0f),
                                size = Size(band, size.height),
                                cornerRadius = CornerRadius(size.height / 2f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(7.dp))
                Text(
                    text = if (progress != null) {
                        "${progress.coerceIn(0, 100)}% · end-to-end encrypted"
                    } else {
                        "Preparing your vault"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                    color = VaultColor.textFaint,
                    maxLines = 1
                )

                if (!minimal) {
                    Spacer(Modifier.height(if (compact) 18.dp else 30.dp))
                    Text(
                        text = "AES-256-GCM  ·  600K PBKDF2  ·  ZERO-KNOWLEDGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = if (compact) 0.4.sp else 1.2.sp
                        ),
                        color = VaultColor.textFaint.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} · offline vault",
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultColor.textFaint.copy(alpha = 0.45f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * The vault mark: layered rotating rings, a pulsing halo and a glass plate whose shield draws in
 * with a lock badge. Drawn with Canvas only — no raster asset, no icon font.
 */
@Composable
private fun VaultMark(
    size: Dp,
    plate: Dp,
    spin: Float,
    wobble: Float,
    pulse: Float,
    intro: Float,
    badge: Float
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val r = this.size.minDimension / 2f

            // Halo: the only light source on the screen, breathing with the pulse.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        VaultColor.accent.copy(alpha = 0.14f + 0.12f * pulse),
                        VaultColor.secondary.copy(alpha = 0.05f + 0.05f * pulse),
                        Color.Transparent
                    ),
                    center = c,
                    radius = r
                ),
                radius = r,
                center = c
            )
            drawCircle(
                color = VaultColor.surfaceLift.copy(alpha = 0.06f),
                radius = r * 0.93f,
                center = c,
                style = Stroke(1.dp.toPx())
            )
            drawCircle(
                color = VaultColor.hairline.copy(alpha = 0.10f),
                radius = r * 0.75f,
                center = c,
                style = Stroke(1.dp.toPx())
            )

            // Tick ring: 24 ticks, every sixth one accent-lit.
            val ticks = 24
            rotate(spin) {
                repeat(ticks) { i ->
                    val angle = 2.0 * PI * i / ticks
                    val hot = i % 6 == 0
                    drawCircle(
                        color = if (hot) {
                            VaultColor.accent.copy(alpha = 0.30f + 0.35f * pulse)
                        } else {
                            VaultColor.surfaceLift.copy(alpha = 0.20f)
                        },
                        radius = if (hot) 1.7.dp.toPx() else 1.dp.toPx(),
                        center = Offset(
                            c.x + (cos(angle) * r * 0.86f).toFloat(),
                            c.y + (sin(angle) * r * 0.86f).toFloat()
                        )
                    )
                }
            }

            rotate(spin) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            VaultColor.accent.copy(alpha = 0.9f),
                            VaultColor.secondary.copy(alpha = 0.55f),
                            Color.Transparent
                        ),
                        center = c
                    ),
                    startAngle = 0f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 0.93f, c.y - r * 0.93f),
                    size = Size(r * 1.86f, r * 1.86f),
                    style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            rotate(wobble) {
                drawArc(
                    color = VaultColor.credit.copy(alpha = 0.30f + 0.30f * pulse),
                    startAngle = 198f,
                    sweepAngle = 64f,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 0.75f, c.y - r * 0.75f),
                    size = Size(r * 1.5f, r * 1.5f),
                    style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = VaultColor.secondary.copy(alpha = 0.24f + 0.24f * pulse),
                    startAngle = 22f,
                    sweepAngle = 34f,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 0.75f, c.y - r * 0.75f),
                    size = Size(r * 1.5f, r * 1.5f),
                    style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(plate)
                .graphicsLayer {
                    alpha = intro
                    val s = 0.84f + 0.16f * intro
                    scaleX = s
                    scaleY = s
                }
                .background(
                    Brush.linearGradient(listOf(VaultColor.elevated, VaultColor.background)),
                    CircleShape
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            VaultColor.accent.copy(alpha = 0.55f),
                            VaultColor.secondary.copy(alpha = 0.28f),
                            VaultColor.hairline
                        )
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            ThemedIcon(
                glyph = Glyph.shield,
                contentDescription = null,
                tint = VaultColor.accent,
                size = plate * 0.44f,
                animate = false
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(plate * 0.28f)
                    .graphicsLayer { alpha = badge }
                    .background(VaultColor.elevated, CircleShape)
                    .border(1.dp, VaultColor.credit.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                ThemedIcon(
                    glyph = Glyph.lock,
                    contentDescription = null,
                    tint = VaultColor.credit,
                    size = plate * 0.15f,
                    animate = false
                )
            }
        }
    }
}

@Composable
private fun FeaturePlateRow(
    features: List<SplashFeature>,
    firstIndex: Int,
    revealed: Boolean,
    compact: Boolean,
    plateHeight: Dp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
    ) {
        features.forEachIndexed { offset, feature ->
            FeaturePlate(
                feature = feature,
                index = firstIndex + offset,
                revealed = revealed,
                compact = compact,
                plateHeight = plateHeight
            )
        }
    }
}

@Composable
private fun RowScope.FeaturePlate(
    feature: SplashFeature,
    index: Int,
    revealed: Boolean,
    compact: Boolean,
    plateHeight: Dp
) {
    // Each plate lands CHIP_STAGGER_MS after the previous one, all of them inside the gate hold.
    val appear by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = VaultMotion.tweenSpec(
            SplashTiming.CHIP_RUN_MS,
            VaultMotion.Emphasis,
            SplashTiming.chipDelayMs(index)
        ),
        label = "feature$index"
    )
    val shape = RoundedCornerShape(if (compact) 10.dp else 12.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(plateHeight)
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 12.dp.toPx()
            }
            .background(
                Brush.verticalGradient(
                    listOf(
                        VaultColor.surfaceLift.copy(alpha = 0.11f),
                        VaultColor.surfaceLift.copy(alpha = 0.03f)
                    )
                ),
                shape
            )
            .border(0.8.dp, VaultColor.hairline.copy(alpha = 0.30f), shape)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ThemedIcon(
                glyph = feature.glyph,
                contentDescription = null,
                tint = feature.tint,
                size = if (compact) 15.dp else 17.dp,
                animate = false
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = feature.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 8.5.sp else 9.sp,
                    letterSpacing = 0.sp,
                    lineHeight = 10.sp
                ),
                color = VaultColor.textBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
