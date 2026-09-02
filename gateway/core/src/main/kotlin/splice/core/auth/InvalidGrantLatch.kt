// NEW: (G15) terminal invalid_grant latch — a dead refresh token was re-POSTed on every single
// turn (one wasted network hop + one log line per request) because nothing remembered "the last
// confirmed invalid_grant we saw was against THIS exact file state." Keyed on the auth file's mtime
// rather than a boolean: mtime IS the auto-clear (a re-login rewrites the file, mtime changes,
// isLatched naturally goes false everywhere) — no explicit clear() method, and a latch keyed on an
// unreadable mtime (null) fails OPEN (never suppresses), since a null mtime is not evidence the
// file is unchanged.
package splice.core.auth

/** DR-176: the credential file state this latch is keyed on.
 *
 *  Was a bare mtime-in-milliseconds, and every caller derived it through FileTime.toMillis(), which
 *  TRUNCATES the nanoseconds ext4 and xfs actually store. So a re-login that landed inside the same
 *  millisecond tick as the rejected one — or any restore that preserves the FileTime — produced a
 *  byte-for-byte NEW credential that the latch still considered the old, rejected file state. The
 *  operator had just re-authenticated and every turn was refused locally, without a request ever
 *  leaving the box, until something else happened to move the mtime.
 *
 *  [sizeBytes] joins the identity, mirroring the fix DR-148 already landed on KimiRefreshedTokens
 *  and its codex twin for the same reason on a different object: "mtime alone cannot see a
 *  rotation that lands inside the same filesystem timestamp tick". Widening the identity can only
 *  ever RELEASE the latch more readily, never hold it longer — a file whose mtime AND size are both
 *  unchanged still compares equal, so the suppression this latch exists for is untouched, and the
 *  direction of the change is away from the lockout rather than toward a refresh storm. */
public data class CredentialFileIdentity(val mtimeMs: Long, val sizeBytes: Long)

/** Per-provider terminal-rejection latch, gated on the auth file's identity. */
public class InvalidGrantLatch {
    @Volatile
    private var latchedAt: CredentialFileIdentity? = null

    /** True only when latched AND [current] matches the identity latched against — a null on
     *  either side (never latched, or the current stat failed) never suppresses. */
    public fun isLatched(current: CredentialFileIdentity?): Boolean =
        latchedAt != null && current != null && latchedAt == current

    /** Record a confirmed invalid_grant against the file state at [identity]. */
    public fun latch(identity: CredentialFileIdentity?) {
        latchedAt = identity
    }
}
