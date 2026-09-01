// DR-177: the shared expiry arithmetic. Four provider call sites each spelled `now + seconds *
// 1000` (kimi in the seconds domain, codex over an absolute JWT exp) and none of them was total.
// The overflow does not merely produce a wrong number: it produces a large NEGATIVE instant, so the
// credential reads expired on every turn, every turn refreshes, and the refresh returns the same
// bad field — a permanent storm out of one number. These are the arms that make that unspellable.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.CredentialExpiry
import splice.core.auth.SYNTHETIC_EXPIRY_TTL_MS

private const val NOW = 1_700_000_000_000L

class CredentialExpiryTest {

    @Test
    fun `a healthy lifetime is still now plus seconds - DR-177 status quo`() {
        assertEquals(NOW + 3_600_000L, CredentialExpiry.expiryFromNowMs(NOW, 3600))
        assertEquals(1000L + 3_600_000L, CredentialExpiry.expiryFromNowMs(1000L, 3600))
    }

    // The multiply-side overflow the row names: Long.MAX_VALUE/1000 + 1 seconds.
    @Test
    fun `a lifetime whose milliseconds do not fit degrades to the synthesized ceiling - DR-177`() {
        val absurd = Long.MAX_VALUE / 1000 + 1
        assertEquals(NOW + SYNTHETIC_EXPIRY_TTL_MS, CredentialExpiry.expiryFromNowMs(NOW, absurd))
    }

    // The half a multiply-side guard alone cannot see: a lifetime perfectly representable in
    // milliseconds that still overflows once the clock is added to it.
    @Test
    fun `a lifetime that fits in millis but overflows the clock also degrades - DR-177`() {
        val fitsAsMillis = Long.MAX_VALUE / 1000
        assertTrue(fitsAsMillis * 1000 > 0, "the fixture must not overflow on the MULTIPLY, or it tests the other arm")
        assertEquals(NOW + SYNTHETIC_EXPIRY_TTL_MS, CredentialExpiry.expiryFromNowMs(NOW, fitsAsMillis))
    }

    @Test
    fun `a negative or zero lifetime expires now, never before the epoch - DR-177`() {
        assertEquals(NOW, CredentialExpiry.expiryFromNowMs(NOW, -5))
        assertEquals(NOW, CredentialExpiry.expiryFromNowMs(NOW, 0))
        assertEquals(NOW, CredentialExpiry.expiryFromNowMs(NOW, Long.MIN_VALUE))
    }

    // THE TRAP, pinned deliberately. Saturating to Long.MAX_VALUE is the obvious way to stop an
    // overflow and it is the WRONG one here: it hands back a credential that never expires, which
    // is precisely the hole SH-01 and G18 exist to close. Degrading to the synthesized ceiling can
    // only ever force an EXTRA refresh — the invariant SynthesizedExpiry.kt states at the top.
    //
    // 2^61 earns its place in the list by proving the MULTIPLY-side guard load-bearing, which none
    // of the values above can: 2^61 * 1000 == 2^64 * 125, so the multiply wraps to exactly ZERO —
    // positive, not negative — and the sum-side guard, which only ever sees a total that went
    // BACKWARDS, cannot see it. Without the multiply guard a 73-billion-year lifetime silently
    // becomes an expiry of "now" (2^61) or one second (2^61+1). The equality is what pins the
    // degradation TARGET rather than a safe-looking envelope those two would slip through.
    @Test
    fun `no input can produce a never-expiring credential - DR-177 trap control`() {
        val hostile = listOf(
            Long.MAX_VALUE,
            Long.MAX_VALUE / 1000,
            Long.MAX_VALUE / 1000 + 1,
            Long.MAX_VALUE - 1,
            1L shl 61,
            (1L shl 61) + 1,
        )
        hostile.forEach { seconds ->
            val expiry = CredentialExpiry.expiryFromNowMs(NOW, seconds)
            assertTrue(expiry > NOW, "an expiry in the PAST is the refresh storm: $seconds -> $expiry")
            assertTrue(
                expiry <= NOW + SYNTHETIC_EXPIRY_TTL_MS,
                "an unrepresentable lifetime must not become a never-expiring credential: $seconds -> $expiry",
            )
            assertEquals(
                NOW + SYNTHETIC_EXPIRY_TTL_MS,
                expiry,
                "an unrepresentable lifetime degrades to the CEILING, not merely to something safe-looking: $seconds",
            )
        }
    }

    @Test
    fun `an absolute exp claim converts, and an unusable one is null not a wrapped instant - DR-177`() {
        assertEquals(1_700_000_000_000L, CredentialExpiry.epochSecondsToMs(1_700_000_000L))
        assertNull(CredentialExpiry.epochSecondsToMs(-1), "a negative exp is nonsense, not 'expired'")
        assertNull(CredentialExpiry.epochSecondsToMs(Long.MAX_VALUE / 1000 + 1), "an exp past Long is unusable")
        assertNull(CredentialExpiry.epochSecondsToMs(Long.MAX_VALUE))
    }

    // Null is not an evasion here: it is the value the codex call sites already answer with
    // `?: synthesizeExpiry(mtime)`, so an unrepresentable claim takes the same path a MISSING one
    // takes. The arm pins that null is reachable ONLY for values that genuinely do not fit.
    @Test
    fun `every representable exp claim converts rather than falling back - DR-177 trap control`() {
        listOf(0L, 1L, 1_700_000_000L, Long.MAX_VALUE / 1000).forEach { seconds ->
            assertEquals(seconds * 1000, CredentialExpiry.epochSecondsToMs(seconds), "exp $seconds must convert")
        }
    }
}
