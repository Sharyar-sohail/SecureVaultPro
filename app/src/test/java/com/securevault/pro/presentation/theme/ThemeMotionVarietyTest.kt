package com.securevault.pro.presentation.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's rejection, second half: fifteen themes shared seven movements, because motion was
 * keyed off `ThemeDesign` and two of its branches could never be reached at all.
 *
 * Motion is now a *theme* token — [motionFor] reads `palette.id`, the same deterministic key the
 * rest of the theme system uses, and composes one signature out of entry axis, arrival order,
 * easing curve, duration, cascade step, travel, size reveal, bloom, sheen and press response. Two
 * themes in the same design family still share their components; nothing here lets them share how
 * they move.
 *
 * Everything is pure arithmetic, so the signature below is what the screen does: [revealForTheme]
 * and [themeRouteEnter] are the only consumers, and both read it straight from this table.
 */
class ThemeMotionVarietyTest {

    private val ids = VaultPalettes.all.map { it.id }

    /**
     * Everything a user can feel, as one string. Colour is absent, exactly as in
     * [ThemeLayoutVarietyTest]: two themes may share a hue, they may not share a movement.
     */
    private fun ThemeMotion.signature(): String = buildString {
        append(axis).append('/').append(order).append('/').append(curve)
        append(" ms=").append(duration).append('+').append(stagger)
        append(" travel=/").append(travel)
        append(" reveal=").append(reveal).append('@').append(scaleFrom)
        append(" glow=").append(if (glow) glowRadius.value else 0f)
        append(" sweep=").append(sweep)
        append(" press=").append(press)
    }

    // ---------------------------------------------------------------------------------
    // (a) fifteen themes, fifteen movements
    // ---------------------------------------------------------------------------------
    @Test
    fun `the fifteen themes move fifteen different ways`() {
        val motions = ids.associateWith { motionFor(it) }
        for (first in ids) {
            for (second in ids) {
                if (first >= second) continue
                assertNotEquals(
                    "themes $first and $second share a motion signature " +
                        "(${motions.getValue(first).signature()} vs ${motions.getValue(second).signature()})",
                    motions.getValue(first).signature(),
                    motions.getValue(second).signature()
                )
            }
        }
        assertEquals(
            "fifteen themes must produce fifteen motion signatures, got " +
                motions.values.map { it.signature() }.distinct().size,
            15,
            motions.values.map { it.signature() }.distinct().size
        )

        // A field every theme agrees on is a field the renderer might as well hardcode. The
        // continuous choices have to split the lineup properly; the discrete ones are checked
        // against their own enum below, because a three-value behaviour cannot make fifteen.
        val continuous = mapOf(
            "single-plate length" to motions.values.map { it.duration },
            "cascade step" to motions.values.map { it.stagger },
            "travel divisor" to motions.values.map { it.travel },
            "entry size" to motions.values.map { it.scaleFrom }
        )
        continuous.forEach { (name, values) ->
            assertTrue(
                "$name only splits the lineup into ${values.distinct().size} answers",
                values.distinct().size >= 4
            )
        }
        assertEquals("every entry axis must be used", EntryAxis.values().size, motions.values.map { it.axis }.distinct().size)
        assertEquals("every arrival order must be used", RevealOrder.values().size, motions.values.map { it.order }.distinct().size)
        assertEquals("every easing curve must be used", MotionCurve.values().size, motions.values.map { it.curve }.distinct().size)
        assertEquals("every press response must be used", PressResponse.values().size, motions.values.map { it.press }.distinct().size)
        assertEquals("every size reveal must be used", TileReveal.values().size, motions.values.map { it.reveal }.distinct().size)
    }

    // ---------------------------------------------------------------------------------
    // (b) the budget: nothing laggy, nothing that hides content
    // ---------------------------------------------------------------------------------
    @Test
    fun `every theme stays inside the motion budget`() {
        for (id in ids) {
            val motion = motionFor(id)
            assertTrue(
                "$id runs a single plate for ${motion.duration} ms — outside 120..420",
                motion.duration in ThemeMotion.MIN_DURATION_MS..ThemeMotion.MAX_DURATION_MS
            )
            val cascade = motion.duration + VaultMotion.MAX_STAGGER * motion.stagger
            assertTrue(
                "$id needs $cascade ms to assemble a full board — over the ${ThemeMotion.MAX_CASCADE_MS} ms allowed",
                cascade <= ThemeMotion.MAX_CASCADE_MS
            )
            // Travel is `size / travel`, so a small divisor would fling the plate clear of the pane.
            assertTrue("$id travels /${motion.travel}", motion.travel >= 4)
            assertTrue(
                "$id enters at ${motion.scaleFrom} of its resting size — more than 15% off is a zoom, not an entrance",
                motion.scaleFrom in 0.85f..1.15f
            )
            if (motion.glow) {
                assertTrue("$id glows without a radius", motion.glowRadius > 0.dp)
            }
        }
    }

