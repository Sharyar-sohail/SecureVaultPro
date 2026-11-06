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
    private const val KEY_SCREEN = "last_screen"

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

    /**
     * The screen the owner was actually standing on, named by that screen.
     *
     * The stage marker comes from the navigation host and can only say which *state* the app was in
     * ("unlocked", "locked"), so every death during ordinary use names the same place: inside the
     * vault. A screen passes its own plain-language name here instead, and the previous run's value
     * is what the report quotes as the place the owner was in.
     */
    @Volatile
    private var screenName: String = ""

    @Volatile
    private var previousScreen: String = ""

    /** The dead run's own trail lines, read before this process writes anything. */
    @Volatile
    private var previousTail: List<String> = emptyList()

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
     * Where the owner was standing, in the words a screen would use about itself.
     *
     * A screen name wins over a stage, because the stage only says which state the app was in.
     * Nothing here is invented: an unknown stage is quoted as it was written.
     */
    val lastPlace: String
        get() = previousScreen.ifBlank { placeWords(previousStage) }

    /** The place named by a stage token, for reports the owner reads out loud. */
    private fun placeWords(stage: String): String {
        val head = stage.substringBefore('/').lowercase()
        val tail = stage.substringAfter('/', "")
        return when {
            head == "unlocked" -> "inside the vault" +
                if (tail.isBlank()) "" else " (theme $tail)"
            head.startsWith("rescue") -> "the recovery screen"
            head.startsWith("activity") -> "opening the app"
            head.startsWith("camera") -> "the document camera"
            head.startsWith("locked") -> if (tail.contains("biometric")) {
                "the unlock screen, face or fingerprint prompt"
            } else {
                "the unlock screen"
            }
            head == "loading" -> "opening the app"
            head == "intro" -> "the welcome screen"
            head == "onboarding" || head == "setup" -> "the setup screens"
            stage.isBlank() || stage == "unknown" || stage == "startup" -> "the app was still opening"
            else -> stage
        }
    }

    /**
     * The one line the owner reads first, taken out of a report that may also contain a stack.
     *
     * The two reports written without a stack start with `Summary:`. Anything else falls back to the
     * first real sentence, so the banner never shows a bare build line or a library frame.
     */
    fun summaryOf(report: String): String {
        val lines = report.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        lines.firstOrNull { it.startsWith("Summary:") }?.let { return it }
        val sentence = lines.firstOrNull {
            it.length > 40 && it.contains(' ') && !it.startsWith("at ") && !it.contains('/')
        }
        val firstFrame = lines.firstOrNull { it.startsWith("at ") }
            ?.removePrefix("at ")?.take(160)
        return (sentence ?: firstFrame ?: lines.firstOrNull().orEmpty()).take(280)
    }

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
     * The dead run's closing steps, quoted inside a report that has no stack trace to quote.
     *
     * Read before this process writes its own launch line, so everything on disk belongs to the run
     * that ended without saying goodbye. A native death leaves no Java frame at all; the last line it
     * wrote is the only name the fault can be given. The lines are kept raw so the recovery screen can
     * quote the same witness without reading the disk again.
     */
    private fun previousRunSteps(limit: Int = 9): String {
        runCatching {
            val target = trailFile ?: app?.getExternalFilesDir(null)
                ?.let { File(it, TRAIL_NAME) }
                ?.also { trailFile = it }
            target?.takeIf { it.isFile }?.readText()?.lines()?.filter { it.isNotBlank() }
                ?.let { previousTail = it.takeLast(30) }
        }
        return ownerSteps(previousTail, limit)
    }

    /**
     * The last thing the dead run wrote down, in the owner's words. For the recovery screen, which
     * must not go back to the disk for it.
     */
    fun deadRunSteps(limit: Int = 8): String = ownerSteps(previousTail, limit)

    /**
     * Translate the written trail into sentences the owner can act on, dropping the machinery.
     *
     * The trail is a developer log: it counts unclean runs, names probes and notes every background
     * handover. Quoted whole into the one document the owner photographs, that vocabulary is the
     * reason a readable report kept coming back unreadable. The clock time stays — it is how he
     * matches a line to what he was doing.
     */
    private fun ownerSteps(lines: List<String>, limit: Int): String = lines
        .mapNotNull { ownerStep(it) }
        .takeLast(limit)
        .joinToString("\n")

    private fun ownerStep(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        val clock = line.substringBefore(' ').substringAfter(' ').take(8)
        val stamp = if (clock.contains(':')) "$clock  " else ""
        val body = line.substringAfter(' ', line).trim()
        return when {
            body.startsWith("launch:") -> null
            body.startsWith("background after") -> null
            body.startsWith("report acknowledged") -> null
            body.startsWith("stage ->") ->
                stamp + "opened: " + placeWords(body.substringAfter("->").trim())
            body.startsWith("screen ->") ->
                stamp + "you were on: " + body.substringAfter("->").trim()
            body.startsWith("probe/") ->
                stamp + "checking ${body.substringAfter("probe/").trim()}"
            body.startsWith("event/") ->
                stamp + body.substringAfter("event/").trim()
            body.startsWith("caught fault:") ->
                stamp + "a problem it recovered from: " + body.substringAfter(":").trim()
            body.startsWith("uncaught on") ->
                stamp + "the app stopped: " + body.substringAfter(":").trim()
            else -> stamp + body.take(120)
        }
    }

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
            previousScreen = disk.getString(KEY_SCREEN, "").orEmpty()
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
                    val life = RunLength.spoken(ranFor)
                    val report = buildString {
                        append("Summary: The app closed suddenly. Your records are safe.\n")
                        append("Secure Wallet Pro ").append(BuildConfig.VERSION_NAME)
                        append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
                        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        append(" · Android ").append(Build.VERSION.RELEASE)
                        append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                        append("It had been open for $life when it stopped answering, and it closed ")
                        append("without showing any error. That is the phone shutting the app down ")
                        append("or a screen-drawing problem — it is not your vault, and nothing was ")
                        append("deleted.\n")
                        append("Where you were: ").append(lastPlace).append('\n')
                        append("This is the ").append(runCount(streak))
                        append(" time in a row the app has closed without saying goodbye.\n")
                        if (_safeRender) {
                            append("To keep the app openable, the frosted-glass background and the ")
                            append("screenshot block are switched off for now. They come back on ")
                            append("their own after the app closes properly once.")
                        }
                        // The written trail is the only surviving witness of a death like this, so
                        // it goes into the report itself: the last line names the work in flight.
                        val steps = previousRunSteps()
                        if (steps.isNotEmpty()) {
                            append("\n\nLast steps of that run:\n").append(steps)
                        }
                        append("\n\nWhat to do: tap Copy report and send the text over. A copy also ")
                        append("sits in ").append(REPORT_LOCATION).append(" — open it with Files.")
                        trailFile?.absolutePath?.let {
                            append("\nFull trail: ").append(it)
                            append("\n(The trail file is inside the app's own folder, which Files ")
                            append("cannot open. You only need the copy above.)")
                        }
                    }
                    _pending.value = report to ""
                    publishToDownloads(appContext, report)
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
     * The screen the owner is standing on, named by the screen itself.
     *
     * [noteStage] can only carry the navigation state, which reads as "inside the vault" for every
     * screen inside it, so a report could not say where the owner was. This is the missing half: a
     * screen passes its own plain-language name once, on entry. Like the stage marker it writes only
     * on a genuine change — never per recomposition — and every write is wrapped.
     */
    fun noteScreen(value: String) {
        val name = value.trim()
        if (name.isEmpty() || name == screenName) return
        screenName = name
        runCatching { store?.edit()?.putString(KEY_SCREEN, name)?.commit() }
        runCatching { trail("screen -> $name") }
    }

    /** Where the owner was standing, as this process knows it. */
    private fun placeForOwner(): String = screenName.ifBlank { placeWords(step) }

    /** "2nd", "3rd", "4th" — a count the owner reads as a count, not as a variable. */
    private fun runCount(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }

    /**
     * Where the run got to, as a step in the written trail rather than as the screen's own stage.
     *
     * A startup probe is not a place the owner is standing: it runs on a background thread and can
     * land minutes after the UI did. Recorded as a stage it was the last line the report quoted, so
     * every death during ordinary use read as a death on the vault database.
     */
    fun noteProbe(name: String) {
        runCatching { trail("probe/$name") }
    }

    /**
     * One transient event — a scan, an import, a camera handover — in the trail.
     *
     * These come and go too quickly to be a stage, and a stage that changes twice per minute would
     * hide where the owner actually was. They are still the first thing to look at when a run dies
     * without a trace: the last line is the work that was in flight.
     */
    fun noteEvent(label: String) {
        runCatching { trail("event/$label") }
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
                append("Summary: ").append(label)
                append(" — the app caught it and carried on.\n")
                append("Secure Wallet Pro ").append(BuildConfig.VERSION_NAME)
                append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
                append(label).append(" (reported ").append(faults).append(" time")
                append(if (faults == 1) ")" else "s)")
                append("\nWhere you were: ").append(placeForOwner()).append('\n')
                append("Technical detail follows; your records are safe.\n")
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
