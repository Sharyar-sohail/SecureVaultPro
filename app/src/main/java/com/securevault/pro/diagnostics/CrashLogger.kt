package com.securevault.pro.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.securevault.pro.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Answers one question this project cannot otherwise answer: what killed the last run.
 *
 * There is no adb here, so the owner is the only instrument. Two kinds of death leave no
 * Java exception at all — a native graphics failure and an OEM process kill — so a handler
 * alone was never going to catch the reports that keep coming back. This object therefore
 * keeps three things: the stack trace when there is one, a written trail of the steps the
 * run took (flushed synchronously, because a native death gives no chance to flush), and a
 * watchdog that treats "the previous run never said goodbye" as a crash even when no
 * handler ran. Everything here is synchronous, allocation-light and wrapped, so a
 * diagnostic can never become the fault it is watching for.
 */
object CrashLogger {

    private const val PREFS = "crash_diagnostics"
    private const val KEY_TRACE = "pending_trace"
    private const val KEY_PATH = "pending_path"
    private const val KEY_COUNT = "consecutive"
    private const val KEY_STORE_FAULTS = "store_faults"
    private const val KEY_RUNNING = "running"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST = "last_step"
    private const val KEY_STARTED = "started_at"

    /** Set by a failed startup probe: open recovery mode on the next launch, crash or no crash. */
    private const val KEY_RESCUE = "force_rescue"
    private const val FILE_NAME = "SecureVaultCrash.txt"
    private const val TRAIL_NAME = "SecureVaultTrail.txt"

    /**
     * A run shorter than this died while the app was still coming up, so the owner never saw
     * a screen at all. One such death is enough to go to recovery mode: waiting for a second
     * meant opening the app, losing it, and having nothing to read until doing it twice.
     */
    private const val EARLY_DEATH_MS = 25_000L
    private const val MAX_STORED_CHARS = 40_000
    private const val MAX_TRAIL_CHARS = 60_000
    private const val HEALTHY_UPTIME_MS = 60_000L
    private const val RESCUE_AFTER = 2

    @Volatile
    private var installed = false

    @Volatile
    private var store: SharedPreferences? = null

    @Volatile
    private var app: Context? = null

    @Volatile
    private var trailFile: File? = null

    @Volatile
    private var step: String = "startup"

    @Volatile
    private var previousStage: String = "unknown"

    @Volatile
    private var startedAt: Long = 0L

    @Volatile
    private var _safeRender = false

    private val _pending = MutableStateFlow<Pair<String, String>?>(null)

    /** The fault left by the last process, kept on screen until it is acknowledged. */
    val pending: StateFlow<Pair<String, String>?> = _pending.asStateFlow()

    private val _rescue = MutableStateFlow(false)

    /**
     * True when the last [RESCUE_AFTER] runs both died without saying goodbye. At that point
     * the normal UI is the thing that is dying, so the activity shows a plain screen built
     * from no palette, no font choice and no vault data instead — the only way this project
     * can read its own crash report without a debug bridge. A healthy run clears it by itself.
     */
    val rescue: StateFlow<Boolean> = _rescue.asStateFlow()

    /** Leave rescue mode for this process without wiping the report. */
    fun leaveRescue() {
        _rescue.value = false
        runCatching { store?.edit()?.remove(KEY_RESCUE)?.commit() }
    }

    /**
     * Called by a startup probe that found a subsystem refusing to come up. Recovery mode
     * opens for this run and for the next one: the run carries on with defaults and may yet
     * die on the same subsystem, and a fault inside the palette list, the font table or the
     * database otherwise looks exactly like the app simply refusing to open, with nothing to
     * read afterwards.
     */
    fun forceRescue(context: Context, label: String, error: Throwable) {
        _rescue.value = true
        _safeRender = true
        runCatching {
            prefs(context.applicationContext).edit().putBoolean(KEY_RESCUE, true).commit()
        }
        noteFault(context, label, error, publish = true)
    }

    /** The furthest point the previous run reached, for the rescue screen. */
    val lastStage: String get() = previousStage

    /**
     * Where the copy of the report lands, spelled out on every screen that mentions it. The
     * private app folder this used to write is not reachable from a phone's own file manager,
     * so a report nobody can open is the same as no report at all.
     */
    const val REPORT_LOCATION = "Downloads/SecureVaultCrash.txt"

    /** The steps the last run reached, for the rescue screen. Newest last. */
    fun lastSteps(limit: Int = 40): String = runCatching {
        trailFile?.takeIf { it.isFile }?.readText()?.lines()?.takeLast(limit)?.joinToString("\n")
    }.getOrNull().orEmpty()

    /**
     * True while a fault is on record. Screens hold back automatic work — the biometric
     * prompt above all — so a fault on that path cannot destroy the report that explains
     * it before the owner has read it.
     */
    val safeMode: Boolean get() = _pending.value != null

