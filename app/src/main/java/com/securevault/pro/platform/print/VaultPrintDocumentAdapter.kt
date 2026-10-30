package com.securevault.pro.platform.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders an already-prepared PDF to the printer.
 *
 * The two methods the framework calls, and what each is for:
 *
  *  - `onLayout` reports the page count and media attributes. Without an honest page count the
 *    print dialog cannot offer a page-range field, and a wrong one prints the wrong sheets, so the
 *    count comes from [PdfRenderer] reading the actual document — never from a guess.
 *  - `onWrite` receives the page ranges the user selected and must write *those* pages into the
 *    descriptor the framework opened. Re-rendering through [PdfRenderer] rather than shipping the
 *    original bytes is what makes "pages 3-5" mean three printed sheets, and what lets each page be
 *    scaled to the printer's media size and rotated to its own orientation.
 *
 * The renderer is opened and closed per call and never held: a `PdfRenderer` pins its
 * `ParcelFileDescriptor`, and a leaked descriptor on a cache file the vault is about to delete is a
 * decrypted page the user thought they had thrown away.
 *
 * Failure is reported, not hidden: every unhappy path calls `onFailed`, which the dialog shows as a
 * failed job. Nothing in here prints a blank sheet or a placeholder page.
 */
class VaultPrintDocumentAdapter(
    private val source: File,
    private val jobName: String
) : PrintDocumentAdapter() {

    @Volatile
    private var lastAttributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        metadata: Bundle?
    ) {
        lastAttributes = newAttributes
        if (cancellationSignal.isCanceled || !source.isFile || source.length() <= 0L) {
            callback.onLayoutFailed(PREPARATION_FAILED)
            return
        }
        val pages = countPages()
        if (pages <= 0) {
            callback.onLayoutFailed(EMPTY_DOCUMENT)
            return
        }
        val info = PrintDocumentInfo.Builder(safeName(jobName))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages)
            .build()
        // contentChanged = true whenever the media size moved since the last layout, so the dialog
        // re-renders the preview instead of showing a page fitted to a different printer.
        val changed = oldAttributes.mediaSize?.id != newAttributes.mediaSize?.id ||
            oldAttributes.resolution?.horizontalDpi != newAttributes.resolution?.horizontalDpi ||
            oldAttributes.resolution?.verticalDpi != newAttributes.resolution?.verticalDpi
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        if (!source.isFile || source.length() <= 0L) {
            callback.onWriteFailed(PREPARATION_FAILED)
            return
        }
        val requested = requestedPages(pages)
        if (requested.isEmpty()) {
            callback.onWriteFailed(EMPTY_DOCUMENT)
            return
        }
        var input: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val document = PdfDocument()
        var written = 0
        var failed = false
        try {
            input = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(input)
            val total = renderer.pageCount
            for (index in requested) {
                if (cancellationSignal.isCanceled) {
                    failed = true
                    break
                }
                if (index !in 0 until total) continue
                written += drawPage(renderer, index, document, written + 1)
            }
            if (written == 0) {
                failed = true
            } else {
                FileOutputStream(destination.fileDescriptor).use { stream ->
                    document.writeTo(stream)
                    stream.flush()
                }
            }
        } catch (error: Throwable) {
            failed = true
        } finally {
            runCatching { renderer?.close() }
            runCatching { input?.close() }
            runCatching { document.close() }
        }
        if (failed) callback.onWriteFailed(PREPARATION_FAILED)
        else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
    }

    /** The framework's end-of-job hook. There is no `onDestroy` on PrintDocumentAdapter. */
    override fun onFinish() {
        lastAttributes = null
    }

    /** Expands the dialog's ranges into page indices; `ALL_PAGES` arrives as one 0..n-1 range. */
    private fun requestedPages(ranges: Array<PageRange>): List<Int> {
        val out = LinkedHashSet<Int>()
        ranges.forEach { range ->
            val low = minOf(range.start, range.end)
            val high = maxOf(range.start, range.end)
            for (index in low..high) if (index >= 0) out.add(index)
        }
        return out.sorted().toList()
    }

    /** Paints one source page onto a new sheet; returns 1 when it drew, 0 when it refused. */
    private fun drawPage(renderer: PdfRenderer, index: Int, document: PdfDocument, pageNumber: Int): Int {
        val page = runCatching { renderer.openPage(index) }.getOrNull() ?: return 0
        try {
            val size = targetSizeOf(page)
            val info = PdfDocument.PageInfo.Builder(size[0], size[1], pageNumber).create()
            val sheet = document.startPage(info)
            renderPageInto(page, sheet.canvas, size[0], size[1])
            document.finishPage(sheet)
            return 1
        } catch (error: Throwable) {
            return 0
        } finally {
            runCatching { page.close() }
        }
    }

    private fun countPages(): Int {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            renderer.pageCount
        } catch (error: Throwable) {
            0
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    /**
     * Destination sheet size in points, from the printer's media size, rotated to suit the page.
     *
     * `MediaSize` reports mils — thousandths of an inch — and a PDF page is 72 points per inch, so
     * the conversion is `mils * 72 / 1000`. The landscape swap follows the *source* page's shape: a
     * printer that advertises a portrait medium still prints a wide scan correctly when the sheet is
     * turned, whereas dropping it onto a portrait sheet leaves two white bars either side.
     */
    private fun targetSizeOf(page: PdfRenderer.Page): IntArray {
        val media = lastAttributes?.mediaSize
        val widthPoints = if (media != null) media.widthMils * 72 / 1000 else 595
        val heightPoints = if (media != null) media.heightMils * 72 / 1000 else 842
        val margin = max(12, minOf(widthPoints, heightPoints) / 25)
        val contentWidth = max(64, widthPoints - 2 * margin)
        val contentHeight = max(64, heightPoints - 2 * margin)
        return if (page.width > page.height && contentHeight > contentWidth) {
            intArrayOf(contentHeight, contentWidth)
        } else {
            intArrayOf(contentWidth, contentHeight)
        }
    }

    /**
     * Rasterises one renderer page at ~200 dpi and paints it fitted and centred onto the sheet.
     *
     * `RENDER_MODE_FOR_PRINT` asks the engine for the print-quality path (embedded fonts, real
     * vector rasterisation) instead of the screen approximation. The bitmap is recycled in the same
     * scope that allocated it: a 30-page job that held one 2400x3200 ARGB frame per page would be
     * 900 MB of native heap for a document the printer cannot even resolve.
     */
    private fun renderPageInto(page: PdfRenderer.Page, canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.WHITE)
        val pointsToPixels = 200f / 72f
        val bitmapWidth = max(64, (page.width * pointsToPixels).roundToInt())
        val bitmapHeight = max(64, (page.height * pointsToPixels).roundToInt())
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (width - drawWidth) / 2f
            val top = (height - drawHeight) / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(left, top, left + drawWidth, top + drawHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun safeName(value: String): String =
        value.replace('/', '-').replace('\\', '-').trim().ifBlank { "document" }

    companion object {
        private const val PREPARATION_FAILED = "The document could not be prepared for printing."
        private const val EMPTY_DOCUMENT = "This document has no printable pages."

        /** Sanitised job name: the framework rejects a '/' in a print job's name. */
        fun jobNameFor(displayName: String): String = displayName
            .replace('/', '-')
            .replace('\\', '-')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Secure Wallet scan" }
            .take(80)
    }
}