    @Test
    fun `a motion signature outside the budget is refused at construction`() {
        val base = ThemeMotion(
            EntryAxis.RISE, RevealOrder.CASCADE, MotionCurve.GLIDE, 300, 50, 12,
            TileReveal.EXPAND, 0.97f, glow = false, glowRadius = 0.dp, sweep = false,
            press = PressResponse.DIP
        )
        assertThrows(IllegalArgumentException::class.java) { base.copy(duration = 480) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(duration = 90) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(stagger = 200) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(travel = 2) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(scaleFrom = 1.6f) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(glow = true, glowRadius = 0.dp) }
    }

    // ---------------------------------------------------------------------------------
    // (c) the cascade order is arithmetic, and it is the theme's own
    // ---------------------------------------------------------------------------------
    @Test
    fun `each arrival order places its plates where the theme says`() {
        // Six rows, five slots worth of delay. An order that ignores either number is a bug the
        // board would show as one long wait on the last row.
        val count = 6
        assertEquals("CASCADE arrives in reading order", listOf(0, 1, 2, 3, 4, 5), (0 until count).map { motionSlot(RevealOrder.CASCADE, it, count) })
        assertEquals("REVERSE arrives newest first", listOf(5, 4, 3, 2, 1, 0), (0 until count).map { motionSlot(RevealOrder.REVERSE, it, count) })
        assertEquals("CENTER_OUT arrives from the middle", listOf(2, 1, 0, 1, 2, 3), (0 until count).map { motionSlot(RevealOrder.CENTER_OUT, it, count) })
        assertEquals("EDGES_IN arrives from both ends", listOf(0, 1, 2, 2, 1, 0), (0 until count).map { motionSlot(RevealOrder.EDGES_IN, it, count) })
        assertEquals("PAIRED arrives two rows at a time", listOf(0, 0, 1, 1, 2, 2), (0 until count).map { motionSlot(RevealOrder.PAIRED, it, count) })
        assertEquals("ALTERNATE arrives in two beats", listOf(0, 1, 0, 1, 0, 1), (0 until count).map { motionSlot(RevealOrder.ALTERNATE, it, count) })
        assertEquals("UNISON arrives together", listOf(0, 0, 0, 0, 0, 0), (0 until count).map { motionSlot(RevealOrder.UNISON, it, count) })

        for (order in RevealOrder.values()) {
            for (index in -2..count + 1) {
                val slot = motionSlot(order, index, count)
                assertTrue("$order at $index left the cascade range: $slot", slot in 0..VaultMotion.MAX_STAGGER)
            }
        }

        // The orders have to be used, not merely available: an arcade theme closing in from both
        // edges and an editorial theme landing on one frame are the difference the owner asked for.
        // Sharing an order is allowed — a signature is only shared when *nothing* else differs.
        val orders = ids.map { motionFor(it).order }
        assertTrue(
            "the lineup only uses ${orders.distinct().size} arrival orders",
            orders.distinct().size >= RevealOrder.values().size - 1
        )
    }

