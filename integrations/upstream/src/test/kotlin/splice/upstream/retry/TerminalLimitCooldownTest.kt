package splice.upstream.retry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.ProviderWordsFilter
import splice.core.util.ElapsedClock
import splice.upstream.RetryNotice
import java.time.Instant

class TerminalLimitCooldownTest {
    private val cause = FailureCause.Provider.Limited(
        Lift.Unstated,
        LimitWindow.BILLING_CYCLE,
        ProviderWordsFilter.of("Monthly usage limit reached."),
    )

    @Test
    fun `only an acceptance posted after the terminal refusal may clear it`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        val olderAcceptance = cooldown.acceptance()
        cooldown.refused(cause)

        olderAcceptance()

        assertEquals(cause, cooldown.terminalLimit, "an older in-flight answer cannot erase the newer refusal")
        assertEquals(20_000L, cooldown.remainingMs())
        cooldown.acceptance()()
        assertNull(cooldown.terminalLimit)
        assertEquals(0L, cooldown.remainingMs())
    }

    @Test
    fun `reading never extends the terminal refusal and unrelated later arms cannot resurrect it`() {
        var now = 0L
        val cooldown = RateLimitCooldown(ElapsedClock { now })
        cooldown.refused(cause)
        now = 19_999L
        assertEquals(cause, cooldown.terminalLimit)
        assertEquals(1L, cooldown.remainingMs())

        now = 20_000L
        cooldown.arm(120_000L)

        assertNull(cooldown.terminalLimit, "a different later cooldown does not revive expired provider words")
        assertEquals(120_000L, cooldown.remainingMs())
    }

    @Test
    fun `restart and a newer finite refusal preserve the explicit re-probe escape`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        cooldown.refused(cause)
        cooldown.clear()
        assertNull(cooldown.terminalLimit)
        assertEquals(0L, cooldown.remainingMs())

        cooldown.refused(cause)
        cooldown.refused(
            FailureCause.Provider.Limited(
                Lift.At(Instant.EPOCH, true, LiftSource.RETRY_AFTER),
                window = null,
            ),
        )
        assertNull(cooldown.terminalLimit, "a newer finite temporary limit retains its ordinary per-turn behavior")
    }

    private fun RateLimitCooldown.refused(limited: FailureCause.Provider.Limited) {
        limitLeft(LimitLeft(limited.lift, null, null, null, false, limited), RetryNotice {})
    }
}
