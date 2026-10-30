package com.securevault.pro.platform.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import com.securevault.pro.data.scanner.ScanBitmaps
import com.securevault.pro.data.scanner.ScanPageProvider
import com.securevault.pro.data.scanner.ScanPdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * The one call any screen makes to put paper in a printer.
 *
 * `PrintManager` needs a file on disk and an adapter that can render it, so the job of this object
 * is to turn whatever the vault actually holds — a sealed scan, an in-memory page session, a
 * decrypted stego image — into a PDF the adapter can read, and to do the expensive part off the
 * main thread:
 *
 *  1. The source is resolved on a single background thread. A PDF passes straight through (no
 *     re-encode, no quality loss, no second copy of a file that is already decrypted in the cache).
 *     An image, a page list or a bitmap is composed into a PDF with [ScanPdfWriter], one page at a
 *     time, so a 40-page job peaks at one decoded page rather than 40.
 *  2. `PrintManager.print` is dispatched back to the main thread, because the print framework
 *     drives a UI state machine and the call must be made on a looper thread.
 *  3. Every failure surfaces on `onError`; nothing is swallowed and nothing prints a blank sheet.
 *
 * The job name is sanitised: `PrintManager` rejects a name containing `/`, which is exactly what a
 * vault file called `scan_document/2.pdf` would produce.
 *
 * Files this writes live in `cacheDir/print/`, which is *not* a FileProvider path — printing reads
 * the file directly through `ParcelFileDescriptor`, so no URI grant is involved and no share of a
 * decrypted page is published by accident. Old jobs are cleared on entry, so a print of a
 * decrypted page does not outlive the dialog that printed it.
 */
object VaultPrint {

    /** Cache subdirectory holding prepared jobs. Not exposed through FileProvider. */
    private const val PRINT_DIR = "print"

    /** A4 at 72 points per inch — the unit `PdfDocument` canvases are drawn in. */
    private const val PAGE_WIDTH_POINTS = 595
    private const val PAGE_HEIGHT_POINTS = 842
    private const val TEXT_MARGIN_POINTS = 48f
    private const val TEXT_POINTS = 10.5f
    private const val MAX_TEXT_PAGES = 200

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vault-print").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** False on a device with no print service (a tablet without the framework's print spooler). */
    fun isAvailable(context: Context): Boolean =
        runCatching {
            context.getSystemService(Context.PRINT_SERVICE) is PrintManager
        }.getOrDefault(false)

    /**
     * Prints [source] under the name [jobName].
     *
     * Safe to call from a composable click handler: the heavy work happens on the daemon worker and
     * only the final framework call returns to the main thread.
     */
    fun print(context: Context, source: PrintSource, jobName: String) {
        print(context, source, jobName, onError = null)
    }

    /** Same, with a user-visible failure path. */
    fun print(
        context: Context,
        source: PrintSource,
        jobName: String,
        onError: ((String) -> Unit)?
    ) {
        val appContext = context.applicationContext
        val name = VaultPrintDocumentAdapter.jobNameFor(jobName)
        if (!isAvailable(appContext)) {
            report(onError, "This device has no print service.")
            return
        }
        if (source is PrintSource.Unsupported) {
            report(onError, source.reason)
            return
        }
        worker.execute {
            val prepared = runCatching { prepare(appContext, source) }
                .onFailure { error ->
                    report(onError, error.message ?: "The document could not be prepared.")
                }
                .getOrNull()
            if (prepared == null) return@execute
            main.post {
                runCatching {
                    val manager = appContext.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    manager.print(
                        name,
                        VaultPrintDocumentAdapter(prepared, name),
                        android.print.PrintAttributes.Builder()
                            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                            .setColorMode(android.print.PrintAttributes.COLOR_MODE_COLOR)
                            // No resolution is requested on purpose: the framework substitutes the
                            // printer's own recommended mode, which is always available, whereas the
                            // named RECOMMENDED constant is @hide and a hardcoded dpi can be refused.
                            .build()
                    )
                }.onFailure { error ->
                    report(onError, error.message ?: "The print job could not be started.")
                }
            }
        }
    }

    /**
     * Turns any supported source into a printable PDF on disk.
     *
     * Exposed for tests and for callers that want to preview the bytes before printing; a PDF
     * source is returned as-is, everything else is composed page by page.
     */
    fun prepare(context: Context, source: PrintSource): File {
        val directory = File(context.cacheDir, PRINT_DIR).apply { mkdirs() }
        clearOldJobs(directory)
        return when (source) {
            is PrintSource.PdfFile -> {
                if (!source.file.isFile || source.file.length() <= 0L) {
                    throw IllegalArgumentException("The PDF is not readable.")
                }
                source.file
            }
            is PrintSource.PdfBytes -> {
                if (source.bytes.isEmpty()) throw IllegalArgumentException("The PDF is empty.")
                write(directory, source.displayName, source.bytes)
            }
            is PrintSource.ImagePages -> composePages(directory, source.displayName, source.pages)
            is PrintSource.StegoImage -> composePages(directory, source.displayName, listOf(source.bytes))
            is PrintSource.TextBytes -> composeText(directory, source.displayName, source.bytes)
            is PrintSource.Unsupported -> throw IllegalArgumentException(source.reason)
        }
    }