    /**
     * True once two runs in a row have ended without saying goodbye. Drops the GPU
     * backdrop blur and screen-capture blocking: both sit on the display pipeline, where
     * nothing this process runs can catch a Skia failure or a HyperOS kill. Staying open
     * matters more than the glass effect, and a clean exit clears this again by itself.
     */
    val safeRender: Boolean get() = _safeRender

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        app = appContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(appContext, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
        runCatching {
            val disk = prefs(appContext)
            startedAt = System.currentTimeMillis()
            val wasRunning = disk.getBoolean(KEY_RUNNING, false)
            val lastStep = disk.getString(KEY_LAST, "unknown")
            previousStage = lastStep ?: "unknown"
            val ranFor = startedAt - disk.getLong(KEY_STARTED, startedAt)
            disk.getString(KEY_TRACE, null)?.let { trace ->
                _pending.value = trace to disk.getString(KEY_PATH, null).orEmpty()
            }
            if (wasRunning) {
                val streak = disk.getInt(KEY_STREAK, 0) + 1
                _safeRender = streak >= 2
                _rescue.value = streak >= RESCUE_AFTER || ranFor < EARLY_DEATH_MS
                disk.edit()
                    .putInt(KEY_STREAK, streak)
                    .putLong(KEY_STARTED, startedAt)
                    .putBoolean(KEY_RUNNING, true)
                    .commit()
                if (_pending.value == null) {
                    _pending.value = buildString {
                        append("Secure Wallet Pro ").append(BuildConfig.VERSION_NAME)
                        append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
                        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        append(" · Android ").append(Build.VERSION.RELEASE)
                        append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                        append("The previous run ended without closing cleanly after ")
                        append("'").append(lastStep).append("' after ").append(ranFor).append(" ms.\n")
                        append("No crash report was left either, which points at a native ")
                        append("graphics failure or the system closing the app, not at ")
                        append("Kotlin code.\n")
                        append("Consecutive unclean runs: ").append(streak)
                        if (_safeRender) {
                            append("\nSafe render mode is ON: backdrop blur and screen-capture")
                            append(" blocking are disabled until a run closes cleanly.")
                        }
                    } to ""
                }
                trail("launch: unclean x$streak, last='$lastStep', safeRender=$_safeRender")
            } else {
                _rescue.value = false
                disk.edit()
                    .putLong(KEY_STARTED, startedAt)
                    .putBoolean(KEY_RUNNING, true)
                    .commit()
                trail("launch: clean start, streak=${disk.getInt(KEY_STREAK, 0)}")
            }
            // A probe failure says so once and then the run closes cleanly, so the unclean
            // streak never sees it. The flag is what puts recovery mode up next launch.
            if (disk.getBoolean(KEY_RESCUE, false)) {
                _rescue.value = true
                trail("launch: recovery requested by an earlier probe")
            }
        }
    }

    /**
     * The last screen or action reached, flushed at once so a native death keeps it. Only a
     * genuine change writes: this is called from a recomposition side effect, and a
     * synchronous disk write per frame would itself be a stall worth crashing over.
     */
    fun noteStage(value: String) {
        if (value == step) return
        step = value
        runCatching { store?.edit()?.putString(KEY_LAST, value)?.commit() }
        runCatching { trail("stage -> $value") }
    }

    /**
     * Called when the screen comes back to the foreground. That is the whole watchdog: the
     * flag is only up while the UI is actually running, so a process that dies with it set
     * died in front of the user rather than being reclaimed in the background.
     */
    fun markForeground(context: Context) {
        runCatching {
            prefs(context.applicationContext).edit()
                .putBoolean(KEY_RUNNING, true)
                .commit()
        }
    }

    /**
     * Called when the screen stops. A run that survives to this point well past launch is a
     * healthy run, so the unclean streak and safe render mode reset themselves; a death in
     * the first minute stays attributed to the next launch.
     */
    fun markBackground(context: Context) {
        runCatching {
            val disk = prefs(context.applicationContext)
            val healthy = System.currentTimeMillis() - startedAt > HEALTHY_UPTIME_MS
            disk.edit()
                .putBoolean(KEY_RUNNING, false)
                .apply { if (healthy) putInt(KEY_STREAK, 0) }
                .commit()
            if (healthy) _safeRender = false
        }
        runCatching { trail("background after ${System.currentTimeMillis() - startedAt} ms") }
    }

    /** Clears the on-screen and on-disk report after the owner has dealt with it. */
    fun acknowledge(context: Context) {
        _pending.value = null
        runCatching {
            prefs(context.applicationContext).edit()
                .remove(KEY_TRACE)
                .remove(KEY_PATH)
                .putInt(KEY_COUNT, 0)
                .putInt(KEY_STORE_FAULTS, 0)
                .remove(KEY_RESCUE)
                .commit()
        }
        runCatching { trail("report acknowledged") }
    }

