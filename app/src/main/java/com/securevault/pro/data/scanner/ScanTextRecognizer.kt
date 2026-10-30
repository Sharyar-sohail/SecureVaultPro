package com.securevault.pro.data.scanner

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit text recognition, mapped into the scanner's own model.
 *
 * `ScanSupport.recognizeText` already did the important part — one client per call, closed in a
 * `finally`, the coroutine resumed on the listener — and that shape is kept: the ML Kit recognizer
 * owns a native model handle and leaking one per scan is how a camera session dies on the fourth
 * page. What is added here is structure: blocks, lines and words with their pixel boxes, so the
 * result is *selectable and placeable* text rather than a string, plus the measured per-block
 * legibility from [OcrQuality].
 *
 * Note on confidence: the on-device Latin recogniser exposes no confidence field at any level
 * (`Text`, `TextBlock`, `TextLine`, `TextElement` carry text + box only). [OcrBlockText.legibility]
 * is computed from the page pixels inside that box and is documented as such — nothing here is
 * dressed up as a probability the SDK never returned.
 */
object ScanTextRecognizer {

    /** The recogniser is a singleton per process in ML Kit's own docs; making one per page is fine
     *  and closing it is mandatory, so that is exactly what happens below. */
    private val options = TextRecognizerOptions.DEFAULT_OPTIONS

    suspend fun recognize(bitmap: Bitmap): OcrPage {
        val client = TextRecognition.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = try {
            suspendCancellableCoroutine { continuation ->
                client.process(image)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
        } finally {
            runCatching { client.close() }
        }
        return map(text, bitmap)
    }

    /** Maps ML Kit's tree onto the pure model; also usable by a cached result. */
    fun map(text: Text, page: Bitmap): OcrPage {
        val plane = ScanBitmaps.argbOf(page)
        return map(text, plane)
    }

    fun map(text: Text, plane: ArgbPlane): OcrPage {
        val blocks = text.textBlocks.mapNotNull { block ->
            val lines = block.lines.map { line ->
                OcrLineText(
                    text = line.text,
                    bounds = boundsOf(line.boundingBox, plane.width, plane.height),
                    elements = line.elements.map { element ->
                        OcrElementText(
                            text = element.text,
                            bounds = boundsOf(element.boundingBox, plane.width, plane.height)
                        )
                    }
                )
            }
            val box = block.boundingBox?.let { boundsOf(it, plane.width, plane.height) }
                ?: unionOf(lines.map { it.bounds })
            if (block.text.isBlank() && box.isEmpty) return@mapNotNull null
            OcrBlockText(
                text = block.text,
                bounds = box,
                lines = lines,
                legibility = if (box.isEmpty) 0.0 else OcrQuality.legibility(plane, box)
            )
        }
        return OcrPage(
            width = plane.width,
            height = plane.height,
            blocks = blocks,
            fullText = text.text ?: blocks.joinToString("\n") { it.text }
        )
    }

    /**
     * ML Kit hands boxes in the coordinate space of the *input image*, with no rotation metadata
     * applied: the caller must pass the same upright bitmap it fed into [recognize], or the boxes
     * will sit on the wrong words. This package keeps that promise — the rectified page is the only
     * thing ever given to the recogniser.
     */
    private fun boundsOf(box: android.graphics.Rect?, planeWidth: Int, planeHeight: Int): OcrBounds {
        if (box == null || box.width() <= 0 || box.height() <= 0) {
            return OcrBounds(0, 0, 0, 0)
        }
        return OcrBounds(box.left, box.top, box.right, box.bottom).clampedToPlane(planeWidth, planeHeight)
    }

    private fun unionOf(boxes: List<OcrBounds>): OcrBounds {
        val usable = boxes.filter { !it.isEmpty }
        if (usable.isEmpty()) return OcrBounds(0, 0, 0, 0)
        return OcrBounds(
            usable.minOf { it.left },
            usable.minOf { it.top },
            usable.maxOf { it.right },
            usable.maxOf { it.bottom }
        )
    }
}
