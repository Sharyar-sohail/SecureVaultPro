package com.securevault.pro.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The class-initialisation fault that stopped the app opening on Android 12 was invisible to
 * every compiler: an object whose list called a top-level helper in its own file, while that
 * file's initialiser read the list back. The JVM ran one inside the other and handed the first
 * composition an `ExceptionInInitializerError`. Touching any of these entry points first is
 * what reproduces it, so each test enters through a different door.
 */
class VaultFontsTest {

    @Test
    fun `the list builds when nothing has touched the file class first`() {
        assertEquals(6, VaultFonts.all.size)
        assertEquals(
            setOf("inter", "medium", "compact", "editorial", "terminal", "script"),
            VaultFonts.all.map { it.id }.toSet()
        )
        assertSame(VaultFonts.default, VaultFonts.find(VaultFonts.DEFAULT_ID))
    }

    @Test
    fun `every font resolves a family, including the device names`() {
        for (font in VaultFonts.all) assertNotNull("${font.id} has no family", font.family)
    }

    @Test
    fun `an unknown or retired font id falls back instead of failing`() {
        val fallback = VaultFonts.find(null)
        assertSame(VaultFonts.default, fallback)
        assertSame(fallback, VaultFonts.find("roboto-flex"))
        assertSame(fallback, VaultFonts.find(" INTER "))
    }

    @Test
    fun `the controller reads the default before anyone applies a choice`() {
        VaultFontController.apply(VaultFonts.DEFAULT_ID)
        assertEquals(VaultFonts.default.id, VaultFontController.current.id)
    }
}
