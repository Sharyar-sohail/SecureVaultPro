package com.securevault.pro.data.remote.firebase

/**
 * The node layout from `sync_engine/SPEC-v1.md`, in one place.
 *
 * Every client — the APK and the Blogger page — reads and writes exactly these paths under the
 * same uid, so a path built here is the contract. The builders return null instead of throwing
 * when a segment is unsafe: a Realtime Database key may not contain `. # $ [ ] /` or whitespace,
 * and a silently mangled path would look like an empty vault rather than a bug.
 */
object RealtimePaths {

    /** Tree root. Bumping this is a breaking protocol change for every client. */
    const val ROOT: String = "svp1"

    /** The record modules the sync engine mirrors, in the order the web renders them. */
    val MODULES: List<String> = listOf(
        "notes", "entries", "cards", "totps", "contacts", "cryptoWallets", "files"
    )

    private val forbiddenChars = charArrayOf('.', '#', '$', '[', ']', '/')
    private const val MAX_SEGMENT_LENGTH = 128

    /** True when `value` can be used as a single database key without escaping anything. */
    fun isSafeSegment(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.length > MAX_SEGMENT_LENGTH) return false
        if (value.any { it in forbiddenChars || it.isWhitespace() }) return false
        return true
    }

    /**
     * Every builder takes a nullable segment: a caller that has not signed in yet holds `null`
     * rather than a bad uid, and both cases must answer the same way — no path, no exception.
     */
    fun user(uid: String?): String? = if (isSafeSegment(uid)) "$ROOT/users/$uid" else null

    /** `svp1/users/$uid/keyring` — the per-user vault key, sealed by the user's passphrase. */
    fun keyring(uid: String?): String? = user(uid)?.let { "$it/keyring" }

    /** `svp1/users/$uid/data` — the module tables live under this node. */
    fun dataRoot(uid: String?): String? = user(uid)?.let { "$it/data" }

    /** `svp1/users/$uid/data/$module` */
    fun module(uid: String?, moduleSegment: String?): String? =
        if (isSafeSegment(moduleSegment)) dataRoot(uid)?.let { "$it/$moduleSegment" } else null

    /** `svp1/users/$uid/data/$module/$id` */
    fun record(uid: String?, moduleSegment: String?, recordId: String?): String? =
        if (isSafeSegment(recordId)) module(uid, moduleSegment)?.let { "$it/$recordId" } else null

    /** `svp1/users/$uid/meta` — revision counter, last write time, last device. */
    fun meta(uid: String?): String? = user(uid)?.let { "$it/meta" }
}
