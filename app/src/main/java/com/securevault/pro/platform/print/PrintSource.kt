package com.securevault.pro.platform.print

import android.graphics.Bitmap
import java.io.File

/**
 * Anything the print pipeline is allowed to be handed.
 *
 * Four shapes cover every case in the app, and each of them is a *file or bytes the vault already
 * owns* — the printer is never given a path it could not have produced itself:
 *
 *  - [PdfFile] / [PdfBytes]: a real PDF, either a sealed scan from `FileVaultRepository.exportToCache`
 *    or a PDF assembled in memory from a live session that has not been sealed yet. Routed to the
 *    renderer straight away, so the print preview shows the actual document.
 *  - [ImagePages]: one or more JPEG/PNG pages (a scan session, an OCR capture) that have to be
 *    composed into a PDF first. That composition streams page by page and is done off the main
 *    thread by [VaultPrint].
 *  - [StegoImage]: a single image, visible pixels only. A stego container's payload is invisible by
 *    construction — the secret lives in the low bits of channels no printer can express — so what
 *    comes out of the tray is the cover image, which is also what the vault's own viewer shows.
 *  - [TextBytes]: UTF-8 text (a note, a .txt import, text extracted from an office document) laid
 *    out onto A4 sheets. The viewer can show such a document, so a Print button that refused it
 *    would be the "silently broken" case this list exists to avoid.
 *  - [Unsupported]: a type the app cannot rasterise (docx, zip, audio). [VaultPrint] reports it
 *    instead of printing a blank sheet, which is the difference between "not supported" and
 *    "silently broken".
 */
sealed class PrintSource {

    abstract val displayName: String

    class PdfFile(val file: File, override val displayName: String) : PrintSource()

    class PdfBytes(val bytes: ByteArray, override val displayName: String) : PrintSource()

    class ImagePages(val pages: List<ByteArray>, override val displayName: String) : PrintSource()

    class StegoImage(val bytes: ByteArray, override val displayName: String) : PrintSource()

    class TextBytes(val bytes: ByteArray, override val displayName: String) : PrintSource()

    class Unsupported(val reason: String, override val displayName: String) : PrintSource()

    companion object {

        /** Convenience for a bitmap a screen is already holding, e.g. a review-sheet page. */
        fun ofBitmap(bitmap: Bitmap, displayName: String, quality: Int = 92): PrintSource {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            return ImagePages(listOf(stream.toByteArray()), displayName)
        }

        fun ofFile(file: File, mimeType: String, displayName: String = file.name): PrintSource = when {
            !file.exists() || file.length() <= 0L -> Unsupported("The file is not readable.", displayName)
            mimeType.equals("application/pdf", ignoreCase = true) -> PdfFile(file, displayName)
            mimeType.startsWith("image/") -> StegoImage(readBytes(file), displayName)
            isTextMime(mimeType) -> TextBytes(readBytes(file), displayName)
            else -> Unsupported("This file type cannot be printed here. Open it and print from its viewer.", displayName)
        }

        fun ofBytes(bytes: ByteArray, mimeType: String, displayName: String): PrintSource = when {
            bytes.isEmpty() -> Unsupported("There is nothing to print.", displayName)
            mimeType.equals("application/pdf", ignoreCase = true) ->
                PdfBytes(bytes, displayName)
            mimeType.startsWith("image/jpeg") || mimeType.startsWith("image/png") ->
                ImagePages(listOf(bytes), displayName)
            isTextMime(mimeType) -> TextBytes(bytes, displayName)
            else -> Unsupported("This file type cannot be printed here. Open it and print from its viewer.", displayName)
        }

        /** The text family [VaultPrint] can lay out: plain text, markdown, CSV, JSON, XML, HTML. */
        fun isTextMime(mimeType: String): Boolean {
            val mime = mimeType.lowercase()
            return mime.startsWith("text/") || mime == "application/json" || mime == "application/xml"
        }

        private fun readBytes(file: File): ByteArray = runCatching {
            file.readBytes()
        }.getOrDefault(ByteArray(0))
    }
}
