package com.securevault.pro.platform.share

/**
 * The FileProvider authority, resolved without touching Android.
 *
 * `AndroidManifest.xml` declares the app's provider as
 * `android:authorities="${applicationId}.fileprovider"`, and `res/xml/file_paths.xml` serves
 * **only** two cache sub-trees — `secure_share/` and `secure_viewer/`. Two consequences, both of
 * which live here so they can be tested on the JVM:
 *
 *  1. The authority string is derived from the *application id*, which is **not** the code
 *     namespace. `applicationId` is `com.securevault.app` while the Kotlin package is
 *     `com.securevault.pro`, so a hardcoded namespace authority throws
 *     `IllegalArgumentException: The supported authorities are [...]` the first time a share sheet
 *     opens. [candidates] therefore tries the runtime package name first and keeps the two known
 *     ids as fallbacks, so a build flavour that renames the application id degrades to a
 *     *reported* failure instead of a crash.
 *  2. A file outside those two folders cannot be shared by URI grant, however correct the
 *     authority is. [isServable] is the pre-flight check [VaultShare] uses to decide whether to
 *     copy a file into `secure_share/` first — which is also the only place a *decrypted* payload
 *     is ever written, so the share path cannot accidentally publish the encrypted vault tree.
 *
 * Nothing here deletes or expires a staged copy: the owner's standing rule is that data stays
 * recoverable until they delete it by hand, so cleanup is an explicit action
 * ([VaultShare.unstage]), never a side effect of the clock.
 */
object VaultShareAuthority {

    /** Suffix the manifest appends to the application id. */
    const val SUFFIX = ".fileprovider"

    /** The shipped `applicationId` — the authority this build really registers. */
    const val PRIMARY_APPLICATION_ID = "com.securevault.app"

    /**
     * The Kotlin namespace. A manifest placeholder or a tooling accident can register the provider
     * under this instead, so it stays in the chain as a last resort rather than as a guess.
     */
    const val SECONDARY_APPLICATION_ID = "com.securevault.pro"

    /** The one cache folder this app uses for outgoing plaintext. Matches `file_paths.xml`. */
    const val STAGED_DIR = "secure_share"

    /** The viewer's folder, also FileProvider-served, so an already-staged copy there is usable. */
    const val VIEWER_DIR = "secure_viewer"

    /** Maximum staged-name length; a long vault filename would blow past some filesystems. */
    const val MAX_STAGED_NAME = 96

    /** The authority for the running package, or the shipped id when the package name is missing. */
    fun of(packageName: String?): String =
        (packageName?.takeIf { it.isNotBlank() } ?: PRIMARY_APPLICATION_ID) + SUFFIX

    /**
     * The ordered chain to try, best first.
     *
     * Deduplicated so a test build whose package name *is* `com.securevault.app` (the usual case)
     * yields one entry and a caller cannot report "tried 3 authorities" when it tried one.
     */
    fun candidates(packageName: String?): List<String> {
        val seeds = listOfNotNull(
            packageName?.trim()?.takeIf { it.isNotEmpty() },
            PRIMARY_APPLICATION_ID,
            SECONDARY_APPLICATION_ID
        )
        val chain = LinkedHashSet<String>()
        seeds.forEach { chain.add(it + SUFFIX) }
        return chain.toList()
    }

    /** Path separators normalised, so a Windows test run and an Android device agree. */
    private fun normalise(path: String): String = path.replace('\\', '/')

    /** True when [path] is under the app's cache dir and inside one of the two served sub-folders. */
    fun isServable(path: String?, cacheDirPath: String?): Boolean {
        val clean = path?.let(::normalise)?.takeIf { it.isNotBlank() } ?: return false
        val cache = cacheDirPath?.let(::normalise)?.trimEnd('/')
        val insideCache = cache.isNullOrEmpty() || clean.startsWith("$cache/")
        if (!insideCache) return false
        return hasSegment(clean, STAGED_DIR) || hasSegment(clean, VIEWER_DIR)
    }

    /**
     * True when [path] names a file inside the staging folder itself.
     *
     * The folder is matched as a *path segment* rather than by `contains("secure_share")`, because
     * a vault file legitimately called `notes/secure_share_todo.txt` would otherwise be treated as
     * already staged and handed to FileProvider from the encrypted tree.
     */
    fun isStaged(path: String?): Boolean {
        val clean = path?.let(::normalise) ?: return false
        return hasSegment(clean, STAGED_DIR)
    }

    /** True for the viewer's served folder, which the in-app viewer already decrypts into. */
    fun isViewerCopy(path: String?): Boolean = hasSegment(path?.let(::normalise), VIEWER_DIR)

    private fun hasSegment(path: String?, segment: String): Boolean {
        val clean = path ?: return false
        return clean.split('/').contains(segment)
    }

    /** The folder a staged copy must land in, given the app's cache dir. */
    fun stagingPath(cacheDirPath: String?): String =
        (cacheDirPath?.trimEnd('/') ?: "") + "/" + STAGED_DIR

    /**
     * A safe file name for the shared copy of [displayName].
     *
     * Keeps the extension (the receiver picks an app by it) and the stem, but strips directory
     * components, characters a filesystem refuses, and anything over [MAX_STAGED_NAME]. A vault
     * note called `../../etc/passwd` must not be able to name a file another app opens.
     */
    fun stagedNameFor(displayName: String?, fallback: String = "vault_file"): String {
        val raw = displayName?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.substringAfterLast(':')
        val base = if (raw.isNullOrEmpty()) fallback else raw
        val extension = extensionOf(base)
        val stem = if (extension.isEmpty()) base else base.removeSuffix(".$extension")
        val cleaned = sanitizeSegment(stem).take(MAX_STAGED_NAME - (extension.length + 1)).trim('_')
        val name = buildString {
            append(cleaned.ifEmpty { sanitizeSegment(fallback).ifEmpty { "file" } })
            if (extension.isNotEmpty()) append('.').append(extension)
        }
        return name
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase()
    }

    /** Letters, digits, dot, dash, underscore — everything else becomes a single underscore. */
    private fun sanitizeSegment(value: String): String = value
        .map { if (SAFE.contains(it)) it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')

    private val SAFE: Set<Char> = ('a'..'z').toSet() + ('A'..'Z').toSet() +
        ('0'..'9').toSet() + setOf('.', '-', '_')

    /**
     * The message shown when every authority in the chain was refused.
     *
     * Names the chain, because that is the exact string the fix has to match in
     * `AndroidManifest.xml`; an owner or a future agent can compare it against the manifest in one
     * reading instead of bisecting the build.
     */
    fun refusalMessage(packageName: String?, fileName: String): String =
        "Android refused to publish \"$fileName\" from this app's cache. " +
            "Authorities tried: ${candidates(packageName).joinToString(", ")}; " +
            "served folders: $STAGED_DIR/, $VIEWER_DIR/."
}
