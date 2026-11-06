package com.securevault.pro.presentation.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's colour ban, expressed as maths so it cannot rot into opinion.
 *
 * "Remove ALL pink, magenta, and dirty/washed-out colours from every theme": the six chromatic
 * slots of every shipped palette (the legacy names cyan/amber/indigo/mint/coral/lavender
 * that map to accent/secondary/warning/credit/debit/decorative) must be sharp and
 * professional. Three rules, checked with plain HSV maths on the JVM — no Android
 * ColorUtils, no device:
 *
 *  1. No hue in the pink/magenta/fuchsia/violet band. Violet begins where royal blue stops
 *     (~245 degrees), magenta sits at ~300 and pink at ~330, so the banned window is 246..355
 *     inclusive at any saturation above 0.10. Reds that wrap past 355 (the approved crimson
 *     #C1121F is ~355.5) stay legal; neutrals below 0.10 sat are not "pink", they are grey.
 *  2. No pastels: a chromatic slot saturated below 0.45 while its HSV value sits above
 *     0.80 is a colour washed out with white. Pale neutrals (saturation below 0.10)
 *     are always allowed — text inks and light-theme surfaces legitimately are.
 *  3. The ban is not palette-only. The pink the owner kept seeing lived in the generated icon
 *     plates, the accent table beside them and the web companion's stylesheet, out of reach of
 *     any palette test, so [no shipped asset draws a banned hue] reads those files directly.
 */
class ThemeColourDisciplineTest {

    private data class Hsv(val hue: Double, val sat: Double, val value: Double)

    /** Standard max/min HSV on the sRGB floats. delta == 0 means a neutral: hue 0. */
    private fun hsv(color: Color): Hsv =
        hsvOf(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())

    private fun hsvOf(r: Double, g: Double, b: Double): Hsv {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta <= 0.0) return Hsv(0.0, 0.0, max)
        val hue = when (max) {
            r -> 60.0 * (((g - b) / delta) % 6.0)
            g -> 60.0 * ((b - r) / delta + 2.0)
            else -> 60.0 * ((r - g) / delta + 4.0)
        }.let { if (it < 0.0) it + 360.0 else it }
        return Hsv(hue, delta / max, max)
    }

    /** True when the hue reads as violet, magenta, fuchsia or pink at a visible saturation. */
    private fun isBannedHue(hsv: Hsv): Boolean = hsv.hue in BANNED_BAND && hsv.sat > 0.10

    /** True when a chromatic accent has been watered down with white. */
    private fun isPastel(hsv: Hsv): Boolean = hsv.value > 0.80 && hsv.sat < 0.45 && hsv.sat >= 0.10

    private val chromaticSlots: List<Pair<String, VaultPalette.() -> Color>> = listOf(
        "cyan" to { cyan },
        "amber" to { amber },
        "indigo" to { indigo },
        "mint" to { mint },
        "coral" to { coral },
        "lavender" to { lavender },
    )

    @Test
    fun `the hsv maths itself is right`() {
        assertTrue(closeTo(hsv(Color(0xFFFF0000)).hue, 0.0))
        assertTrue(closeTo(hsv(Color(0xFF00FF00)).hue, 120.0))
        assertTrue(closeTo(hsv(Color(0xFF0000FF)).hue, 240.0))
        // The two legal deep reds must wrap to just over 355, inside the last degree
        // before true red — the owner's approved crimson, not a banned magenta.
        assertTrue("C1121F hue ${hsv(Color(0xFFC1121F)).hue}", hsv(Color(0xFFC1121F)).hue > 355.0)
        assertTrue("D2222F hue ${hsv(Color(0xFFD2222F)).hue}", hsv(Color(0xFFD2222F)).hue > 355.0)
        // The owner's own "Royal Blue" and "Light Blue" must survive the widened band.
        val blues = listOf(
            Color(0xFF1E4FD8), Color(0xFF2563EB), Color(0xFF1D4ED8),
            Color(0xFF38BDF8), Color(0xFF16337E), Color(0xFF6C8CFF),
        )
        for (blue in blues) {
            val value = hsv(blue)
            assertTrue("${hex(blue)} sits at hue ${value.hue}", !isBannedHue(value))
        }
        // Hot magenta #FF00FF sits at 300: banned. Fuchsia #D500F9 at ~295: banned.
        assertTrue(isBannedHue(hsv(Color(0xFFFF00FF))))
        assertTrue(isBannedHue(hsv(Color(0xFFD500F9))))
        // Violet #8B5CF6 at ~258 is the shade the old 255-degree floor waved through.
        assertTrue(isBannedHue(hsv(Color(0xFF8B5CF6))))
        // Washed-out pink and pale mint are "dirty", not accents.
        assertTrue(isPastel(hsv(Color(0xFFFADADD))))
        assertTrue(isPastel(hsv(Color(0xFFA8DCCA))))
    }

    @Test
    fun `no shipped theme carries a pink violet or pastel chromatic`() {
        val failures = StringBuilder()
        for (palette in VaultPalettes.all) {
            for ((slotName, selector) in chromaticSlots) {
                val color = selector(palette)
                val value = hsv(color)
                val bannedHue = isBannedHue(value)
                val pastel = isPastel(value)
                if (bannedHue || pastel) {
                    val reason = if (bannedHue) {
                        "pink/magenta/violet hue band (246..355)"
                    } else {
                        "pastel (washed-out with white)"
                    }
                    failures.append(
                        "\n  ${palette.id}.${slotName} = ${hex(color)} -> " +
                            "hue ${"%.1f".format(value.hue)}, sat ${"%.3f".format(value.sat)}, " +
                            "value ${"%.3f".format(value.value)}: $reason"
                    )
                }
            }
        }
        assertTrue(
            "Banned colours in the lineup (the owner demands sharp, professional, " +
                "high-contrast accents — no pink, magenta, fuchsia, violet or pastel):" +
                failures.toString(),
            failures.isEmpty()
        )
    }

    /**
     * The files that actually paint the app: the generated module plates, the accent table that
     * tints them, the palettes, the icon generator's theme ramps and the web companion's
     * stylesheet, shipped asset and clean template alike.
     */
    @Test
    fun `no shipped asset draws a banned hue`() {
        val root = repoRoot()
        val drawableDir = File(root, "app/src/main/res/drawable")
        val plates = drawableDir.listFiles { file ->
            file.name.startsWith("ic_b_") && file.name.endsWith(".xml")
        } ?: emptyArray()

        val targets = plates.toList() + listOf(
            File(root, "app/src/main/java/com/securevault/pro/presentation/components/BrandIconCatalog.kt"),
            File(root, "app/src/main/java/com/securevault/pro/presentation/theme/Palette.kt"),
            File(root, "app/src/main/java/com/securevault/pro/presentation/components/WhatsAppChatView.kt"),
            File(root, "app/src/main/java/com/securevault/pro/presentation/screens/diagnostics/RescueScreen.kt"),
            File(root, "app/src/main/java/com/securevault/pro/presentation/screens/wallet/CardEditorViewModel.kt"),
            File(root, "tools/brand-icons/build.mjs"),
            File(root, "app/src/main/assets/blogger_theme.xml"),
            File(root, "tools/blogger-theme/secure_wallet_blogger_theme.xml"),
        )
        val present = targets.filter { it.isFile }
        assertTrue(
            "colour discipline lost its sources: root resolved to ${root.path}, " +
                "plates found ${plates.size} of ${plates.size}",
            plates.isNotEmpty() &&
                File(root, "app/src/main/assets/blogger_theme.xml").isFile &&
                File(root, "tools/brand-icons/build.mjs").isFile
        )

        val hexToken = Regex("(?:#|0x)([0-9A-Fa-f]{8})\\b|(?:#|0x)([0-9A-Fa-f]{6})\\b")
        val failures = StringBuilder()
        var scanned = 0
        for (file in present) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    return@forEachIndexed
                }
                for (match in hexToken.findAll(line)) {
                    val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    val body = if (raw.length == 8) raw.substring(2) else raw
                    val value = hsvOf(
                        body.substring(0, 2).toInt(16) / 255.0,
                        body.substring(2, 4).toInt(16) / 255.0,
                        body.substring(4, 6).toInt(16) / 255.0,
                    )
                    scanned++
                    if (!isBannedHue(value)) continue
                    failures.append(
                        "\n  ${file.name}:${index + 1} #$body -> hue ${"%.1f".format(value.hue)}," +
                            " sat ${"%.3f".format(value.sat)}"
                    )
                }
            }
        }
        assertTrue("the hue scan read $scanned colour tokens — the pattern broke", scanned > 500)
        assertTrue(
            "Pink, magenta or violet survived in the shipped assets. The ban covers the icon " +
                "plates, the accent table, the palettes and the web companion, not only the " +
                "theme slots:" + failures.toString(),
            failures.isEmpty()
        )
    }

    /** Walks up from the Gradle test working directory until the repository root is found. */
    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        var dir: File? = start
        var hops = 0
        while (dir != null && hops < 6) {
            if (File(dir, "app/src/main/res/drawable").isDirectory && File(dir, "tools/brand-icons").isDirectory) {
                return dir
            }
            val parent = dir.parentFile
            if (File(dir, "src/main/res/drawable").isDirectory &&
                parent != null && File(parent, "tools/brand-icons").isDirectory
            ) {
                return parent
            }
            dir = parent
            hops++
        }
        return start
    }

    private fun closeTo(actual: Double, expected: Double): Boolean =
        kotlin.math.abs(actual - expected) < 0.5

    private fun hex(color: Color): String {
        fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            .toString(16).uppercase().padStart(2, '0')
        return "#${byte(color.red)}${byte(color.green)}${byte(color.blue)}"
    }

    private companion object {
        /** Violet through pink, up to the last degree before true red. */
        val BANNED_BAND: ClosedRange<Double> = 246.0..355.0
    }
}
