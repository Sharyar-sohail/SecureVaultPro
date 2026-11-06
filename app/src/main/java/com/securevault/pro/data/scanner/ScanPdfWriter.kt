package com.securevault.pro.data.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * One page's worth of pixels, handed over lazily.
 *
 * This is the shape requirement H forced: a 40-page session cannot hold 40 decoded bitmaps, so the
 * PDF writer asks for page *i*, draws it, releases it and only then asks for page *i+1*. The
 * session VM's implementation decodes from the retained JPEG bytes; the print adapter's
 * implementation does the same for a list of saved images.
 */
interface ScanPageProvider {
    val pageCount: Int

    /** Decodes (or returns cached) pixels for [index]; null when the page cannot be read. */
    fun acquire(index: Int): Bitmap?

    /** Called for every bitmap [acquire] returned, exactly once, after it has been drawn. */
    fun release(index: Int, bitmap: Bitmap)
}

/**
 * Multi-page PDF export on `android.graphics.pdf.PdfDocument`.
 *
 * Nothing custom about the container — `PdfDocument` writes a real PDF that Android, Drive and
 * every viewer in the vault already open — and that is the reason printing a scan needs no new
 * dependency: the same bytes go to `PdfRenderer` on the print side.
 *
 * Sizing is aspect-preserving and orientation-aware: a landscape page gets a landscape sheet
 * instead of a portrait sheet with two grey bars, the image is fitted into the printable area at
 * its own ratio (never stretched), and it is centred. The default margin, 24 pt ≈ 8.5 mm, is wide
 * enough that the page does not lose content in a printer's unprintable border.
 */
object ScanPdfWriter {

    /** ISO A4 in PostScript points: 210 x 297 mm at 72 dpi. */
    const val A4_PORTRAIT_WIDTH = 595
    const val A4_PORTRAIT_HEIGHT = 842

    /** Printable inset per edge, in points. */
    const val MARGIN_POINTS = 24f

    class Failure(val message: String)

    /**
     * Writes [provider]'s pages into a PDF.
     *
     * @param pages how many pages the document should carry, after any reorder/delete the user did.
     * @param onProgress fraction in 0..1, one call per finished page; safe to wire straight to a
     *   Compose state read.
     * @param cancelled consulted before every page so a long export can be abandoned without
     *   leaving a half-written file behind (the temp target is deleted in that case).
     * @return the number of pages written, or a [Failure].
     */
    fun compose(
        provider: ScanPageProvider,
        target: OutputStream,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Pair<Int, Failure?> {
        if (provider.pageCount <= 0) return 0 to Failure("There are no pages to export.")
        val document = PdfDocument()
        var written = 0
        var failure: Failure? = null
        try {
            for (index in 0 until provider.pageCount) {
                if (cancelled()) {
                    failure = Failure("The export was cancelled.")
                    break
                }
                val bitmap = provider.acquire(index)
                if (bitmap == null) {
                    failure = Failure("Page ${index + 1} could not be read.")
                    break
                }
                try {
                    val pageSize = pageSizeFor(bitmap.width, bitmap.height)
                    val info = PdfDocument.PageInfo.Builder(pageSize[0], pageSize[1], index + 1).create()
                    val page = document.startPage(info)
                    drawPageFitted(page.canvas, bitmap, pageSize[0], pageSize[1])
                    document.finishPage(page)
                    written += 1
                    onProgress((index + 1).toFloat() / provider.pageCount)
                } catch (error: Throwable) {
                    failure = Failure(error.message ?: "A page could not be drawn.")
                    break
                } finally {
                    provider.release(index, bitmap)
                }
            }
            if (failure == null && written > 0) {
                document.writeTo(target)
                target.flush()
            }
        } catch (error: Throwable) {
            failure = Failure(error.message ?: "The PDF could not be written.")
        } finally {
            runCatching { document.close() }
        }
        if (written == 0 && failure == null) failure = Failure("No pages were written.")
        return written to failure
    }

    /**
     * Sheet size for a page: A4 in whichever orientation the pixels are in.
     *
     * The comparison is on the bitmap's own shape, so a rectified landscape receipt comes out as a
     * landscape sheet and the fitted image keeps its ratio either way.
     */
    fun pageSizeFor(imageWidth: Int, imageHeight: Int): IntArray =
        if (imageWidth > imageHeight) intArrayOf(A4_PORTRAIT_HEIGHT, A4_PORTRAIT_WIDTH)
        else intArrayOf(A4_PORTRAIT_WIDTH, A4_PORTRAIT_HEIGHT)

    /** White sheet, then the image scaled to fit inside the margins, centred. Never distorted. */
    fun drawPageFitted(canvas: Canvas, bitmap: Bitmap, pageWidth: Int, pageHeight: Int) {
        canvas.drawColor(Color.WHITE)
        val availableWidth = pageWidth - 2 * MARGIN_POINTS
        val availableHeight = pageHeight - 2 * MARGIN_POINTS
        if (availableWidth <= 0f || availableHeight <= 0f) return
        val scale = min(availableWidth / bitmap.width, availableHeight / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (pageWidth - drawWidth) / 2f
        val top = (pageHeight - drawHeight) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
    }

    /** Convenience for the one-call case: write straight to a file, replacing anything there. */
    fun composeToFile(
        provider: ScanPageProvider,
        file: File,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Pair<Int, Failure?> {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { stream ->
            val (written, failure) = compose(provider, stream, onProgress, cancelled)
            if (failure != null) runCatching { file.delete() }
            return written to failure
        }
    }

    /** The pixel size a page buffer should be decoded to for a print or PDF job at ~200 dpi. */
    fun pagePixelCap(): Int = max(1200, A4_PORTRAIT_HEIGHT * 200 / 72)
}
