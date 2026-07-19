// NEW: (G15) terminal invalid_grant latch — a dead refresh token was re-POSTed on every single
// turn (one wasted network hop + one log line per request) because nothing remembered "the last
// confirmed invalid_grant we saw was against THIS exact file state." Keyed on the auth file's mtime
// rather than a boolean: mtime IS the auto-clear (a re-login rewrites the file, mtime changes,
// isLatched naturally goes false everywhere) — no explicit clear() method, and a latch keyed on an
// unreadable mtime (null) fails OPEN (never suppresses), since a null mtime is not evidence the
// file is unchanged.
package splice.core.auth

/** Per-provider terminal-rejection latch, gated on the auth file's last-modified-time (ms). */
public class InvalidGrantLatch {
    @Volatile
    private var latchedAtMtimeMs: Long? = null

    /** True only when latched AND [currentMtimeMs] matches the mtime latched against — a null on
     *  either side (never latched, or the current stat failed) never suppresses. */
    public fun isLatched(currentMtimeMs: Long?): Boolean =
        latchedAtMtimeMs != null && currentMtimeMs != null && latchedAtMtimeMs == currentMtimeMs

    /** Record a confirmed invalid_grant against the file state at [mtimeMs]. */
    public fun latch(mtimeMs: Long?) {
        latchedAtMtimeMs = mtimeMs
    }
}
