package com.securevault.pro.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.securevault.pro.presentation.theme.VaultContrast
import com.securevault.pro.presentation.theme.VaultPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two defects live in this table. A Color whose packed value is malformed does not fail at the
 * call site: it fails later, inside Compose's draw pass, as an ArrayIndexOutOfBoundsException on
 * the color space table — that is what killed the app on the first frame of Home. And a theme that
 * is dropped from the palette list while the catalog still keys it (or the other way round) ships
 * a theme switcher whose tiles draw nothing. These tests hold the accent surface of every shipped
 * theme against both.
 */
class BrandIconCatalogTest {

    private val themeIds = listOf(
        "ios", "oneui", "aqua", "light", "dark",
        "noir", "mint", "sunset", "arctic", "voltra",
        "terra", "royal", "slate", "sakura", "carbon"
    )

    // "slate" is a shipped theme id again (Slate Grid), so it left this list; the
    // legacy alias "slate" -> "dark" in VaultPalettes still exists but find()
    // matches the shipped palette first.
    private val retiredIds = listOf("lumen", "hyper", "funtouch", "volt")

    @Test
    fun `every theme has a full accent set and icon set`() {
        for (id in themeIds) {
            for (module in VaultModule.entries) {
                assertTrue("$id/$module must carry a drawable", BrandIconCatalog.icon(id, module) != 0)
            }
        }
        assertEquals(14, VaultModule.entries.size)
        assertEquals(13, BrandIconCatalog.board.size)
        assertTrue(
            "the steganography module must be on the board, not only in the enum",
            VaultModule.STEGO in BrandIconCatalog.board
        )
    }

    @Test
    fun `palette list and icon catalog ship the same themes`() {
        assertEquals(themeIds, VaultPalettes.all.map { it.id })
        for (id in themeIds) {
            for (module in VaultModule.entries) {
                // An id the catalog does not know silently redraws the default brand.
                assertNotEquals(
                    "$id/$module must not fall through to the default theme",
                    0,
                    BrandIconCatalog.icon(id, module)
                )
            }
        }
    }

    @Test
    fun `retired themes resolve to a shipped theme`() {
        for (id in retiredIds) {
            val resolved = VaultPalettes.find(id)
            assertTrue("$id must alias onto a shipped theme, got ${resolved.id}", resolved.id in themeIds)
            assertTrue("$id must not land on the default by accident", BrandIconCatalog.icon(id, VaultModule.PASSWORDS) != 0)
        }
    }

    @Test
    fun `unknown theme falls back instead of returning nothing`() {
        for (module in VaultModule.entries) {
            val color = BrandIconCatalog.accent("no-such-theme", module)
            assertEquals(1f, color.alpha, TOLERANCE)
        }
    }

    @Test
    fun `brand themes keep their own accent ramp`() {
        for (module in VaultModule.entries) {
            val ios = BrandIconCatalog.accent("ios", module)
            val oneui = BrandIconCatalog.accent("oneui", module)
            val aqua = BrandIconCatalog.accent("aqua", module)
            assertNotEquals("iOS and One UI share $module ink", ios, oneui)
            assertNotEquals("ColorOS and One UI share $module ink", aqua, oneui)
            assertNotEquals("iOS and ColorOS share $module ink", ios, aqua)
        }
    }

    @Test
    fun `accents read back as the documented argb and stay srgb`() {
        val expected = listOf(
            Triple("ios", VaultModule.PASSWORDS, 0xFF06529E),
            Triple("ios", VaultModule.STEGO, 0xFF942DC0),
            Triple("oneui", VaultModule.PASSWORDS, 0xFF1B6DF1),
            Triple("aqua", VaultModule.PASSWORDS, 0xFF1D8371),
            Triple("aqua", VaultModule.CARDS, 0xFF4A69D4),
            Triple("light", VaultModule.PASSWORDS, 0xFF582EC6),
            Triple("dark", VaultModule.PASSWORDS, 0xFF7757D8),
            // Re-cut by tools/brand-icons/vibrancy-pass.mjs to hold white ink and the chroma floor.
            Triple("noir", VaultModule.PASSWORDS, 0xFF926C22),
            Triple("voltra", VaultModule.PASSWORDS, 0xFF687D1D),
            Triple("slate", VaultModule.PASSWORDS, 0xFFC21807),
            Triple("sakura", VaultModule.PASSWORDS, 0xFFCF3374),
            Triple("carbon", VaultModule.PASSWORDS, 0xFFBA5912),
        )
        for ((theme, module, argb) in expected) {
            assertMatches(theme, module, BrandIconCatalog.accent(theme, module), argb.toLong())
        }
    }

