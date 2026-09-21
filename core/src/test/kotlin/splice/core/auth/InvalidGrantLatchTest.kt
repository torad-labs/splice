// NEW (G15): pure unit tests on InvalidGrantLatch — no I/O, no auth-provider wiring. The file
// identity comparison IS the clear (see InvalidGrantLatch.kt); the one invariant worth pinning
// directly is that an unreadable identity (null) never suppresses, on either side of the comparison.
package splice.core.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class InvalidGrantLatchTest {

    private fun id(
        mtimeMs: Long,
        sizeBytes: Long = 100L,
        contentDigest: String = "unchanged-credential-digest",
    ) = CredentialFileIdentity(mtimeMs, sizeBytes, contentDigest)

    @Test
    fun `unlatched by default returns false for any identity including null`() {
        val latch = InvalidGrantLatch()
        assertFalse(latch.isLatched(id(100L)))
        assertFalse(latch.isLatched(null))
    }

    @Test
    fun `latch matches only the exact identity it was latched against`() {
        val latch = InvalidGrantLatch()
        latch.latch(id(100L))
        assertTrue(latch.isLatched(id(100L)))
        assertFalse(latch.isLatched(id(101L)))
        assertFalse(latch.isLatched(null))
    }

    @Test
    fun `latch against an unreadable identity never suppresses - fail open on a stat failure`() {
        val latch = InvalidGrantLatch()
        latch.latch(null)
        assertFalse(latch.isLatched(null))
    }

    // DR-176: the identity was a bare mtime-in-milliseconds and every caller derived it through
    // FileTime.toMillis(), which truncates the nanoseconds ext4 and xfs actually store. A re-login
    // landing inside the same millisecond tick as the rejected one — or any restore that preserves
    // the FileTime — produced a genuinely NEW credential the latch still read as the old rejected
    // file state, so every turn was refused locally, without a request leaving the box, for an
    // operator who had just re-authenticated.
    @Test
    fun `a replacement credential inside the same millisecond releases the latch - DR-176`() {
        val latch = InvalidGrantLatch()
        latch.latch(
            CredentialFileIdentity(
                mtimeMs = 1_700_000_000_000,
                sizeBytes = 812,
                contentDigest = "unchanged-credential-digest",
            ),
        )
        assertFalse(
            latch.isLatched(
                CredentialFileIdentity(
                    mtimeMs = 1_700_000_000_000,
                    sizeBytes = 934,
                    contentDigest = "a-different-credential",
                ),
            ),
            "a different credential sharing a truncated mtime must not stay latched out",
        )
    }

    // THE TRAP, pinned deliberately: widening the identity must not make the latch fail open more
    // often than before. The whole point of the latch is that a dead refresh token is not re-POSTed
    // on every single turn, so an unchanged file must still suppress — otherwise this repair trades
    // a lockout bug for a refresh storm, which is the worse of the two.
    @Test
    fun `an unchanged credential is still latched - DR-176 trap control`() {
        val latch = InvalidGrantLatch()
        val unchanged = CredentialFileIdentity(
            mtimeMs = 1_700_000_000_000,
            sizeBytes = 812,
            contentDigest = "unchanged-credential-digest",
        )
        latch.latch(unchanged)
        assertTrue(
            latch.isLatched(
                CredentialFileIdentity(
                    mtimeMs = 1_700_000_000_000,
                    sizeBytes = 812,
                    contentDigest = "unchanged-credential-digest",
                ),
            ),
            "an untouched credential must keep suppressing: the latch exists to stop a refresh storm",
        )
    }

    // ── V4-70: THE COLLISION METADATA CANNOT SEE ────────────────────────────────────────────────
    // The bug this pin exists for, and it is constructed rather than awaited: a re-login that
    // writes a SAME-LENGTH credential in the SAME millisecond (or restores the timestamp, as cp -p
    // and tar do) was indistinguishable from the file the latch was armed against, so the sentinel
    // outlived the refresh that was meant to clear it. DR-176 is the same incident in production —
    // an operator who had just re-authenticated, every turn refused locally, no request leaving the
    // box — and its widening did not close it because size is a field a same-length rewrite holds
    // constant. Measured 2026-09-17 as a one-in-N red in MuseAuthProviderFixesTest.
    @Test
    fun `a same-length rewrite that keeps the timestamp releases the latch - V4-70`() {
        val latch = InvalidGrantLatch()
        latch.latch(id(1_700_000_000_000, sizeBytes = 812, contentDigest = "digest-of-the-rejected-file"))
        assertFalse(
            latch.isLatched(
                id(1_700_000_000_000, sizeBytes = 812, contentDigest = "digest-of-the-new-credential"),
            ),
            "identical mtime AND identical size must not hold a latch against DIFFERENT content",
        )
    }

    @Test
    fun `a digest read failure yields no identity, so the latch can never suppress on it - V4-70`() {
        // The fail-open direction, pinned where it can actually be provoked: CredentialFileDigest.of
        // THROWS on an unreadable path, so every caller's existing best-effort catch collapses it to
        // the null identity that means UNKNOWN. A null identity must never suppress, or a transient
        // I/O error becomes a lockout — the one way this fix could be worse than the bug.
        assertThrows<IOException> {
            CredentialFileDigest.of(java.nio.file.Path.of("/nonexistent/credential-file-that-cannot-be-read"))
        }
        val latch = InvalidGrantLatch()
        latch.latch(null)
        assertFalse(latch.isLatched(null), "a null identity is unknown, not unchanged")
    }
}
