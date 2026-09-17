// NEW: (G15) terminal invalid_grant latch — a dead refresh token was re-POSTed on every single
// turn (one wasted network hop + one log line per request) because nothing remembered "the last
// confirmed invalid_grant we saw was against THIS exact file state." Keyed on the auth file's mtime
// rather than a boolean: mtime IS the auto-clear (a re-login rewrites the file, mtime changes,
// isLatched naturally goes false everywhere) — no explicit clear() method, and a latch keyed on an
// unreadable mtime (null) fails OPEN (never suppresses), since a null mtime is not evidence the
// file is unchanged.
package splice.core.auth

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

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
 *  direction of the change is away from the lockout rather than toward a refresh storm.
 *
 *  V4-70 (2026-09-17) — THE SCAR, and DR-176'S WIDENING WAS INCOMPLETE. Read this before widening
 *  the type again, because the subject is not the file's METADATA. DR-176's incident is recorded
 *  above: an operator who had just re-authenticated had every turn refused LOCALLY, without a
 *  request ever leaving the box, until something moved the mtime. That is THIS bug — suffered in
 *  production once already, fixed once, and the fix did not close it, because [sizeBytes] is a
 *  field a same-length rewrite does not change. A re-login whose new token is the same length as
 *  the rejected one, landing in the same millisecond (or restoring the timestamp, as cp -p, a tar
 *  extract, or any mtime-preserving tool does), is therefore INDISTINGUISHABLE from the file the
 *  latch was armed against, and the sentinel survives the very refresh meant to clear it. Measured
 *  2026-09-17 as a one-in-N failure of MuseAuthProviderFixesTest's poll-mint test, then constructed
 *  deterministically.
 *
 *  [contentDigest] is what the check should have been keyed on from the start: the credential
 *  file's CONTENT, because "the credential the latch was armed against is still the credential
 *  here" is a claim about bytes, and every metadata field is a proxy that a rewrite can hold
 *  constant. It stays monotone in DR-176's direction — a different file can never compare equal
 *  (the lockout dies), and an unchanged file still compares equal (the refresh storm the latch
 *  exists to prevent is still suppressed) — so it can only ever release the latch more readily,
 *  exactly as the paragraph above requires.
 *
 *  A DIGEST STRING, NOT A ByteArray, and that is load-bearing rather than style: this is a `data
 *  class`, so array equality is REFERENCE equality, and a ByteArray field would make two identical
 *  files compare UNEQUAL — the latch would never suppress anything, which is the refresh storm this
 *  whole file exists to prevent, arriving silently and looking like a fix. */
public data class CredentialFileIdentity(
    val mtimeMs: Long,
    val sizeBytes: Long,
    val contentDigest: String,
)

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

/** V4-70: the CONTENT half of a [CredentialFileIdentity] — SHA-256 of the file's bytes, lowercase
 *  hex. One reader for every provider, so the four construction sites cannot drift on how the
 *  digest is taken.
 *
 *  THROWS on any I/O failure, deliberately, rather than returning null: all four callers already
 *  wrap their identity construction in a best-effort catch that collapses to a NULL identity, and
 *  a null identity is this file's established way of saying UNKNOWN — which the latch treats as
 *  fail-open (never suppresses), because a null is not evidence the file is unchanged. Returning
 *  null here instead would hand each caller a second way to get it wrong: a `?:` they can forget,
 *  turning a transient read error into a lockout. */
public object CredentialFileDigest {
    public fun of(path: Path): String {
        val bytes = Files.readAllBytes(path)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
