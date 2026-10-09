// NEW: 2026-10-09 — the one named reading of "the daemon did not answer", so the probes that return null for it say so
// by type instead of by an ast-grep-ignore comment on a collapsed Result (kt-no-silent-result-collapse).
package splice.daemonclient

import java.io.IOException

/** A reading of the control plane that is null when nothing answered. */
internal object Unanswered {
    /** [block]'s value, or null when the call never connected or the answer could not be read: a closed control port
     *  is the normal no-daemon reading, and each caller turns the null into its own sentence. Only the connection's I/O
     *  failure and a malformed address or body are that answer; anything else is a defect and propagates. */
    inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
