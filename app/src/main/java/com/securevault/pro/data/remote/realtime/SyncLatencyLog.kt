package com.securevault.pro.data.remote.realtime

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The measured numbers for one sync event. Only ever identifiers and timestamps — never a record
 * field, a clock value that came from the wire alone, or anything secret.
 */
data class SyncLatencySample(
    val module: String,
    val id: String,
    /** Push path: local ms when the write was issued. */
    val tWrite: Long?,
    /** Push path: ms when the write was honoured (offline queue accepted it). */
    val tAck: Long?,
    /** Receive path: ms the listener fired for a remote change. */
    val tReceive: Long?,
    /** Receive path: `tReceive - u`, the number the acceptance metric is about. */
    val deltaMs: Long?,
    /** Wall-clock stamp of the log line, for the Settings readout. */
    val at: Long
)

/**
 * The latency history `SPEC-v1.md` §"Latency instrumentation" demands: every push writes
 * `tWrite`/`tAck`, every receive writes `tReceive` and the receiving-side delta `tReceive - u`,
 * appended to `Downloads/SecureVaultSync.txt`.
 *
 * The two-path write structure is copied from `diagnostics/CrashLogger.kt` (MediaStore Downloads on
 * API 29+, `getExternalStoragePublicDirectory` fallback below it) because that is the only way an
 * owner with no `adb` can read the numbers back. Unlike the crash report, this log is **append-only**
 * — a latency history is the whole point, so a new row is opened and written `wa` rather than
 * replacing the file. Every write is wrapped: a failure to record a number must never break a sync.
 *
 * The last sample is published through [latest] so the Settings screen can show live milliseconds
 * without parsing the file.
 */
@Singleton
class SyncLatencyLog @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _latest = MutableStateFlow<SyncLatencySample?>(null)
    val latest: StateFlow<SyncLatencySample?> = _latest.asStateFlow()

    /** Total receive-side samples; feeds the "records pending / events" readout. */
    private val _eventCount = MutableStateFlow(0)
    val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

    fun noteWrite(module: String, id: String, tWrite: Long, tAck: Long) {
        val now = System.currentTimeMillis()
        val sample = SyncLatencySample(
            module = module,
            id = id,
            tWrite = tWrite,
            tAck = tAck,
            tReceive = null,
            deltaMs = null,
            at = now
        )
        publish(sample, "PUSH  $module/$id  tWrite=$tWrite tAck=$tAck ack-lag=${(tAck - tWrite).coerceAtLeast(0)}ms")
    }

    /**
     * The metric the program is graded on: how long after the sender's `u` this device saw the
     * change. A negative delta means the two clocks disagree; it is logged, never clamped away.
     */
    fun noteReceive(module: String, id: String, u: Long, tReceive: Long) {
        val delta = tReceive - u
        val sample = SyncLatencySample(
            module = module,
            id = id,
            tWrite = null,
            tAck = null,
            tReceive = tReceive,
            deltaMs = delta,
            at = tReceive
        )
        publish(sample, "RECV  $module/$id  u=$u tReceive=$tReceive delta=${delta}ms")
    }

    private fun publish(sample: SyncLatencySample, line: String) {
        _latest.value = sample
        _eventCount.value = _eventCount.value + 1
        appendLine(line)
    }

    private fun appendLine(line: String) {
        val stamped = "${stamp()} $line\n"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (appendViaMediaStore(stamped)) return
        }
        appendLegacy(stamped)
    }

    /** MediaStore Downloads append: find our row, open it `wa`, otherwise create it. Never throws. */
    private fun appendViaMediaStore(text: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val existing = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(FILE_NAME),
            null
        )
        val uri = existing?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            } else null
        }
        val target = uri ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: return@runCatching false
        // "wa" appends; the crash log replaces in place, a latency history must not.
        resolver.openOutputStream(target, "wa")?.use { it.write(text.toByteArray()) } != null
    }.getOrElse {
        appendLegacy(text)
        true
    }

    private fun appendLegacy(text: String) {
        runCatching {
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(directory, FILE_NAME).appendText(text)
        }.onFailure {
            // A denied legacy write is a lost number, not a crash — say so once and carry on.
            Log.w(TAG, "Sync latency log could not be written")
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    companion object {
        const val FILE_NAME = "SecureVaultSync.txt"
        const val REPORT_LOCATION = "Downloads/SecureVaultSync.txt"
        private const val TAG = "SyncLatencyLog"
    }
}
