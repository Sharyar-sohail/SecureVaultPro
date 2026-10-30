package com.securevault.pro.presentation.screens.welcome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.components.Glyph

@Composable
fun SplashScreen(
    status: String? = null,
    title: String? = null,
    progress: Int? = null
) {
    val transition = rememberInfiniteTransition(label = "splash")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    NeonBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(198.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(VaultColor.accent.copy(alpha = 0.16f), Color.Transparent),
                            center = center,
                            radius = baseRadius
                        ),
                        radius = baseRadius,
                        center = center
                    )
                    drawCircle(
                        color = VaultColor.surfaceLift.copy(alpha = 0.06f),
                        radius = baseRadius * 0.82f,
                        center = center,
                        style = Stroke(1.dp.toPx())
                    )
                    drawCircle(
                        color = VaultColor.surfaceLift.copy(alpha = 0.04f),
                        radius = baseRadius * 0.64f,
                        center = center,
                        style = Stroke(1.dp.toPx())
                    )
                    rotate(rotation) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(Color.Transparent, VaultColor.accent, Color.Transparent)
                            ),
                            startAngle = 0f,
                            sweepAngle = 120f,
                            useCenter = false,
                            topLeft = Offset(
                                center.x - baseRadius * 0.94f,
                                center.y - baseRadius * 0.94f
                            ),
                            size = Size(baseRadius * 1.88f, baseRadius * 1.88f),
                            style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    rotate(-rotation * 0.65f) {
                        drawArc(
                            color = VaultColor.secondary.copy(alpha = 0.5f * pulse),
                            startAngle = 180f,
                            sweepAngle = 70f,
                            useCenter = false,
                            topLeft = Offset(
                                center.x - baseRadius * 0.72f,
                                center.y - baseRadius * 0.72f
                            ),
                            size = Size(baseRadius * 1.44f, baseRadius * 1.44f),
                            style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    VaultColor.elevated,
                                    VaultColor.background,
                                )
                            ),
                            CircleShape
                        )
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(VaultColor.accent.copy(alpha = 0.5f), VaultColor.hairline)
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.shield,
                        contentDescription = null,
                        tint = VaultColor.accent,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
            Spacer(Modifier.height(38.dp))
            Text(
                text = "SECURE WALLET",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 6.sp),
                color = VaultColor.textBody
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "PRO",
                style = MaterialTheme.typography.displayMedium.copy(
                    letterSpacing = 10.sp,
                    fontWeight = FontWeight.Black
                ),
                color = VaultColor.textStrong
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Your secrets live behind\nmilitary-grade encryption.",
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColor.textFaint,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(54.dp))
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColor.textStrong,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.6.dp,
                    color = VaultColor.accent.copy(alpha = pulse)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = status?.takeIf { it.isNotBlank() } ?: "Establishing secure enclave",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint,
                    textAlign = TextAlign.Center
                )
            }
            if (progress != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(5.dp)
                        .background(VaultColor.surfaceLift.copy(alpha = 0.08f), CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((progress.coerceIn(0, 100) / 100f))
                            .height(5.dp)
                            .background(
                                Brush.horizontalGradient(listOf(VaultColor.accent, VaultColor.secondary)),
                                CircleShape
                            )
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$progress% · end-to-end encrypted",
                    style = MaterialTheme.typography.labelSmall,
                    color = VaultColor.textFaint
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "AES-256-GCM  ·  600K PBKDF2  ·  ZERO-KNOWLEDGE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = VaultColor.textFaint.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "v3.0 · Abyss build",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint.copy(alpha = 0.45f)
            )
        }
    }
}