    /**
     * Records a fault that was caught instead of crashing. The vault keeps working on
     * defaults, but the owner is told: a settings store that cannot be read means the PIN
     * on disk may not match what the screen shows, and a background job that died usually
     * means a sync that did not happen.
     */
    fun noteFault(context: Context, label: String, error: Throwable, publish: Boolean = false) {
        runCatching {
            val disk = prefs(context.applicationContext)
            val faults = disk.getInt(KEY_STORE_FAULTS, 0) + 1
            val trace = buildString {
                append("Secure Wallet Pro ").append(BuildConfig.VERSION_NAME)
                append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
                append(label).append(" (reported ").append(faults).append(" time")
                append(if (faults == 1) ")" else "s)")
                append("\nScreen: ").append(step).append('\n')
                append(Log.getStackTraceString(error))
            }
            // A caught fault is written to disk too. A probe that names the settings store as
            // unreadable is precisely the one that may not survive to show the banner, so the
            // report has to be readable by the next launch rather than living in this process.
            disk.edit()
                .putInt(KEY_STORE_FAULTS, faults)
                .putString(KEY_TRACE, trace.take(MAX_STORED_CHARS))
                .commit()
            if (_pending.value == null) {
                _pending.value = trace to ""
            }
            Log.w("CrashLogger", label, error)
            trail("caught fault: $label")
            if (publish) publishToDownloads(context.applicationContext, trace)
        }
    }

    private fun record(context: Context, threadName: String, error: Throwable) {
        val trace = buildString {
            append("Secure Wallet Pro ").append(BuildConfig.VERSION_NAME)
            append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Android ").append(Build.VERSION.RELEASE)
            append(" (SDK ").append(Build.VERSION.SDK_INT).append(") on ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Thread: ").append(threadName).append('\n')
            append("Screen: ").append(step).append('\n')
            append('\n')
            append(Log.getStackTraceString(error))
            append(trailTail())
        }

        val target = runCatching {
            context.getExternalFilesDir(null)?.let { directory ->
                File(directory, FILE_NAME).also { it.writeText(trace) }
            }
        }.getOrNull()

        val disk = prefs(context)
        disk.edit()
            .putString(KEY_TRACE, trace.take(MAX_STORED_CHARS))
            .putString(KEY_PATH, target?.absolutePath.orEmpty())
            .putInt(KEY_COUNT, disk.getInt(KEY_COUNT, 0) + 1)
            .commit()
        _pending.value = trace.take(MAX_STORED_CHARS) to target?.absolutePath.orEmpty()
        trail("uncaught on $threadName: ${error.javaClass.simpleName}")
        publishToDownloads(context, trace)
    }

    /**
     * Drop a copy of the report where a phone's own file manager can reach it.
     *
     * The private app directory that used to hold this is hidden by current Android file
     * managers, so an owner with no debug bridge had a report and no way to open it. Media
     * store Downloads needs no runtime permission on API 29+, which is what makes the trace
     * findable from the Files app alone; a plain write to the public folder covers older
     * releases and is wrapped because it can legitimately be denied there.
     */
    private fun publishToDownloads(context: Context, trace: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val stamped = stamp() + "\n" + trace
            runCatching {
                val resolver = context.contentResolver
                // One report, always at the same name. MediaStore would otherwise hand back
                // "SecureVaultCrash (1).txt" and the location the screens quote stops being
                // the file the owner is told to open. A row this app does not own cannot be
                // deleted, so the cleanup is allowed to fail without losing the write.
                runCatching {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                        arrayOf(FILE_NAME, "${Environment.DIRECTORY_DOWNLOADS}%")
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    writeLegacyDownload(stamped)
                } else {
                    resolver.openOutputStream(uri, "w")?.use { it.write(stamped.toByteArray()) }
                }
            }.onFailure { runCatching { writeLegacyDownload(stamped) } }
            return
        }
        runCatching { writeLegacyDownload(stamp() + "\n" + trace) }
    }

    private fun writeLegacyDownload(trace: String) {
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        File(directory, FILE_NAME).writeText(trace)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " — "

    /**
     * The steps this run actually took, under the stack. Most of these traces are library
     * frames with our own names stripped, so the trail is what says which screen and which
     * theme were on screen when it died.
     */
    private fun trailTail(): String {
        val tail = runCatching {
            trailFile?.takeIf { it.isFile }?.readText()?.lines()?.takeLast(16)
        }.getOrNull().orEmpty()
        if (tail.isEmpty()) return ""
        return "\nLast steps:\n" + tail.joinToString("\n") { "  $it" }
    }

    private fun trail(line: String) {
        val target = trailFile ?: runCatching {
            app?.getExternalFilesDir(null)?.let { File(it, TRAIL_NAME) }
        }.getOrNull()?.also { trailFile = it } ?: return
        runCatching {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            target.appendText("\n$stamp $line")
            if (target.length() > MAX_TRAIL_CHARS * 2) {
                target.writeText(target.readText().takeLast(MAX_TRAIL_CHARS))
            }
        }
        Log.i("SVP-trail", line)
    }

    private fun prefs(context: Context): SharedPreferences {
        store?.let { return it }
        val created = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        store = created
        return created
    }
}
