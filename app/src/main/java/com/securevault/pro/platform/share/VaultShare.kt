package com.securevault.pro.platform.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.securevault.pro.platform.print.PrintSource
import com.securevault.pro.platform.print.VaultPrint
import java.io.File
import java.io.FileOutputStream

/**
 * The one way this app hands a file to another app — or to a printer.
 *
 * Three jobs, all of them deliberately in one place:
 *
 *  1. **Stage.** A vault file is an encrypted `.svf` container on private storage, and a decrypted
 *     payload only ever exists in memory. `FileProvider` can publish nothing outside the two cache
 *     folders `res/xml/file_paths.xml` declares, so the plaintext is written into
 *     `cacheDir/secure_share/` first. That is the *only* copy this object creates, it is created
 *     per share request, and it is never deleted by a timer — the standing rule is that data stays
 *     recoverable until the owner removes it, so [unstage] exists and nothing calls it on its own.
 *  2. **Grant + choose.** The URI is minted through [VaultShareAuthority]'s chain,
 *     `FLAG_GRANT_READ_URI_PERMISSION` is set on the payload intent *before* it is wrapped in
 *     `Intent.createChooser`, and the launch happens with `FLAG_ACTIVITY_NEW_TASK` so a call from a
 *     ViewModel holding an application context still works.
 *  3. **Print.** A share is not a print, and the print path already exists
 *     ([VaultPrint] + [PrintSource]) with page-by-page PDF composition, a real page count and an
 *     error channel. [printFile] therefore only resolves a file into a [PrintSource] and hands it
 *     over; it reinvents none of that.
 *
 * Every public function returns a [ShareOutcome]. A caller that ignores it has written a share
 * button that can silently do nothing, which is the exact failure this ticket was opened for.
 */
object VaultShare {

    /** Folder the FileProvider is configured to serve for outgoing plaintext. */
    fun directory(context: Context): File =
        File(context.applicationContext.cacheDir, VaultShareAuthority.STAGED_DIR)

