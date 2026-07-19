// NEW (G15): pure unit tests on InvalidGrantLatch — no I/O, no auth-provider wiring. The mtime
// comparison IS the clear (see InvalidGrantLatch.kt); the one invariant worth pinning directly is
// that an unreadable mtime (null) never suppresses, on either side of the comparison.
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.InvalidGrantLatch

class InvalidGrantLatchTest {

    @Test
    fun `unlatched by default returns false for any mtime including null`() {
        val latch = InvalidGrantLatch()
        assertFalse(latch.isLatched(100L))
        assertFalse(latch.isLatched(null))
    }

    @Test
    fun `latch matches only the exact mtime it was latched against`() {
        val latch = InvalidGrantLatch()
        latch.latch(100L)
        assertTrue(latch.isLatched(100L))
        assertFalse(latch.isLatched(101L))
        assertFalse(latch.isLatched(null))
    }

    @Test
    fun `latch against an unreadable mtime never suppresses - fail open on a stat failure`() {
        val latch = InvalidGrantLatch()
        latch.latch(null)
        assertFalse(latch.isLatched(null))
    }
}
