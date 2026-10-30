package com.securevault.pro.data.imports

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The share sheet's mailbox.
 *
 * WhatsApp's "Export chat" → Share → Secure Wallet Pro is the only intake that can read a
 * conversation at all on Android 11+, and the vault is closed when the share arrives: the bytes
 * cannot be sealed into an encrypted note before the owner unlocks. So a share is queued here
 * first, and it has to survive the two things that genuinely happen on a phone — the system
 * killing our process while WhatsApp is in the foreground, and the temporary read grant the share
 * carried expiring with it.
 *
 * Two records are therefore kept per share, in app-private storage:
 *  - the `content://` URI, with a persistable grant taken when the sender allowed one, and
 *  - a copy of the bytes under `filesDir/whatsapp_inbox`, taken on a background thread while the
 *    grant is still live.
 *
 * The copy is plaintext for exactly as long as it sits unsealed — one chat export, in the app's
 * own private directory, deleted the moment the note lands. That is the cost of a share arriving
 * while the vault is locked; without it the share simply evaporates and the owner sees nothing.
 */
object SharedInbox {

    /** One chat waiting to be sealed. [staged] is set once the copy of the bytes is on disk. */
    data class Pending(
        val id: String,
        val uri: String?,
        val name: String,
        val staged: String?,
        val bytes: Long,
        val queuedAt: Long
    ) {
        /** Readable after process death only if the copy landed or the sender granted a persistable URI. */
        val readable: Boolean get() = staged != null || uri != null
    }

    private const val PREFS = "whatsapp_shared_inbox"
    private const val KEY_ITEMS = "items"
    private const val DIR_NAME = "whatsapp_inbox"