    @Test
    fun `the cascade delay is the theme's own step and never an invented one`() {
        // The delay is what the board pays for a plate's slot. A theme with no step (the editorial
        // one) must collapse to one frame; a slow theme must not exceed the budget at its last slot.
        for (id in ids) {
            val motion = motionFor(id)
            val lastSlot = motionSlot(motion.order, 5, 6)
            val delay = lastSlot * motion.stagger
            assertTrue("$id delays its sixth row by $delay ms", delay in 0..ThemeMotion.MAX_CASCADE_MS)
            if (motion.stagger == 0) {
                assertEquals("$id claims no cascade but still waits", 0, delay)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // (d) the curve and the press answer are per theme too
    // ---------------------------------------------------------------------------------
    @Test
    fun `every curve resolves to its own easing and every press to its own answer`() {
        // Easings are compared as drawn, not as objects: two curves whose shape is the same number
        // sequence are one curve wearing two names, which is what the owner objected to.
        val shapes = MotionCurve.values().associateWith { curve ->
            val easing = easingFor(curve)
            listOf(0f, 0.15f, 0.3f, 0.5f, 0.7f, 0.9f, 1f).map { easing.transform(it) }
        }
        assertEquals(
            "two curves collapsed onto one shape: ${duplicatesOf(shapes)}",
            MotionCurve.values().size,
            shapes.values.distinct().size
        )

        val answers = PressResponse.values().associateWith { press ->
            Triple(pressedScaleFor(press), pressedLiftFor(press), pressedSinkFor(press))
        }
        assertEquals(
            "two presses answer the same way: ${answers.values.groupBy { it }.filterValues { it.size > 1 }.keys}",
            PressResponse.values().size,
            answers.values.distinct().size
        )
        assertTrue("a held plate may not grow", answers.values.all { it.first in 0.9f..1f })
        assertTrue(
            "a held plate may not keep more lift than it rests with",
            answers.values.all { it.second in 0f..1f }
        )
        assertTrue(
            "SINK must stay its size and travel down instead",
            pressedScaleFor(PressResponse.SINK) == 1f && pressedSinkFor(PressResponse.SINK) > 0f
        )
        PressResponse.values().filter { it != PressResponse.SINK }.forEach {
            assertEquals("$it must not travel down", 0f, pressedSinkFor(it), 0f)
        }
        // The spring answers are the ones that overshoot; the rest settle on a tween.
        assertTrue(pressSprings(PressResponse.DIP))
        assertTrue(pressSprings(PressResponse.POP))
        assertTrue(!pressSprings(PressResponse.FLATTEN))
        assertTrue(!pressSprings(PressResponse.SINK))
        assertTrue(!pressSprings(PressResponse.HOLD))
    }

    /** The curves whose drawn shape is not their own, named for the failure message. */
    private fun duplicatesOf(shapes: Map<MotionCurve, List<Float>>): String =
        shapes.entries.groupBy { it.value }.filterValues { it.size > 1 }
            .values.flatten().joinToString { it.key.name }

    // ---------------------------------------------------------------------------------
    // (e) glow and sheen are chosen, not inherited
    // ---------------------------------------------------------------------------------
    @Test
    fun `bloom and sheen split the lineup rather than covering everything`() {
        val glowing = ids.filter { motionFor(it).glow }
        val sweeping = ids.filter { motionFor(it).sweep }
        assertTrue("too few themes light a bloom: $glowing", glowing.size in 5..10)
        assertTrue("too few themes cross a sheen: $sweeping", sweeping.size in 4..9)
        assertTrue(
            "a calm editorial theme must not bloom",
            glowing.none { motionFor(it).press == PressResponse.HOLD }
        )
        // Every bloom radius is the theme's own, so two glowing themes cannot glow identically
        // while claiming to differ.
        assertEquals(
            "glow radii must be per theme",
            glowing.size,
            glowing.map { motionFor(it).glowRadius.value }.distinct().size
        )
    }

    // ---------------------------------------------------------------------------------
    // (f) a pop is the push, run backwards
    // ---------------------------------------------------------------------------------
    @Test
    fun `a pop mirrors the theme's own entrance`() {
        for (id in ids) {
            val motion = motionFor(id)
            val back = popMotion(motion)
            assertEquals("a pop may not change the curve", motion.curve, back.curve)
            assertEquals("a pop may not change the length", motion.duration, back.duration)
            assertEquals("a pop may not change the cascade", motion.order, back.order)
            assertEquals("mirroring twice is the push again", motion.axis, popMotion(back).axis)
            if (motion.axis != EntryAxis.NIL) {
                assertNotEquals("a ${motion.axis} theme pops in the same direction it pushed", motion.axis, back.axis)
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // (g) the dead branches stay dead
    // ---------------------------------------------------------------------------------
    @Test
    fun `motion is keyed by theme id and never by design family`() {
        // The defect was a `when (palette.design)` with fifteen themes and seven answers, two of
        // whose branches nothing could reach. The table may only be read through `palette.id`.
        val byDesign = VaultPalettes.all.groupBy { motionFor(it.id).signature() }
        assertEquals("no design family may share a movement", 15, byDesign.size)

        // Every shipped id must hit its own row: falling through to the default is how a theme ends
        // up moving like another one.
        val default = motionFor(VaultPalettes.DEFAULT_ID)
        for (palette in VaultPalettes.all) {
            if (palette.id == VaultPalettes.DEFAULT_ID) continue
            assertNotEquals(
                "${palette.id} silently borrows the default theme's motion",
                default.signature(),
                motionFor(palette.id).signature()
            )
        }
        assertEquals("an unknown id must resolve to the default theme", default, motionFor("no_such_theme"))
    }

    @Test
    fun `the fifteen palettes and the fifteen layouts both cover the table`() {
        // motionFor has one row per shipped palette; a retired id falls through to the default,
        // which is the only fall-through allowed.
        val layouts = VaultLayout.values().toList()
        assertEquals("fifteen layouts", 15, layouts.size)
        assertEquals("fifteen palettes", 15, VaultPalettes.all.size)
        assertEquals(
            "every layout must belong to exactly one shipped palette",
            layouts.toSet(),
            VaultPalettes.all.map { it.layout }.toSet()
        )
        for (palette in VaultPalettes.all) {
            // The dock and the motion are keyed off the same two tokens the bar and the board read,
            // so a theme cannot be half-finished: a unique dock with a borrowed movement is the bug.
            assertTrue("${palette.id} has no dock", dockPlan(palette, 5).itemWeights.size == 5)
            assertTrue("${palette.id} has no motion", motionFor(palette.id).duration > 0)
        }
    }
}
