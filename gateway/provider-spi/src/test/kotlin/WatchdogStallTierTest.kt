// NEW: V4-116 — the MID-OUTPUT STALL RE-ANCHOR tier (`WatchdogBudget.stallReanchor`).
//
// The scar: claude-deepseek session b10459ba streamed 3810 content frames, went silent, and sat the
// WHOLE 300s mid-output tier before ending a turn that was continuable the entire time. streamIdle
// is a STALL DETECTOR — breaching it is an admission of defeat — while a breach of this tier is the
// signal to CANCEL the round and resume it from its own salvage, which the client cannot see. So
// the two are different machines and this file pins the three facts that separate them:
//
//   1. an ARMED tier reaps a mid-output round at ITS limit, four orders of magnitude before
//      streamIdle, and the fired verdict NAMES that limit (the honest-message contract: a holder
//      that cannot see which tier fired will report the 300s number for a 20s decision);
//   2. it NEVER applies before the first client frame — the pre-output silence is prefill, and the
//      v35 doctrine that protects a minutes-long compaction prefill is not weakened here;
//   3. an UNARMED head is byte-for-byte the old watchdog: streamIdle stays its floor. That is what
//      a head which has not been measured to continue from a prefill keeps, and it is the reason
//      the field defaults to INFINITE rather than to a number.
//
// CLOCK POLICY: real clock and real sleeps, like the idle arms of WatchdogTest, because what is
// under test is the IDLE tier and idleness is measured by InflightGate.Slot, whose clock is its own
// and outside this wave's seams. The budgets are scaled so the slowest arm here is under a second.
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.WatchdogBudget
import splice.spi.ClientFrameEmitted
import splice.spi.InflightGate
import splice.spi.TurnWatchdog
import splice.spi.WatchdogFired
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Long enough for several polls of a 200ms tier to have happened, and ~1/300th of streamIdle. */
private const val SETTLE_MS = 900L

/** The tier under test. Kept far below the polls' 250ms floor only in the sense that matters: the
 *  poller samples every 250ms, so a 200ms tier is reached on the very first tick. */
private const val STALL_MS = 200L

private const val STREAM_IDLE_MS = 300_000L

class WatchdogStallTierTest {

    private fun budget(stallMs: Long?) = WatchdogBudget(
        firstByteTimeout = 300.seconds,
        streamIdle = STREAM_IDLE_MS.milliseconds,
        totalCap = 900.seconds,
        stallReanchor = stallMs?.milliseconds ?: Duration.INFINITE,
    )

    @Test
    fun `an armed stall tier reaps a mid-output round at ITS limit, not at streamIdle`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(budget(STALL_MS))
            val cancelled = AtomicBoolean(false)
            val target = launch {
                try {
                    delay(10.seconds)
                } finally {
                    cancelled.set(true)
                }
            }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { true })
            delay(SETTLE_MS)

            val fired = dog.fired
            assertTrue(
                fired is WatchdogFired.Idle,
                "a mid-output round silent past the armed stall tier must be reaped — this is the " +
                    "whole row: at streamIdle the client waits 300s for a repair splice could make " +
                    "in seconds, and the salvage dies with the round",
            )
            // The verdict names the number the poller actually compared. A holder that has to guess
            // which tier fired reports the 300s cap for a 20s decision, which is the same defect
            // the responses dialect already fixed for its own two tiers.
            assertEquals(STALL_MS, (fired as WatchdogFired.Idle).limitMs, "the tier that fired must name ITS limit")
            assertTrue(fired.sawClientFrame, "the stall tier is a MID-OUTPUT tier — its family is the client frame")
            assertTrue(cancelled.get(), "the typed sentinel is set FIRST, then the round is cancelled")

            target.cancel()
            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `the stall tier never applies before the first client frame`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(budget(STALL_MS))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { false })
            delay(SETTLE_MS) // 4x the stall tier, all of it pre-output

            assertNull(
                dog.fired,
                "a pre-output silence is PREFILL and is judged by firstByteTimeout alone — arming " +
                    "the stall tier must not resurrect the compaction-ate-my-quota regression",
            )
            target.cancel()
            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `an unarmed head keeps streamIdle as its floor`() {
        runBlocking {
            val gate = InflightGate({ 0 })
            val slot = gate.admittedSlot()
            val dog = TurnWatchdog(budget(null))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { true })
            delay(SETTLE_MS) // mid-output, and 150x under streamIdle

            assertNull(
                dog.fired,
                "an unarmed head is the OLD watchdog: a provider with no continuation to resume into " +
                    "keeps streamIdle, because for it an early reap only ends the turn sooner",
            )
            target.cancel()
            poller.cancel()
            slot.release()
        }
    }
}
