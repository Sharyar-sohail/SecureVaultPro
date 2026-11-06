package com.securevault.pro.crypto.stego

import com.securevault.pro.presentation.screens.stego.StegoChannel
import com.securevault.pro.presentation.screens.stego.StegoCodec
import com.securevault.pro.presentation.screens.stego.StegoPlanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the owner actually cares about: a carrier sent as an ordinary photo through a chat app
 * still gives up its text at the other end.
 *
 * A chat app does not forward the file. It decodes the picture, rescales it, softens it and re-encodes
 * it as a middling-quality JPEG, and a re-encoder keeps the average of every 8x8 block while throwing
 * away the variation inside it. [StegoChannel] models exactly that damage, so these tests are a real
 * round trip through a hostile grid rather than a claim about one.
 *
 * Two separate things can break a mark, and both are measured here. The requantisation is answered by
 * the coarse grid, whose cells are the encoder's own blocks; the rescale is answered by the reader
 * putting the copy back on [com.securevault.pro.crypto.stego.StegoLimits.CANONICAL_LONGEST], the grid
 * every carrier is sealed on. [StegoChannel.redraw] therefore defaults to the *tight* send budget — a
 * messenger that trims below the canonical size — because a copy that lands back on the writer's grid
 * would need no rescue and prove nothing.
 *
 * The coarse grid is measured on [StegoFixtures.photoCarrier] — a 1280x960 plane, the size a picked
 * photo is actually sealed at — because a coarse band has a quarter of the cells of a fine one and a
 * 640x480 fixture has no room to host a lattice sparsely. The encoder refuses that rather than writing
 * a mark whose own corrections cancel each other out.
 */
class StegoRobustnessTest {

    private val codec = StegoFixtures.codec()
    private val phrase = "robust phrase"

    private fun request(plane: LumaPlane, text: String): StegoEncodeRequest = StegoEncodeRequest(
        carrier = plane,
        envelopes = listOf(StegoFixtures.envelope(StegoFixtures.secret(text), phrase)),
        salt = StegoFixtures.SALT
    )

    private fun read(plane: LumaPlane): String? {
        val finding = codec.scan(StegoDecodeRequest(plane, listOf(phrase.toCharArray())))
        val revealed = finding.read as? StegoRead.Revealed ?: return null
        return String(revealed.secret, Charsets.UTF_8)
    }

    @Test
    fun `the coarse grid gives the frame back after a chat app redraws the photo`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "ride the block average"), StegoProfile.ROBUST)
        assertEquals("ride the block average", read(StegoChannel.redraw(sealed.marked)))
    }

    @Test
    fun `a redrawn photo reads on both a send that rescales it and one that does not`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "two budgets"), StegoProfile.ROBUST)
        assertEquals("two budgets", read(StegoChannel.redraw(sealed.marked, StegoChannel.TIGHT_SEND_LONGEST)))
        assertEquals("two budgets", read(StegoChannel.redraw(sealed.marked, StegoChannel.SEND_LONGEST)))
    }

    @Test
    fun `a copy that arrives on a different grid is put back on the one it was written on`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "just the resize"), StegoProfile.ROBUST)
        val shrunk = StegoChannel.resized(sealed.marked, StegoChannel.TIGHT_SEND_LONGEST)
        assertTrue(
            "the rescue only means something if the copy really is off the writer's grid",
            shrunk.width != sealed.marked.width || shrunk.height != sealed.marked.height
        )
        assertEquals("just the resize", read(shrunk))
    }

    @Test
    fun `the quiet grid does not survive that same redraw`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "the fragile one"), StegoProfile.CLASSIC)
        assertNull("the fine grid survived the redraw, so the coarse grid proves nothing",
            read(StegoChannel.redraw(sealed.marked)))
    }

    @Test
    fun `a seal that will be sent picks the grid measured to survive`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "measured, not promised"), attack = {
            StegoChannel.redraw(it)
        })
        assertTrue("the seal did not measure a surviving frame", sealed.measuredSurvival)
        assertTrue("the seal was not marked as tested", sealed.sendTested)
        assertEquals(StegoProfile.ROBUST, sealed.plan.profile)
        assertEquals("measured, not promised", read(StegoChannel.redraw(sealed.marked)))
    }

    @Test
    fun `a seal with no redraw to measure keeps the grid it was given`() {
        val sealed = codec.seal(request(StegoFixtures.gentleCarrier(), "printed, not sent"))
        assertEquals(StegoProfile.CLASSIC, sealed.plan.profile)
        assertFalse(sealed.sendTested)
        assertFalse(sealed.measuredSurvival)
        assertEquals("printed, not sent", read(sealed.marked))
    }

    @Test
    fun `a carrier written on the quiet grid still reads exactly as it did`() {
        val sealed = codec.seal(request(StegoFixtures.gentleCarrier(), "older carriers"))
        assertEquals(StegoProfile.CLASSIC, sealed.plan.profile)
        assertEquals("older carriers", read(sealed.marked))
    }

    @Test
    fun `the reader tells the two grids apart without being told which one was used`() {
        val text = "one photo two grids"
        val quiet = codec.seal(request(StegoFixtures.photoCarrier(), text), StegoProfile.CLASSIC).marked
        val rugged = codec.seal(request(StegoFixtures.photoCarrier(), text), StegoProfile.ROBUST).marked
        assertEquals(text, read(quiet))
        assertEquals(text, read(rugged))
    }

    @Test
    fun `a coarse carrier still survives a print`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "printed and copied"), StegoProfile.ROBUST)
        val copied = PrintModel.press(sealed.marked, levels = 16, contrast = 1.18, noise = 4)
        assertEquals("printed and copied", read(copied))
    }

    @Test
    fun `a coarse carrier survives being looked at sideways`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "turned twice"), StegoProfile.ROBUST)
        val turned = StegoPlanes.rotated(StegoChannel.redraw(sealed.marked), 2)
        assertEquals("turned twice", read(turned))
    }

    @Test
    fun `the viewfinder probe answers on a coarse carrier`() {
        val sealed = codec.seal(request(StegoFixtures.photoCarrier(), "viewfinder"), StegoProfile.ROBUST)
        val probe = codec.probe(StegoChannel.redraw(sealed.marked))
        assertNotNull(probe)
        assertEquals(sealed.plan.lattice, probe?.lattice)
    }

    @Test
    fun `a photo too small for the coarse grid is sealed on the quiet one, and says so`() {
        val sealed = codec.seal(request(StegoFixtures.tinyPhotoCarrier(), "no room for eight"), attack = {
            StegoChannel.redraw(it)
        })
        assertEquals(StegoProfile.CLASSIC, sealed.plan.profile)
        assertFalse("an unmeasured fallback claimed survival", sealed.measuredSurvival)
        assertTrue("the redraw ran, so the seal must report that it was tested", sealed.sendTested)
    }
}
