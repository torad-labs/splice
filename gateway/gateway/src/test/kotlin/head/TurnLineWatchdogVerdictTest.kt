// NEW: (2026-09-02) the watchdog's verdict on the turn line, in the numbers it judged on.
//
// The gap this closes, from the live log: five compactions died saying "no completion within the
// 180s idle cap". That wording is chosen by the fired sentinel's sawClientFrame flag, and the
// number in it is the CONFIGURED streamIdle, never the limit the poller actually compared against.
// A compact turn has no first-output tier: WatchdogBudget.forCompact disables it, so only the
// total cap can end a silent compaction. These arms pin the valid verdicts and their measured values.
package head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.head.TurnLine
import splice.spi.WatchdogFired

private fun meta(compact: Boolean) = TurnMeta(
    compact = compact,
    showReasoning = ReasoningDisplay.TEXT,
    stream = true,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    clientMaxTokens = 8000,
    effort = "high",
    summary = "detailed",
    budgetTokens = null,
)

private val stalled = TurnOutcome.Failure(ErrorType.OVERLOADED, "claudex: upstream stream stalled — aborted; retry")

class TurnLineWatchdogVerdictTest {

    private val line = TurnLine("claudex")

    @Test
    fun `a mid-output fire names the tier, the limit it fired on and the idleness that tripped it`() {
        val rendered = line.render(
            meta(compact = true),
            "gpt-5.6-sol",
            stalled,
            latencyMs = 235011,
            fired = WatchdogFired.Idle(idleMs = 180004, sawClientFrame = true, limitMs = 180000),
        )
        assertTrue("watchdog=idle(tier=mid-output limit=180000ms idle=180004ms)" in rendered, rendered)
    }

    @Test
    fun `a first-output fire on a non-compact turn names its real tier`() {
        val rendered = line.render(
            meta(compact = false),
            "gpt-5.6-sol",
            stalled,
            latencyMs = 300_120,
            fired = WatchdogFired.Idle(idleMs = 300_001, sawClientFrame = false, limitMs = 300_000),
        )
        assertTrue("watchdog=idle(tier=first-output limit=300000ms idle=300001ms)" in rendered, rendered)
        assertFalse("mid-output" in rendered, "a turn the client never saw output from is not mid-output: $rendered")
    }

    @Test
    fun `a silent compaction ended by the total cap names that verdict`() {
        val rendered = line.render(
            meta(compact = true),
            "gpt-5.6-sol",
            stalled,
            latencyMs = 900_030,
            fired = WatchdogFired.TotalCap(elapsedMs = 900_002),
        )
        assertTrue("watchdog=total-cap(elapsed=900002ms)" in rendered, rendered)
        assertFalse("first-output" in rendered, "a compact turn has no pre-output idle tier: $rendered")
    }

    @Test
    fun `a turn no watchdog touched is byte-identical to before`() {
        val ok = TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 42))
        assertEquals(
            line.render(meta(compact = false), "gpt-5.6-sol", ok, latencyMs = 1200),
            "[claudex] turn compact=false model=gpt-5.6-sol latency=1200ms ok out=42 tool=false incomplete=false\n",
        )
    }
}
