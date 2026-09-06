// NEW (2026-09-06): what splice actually says on a quiet wire. The first line explains itself and
// names the row; the rest are a ticker. The wording turns on whether the CLIENT has seen a delta,
// which is the difference between a turn that has not begun answering (gpt-6-astra's 5-12 minute
// buffered turns) and one that stopped mid-answer — two different problems, and the line must not
// call one the other.
package head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.gateway.head.TurnProgressLine

class TurnProgressLineTest {

    @Test
    fun `the first line explains itself and names the row, the rest tick`() {
        val line = TurnProgressLine()
        assertEquals(
            "[splice] holding this turn open. 30s into the turn, no output from gpt-6-astra yet.",
            line.next(elapsedMs = 30_000, model = "gpt-6-astra", sawOutput = false),
        )
        assertEquals(
            "\n[splice] 1m0s, still waiting.",
            line.next(elapsedMs = 60_000, model = "gpt-6-astra", sawOutput = false),
            "every later line appends to the block already open, so it leads with the separator",
        )
        assertEquals(
            "\n[splice] 4m20s, still waiting.",
            line.next(elapsedMs = 260_000, model = "gpt-6-astra", sawOutput = false),
        )
    }

    @Test
    fun `a turn that stopped mid-answer is named as paused, never as not started`() {
        val line = TurnProgressLine()
        assertEquals(
            "[splice] holding this turn open. 45s into the turn, gpt-5.6-sol has paused mid-answer.",
            line.next(elapsedMs = 45_000, model = "gpt-5.6-sol", sawOutput = true),
        )
        assertEquals("\n[splice] 2m5s, still paused.", line.next(125_000, "gpt-5.6-sol", sawOutput = true))
    }

    @Test
    fun `elapsed reads in minutes and seconds, and never as a bare millisecond count`() {
        val line = TurnProgressLine()
        assertEquals(
            "[splice] holding this turn open. 0s into the turn, no output from m yet.",
            line.next(400, "m", false),
        )
        assertEquals("\n[splice] 10m0s, still waiting.", line.next(600_000, "m", false))
        assertEquals("\n[splice] 12m3s, still waiting.", line.next(723_400, "m", false))
    }
}
