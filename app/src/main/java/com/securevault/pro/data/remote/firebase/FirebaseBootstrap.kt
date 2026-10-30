package com.securevault.pro.data.remote.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.securevault.pro.R
import com.securevault.pro.diagnostics.CrashLogger

/**
 * Firebase comes up here, and only here.
 *
 * The library's own `FirebaseInitProvider` is removed from the manifest, because this app has been
 * killed twice by static initialisers firing while the process was still starting (the Compose
 * palette cycle on the owner's handset) and a library frame in that window has no name to report.
 * So nothing in this file runs until an operation actually asks for it, every entry point catches
 * [Throwable] — not just [Exception]; a missing resource or a NoClassDefFoundError is an Error —
 * and every failure is returned as null plus a one-line reason rather than thrown at the caller.
 *
 * The API key, app id and project id are read from the resources the google-services plugin
 * generates at build time; none of them is written into this source, and the Realtime Database
 * address is derived from the project id so there is a single source of truth.
 */
object FirebaseBootstrap {

    /** Region is fixed by the provisioned instance in `sync_engine/SPEC-v1.md`; the URL is public, not a secret. */
    private const val RTDB_URL_TEMPLATE = "https://%s-default-rtdb.asia-southeast1.firebasedatabase.app"

    private val lock = Any()

    @Volatile
    private var appHandle: Any? = null

    @Volatile
    private var databaseHandle: Any? = null

    @Volatile
    private var lastFault: String? = null

    @Volatile
    private var lastFaultDetail: String? = null

    @Volatile
    private var faultReported = false

    /**
     * True once the library is up. Cheap: after the first success it is a field read, so a sync
     * loop can ask before every operation without touching Firebase again.
     */
    fun isReady(context: Context): Boolean = appHandle != null || ensureApp(context) != null

    /** Idempotent, guarded, never throws. Null means "Firebase is not usable on this device". */
    fun ensureApp(context: Context): FirebaseApp? {
        (appHandle as? FirebaseApp)?.let { return it }
        return synchronized(lock) {
            (appHandle as? FirebaseApp)?.let { return@synchronized it }
            try {
                val app = FirebaseApp.initializeApp(context.applicationContext)
                    ?: FirebaseApp.getInstance()
                appHandle = app
                lastFault = null
                lastFaultDetail = null
                app
            } catch (error: Throwable) {
                record("Firebase could not be started", error, context)
                null
            }
        }
    }

    /** The database handle for the provisioned instance; null when Firebase or the URL is unusable. */
    fun database(context: Context): FirebaseDatabase? {
        (databaseHandle as? FirebaseDatabase)?.let { return it }
        val app = ensureApp(context) ?: return null
        return synchronized(lock) {
            (databaseHandle as? FirebaseDatabase)?.let { return@synchronized it }
            try {
                val url = databaseUrl(context)
                if (url.isNullOrBlank()) {
                    record("The project id is missing, so the database address cannot be built", null, context)
                    return@synchronized null
                }
                FirebaseDatabase.getInstance(app, url).also { databaseHandle = it }
            } catch (error: Throwable) {
                record("The realtime database could not be opened", error, context)
                null
            }
        }
    }

    /** A live reference for a path built by [RealtimePaths]; null on any failure or unsafe path. */
    fun reference(context: Context, path: String?): DatabaseReference? {
        if (path.isNullOrBlank()) return null
        val db = database(context) ?: return null
        return try {
            db.getReference(path)
        } catch (error: Throwable) {
            record("A database node path was rejected", error, context)
            null
        }
    }

    /** Null when the library is down; the caller must treat that as "not signed in", never as a crash. */
    fun auth(context: Context): FirebaseAuth? {
        val app = ensureApp(context) ?: return null
        return try {
            FirebaseAuth.getInstance(app)
        } catch (error: Throwable) {
            record("Firebase Auth could not be reached", error, context)
            null
        }
    }

    /**
     * https only, always: the template is fixed and the project id is validated before it is
     * interpolated, so a damaged resource can never produce a cleartext or foreign endpoint.
     */
    fun databaseUrl(context: Context): String? {
        val project = runCatching { context.getString(R.string.project_id) }.getOrNull()?.trim()
        if (project.isNullOrBlank()) return null
        if (!project.matches(PROJECT_ID_PATTERN)) return null
        return String.format(RTDB_URL_TEMPLATE, project)
    }

    /** The OAuth client id Firebase needs a Google id token issued for; public, not a secret. */
    fun webClientId(context: Context): String? =
        runCatching { context.getString(R.string.default_web_client_id) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.endsWith(".apps.googleusercontent.com") }

    /** A short, non-secret reason the UI or the crash log can show. Never contains key material. */
    fun faultReason(): String? = lastFault

    fun faultDetail(): String? = lastFaultDetail

    /** Drops the cached handles; used after a sign-out or when the account changes. */
    fun reset() {
        synchronized(lock) {
            appHandle = null
            databaseHandle = null
        }
    }

    private fun record(label: String, error: Throwable?, context: Context? = null) {
        lastFault = label
        lastFaultDetail = error?.javaClass?.simpleName
        // Reported once per process: a phone with no network would otherwise turn every retry
        // into a fault banner, and the owner would read a sync failure as a crash.
        if (error != null && context != null && !faultReported) {
            faultReported = true
            runCatching { CrashLogger.noteFault(context, "Realtime layer: $label", error) }
        }
    }

    private val PROJECT_ID_PATTERN = Regex("^[a-z0-9-]{4,64}$")
}
