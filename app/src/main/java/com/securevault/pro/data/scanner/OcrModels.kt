package com.securevault.pro.data.scanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Recognised text, as the scanner models it.
 *
 * ML Kit's on-device Latin recogniser returns `Text → Block → Line → Element` with a bounding box
 * per level. It does **not** publish a confidence at any of them (that field only exists on the
 * cloud recognizer), so [legibility] here is a measured property of the *pixels inside the block's
 * box*, not a fake API number: stroke density, contrast and edge sharpness, computed by
 * [OcrQuality]. It answers "how much should the user trust this text", which is what a confidence
 * is for, and the HANDOFF says plainly that it is derived rather than returned.
 */
data class OcrElementText(val text: String, val bounds: OcrBounds)

data class OcrLineText(
    val text: String,
    val bounds: OcrBounds,
    val elements: List<OcrElementText>
)

data class OcrBlockText(
    val text: String,
    val bounds: OcrBounds,
    val lines: List<OcrLineText>,
    /** 0..1 measured legibility of this block's pixels; see [OcrQuality]. */
    val legibility: Double
)

/** An axis-aligned rectangle in image pixels. */
data class OcrBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = max(0, right - left)
    val height: Int get() = max(0, bottom - top)
    val area: Int get() = width * height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun clampedToPlane(width: Int, height: Int): OcrBounds = OcrBounds(
        left.coerceIn(0, max(0, width - 1)),
        top.coerceIn(0, max(0, height - 1)),
        min(right, width).coerceAtLeast(0),
        min(bottom, height).coerceAtLeast(0)
    )
}

/** Everything one page's recognition produced, in one object the UI can render and copy from. */
data class OcrPage(
    val width: Int,
    val height: Int,
    val blocks: List<OcrBlockText>,
    val fullText: String
) {
    val isEmpty: Boolean get() = fullText.isBlank()

    /** Mean legibility over the blocks that hold text; 0 when there are none. */
    val legibility: Double
        get() {
            val usable = blocks.filter { it.text.isNotBlank() }
            if (usable.isEmpty()) return 0.0
            return usable.sumOf { it.legibility } / usable.size
        }

    /** Text with one blank line per block, which is how the note body is written. */
    fun toParagraphText(): String = blocks.joinToString("\n\n") { it.text.trim() }
}

/**
 * The legibility measure behind [OcrBlockText.legibility].
 *
 * Three ingredients, all read from the rectified page buffer:
 *
 *  - **ink ratio** — the fraction of texels the local threshold calls ink. Text sits in a band:
 *    under 1 % is a blank box, over 45 % is a photograph or a black bar, not a line of type.
 *  - **stroke contrast** — the Otsu split inside the box, i.e. how far the ink is from the paper.
 *    A correctly recognised line is 100+ grey levels apart from its background.
 *  - **edge density** — the mean Sobel response in the box, which is where the "is it actually
 *    sharp" question is answered: a blurry page and a sharp page have almost the same ink ratio
 *    and very different gradient energy.
 *
 * The three are combined with fixed weights so the number is reproducible and testable; the result
 * is not a probability and is never presented as one.
 */
object OcrQuality {

    const val INK_WEIGHT: Double = 0.30
    const val CONTRAST_WEIGHT: Double = 0.30
    const val EDGE_WEIGHT: Double = 0.40

    /** Ink ratios that still look like text, as `low..high` of the box area. */
    val TEXT_INK_BAND: ClosedRange<Double> = 0.012..0.45

    fun legibility(page: ArgbPlane, bounds: OcrBounds): Double {
        val box = bounds.clampedToPlane(page.width, page.height)
        if (box.width < 4 || box.height < 4) return 0.0
        val grey = cropLuma(page, box)
        val hist = Thresholds.histogram(grey.pixels)
        val threshold = Thresholds.otsu(hist)
        val separation = Thresholds.otsuSeparation(hist)
        var ink = 0
        var inkSum = 0L
        var paperSum = 0L
        for (value in grey.pixels) {
            if (value <= threshold) {
                ink += 1
                inkSum += value
            } else {
                paperSum += value
            }
        }
        val ratio = ink.toDouble() / max(1, grey.size)
        val band = when {
            ratio < TEXT_INK_BAND.start -> 0.0
            ratio > TEXT_INK_BAND.endInclusive -> 0.15
            else -> 1.0
        }
        val meanInk = if (ink == 0) 0.0 else inkSum.toDouble() / ink
        val meanPaper = if (ink == grey.size) 255.0 else paperSum.toDouble() / (grey.size - ink)
        val contrast = ((meanPaper - meanInk) / 200.0).coerceIn(0.0, 1.0)
        val gradient = EdgeMap.sobel(grey)
        val edge = Thresholds.percentile(Thresholds.histogram(gradient.pixels), 0.85) / 255.0
        val measured = INK_WEIGHT * band + CONTRAST_WEIGHT * contrast + EDGE_WEIGHT * edge.coerceIn(0.0, 1.0)
        // Separation is a gate, not a contributor: a box with no bimodal split at all is a flat
        // region the recogniser hallucinated over, and no amount of "edge" should score it up.
        return if (separation < 0.10) measured * 0.35 else measured
    }

    /** A single number for a whole page, used by the "text may be unreliable" warning. */
    fun pageLegibility(page: ArgbPlane, blocks: List<OcrBlockText>): Double {
        val usable = blocks.filter { !it.bounds.isEmpty }
        if (usable.isEmpty()) return 0.0
        return usable.sumOf { legibility(page, it.bounds) } / usable.size
    }

    private fun cropLuma(page: ArgbPlane, box: OcrBounds): LumaPlane {
        // Cap the analysis window: a 2400-px block is downsampled so a 40-page OCR does not spend
        // its time in Sobel on texels nobody can read anyway.
        val maxSide = 512
        val step = max(1, max(box.width, box.height) / maxSide)
        val width = max(1, box.width / step)
        val height = max(1, box.height / step)
        val out = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            val sourceY = min(page.height - 1, box.top + y * step)
            val row = sourceY * page.width
            for (x in 0 until width) {
                val sourceX = min(page.width - 1, box.left + x * step)
                val color = page.pixels[row + sourceX]
                out[index] = lumaOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
                index += 1
            }
        }
        return LumaPlane(width, height, out)
    }

    /** Distance helper kept with the measure so the weights above are the only magic numbers. */
    fun norm(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy)
}