    /** Files currently sitting in the staging folder, newest first. Never mutated by this object. */
    fun stagedFiles(context: Context): List<File> =
        directory(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Removes one staged plaintext copy, on purpose, because the owner asked.
     *
     * Only the staging folder is honoured: a path outside it returns false rather than deleting
     * something from the vault, so a mis-wired button cannot destroy a real file.
     */
    fun unstage(context: Context, file: File): Boolean {
        val staged = directory(context).canonicalFile
        return runCatching {
            val target = file.canonicalFile
            if (!target.path.startsWith(staged.path + File.separator)) {
                false
            } else {
                target.delete() || !target.exists()
            }
        }.getOrDefault(false)
    }

    /**
     * Writes [bytes] into the staging folder and returns the file, or null when the folder could
     * not be written.
     *
     * The name is sanitised by [VaultShareAuthority.stagedNameFor]; an existing copy of the same
     * name is replaced, so re-sharing the same note twice does not accumulate plaintext on disk.
     */
    fun stageBytes(context: Context, bytes: ByteArray, displayName: String): File? {
        if (bytes.isEmpty()) return null
        val folder = directory(context)
        if (!folder.isDirectory && !folder.mkdirs()) return null
        val target = File(folder, VaultShareAuthority.stagedNameFor(displayName))
        return runCatching {
            FileOutputStream(target).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            if (target.length() == bytes.size.toLong()) target else null
        }.getOrNull().also { written ->
            // A short or failed write leaves no half-file behind for the next app to open.
            if (written == null) runCatching { target.delete() }
        }
    }

    /**
     * Returns a file FileProvider can publish for [source], copying it into staging when needed.
     *
     * A file already inside `secure_share/` or `secure_viewer/` is used as-is — both are declared
     * roots, and `FileVaultRepository.exportToCache` already lands decrypted plaintext in the
     * first of them, so copying it again would put a second plaintext copy on disk for nothing.
     */
    fun stageFile(context: Context, source: File, displayName: String = source.name): File? {
        if (!source.isFile || source.length() <= 0L) return null
        val path = runCatching { source.canonicalFile.path }.getOrDefault(source.absolutePath)
        val served = VaultShareAuthority.isStaged(path) || VaultShareAuthority.isViewerCopy(path)
        if (served) return source
        val folder = directory(context)
        if (!folder.isDirectory && !folder.mkdirs()) return null
        val target = File(folder, VaultShareAuthority.stagedNameFor(displayName))
        return runCatching {
            source.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (target.length() > 0L) target else null
        }.getOrNull().also { written ->
            // A short or failed write leaves no half-file behind for the next app to open.
            if (written == null) runCatching { target.delete() }
        }
    }

    /**
     * Mints a content URI for [file], trying each authority the manifest could plausibly have
     * registered.
     *
     * Returns the authority that worked alongside the URI, because [ShareOutcome] records it and a
     * refused share prints the whole chain. FileProvider throws `IllegalArgumentException` for both
     * ways this can fail — an unknown authority and a file outside the declared roots — and no
     * retry distinguishes them, so the caller gets one honest outcome that names both causes.
     */
    fun uriFor(context: Context, file: File): Pair<String, Uri>? {
        val app = context.applicationContext
        for (authority in VaultShareAuthority.candidates(app.packageName)) {
            val uri = runCatching { FileProvider.getUriForFile(app, authority, file) }.getOrNull()
            if (uri != null) return authority to uri
        }
        return null
    }

    /** A staged image (JPEG/PNG bytes) out to the share sheet — the stego carrier's happy path. */
    fun shareImage(context: Context, bytes: ByteArray, displayName: String = "image.jpg"): ShareOutcome =
        shareBytes(context, bytes, displayName, ShareMime.guess(displayName))

    /** A PDF, staged and handed over; the receiver sees a real document, not a download. */
    fun sharePdf(context: Context, bytes: ByteArray, displayName: String = "document.pdf"): ShareOutcome =
        shareBytes(context, bytes, displayName, ShareMime.PDF)

    /** Any bytes, with the type guessed from the name unless one is given. */
    fun shareBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String? = null
    ): ShareOutcome {
        if (bytes.isEmpty()) {
            return ShareOutcome.failed(
                ShareStage.NothingToShare,
                displayName,
                "\"$displayName\" is empty, so there is nothing to hand to another app."
            )
        }
        val file = stageBytes(context, bytes, displayName)
            ?: return ShareOutcome.failed(
                ShareStage.StageUnwritable,
                displayName,
                "The app could not write \"${VaultShareAuthority.stagedNameFor(displayName)}\" " +
                    "into its shared cache folder."
            )
        return launch(context, file, displayName, mimeType)
    }

    /** A file the caller already holds (a decrypted export, a photo, a PDF). */
    fun shareFile(
        context: Context,
        file: File,
        displayName: String = file.name,
        mimeType: String? = null
    ): ShareOutcome {
        if (!file.isFile || file.length() <= 0L) {
            return ShareOutcome.failed(
                ShareStage.NothingToShare,
                displayName,
                "\"$displayName\" is missing or empty, so there is nothing to share."
            )
        }
        val staged = stageFile(context, file, displayName)
            ?: return ShareOutcome.failed(
                ShareStage.StageUnwritable,
                displayName,
                "The copy for the share sheet could not be written."
            )
        return launch(context, staged, displayName, mimeType)
    }

