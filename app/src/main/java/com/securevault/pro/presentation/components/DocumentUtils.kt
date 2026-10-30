package com.securevault.pro.presentation.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

fun queryDisplayName(context: Context, uri: Uri, fallback: String = "backup.enc"): String {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: fallback
}

fun queryDocumentSize(context: Context, uri: Uri): Long {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                0L
            }
        }
    }.getOrNull() ?: 0L
}

class DocumentTooLargeException(message: String) : Exception(message)

suspend fun readDocumentBytes(
    context: Context,
    uri: Uri,
    limit: Long = 120L * 1024L * 1024L
): ByteArray = withContext(Dispatchers.IO) {
    val declared = queryDocumentSize(context, uri)
    if (declared > limit) {
        throw DocumentTooLargeException("This file is larger than 120 MB")
    }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw DocumentTooLargeException("This file is larger than 120 MB")
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: throw IllegalStateException("The selected file could not be opened")
}
