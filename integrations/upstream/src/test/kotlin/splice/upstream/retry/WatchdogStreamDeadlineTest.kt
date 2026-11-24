// The operator's stream deadline (#791, server mode's `stream_idle_seconds` on a turn through the team's door): the one
// tier no live path holds off. Every other idle tier is a probe that asks the path before it reaps (WatchdogLivenessTest);
// this one is the operator saying how long a silence after content may last, so a round past it is ended even on a
// connection that is still open. Before the client has seen content it times nothing: that silence is a prefill.
//
// CLOCK POLICY: real clock and real sleeps, as WatchdogLivenessTest: the idleness under test is InflightGate.Slot's,
// on its own clock. Budgets are scaled so each arm takes well under a second.
package splice.upstream.retry

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.WatchdogBudget
import splice.upstream.ClientFrameEmitted
import splice.upstream.PathEvidence
import splice.upstream.PathLiveness
import splice.upstream.WsPathPulse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WatchdogStreamDeadlineTest {
    /** A budget whose own tiers are far off, with the operator's deadline at [deadlineMs]. */
    private fun budget(deadlineMs: Long, firstByteMs: Long = 30_000) = WatchdogBudget(
        firstByteTimeout = firstByteMs.milliseconds,
        streamIdle = 30.seconds,
        totalCap = 30.seconds,
        streamDeadline = deadlineMs.milliseconds,
    )

    /** The reading every SSE round supplies: its connection is open and has not errored. */
    private fun openConnection(): PathLiveness = PathLiveness(WsPathPulse { 0L }, PathEvidence.OPEN_CONNECTION)

    @Test
    fun `a round silent past the operator's deadline after content is ended though its connection is open`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val dog = TurnWatchdog(budget(deadlineMs = 300))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { true }, openConnection())

            val ended = withTimeoutOrNull(5.seconds) { target.join() }

            assertNotNull(ended, "a live path held a round past the operator's deadline")
            val fired = dog.fired
            assertTrue(fired is WatchdogFired.Idle, "the deadline ends the round as a silence: $fired")
            fired as WatchdogFired.Idle
            assertTrue(fired.sawClientFrame, "the deadline is a mid-output one")
            assertEquals(300L, fired.limitMs, "the verdict names the deadline that fired")
            assertTrue(fired.deadline, "the verdict says the operator's deadline ended the round")
            assertNull(dog.held, "a round past the deadline is not held")

            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `bytes that carry no progress, such as keepalives, never renew the deadline`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val dog = TurnWatchdog(budget(deadlineMs = 300))
            val target = launch { delay(10.seconds) }
            // The upstream's keepalives touch the round's connection every 50 ms; the model itself says nothing.
            val keepalives = launch {
                while (true) {
                    slot.touch()
                    delay(KEEPALIVE_MS)
                }
            }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { true }, openConnection())

            val ended = withTimeoutOrNull(5.seconds) { target.join() }

            assertNotNull(ended, "keepalives held a silent model past the operator's deadline")
            assertTrue((dog.fired as? WatchdogFired.Idle)?.deadline == true, "the deadline ended it: ${dog.fired}")

            keepalives.cancel()
            poller.cancel()
            slot.release()
        }
    }

    @Test
    fun `before the client has seen content the deadline times nothing, and a live path holds the prefill`() {
        runBlocking {
            val slot = InflightGate({ 0 }).admittedSlot()
            val dog = TurnWatchdog(budget(deadlineMs = 300, firstByteMs = 300))
            val target = launch { delay(10.seconds) }
            val poller = dog.launchIn(this, slot, target, ClientFrameEmitted { false }, openConnection())

            withTimeout(5.seconds) { while (dog.held == null) delay(DEADLINE_POLL_MS) }

            assertNull(dog.fired, "the operator's deadline reaped a prefill")
            assertTrue(target.isActive, "the prefill's round must still be running")

            poller.cancel()
            target.cancel()
            slot.release()
        }
    }
}

/** Long enough for several polls of a 300ms tier to have landed, and far below any budget here. */
private const val DEADLINE_POLL_MS = 10L

/** How often the stand-in upstream's keepalives touch the round's connection: far inside the 300ms deadline. */
private const val KEEPALIVE_MS = 50L