    /** Convenience for a screen holding a live bitmap (the review sheet's current page). */
    fun printBitmap(context: Context, bitmap: Bitmap, jobName: String, onError: ((String) -> Unit)? = null) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        print(context, PrintSource.ImagePages(listOf(stream.toByteArray()), jobName), jobName, onError)
    }

    /**
     * Composes JPEG/PNG pages into a PDF and returns the file.
     *
     * [ScanPageProvider] decodes exactly one page at a time and recycles it immediately after the
     * sheet is drawn, which is requirement H's rule applied to the print path: the cap is not "keep
     * three bitmaps", it is "never keep more than one at a time in here".
     */
    private fun composePages(directory: File, displayName: String, pages: List<ByteArray>): File {
        if (pages.isEmpty()) throw IllegalArgumentException("There are no pages to print.")
        val target = File(directory, "${slug(displayName)}.pdf")
        val provider = object : ScanPageProvider {
            override val pageCount: Int get() = pages.size

            override fun acquire(index: Int): Bitmap? = runCatching {
                val bytes = pages.getOrNull(index) ?: return null
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                ScanBitmaps.fitWithin(decoded, ScanPdfWriter.pagePixelCap())
                    .also { if (it !== decoded) decoded.recycle() }
            }.getOrNull()

            override fun release(index: Int, bitmap: Bitmap) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        val (written, failure) = FileOutputStream(target).use { stream ->
            ScanPdfWriter.compose(provider, stream)
        }
        if (failure != null || written == 0) {
            target.delete()
            throw IllegalStateException(failure?.message ?: "The pages could not be assembled.")
        }
        return target
    }

    private fun write(directory: File, displayName: String, bytes: ByteArray): File {
        val target = File(directory, "${slug(displayName)}.pdf")
        FileOutputStream(target).use { it.write(bytes) }
        return target
    }

    /**
     * Lays UTF-8 text onto A4 sheets, one page at a time.
     *
     * The viewer can show a `.txt`/`.md`/`.csv` and the text it extracts from an office document,
     * so refusing to print them would be the silent hole this pipeline exists to avoid. The text is
     * split into lines at the page width with [Paint.breakText], and every page is finished before
     * the next one starts, so a long document never holds more than one page of canvas. A document
     * that would need more than [MAX_TEXT_PAGES] is refused with the page count, because a spooler
     * job of a thousand blank-looking sheets is worse than an honest "too long".
     */
    private fun composeText(directory: File, displayName: String, bytes: ByteArray): File {
        val text = String(bytes, Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (text.isBlank()) throw IllegalArgumentException("This document has no text to print.")

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = TEXT_POINTS
            typeface = Typeface.MONOSPACE
        }
        val margin = TEXT_MARGIN_POINTS
        val contentWidth = PAGE_WIDTH_POINTS - 2 * margin
        val lineHeight = paint.fontSpacing
        val linesPerPage = (((PAGE_HEIGHT_POINTS - 2 * margin) / lineHeight).toInt()).coerceAtLeast(1)
        val lines = wrapText(text, paint, contentWidth)
        val pageCount = ceil(lines.size.toFloat() / linesPerPage).toInt().coerceAtLeast(1)
        if (pageCount > MAX_TEXT_PAGES) {
            throw IllegalArgumentException(
                "This text needs $pageCount pages; one job is limited to $MAX_TEXT_PAGES pages."
            )
        }

        val target = File(directory, "${slug(displayName)}.pdf")
        val document = PdfDocument()
        try {
            for (pageIndex in 0 until pageCount) {
                val info = PdfDocument.PageInfo
                    .Builder(PAGE_WIDTH_POINTS, PAGE_HEIGHT_POINTS, pageIndex + 1)
                    .create()
                val page = document.startPage(info)
                var baseline = margin + TEXT_POINTS
                val first = pageIndex * linesPerPage
                val last = minOf(first + linesPerPage, lines.size)
                for (index in first until last) {
                    page.canvas.drawText(lines[index], margin, baseline, paint)
                    baseline += lineHeight
                }
                document.finishPage(page)
            }
            FileOutputStream(target).use { document.writeTo(it) }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        } finally {
            runCatching { document.close() }
        }
        if (!target.isFile || target.length() <= 0L) {
            target.delete()
            throw IllegalStateException("The text could not be laid out for printing.")
        }
        return target
    }

    /** Word-wraps [text]; a word longer than the sheet is cut at the page edge, never dropped. */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        text.split('\n').forEach { raw ->
            val line = raw.replace("\t", "    ").trimEnd()
            if (line.isEmpty()) {
                lines += ""
                return@forEach
            }
            var start = 0
            while (start < line.length) {
                var count = paint.breakText(line, start, line.length, true, maxWidth, null)
                if (count <= 0) count = 1
                val end = start + count
                if (end < line.length) {
                    val space = line.lastIndexOf(' ', end - 1)
                    if (space > start) count = space - start + 1
                }
                lines += line.substring(start, start + count).trimEnd()
                start += count
            }
        }
        return lines
    }

    /** Drops yesterday's decrypted sheets before starting a job; only the new one stays. */
    private fun clearOldJobs(directory: File) {
        directory.listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun slug(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "print_job" }
        .take(64) + "_" + System.currentTimeMillis()

    private fun report(onError: ((String) -> Unit)?, message: String) {
        onError?.let { handler -> main.post { handler(message) } }
    }

    /**
     * Quick structural check that a prepared file is a PDF the renderer will accept, used before a
     * job is handed to the framework so an obviously broken container fails here, with a message,
     * instead of as a silent failed job in the notification shade.
     */
    fun isRenderable(file: File): Boolean {
        var descriptor: android.os.ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            renderer.pageCount > 0
        } catch (error: Throwable) {
            false
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

}