    /**
     * The largest staged copy accepted, chosen by what the scratch partition can hold — not a queue
     * length. A queue cap that silently evicted the oldest entry would delete a chat the owner shared
     * but has not imported yet, which the retention rule forbids.
     */
    private const val MAX_STAGED_BYTES = 250L * 1024L * 1024L


    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    /** The staging copy runs off the caller's thread — a 250 MB archive must never block activity start. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called from every entry point Android gives a share target: `ACTION_SEND` for one chat file,
     * `ACTION_SEND_MULTIPLE` for a split export or several chats at once, and the plain-text share
     * of selected messages, which is a conversation the moment it carries WhatsApp timestamps.
     */
    @Suppress("DEPRECATION")
    fun offer(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val app = context.applicationContext
        reload(app)

        val streams = ArrayList<Uri>()
        when (action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { streams.add(it) }
            else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { streams.addAll(it) }
        }
        if (streams.isEmpty()) {
            runCatching {
                intent.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) clip.getItemAt(index)?.uri?.let { streams.add(it) }
                }
            }
        }
        streams.distinct().forEach { uri -> enqueue(app, uri) }

        if (streams.isEmpty()) {
            val text = runCatching { intent.getStringExtra(Intent.EXTRA_TEXT) }.getOrNull()
            if (!text.isNullOrBlank() && WhatsAppTranscript.messageCount(text) >= 3) enqueueText(app, text)
        }
    }

    /** Reads the queue back after a cold start; safe to call on every screen open. */
    @Synchronized
    fun reload(context: Context) {
        if (_pending.value.isNotEmpty()) return
        val saved = store(context).getString(KEY_ITEMS, null)
        if (saved.isNullOrBlank()) return
        _pending.value = decode(saved).filter { entry ->
            // A staged copy that has gone was cleaned out of storage by the system; keep the row
            // only when at least one of its two halves still exists, so no row can only fail.
            (entry.staged == null || File(entry.staged).isFile) && entry.readable
        }
    }

    /** The chat the owner imported from the share sheet: out of the queue and off the disk. */
    fun complete(context: Context, id: String) {
        val entry = _pending.value.firstOrNull { it.id == id }
        _pending.value = _pending.value.filterNot { it.id == id }
        persist(context)
        deleteStaged(entry)
    }

    /** Throwing a share away must also throw away its plaintext copy — nothing is kept on purpose. */
    fun clear(context: Context, id: String? = null) {
        val doomed = if (id == null) _pending.value else _pending.value.filter { it.id == id }
        _pending.value = if (id == null) emptyList() else _pending.value.filterNot { it.id == id }
        persist(context)
        doomed.forEach { deleteStaged(it) }
    }

    @Synchronized
    private fun persist(context: Context) {
        store(context).edit().putString(KEY_ITEMS, encode(_pending.value)).commit()
    }

    private fun deleteStaged(entry: Pending?) {
        entry?.staged?.let { path -> runCatching { File(path).delete() } }
    }

    private fun enqueue(context: Context, uri: Uri) {
        if (_pending.value.any { it.uri == uri.toString() && it.staged == null }) return
        val name = displayName(context, uri)
        val id = UUID.randomUUID().toString()
        val staged = File(inboxDir(context), "$id-${name.replace('/', '_')}")
        _pending.value = _pending.value + Pending(
            id = id,
            uri = uri.toString(),
            name = name,
            staged = null,
            bytes = 0L,
            queuedAt = System.currentTimeMillis()
        )
        persist(context)
        // A share target's read grant normally dies with the hand-over, so the copy is the only
        // thing that makes "import after unlock" real rather than a screen that waits on nothing.
        scope.launch {
            if (stage(context, uri, staged)) {
                markStaged(context, id, staged.absolutePath, staged.length())
            } else {
                staged.delete()
                markUnreadable(context, id)
            }
        }
    }

    /**
     * Copies the shared bytes into app storage while the hand-over grant is still live. False means
     * the copy is gone or was never possible — the queue then keeps only the URI, which may or may
     * not still be readable once the vault opens.
     */
    private fun stage(context: Context, uri: Uri, target: File): Boolean {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return false
        return runCatching {
            input.use { source ->
                var total = 0L
                val buffer = ByteArray(128 * 1024)
                target.outputStream().use { sink ->
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_STAGED_BYTES) return false
                        sink.write(buffer, 0, read)
                    }
                }
            }
            target.isFile && target.length() > 0L
        }.getOrDefault(false)
    }

    /** A text share of selected messages is already a transcript, so it is written out and queued. */
    private fun enqueueText(context: Context, text: String) {
        val id = UUID.randomUUID().toString()
        val file = File(inboxDir(context), "$id-shared messages.txt")
        runCatching { file.writeText(text, Charsets.UTF_8) }.onFailure { return }
        _pending.value = _pending.value + Pending(
            id = id,
            uri = null,
            name = "WhatsApp messages.txt",
            staged = file.absolutePath,
            bytes = file.length(),
            queuedAt = System.currentTimeMillis()
        )
        persist(context)
    }

    @Synchronized
    private fun markStaged(context: Context, id: String, path: String, bytes: Long) {
        _pending.value = _pending.value.map { if (it.id == id) it.copy(staged = path, bytes = bytes) else it }
        persist(context)
    }

    @Synchronized
    private fun markUnreadable(context: Context, id: String) {
        // The copy did not land. The row stays so the owner can see the share and send it again,
        // but it says so rather than failing later with a permissions message nobody can act on.
        _pending.value = _pending.value.map {
            if (it.id == id) it.copy(staged = null, name = "${it.name} · re-share needed") else it
        }
        persist(context)
    }

    private fun inboxDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
        val tail = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return tail.ifBlank { "WhatsApp chat" }
    }

    private fun encode(items: List<Pending>): String {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("uri", entry.uri ?: JSONObject.NULL)
                    put("name", entry.name)
                    put("staged", entry.staged ?: JSONObject.NULL)
                    put("bytes", entry.bytes)
                    put("queuedAt", entry.queuedAt)
                }
            )
        }
        return array.toString()
    }

    private fun decode(text: String): List<Pending> = runCatching {
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val staged = obj.optString("staged").takeIf { it.isNotBlank() && it != "null" }
                val uri = obj.optString("uri").takeIf { it.isNotBlank() && it != "null" }
                add(
                    Pending(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        uri = uri,
                        name = obj.optString("name", "WhatsApp chat"),
                        staged = staged,
                        bytes = obj.optLong("bytes", 0L),
                        queuedAt = obj.optLong("queuedAt", System.currentTimeMillis())
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun store(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
