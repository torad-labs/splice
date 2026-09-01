// NEW (G15): pure unit tests on InvalidGrantLatch — no I/O, no auth-provider wiring. The file
// identity comparison IS the clear (see InvalidGrantLatch.kt); the one invariant worth pinning
// directly is that an unreadable identity (null) never suppresses, on either side of the comparison.
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.InvalidGrantLatch

class InvalidGrantLatchTest {

    private fun id(mtimeMs: Long, sizeBytes: Long = 100L) = CredentialFileIdentity(mtimeMs, sizeBytes)

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
        latch.latch(CredentialFileIdentity(mtimeMs = 1_700_000_000_000, sizeBytes = 812))
        assertFalse(
            latch.isLatched(CredentialFileIdentity(mtimeMs = 1_700_000_000_000, sizeBytes = 934)),
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
        val unchanged = CredentialFileIdentity(mtimeMs = 1_700_000_000_000, sizeBytes = 812)
        latch.latch(unchanged)
        assertTrue(
            latch.isLatched(CredentialFileIdentity(mtimeMs = 1_700_000_000_000, sizeBytes = 812)),
            "an untouched credential must keep suppressing: the latch exists to stop a refresh storm",
        )
    }
}
