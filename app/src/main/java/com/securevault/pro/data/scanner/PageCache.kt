package com.securevault.pro.data.scanner

/**
 * The page cache, and the cap that makes a long session survivable.
 *
 * A 40-page scan is 40 sets of JPEG bytes (roughly 300 KB each, ~12 MB total, which is fine to
 * hold) but *not* 40 decoded bitmaps: one 2400x3200 ARGB page is 30 MB of native heap, so 40 of
 * them is 1.2 GB and the process is killed on the twelfth page. The rule the scanner follows, and
 * the reason this class exists instead of a `MutableMap`:
 *
 *  - full-resolution pages are **decoded one at a time, used, and released**; nothing keeps a
 *    [ArgbPlane] alive outside this cache;
 *  - the cache holds at most [maxPages] decoded pages and at most [maxBytes] of them, whichever
 *    trips first evicts the least recently used entry;
 *  - an evicted page is not lost, only *re-derivable* — the caller hands the loader function back
 *   on the next touch, which is why the cap is a performance contract and not a data-loss one;
 *  - a PDF composition never touches the cache at all: it streams page by page (decode → draw →
 *    recycle → next), so its peak is one page plus the PDF writer's own buffer.
 *
 * Default cap: 3 pages. At 2400x3200 that is ~90 MB, which is a page or two of what an
 * `largeHeap` Compose screen can afford next to the thumbnails already on screen.
 */
class PageCache<Key : Any>(
    val maxPages: Int = DEFAULT_MAX_PAGES,
    val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    init {
        require(maxPages >= 1) { "a page cache needs at least one slot" }
        require(maxBytes >= 0) { "the byte budget cannot be negative" }
    }

    private class Entry<Key : Any>(val key: Key, var bytes: Long, val release: (ArgbPlane) -> Unit) {
        var plane: ArgbPlane? = null
        var touches: Long = 0
    }

    private val entries = LinkedHashMap<Key, Entry<Key>>()
    private var clock: Long = 0
    private var bytes: Long = 0

    /** Held pages right now, oldest touch first. */
    val size: Int get() = entries.size
    val heldBytes: Long get() = bytes

    /**
     * Returns the cached page for [key], building it with [load] when absent.
     *
     * [load] is only ever called with the cache lock concept held by the caller's single worker
     * thread; the scanner runs every page pass on `Dispatchers.Default` inside a `viewModelScope`,
     * and this class is not thread-safe by design — one session, one owner.
     */
    fun getOrLoad(key: Key, load: () -> ArgbPlane?, release: (ArgbPlane) -> Unit = {}): ArgbPlane? {
        clock += 1
        entries[key]?.let { existing ->
            existing.plane?.let { held ->
                existing.touches = clock
                return held
            }
        }
        val plane = load() ?: return null
        val existing = entries[key]
        if (existing != null) {
            existing.plane?.let { entry ->
                bytes -= existing.bytes
                existing.release(entry)
            }
            existing.bytes = plane.byteCost.toLong()
            existing.plane = plane
            existing.touches = clock
            bytes += existing.bytes
        } else {
            val entry = Entry(key, plane.byteCost.toLong(), release)
            entry.plane = plane
            entry.touches = clock
            entries[key] = entry
            bytes += entry.bytes
        }
        trim()
        return plane
    }

    /** Evicts until both budgets fit; the least recently touched page goes first. */
    private fun trim() {
        while (entries.size > maxPages || bytes > maxBytes) {
            val victim = entries.values.sortedBy { it.touches }.firstOrNull { it.plane != null } ?: break
            remove(victim.key)
        }
    }

    fun remove(key: Key): Boolean {
        val entry = entries.remove(key) ?: return false
        entry.plane?.let { held ->
            bytes -= entry.bytes
            entry.release(held)
        }
        return true
    }

    /** Drops every page and reports how many were held; called when a session is closed. */
    fun clear(): Int {
        val count = entries.size
        entries.values.forEach { entry ->
            entry.plane?.let { held ->
                bytes -= entry.bytes
                entry.release(held)
            }
        }
        entries.clear()
        bytes = 0
        return count
    }

    fun keys(): List<Key> = entries.keys.toList()

    companion object {
        const val DEFAULT_MAX_PAGES: Int = 3
        const val DEFAULT_MAX_BYTES: Long = 96L * 1024L * 1024L
    }
}
