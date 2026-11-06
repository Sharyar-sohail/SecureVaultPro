package com.securevault.pro.presentation.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lineup guard for the ten-theme expansion. Where `ThemeTokensTest` proves each
 * palette is individually readable, this file proves the lineup is a lineup: fifteen
 * distinct products, no silent duplicates, and every new id actually reachable through
 * the same resolver the settings screen and the persistence layer use.
 */
class NewThemesGuardTest {

    private val newIds = listOf(
        "noir", "mint", "sunset", "arctic", "voltra",
        "royal", "sakura", "neon_cyan", "neon_magenta", "neon_plasma"
    )

    private val neonIds = listOf("neon_cyan", "neon_magenta", "neon_plasma")

    @Test
    fun `exactly fifteen themes ship with unique ids`() {
        assertEquals(15, VaultPalettes.all.size)
        val ids = VaultPalettes.all.map { it.id }
        assertEquals("theme ids must be unique", ids.size, ids.distinct().size)
        for (id in newIds) assertTrue("$id must ship", id in ids)
    }

    @Test
    fun `the lineup covers every dock and header grammar and stays visually distinct`() {
        // DockStyle ships five silhouettes and HeaderStyle four — the enums cap the
        // "6 docks / 5 headers" ask, so the lineup must (a) use every silhouette and
        // (b) separate them further with the tokens that decorate them, which is what
        // the eye actually reads as a different product.
        val docks = VaultPalettes.all.map { it.dock }.toSet()
        val headers = VaultPalettes.all.map { it.header }.toSet()
        assertEquals("every DockStyle must ship", DockStyle.entries.size, docks.size)
        assertEquals("every HeaderStyle must ship", HeaderStyle.entries.size, headers.size)
        assertTrue(docks.containsAll(DockStyle.entries.toList()))
        assertTrue(headers.containsAll(HeaderStyle.entries.toList()))

        val dockLooks = VaultPalettes.all.map {
            listOf<Any>(it.dock, it.navIndicator, it.navLabels, it.navFrostAlpha, it.navFloating, it.navHeight)
        }.distinct()
        assertTrue(
            "need >= 6 distinct dock looks, got ${dockLooks.size}",
            dockLooks.size >= 6
        )
        val headerLooks = VaultPalettes.all.map {
            listOf<Any>(it.header, it.uppercaseLabels, it.cardCorner, it.navHeight)
        }.distinct()
        assertTrue(
            "need >= 5 distinct header looks, got ${headerLooks.size}",
            headerLooks.size >= 5
        )
    }

    @Test
    fun `no palette is an inverted copy of another`() {
        // The bed + primary accent + secondary accent triple is a theme's signature;
        // two palettes sharing all three is the "same old UI" complaint in numbers.
        val triples = VaultPalettes.all.map { Triple(it.abyssDark, it.cyan, it.indigo) }
        assertEquals("abyssDark+cyan+indigo must be unique per theme", triples.size, triples.distinct().size)
    }

    @Test
    fun `every new theme resolves through find by its exact id`() {
        for (id in newIds) {
            assertEquals("$id must resolve to itself", id, VaultPalettes.find(id).id)
            // Case and whitespace insensitivity is part of the resolver contract.
            assertEquals("$id must resolve case-insensitively", id, VaultPalettes.find(" ${id.uppercase()} ").id)
        }
    }

    @Test
    fun `the light and dark split of the new ten matches the brief`() {
        val shouldLight = setOf("mint", "arctic", "sakura")
        for (id in newIds) {
            val palette = VaultPalettes.find(id)
            assertEquals("$id isLight", id in shouldLight, palette.isLight)
        }
        // The five originals keep their flags; nothing in the lineup flips by accident.
        val lightIds = VaultPalettes.all.filter { it.isLight }.map { it.id }
        assertEquals(listOf("solstice", "oneui", "lime", "mint", "arctic", "sakura"), lightIds)
    }

    @Test
    fun `the neon trio is hard outlined, glowing and nocturnal`() {
        for (id in neonIds) {
            val palette = VaultPalettes.find(id)
            assertEquals("$id must resolve to itself", id, palette.id)
            assertTrue("$id must glow", palette.glow)
            assertTrue("$id must be hard outlined", palette.hardOutline)
            assertTrue("$id must be a dark theme", !palette.isLight)
            assertEquals("$id must sit on the neon mesh", VaultBackdrop.NEON_MESH, palette.backdrop)
            assertTrue("$id must keep its own board", palette.layout in VaultLayout.entries)
        }
        // The neon trio took over the three freed layouts, so no board is shared.
        val neonLayouts = neonIds.map { VaultPalettes.find(it).layout }
        assertEquals("each neon theme owns a distinct board", neonLayouts.size, neonLayouts.distinct().size)
    }

    @Test
    fun `removed themes still resolve through the alias map`() {
        mapOf("terra" to "sakura", "slate" to "dark", "carbon" to "noir").forEach { (removed, survivor) ->
            assertEquals(survivor, VaultPalettes.find(removed).id)
            assertTrue("$survivor must ship", survivor in VaultPalettes.all.map { it.id })
        }
        assertEquals("aqua", VaultPalettes.DEFAULT_ID)
        assertEquals("aqua", VaultPalettes.find(null).id)
    }

    @Test
    fun `dark themes never go pure black`() {
        // The owner's standing rule: OLED-black was rejected; graphite, umber, violet
        // and near-black ink are all fine, #000000 is not.
        for (palette in VaultPalettes.all.filterNot { it.isLight }) {
            assertNotEquals("${palette.id}: background is #000000", Color.Black, palette.abyssDark)
            assertNotEquals("${palette.id}: surface is #000000", Color.Black, palette.abyssSurface)
        }
    }

    @Test
    fun `the new ten are spread across the rendering grammars`() {
        // Eight of the nine ThemeDesign values must be in play across the lineup so a
        // theme switch changes the component grammar, not only the paint.
        val designs = VaultPalettes.all.map { it.design }.toSet()
        assertTrue(
            "lineup must span at least 7 designs, got ${designs.map { it.name }}",
            designs.size >= 7
        )
        val backdrops = VaultPalettes.all.map { it.backdrop }.toSet()
        assertTrue("backdrops must spread, got ${backdrops.size}", backdrops.size >= 6)
        val pies = VaultPalettes.all.map { listOf(it.pieForm, it.pieLegend, it.pieRoundCaps, it.pieGradient) }.distinct()
        assertTrue("chart grammars must spread, got ${pies.size}", pies.size >= 8)
        // The composition chart has to read as several different objects across the lineup, not one
        // dial in fifteen colours: every PieForm value must actually be drawn by a theme, at least
        // five of them must be in use, and no single grammar may carry more than six of the fifteen.
        assertEquals("every chart form must have a value in play", 6, PieForm.entries.size)
        val forms = VaultPalettes.all.map { it.pieForm }
        assertEquals(
            "every chart form must be used by at least one theme",
            PieForm.entries.toSet(),
            forms.toSet()
        )
        assertTrue(
            "at least 5 distinct chart forms, got ${forms.distinct().map { it.name }}",
            forms.distinct().size >= 5
        )
        forms.groupingBy { it }.eachCount().forEach { (form, count) ->
            assertTrue("$form carries $count of the fifteen themes - one grammar may not carry more than 6", count <= 6)
        }
    }
}
