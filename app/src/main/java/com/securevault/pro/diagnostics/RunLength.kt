package com.securevault.pro.diagnostics

/**
 * How long a finished run was, in the words the report quotes.
 *
 * The raw millisecond figure went into the report once and the owner read "after 4904842 ms" as a
 * fault of its own. A run that died four seconds after launch is a start-up crash worth chasing;
 * one that died after an hour and a quarter of use is the phone reclaiming memory, and the two
 * need different answers. Spelling the difference out is what makes the report readable alone.
 */
object RunLength {

    /** [ms] as "41 s", "12 min 7 s" or "1 h 21 min". A negative gap reads as unknown, not as a lie. */
    fun spoken(ms: Long): String {
        if (ms < 0L) return "an unknown time"
        val seconds = ms / 1000
        if (seconds < 60) return "$seconds s"
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes min ${seconds % 60} s"
        return "${minutes / 60} h ${minutes % 60} min"
    }
}