    /** Several files at once, through `ACTION_SEND_MULTIPLE` (a dispersed 3-image set, a chat). */
    fun shareFiles(
        context: Context,
        files: List<File>,
        subject: String,
        intro: String? = null
    ): ShareOutcome {
        val named = files.filter { it.isFile && it.length() > 0L }
        val displayName = subject.ifBlank { "shared files" }
        if (named.isEmpty()) {
            return ShareOutcome.failed(
                ShareStage.NothingToShare,
                displayName,
                "None of the ${files.size} files could be read."
            )
        }
        val staged = named.mapNotNull { stageFile(context, it, it.name) }
        if (staged.size != named.size) {
            return ShareOutcome.failed(
                ShareStage.StageUnwritable,
                displayName,
                "Only ${staged.size} of ${named.size} files could be prepared for the share sheet."
            )
        }
        // Every file has to mint a URI or the whole batch is refused: a half-published dispersal
        // set is worse than none, because the owner would believe all three shares arrived.
        val granted = staged.mapNotNull { file ->
            uriFor(context, file)?.let { (authority, uri) -> PublishedFile(authority, uri, file) }
        }
        if (granted.size != staged.size) {
            return ShareOutcome.failed(
                ShareStage.NoProviderRoot,
                displayName,
                "${staged.size - granted.size} of ${staged.size} files could not be published. " +
                    VaultShareAuthority.refusalMessage(context.applicationContext.packageName, displayName)
            )
        }
        val intent = Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = commonType(staged.map { ShareMime.guess(it.name) })
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(granted.map { it.uri }))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            intro?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(
            context = context,
            payload = intent,
            displayName = displayName,
            authority = granted.first().authority,
            uriText = granted.joinToString(", ") { it.uri.toString() },
            size = staged.sumOf { it.length() }
        )
    }

    /** Plain text — a passphrase, a seed hint, a share note. Never touches the staging folder. */
    fun shareText(context: Context, text: String, subject: String? = null): ShareOutcome {
        val displayName = subject ?: "Note"
        if (text.isBlank()) {
            return ShareOutcome.failed(
                ShareStage.NothingToShare,
                displayName,
                "There is no text to send."
            )
        }
        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            type = ShareMime.PLAIN_TEXT
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        return start(context, intent, displayName, authority = null, uriText = null)
    }

    /**
     * Opens [file] in another app rather than sending it somewhere: the viewer's "open with".
     *
     * Uses `ACTION_VIEW` through the same grant path, so a decrypted stego carrier can be inspected
     * in a gallery app without ever being copied out of cache first.
     */
    fun openWith(context: Context, file: File, displayName: String = file.name, mimeType: String? = null): ShareOutcome {
        if (!file.isFile || file.length() <= 0L) {
            return ShareOutcome.failed(
                ShareStage.NothingToShare,
                displayName,
                "\"$displayName\" is missing or empty."
            )
        }
        val staged = stageFile(context, file, displayName)
            ?: return ShareOutcome.failed(ShareStage.StageUnwritable, displayName, "The file could not be prepared.")
        val (authority, uri) = uriFor(context, staged) ?: return ShareOutcome.failed(
            ShareStage.NoProviderRoot,
            displayName,
            VaultShareAuthority.refusalMessage(context.applicationContext.packageName, displayName)
        )
        val type = mimeType ?: ShareMime.guess(displayName)
        val intent = Intent().apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, intent, displayName, authority, uri.toString(), chooser = false)
    }

    /**
     * Prints any file the app can produce, through the existing print pipeline.
     *
     * `PrintSource.ofFile` is the single decision point: a PDF goes to the renderer as-is, an image
     * (including a stego carrier, whose hidden payload is invisible to a printer by construction) is
     * composed into a page, text is laid out onto A4 sheets, and anything else is reported as
     * unprintable instead of printing a blank sheet. The staged copy is reused, so printing a vault
     * file does not decrypt it twice.
     */
    fun printFile(
        context: Context,
        file: File,
        displayName: String = file.name,
        onError: ((String) -> Unit)? = null
    ): ShareOutcome {
        if (!file.isFile || file.length() <= 0L) {
            return ShareOutcome.failed(
                ShareStage.NothingToShare,
                displayName,
                "\"$displayName\" is missing or empty, so there is no page to print."
            )
        }
        if (!VaultPrint.isAvailable(context)) {
            return ShareOutcome.failed(
                ShareStage.PrinterMissing,
                displayName,
                "This device has no print service, so nothing can be sent to a printer."
            )
        }
        val staged = stageFile(context, file, displayName)
            ?: return ShareOutcome.failed(
                ShareStage.StageUnwritable,
                displayName,
                "The printable copy could not be written."
            )
        val source = PrintSource.ofFile(staged, ShareMime.guess(displayName), displayName)
        if (source is PrintSource.Unsupported) {
            return ShareOutcome.failed(ShareStage.Unprintable, displayName, source.reason)
        }
        VaultPrint.print(context, source, displayName, onError)
        return ShareOutcome(
            stage = ShareStage.PrintStarted,
            displayName = displayName,
            detail = "The print job \"$displayName\" was handed to the system printer.",
            uri = staged.absolutePath
        )
    }

    /** Same, for bytes that only ever existed in memory (a freshly sealed carrier). */
    fun printBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        onError: ((String) -> Unit)? = null
    ): ShareOutcome {
        if (bytes.isEmpty()) {
            return ShareOutcome.failed(ShareStage.NothingToShare, displayName, "There is nothing to print.")
        }
        val file = stageBytes(context, bytes, displayName)
            ?: return ShareOutcome.failed(ShareStage.StageUnwritable, displayName, "The print buffer could not be written.")
        return printFile(context, file, displayName, onError)
    }

    /**
     * Prints text the viewer extracted from a document it cannot render (a `.docx`, a `.pptx`).
     *
     * The plain text is round-tripped through the staging folder as a `.txt` so it travels the same
     * [printFile] decision path as any other file — nothing here talks to the print framework
     * directly.
     */
    fun printText(
        context: Context,
        text: String,
        displayName: String = "document.txt",
        onError: ((String) -> Unit)? = null
    ): ShareOutcome {
        if (text.isBlank()) {
            return ShareOutcome.failed(
                ShareStage.NothingToShare,
                displayName,
                "This document exposed no text, so there is no page to print."
            )
        }
        return printBytes(context, text.toByteArray(Charsets.UTF_8), displayName, onError)
    }

    /** True when [displayName] has a chance of reaching paper, so a menu can grey the item out. */
    fun isPrintable(displayName: String): Boolean {
        val mime = ShareMime.guess(displayName)
        return ShareMime.isPdf(mime) || ShareMime.isImage(mime) || ShareMime.isText(mime)
    }

    // --- internals ---------------------------------------------------------------------------------

    /** One staged file that FileProvider accepted, with the authority and URI it published under. */
    private data class PublishedFile(val authority: String, val uri: Uri, val file: File)

    /** Stages, mints a URI, wraps in a chooser, launches. All failure paths return a named stage. */
    private fun launch(
        context: Context,
        staged: File,
        displayName: String,
        mimeType: String?
    ): ShareOutcome {
        val (authority, uri) = uriFor(context, staged) ?: return ShareOutcome.failed(
            ShareStage.NoProviderRoot,
            displayName,
            VaultShareAuthority.refusalMessage(context.applicationContext.packageName, displayName)
        )
        val type = mimeType?.takeIf { it.isNotBlank() } ?: ShareMime.guess(displayName)
        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(
            context = context,
            payload = intent,
            displayName = displayName,
            authority = authority,
            uriText = uri.toString(),
            size = staged.length()
        )
    }

    private fun start(
        context: Context,
        payload: Intent,
        displayName: String,
        authority: String?,
        uriText: String?,
        chooser: Boolean = true,
        size: Long = -1L
    ): ShareOutcome {
        val app = context.applicationContext
        val visible = countTargets(app, payload)
        val request = if (chooser) Intent.createChooser(payload, displayName) else payload
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { app.startActivity(request) }
        val error = launched.exceptionOrNull()
        if (error != null) {
            val stage = if (error is ActivityNotFoundException) ShareStage.NoTarget else ShareStage.Refused
            return ShareOutcome.failed(
                stage,
                displayName,
                if (stage == ShareStage.NoTarget) {
                    "No installed app accepted \"$displayName\"."
                } else {
                    "Android blocked the hand-over of \"$displayName\": ${error.message ?: error.javaClass.simpleName}"
                },
                authority
            )
        }
        val sizeNote = if (size > 0L) " (${size} bytes)" else ""
        val visibilityNote = if (chooser && visible == 0) {
            " No target was listed in advance: package visibility hides other apps from this build " +
                "unless AndroidManifest declares a matching SEND query, so the sheet may still show more."
        } else {
            ""
        }
        return ShareOutcome(
            stage = ShareStage.Launched,
            displayName = displayName,
            detail = "\"$displayName\"$sizeNote was published through ${authority ?: "the share intent"}$visibilityNote",
            authority = authority,
            uri = uriText
        )
    }

    private fun countTargets(context: Context, intent: Intent): Int = runCatching {
        if (intent.action.isNullOrBlank()) 0
        else context.packageManager.queryIntentActivities(intent, 0).size
    }.getOrDefault(0)

    /** `image/*` for a mixed image batch, `*/*` when the set has nothing in common. */
    private fun commonType(types: List<String>): String {
        if (types.isEmpty()) return "*/*"
        val first = types.first()
        if (types.all { it == first }) return first
        val majors = types.map { it.substringBefore('/') }.distinct()
        return if (majors.size == 1) "${majors.first()}/*" else "*/*"
    }
}
