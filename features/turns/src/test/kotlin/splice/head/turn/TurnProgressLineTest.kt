// NEW (2026-09-06): what splice actually says on a quiet wire. The wording turns on whether the CLIENT has seen a
// delta, which is the difference between a turn that has not begun answering (gpt-6-astra's 5-12 minute buffered
// turns) and one that stopped mid-answer: two different problems, and the line must not call one the other. V4-451:
// within one quiet stretch the line thins out to the 1st, 2nd, 4th and 8th heartbeat, and a stretch that opens its
// own block starts with a whole sentence.
//
// Failure-handling slice 2 (2026-10-26): the words are the turn voice's quiet line (final-design.md section 10, "Quiet
// line before any answer"), naming the head a person launched rather than the model behind it, and the time as a person
// says it; the cadence is unchanged. The test that pinned the old "holding this turn open" words was rewritten here.
package splice.head.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.HeadVoice
import splice.core.failure.VoiceFacts
import java.time.ZoneId

private const val HEARTBEAT_MS = 30_000L

class TurnProgressLineTest {
    private val voice = HeadVoice("claude-muse", null, VoiceFacts(zone = ZoneId.of("America/Chicago"))).anyTurn

    private fun TurnProgressLine.said(elapsedMs: Long, sawOutput: Boolean, fresh: Boolean): String? =
        next(elapsedMs = elapsedMs, sawOutput = sawOutput, fresh = fresh)?.text

    @Test
    fun `a quiet turn names its head and says it has no answer yet, and the next line says still`() {
        val line = TurnProgressLine(voice)
        assertEquals(
            "[splice] No answer from claude-muse yet, 30 seconds into the turn.",
            line.said(elapsedMs = 30_000, sawOutput = false, fresh = true),
        )
        assertEquals(
            "[splice] Still no answer from claude-muse, 60 seconds into the turn.",
            line.said(elapsedMs = 60_000, sawOutput = false, fresh = false),
            "a line in the block already open follows the one before it",
        )
    }

    @Test
    fun `one quiet stretch speaks at its 1st, 2nd, 4th and 8th heartbeat`() {
        val line = TurnProgressLine(voice)
        val spoken = (1..16).mapNotNull { beat ->
            line.said(beat * HEARTBEAT_MS, sawOutput = false, fresh = beat == 1)?.let { beat to it }
        }
        assertEquals(listOf(1, 2, 4, 8, 16), spoken.map { it.first }, "lines: $spoken")
        assertEquals(
            listOf(
                "[splice] No answer from claude-muse yet, 30 seconds into the turn.",
                "[splice] Still no answer from claude-muse, 60 seconds into the turn.",
                "[splice] Still no answer from claude-muse, 2 minutes into the turn.",
                "[splice] Still no answer from claude-muse, 4 minutes into the turn.",
                "[splice] Still no answer from claude-muse, 8 minutes into the turn.",
            ),
            spoken.map { it.second },
        )
    }

    @Test
    fun `a turn that stopped mid-answer is named as paused, never as not started`() {
        val line = TurnProgressLine(voice)
        assertEquals(
            "[splice] claude-muse has paused mid-answer, 45 seconds into the turn.",
            line.said(elapsedMs = 45_000, sawOutput = true, fresh = true),
        )
        assertEquals(
            "[splice] claude-muse is still paused mid-answer, 75 seconds into the turn.",
            line.said(elapsedMs = 75_000, sawOutput = true, fresh = false),
        )
    }

    @Test
    fun `a stretch after the model wrote opens with a whole sentence and its own count`() {
        val line = TurnProgressLine(voice)
        line.said(30_000, sawOutput = false, fresh = true)
        line.said(60_000, sawOutput = false, fresh = false)
        line.said(90_000, sawOutput = false, fresh = false)
        // The model wrote, the wire ended the notice, and the next quiet stretch begins.
        assertEquals(
            "[splice] claude-muse has paused mid-answer, 3 minutes into the turn.",
            line.said(180_000, sawOutput = true, fresh = true),
        )
        assertEquals(
            "[splice] claude-muse is still paused mid-answer, 4 minutes into the turn.",
            line.said(210_000, sawOutput = true, fresh = false),
            "the new stretch counts its own heartbeats from one, so its second speaks",
        )
    }

    @Test
    fun `elapsed reads as a person says it, never as a bare millisecond count`() {
        val line = TurnProgressLine(voice)
        assertEquals(
            "[splice] No answer from claude-muse yet, 1 second into the turn.",
            line.said(400, sawOutput = false, fresh = true),
        )
        assertEquals(
            "[splice] Still no answer from claude-muse, 12 minutes into the turn.",
            line.said(723_400, sawOutput = false, fresh = false),
        )
    }
}
