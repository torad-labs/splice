// NEW (2026-09-06): what splice actually says on a quiet wire. The first line explains itself and
// names the row; the rest are a ticker. The wording turns on whether the CLIENT has seen a delta,
// which is the difference between a turn that has not begun answering (gpt-6-astra's 5-12 minute
// buffered turns) and one that stopped mid-answer — two different problems, and the line must not
// call one the other. V4-451: within one quiet stretch the ticker thins out to the 1st, 2nd, 4th and
// 8th heartbeat, and a stretch that opens its own block starts with a whole sentence.
package splice.head.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val HEARTBEAT_MS = 30_000L

class TurnProgressLineTest {

    @Test
    fun `the first line explains itself and names the row, the rest tick`() {
        val line = TurnProgressLine()
        assertEquals(
            "[splice] holding this turn open. 30s into the turn, no answer from gpt-6-astra yet.",
            line.next(elapsedMs = 30_000, model = "gpt-6-astra", sawOutput = false, fresh = true),
        )
        assertEquals(
            "\n[splice] 1m0s, still waiting.",
            line.next(elapsedMs = 60_000, model = "gpt-6-astra", sawOutput = false, fresh = false),
            "every later line appends to the block already open, so it leads with the separator",
        )
    }

    @Test
    fun `one quiet stretch speaks at its 1st, 2nd, 4th and 8th heartbeat`() {
        val line = TurnProgressLine()
        val spoken = (1..16).mapNotNull { beat ->
            line.next(beat * HEARTBEAT_MS, "m", sawOutput = false, fresh = beat == 1)?.let { beat to it }
        }
        assertEquals(listOf(1, 2, 4, 8, 16), spoken.map { it.first }, "lines: $spoken")
        assertEquals(
            listOf(
                "[splice] holding this turn open. 30s into the turn, no answer from m yet.",
                "\n[splice] 1m0s, still waiting.",
                "\n[splice] 2m0s, still waiting.",
                "\n[splice] 4m0s, still waiting.",
                "\n[splice] 8m0s, still waiting.",
            ),
            spoken.map { it.second },
        )
    }

    @Test
    fun `a turn that stopped mid-answer is named as paused, never as not started`() {
        val line = TurnProgressLine()
        assertEquals(
            "[splice] holding this turn open. 45s into the turn, gpt-5.6-sol has paused mid-answer.",
            line.next(elapsedMs = 45_000, model = "gpt-5.6-sol", sawOutput = true, fresh = true),
        )
        assertEquals(
            "\n[splice] 1m15s, still paused.",
            line.next(elapsedMs = 75_000, model = "gpt-5.6-sol", sawOutput = true, fresh = false),
        )
    }

    @Test
    fun `a stretch after the model wrote opens with a whole sentence and its own count`() {
        val line = TurnProgressLine()
        line.next(30_000, "gpt-6.1-sol", sawOutput = false, fresh = true)
        line.next(60_000, "gpt-6.1-sol", sawOutput = false, fresh = false)
        line.next(90_000, "gpt-6.1-sol", sawOutput = false, fresh = false)
        // The model wrote, the wire ended the notice, and the next quiet stretch begins.
        assertEquals(
            "[splice] 3m0s into the turn, gpt-6.1-sol has paused mid-answer.",
            line.next(180_000, "gpt-6.1-sol", sawOutput = true, fresh = true),
        )
        assertEquals(
            "\n[splice] 3m30s, still paused.",
            line.next(210_000, "gpt-6.1-sol", sawOutput = true, fresh = false),
            "the new stretch counts its own heartbeats from one, so its second speaks",
        )
    }

    @Test
    fun `elapsed reads in minutes and seconds, and never as a bare millisecond count`() {
        val line = TurnProgressLine()
        assertEquals(
            "[splice] holding this turn open. 0s into the turn, no answer from m yet.",
            line.next(400, "m", sawOutput = false, fresh = true),
        )
        assertEquals("\n[splice] 12m3s, still waiting.", line.next(723_400, "m", sawOutput = false, fresh = false))
    }
}
