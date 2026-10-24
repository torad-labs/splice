// NEW: 2026-10-09 — the named readings of a control-plane call, so a closed port and an unreadable answer are
// two values the caller maps on purpose, not one null (kt-no-silent-result-collapse).
package splice.daemonclient

import java.io.IOException

/** What one control-plane call came to: an answer, a connection that never answered, or an unreadable answer. */
public sealed class Reading<out T> {
    public data class Answered<T>(val value: T) : Reading<T>()

    /** The connection failed: a closed control port is the normal no-daemon reading. */
    public data object Refused : Reading<Nothing>()

    /** The daemon answered, but the address or the body was malformed. */
    public data object Malformed : Reading<Nothing>()
}

internal object Unanswered {
    /** [read] for a call whose own answer is null on a non-2xx status: that status is a refusal, not a value. */
    inline fun <T : Any> readOrRefused(block: () -> T?): Reading<T> = when (val read = read(block)) {
        is Reading.Answered -> read.value?.let { Reading.Answered(it) } ?: Reading.Refused
        Reading.Refused -> Reading.Refused
        Reading.Malformed -> Reading.Malformed
    }

    /** [block]'s value as an [Reading.Answered], or the reading that says why there is none. Only the connection's I/O
     *  failure and a malformed address or body are readings; anything else is a defect and propagates. */
    inline fun <T> read(block: () -> T): Reading<T> = try {
        Reading.Answered(block())
    } catch (_: IOException) {
        Reading.Refused
    } catch (_: IllegalArgumentException) {
        Reading.Malformed
    }
}
