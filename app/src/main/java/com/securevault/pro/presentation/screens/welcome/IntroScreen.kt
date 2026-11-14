package com.securevault.pro.presentation.screens.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.securevault.pro.presentation.components.GradientButton
import com.securevault.pro.presentation.components.NeonBackground
import com.securevault.pro.presentation.components.ThemedIcon
import com.securevault.pro.presentation.theme.VaultPaletteController
import com.securevault.pro.presentation.theme.VaultColor
import com.securevault.pro.presentation.components.Glyph

private data class IntroPage(
    val icon: Glyph,
    val accent: Color,
    val kicker: String,
    val title: String,
    val body: String
)

/**
 * Short, replayable product introduction shown once before the three-option
 * entry screen. Every element is wrap-content and scrollable, so large system
 * font scales cannot push the copy under the controls.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val palette = VaultPaletteController.current
    val pages = listOf(
        IntroPage(
            icon = Glyph.lock,
            accent = VaultColor.accent,
            kicker = "ZERO-KNOWLEDGE VAULT",
            title = "Everything sealed,\nnothing shared.",
            body = "Passwords, cards, IDs, notes and files are encrypted with AES-256-GCM. " +
                "The key lives in the Android Keystore — never on a server, never with us."
        ),
        IntroPage(
            icon = Glyph.fingerprint,
            accent = VaultColor.credit,
            kicker = "INSTANT ACCESS",
            title = "One glance\nopens the vault.",
            body = "Native BiometricPrompt uses your face or fingerprint directly. " +
                "No lock-screen PIN detour, no password typed in public."
        ),
        IntroPage(
            icon = Glyph.cloudDone,
            accent = VaultColor.decorative,
            kicker = "PORTABLE BY DESIGN",
            title = "Backups that\nfollow you.",
            body = "Restore from Google Drive or an encrypted .enc file. Every wipe archives a " +
                "timestamped ZIP first, so a mistake never costs you the vault."
        )
    )
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val last = index >= pages.lastIndex

    NeonBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // Text-only screen: it may scroll, but the viewport is what shrinks when an IME
                // is up, so the window itself never bounces or scrolls.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(palette.chipShape)
                        .background(palette.cyan.copy(alpha = 0.14f))
                        .border(palette.cardBorderWidth, palette.cyan.copy(alpha = 0.32f), palette.chipShape),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedIcon(
                        glyph = Glyph.shield,
                        contentDescription = null,
                        tint = palette.cyan,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "SECURE VAULT PRO",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                    color = VaultColor.textStrong,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButtonText(onClick = onFinished)
            }

            Spacer(Modifier.height(30.dp))

            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    (slideInHorizontally { it / 4 } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut())
                },
                label = "introPage"
            ) { current ->
                val shown = pages[current.coerceIn(0, pages.lastIndex)]
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IntroIllustration(icon = shown.icon, accent = shown.accent)
                    Spacer(Modifier.height(26.dp))
                    Text(
                        text = shown.kicker,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = shown.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = shown.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = VaultColor.textStrong,
                        textAlign = TextAlign.Center,
                        lineHeight = MaterialTheme.typography.displaySmall.lineHeight
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = shown.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColor.textBody,
                        textAlign = TextAlign.Center,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                pages.indices.forEach { dot ->
                    Box(
                        modifier = Modifier
                            .width(if (dot == index) 20.dp else 7.dp)
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (dot == index) page.accent else palette.textMuted.copy(alpha = 0.35f)
                            )
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            GradientButton(
                text = if (last) "Set up my vault" else "Continue",
                onClick = {
                    if (last) onFinished() else index += 1
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "AES-256-GCM · PBKDF2-SHA256 600,000 · Keystore-backed",
                style = MaterialTheme.typography.labelSmall,
                color = VaultColor.textFaint.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.labelSmall.lineHeight
            )
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun IntroIllustration(icon: Glyph, accent: Color) {
    val palette = VaultPaletteController.current
    Box(
        modifier = Modifier.size(168.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(palette.abyssElevated.copy(alpha = 0.72f))
                .border(1.dp, accent.copy(alpha = 0.28f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(palette.cardShape)
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.26f), palette.abyssSurface)
                    )
                )
                .border(palette.cardBorderWidth, accent.copy(alpha = 0.45f), palette.cardShape),
            contentAlignment = Alignment.Center
        ) {
            ThemedIcon(
                glyph = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(38.dp)
            )
        }
    }
}

@Composable
private fun IconButtonText(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(
            text = "SKIP INTRO",
            style = MaterialTheme.typography.labelSmall,
            color = VaultColor.accent,
            fontWeight = FontWeight.SemiBold
        )
        ThemedIcon(
            glyph = Glyph.arrowForward,
            contentDescription = null,
            tint = VaultColor.accent,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(14.dp)
        )
    }
}