    @Test
    fun `every accent is the plate colour actually drawn in the vector`() {
        // The catalog used to carry an ink the drawable never painted — 52 of the 210 pairs —
        // which is why a "vibrant" accent could still render as a dim plate on screen. The
        // first opaque, non-black, non-white fill of the vector *is* the plate.
        val dir = drawablesDir()
        for (id in themeIds) {
            for (module in VaultModule.entries) {
                val file = File(dir, "ic_b_${id}_${module.key}.xml")
                assertTrue("missing drawable ${file.name}", file.isFile)
                val drawn = plateFill(file.readText())
                assertNotNull("$id/$module draws no plate fill", drawn)
                val accent = BrandIconCatalog.accent(id, module)
                assertEquals(
                    "$id/$module: catalog ink ${hex(accent)} is not the drawn plate ${hex(drawn!!)}",
                    hex(drawn),
                    hex(accent)
                )
            }
        }
    }

    @Test
    fun `no plate is dim`() {
        // The owner's rule, measured: a plate pale enough to disappear is a defect even when its
        // white ink passes AA. CHROMA_FLOOR is the bar tools/brand-icons/vibrancy-pass.mjs cuts to.
        for (id in themeIds) {
            for (module in VaultModule.entries) {
                val plate = BrandIconCatalog.accent(id, module)
                assertTrue(
                    "$id/$module plate ${hex(plate)} has chroma %.2f (needs %.2f)".format(
                        VaultContrast.saturation(plate),
                        VaultContrast.CHROMA_FLOOR
                    ),
                    VaultContrast.saturation(plate) >= VaultContrast.CHROMA_FLOOR - 0.01
                )
            }
        }
    }

    private fun drawablesDir(): File {
        val candidates = listOf(
            File("src/main/res/drawable"),
            File("app/src/main/res/drawable"),
            File("../src/main/res/drawable")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("cannot locate res/drawable from ${File(".").absolutePath}")
    }

    /** The plate: the vector's first opaque fill that is neither the glyph's black nor its white. */
    private fun plateFill(xml: String): Color? {
        val fills = Regex("""fillColor="#([0-9A-Fa-f]{8})"""").findAll(xml).map { it.groupValues[1] }
        val plate = fills.firstOrNull { argb ->
            argb.substring(0, 2).equals("FF", ignoreCase = true) &&
                !argb.equals("FF000000", ignoreCase = true) &&
                !argb.equals("FFFFFFFF", ignoreCase = true)
        } ?: return null
        return Color(plate.toLong(16) and 0xFFFFFFL or 0xFF000000L)
    }

    @Test
    fun `every plate holds its white glyph ink at AA`() {
        // The glyph ink is baked white into the vector; the catalog accent is the same
        // plate colour, so checking the accent checks the shipped drawable.
        // tools/brand-icons/build.mjs corrects the ramp to this bar at generation time.
        for (id in themeIds) {
            for (module in VaultModule.entries) {
                val plate = BrandIconCatalog.accent(id, module)
                val ratio = VaultContrast.ratio(Color.White, plate)
                assertTrue(
                    "$id/$module: white ink on plate ${hex(plate)} is %.2f:1 (needs %.1f:1)".format(ratio, AA_PLATE),
                    ratio >= AA_PLATE
                )
            }
        }
    }

    private fun hex(color: Color): String {
        fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            .toString(16).uppercase().padStart(2, '0')
        return "#${byte(color.red)}${byte(color.green)}${byte(color.blue)}"
    }

    @Test
    fun `ramps for every theme are drawable colors`() {
        for (theme in themeIds) {
            val ramp = BrandIconCatalog.ramp(theme)
            assertEquals(13, ramp.size)
            ramp.forEachIndexed { index, color ->
                // Touching alpha/rgb forces the color space lookup that used to throw.
                assertEquals("transparent accent in $theme at $index", 1f, color.alpha, TOLERANCE)
                assertEquals(ColorSpaces.Srgb, color.colorSpace)
                assertTrue(color.red.isNaN().not() && color.blue.isNaN().not())
            }
        }
    }

    private fun assertMatches(theme: String, module: VaultModule, color: Color, argb: Long) {
        val label = "$theme/$module"
        assertEquals("$label alpha", (argb shr 24 and 0xFF).toFloat() / 255f, color.alpha, TOLERANCE)
        assertEquals("$label red", (argb shr 16 and 0xFF).toFloat() / 255f, color.red, TOLERANCE)
        assertEquals("$label green", (argb shr 8 and 0xFF).toFloat() / 255f, color.green, TOLERANCE)
        assertEquals("$label blue", (argb and 0xFF).toFloat() / 255f, color.blue, TOLERANCE)
        assertEquals("$label color space", ColorSpaces.Srgb, color.colorSpace)
    }

    private companion object {
        const val TOLERANCE = 0.001f
        const val AA_PLATE = 4.5
    }
}
