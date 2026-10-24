// splice's wait notice ends before the model's next block boundary, so Claude Code,
// which commits blocks in content_block_stop order, draws it above the answer it waited for.
package splice.head.wire

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.Usage

class ProgressNoticeOrderTest {

    private val frames = mutableListOf<String>()
    private val emitter = SseEmitterFactory().create(
        write = { frames.add(it) },
        model = "m",
        usagePayload = { buildJsonObject { put("input_tokens", 0) } },
        messageId = "msg_fixed",
    )

    private fun stopOf(index: Int): Int =
        frames.indexOfFirst { it.startsWith("event: content_block_stop") && it.contains("\"index\":$index") }

    private fun startOf(index: Int): Int =
        frames.indexOfFirst { it.startsWith("event: content_block_start") && it.contains("\"index\":$index") }

    @Test
    fun `the notice stops before the model's next block starts`() = runTest {
        emitter.ensureStarted()
        emitter.progress { "[splice] holding this turn open." }
        val answer = emitter.openThinking()
        emitter.thinkingDelta(answer, "the model's reasoning")
        emitter.closeBlock(answer)
        emitter.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())

        assertEquals(1, answer.value, "the notice took index 0: $frames")
        val signature = frames.indexOfFirst { it.contains("signature_delta") && it.contains("\"index\":0") }
        assertTrue(signature in 0 until stopOf(0), "the notice is signed before its stop: $frames")
        assertTrue(stopOf(0) < startOf(1), "the notice's stop precedes the model block's start: $frames")
    }

    @Test
    fun `a notice opened inside a quiet model block stops before that block does`() = runTest {
        emitter.ensureStarted()
        val answer = emitter.openThinking()
        emitter.thinkingDelta(answer, "half a thought")
        emitter.progress { "[splice] 30s into the turn, m has paused mid-answer." }
        emitter.thinkingDelta(answer, ", and the rest")
        emitter.closeBlock(answer)

        assertTrue(stopOf(1) in 0 until stopOf(0), "notice (1) stops before the model block (0): $frames")
    }

    @Test
    fun `a line after the model wrote opens a fresh block and is told so`() = runTest {
        val fresh = mutableListOf<Boolean>()
        val line = ProgressLine {
            fresh.add(it)
            "line ${fresh.size}"
        }
        emitter.ensureStarted()
        emitter.progress(line)
        emitter.progress(line)
        emitter.addTextBlock("the model wrote")
        emitter.progress(line)

        assertEquals(listOf(true, false, true), fresh)
        assertEquals(
            2,
            frames.count { it.startsWith("event: content_block_start") && it.contains("\"type\":\"thinking\"") },
            "one notice block per quiet stretch: $frames",
        )
    }

    @Test
    fun `a silent beat writes nothing and leaves the next line fresh`() = runTest {
        emitter.ensureStarted()
        val opened = frames.size
        emitter.progress { null }
        assertEquals(opened, frames.size, "a null line writes no frame and opens no block: $frames")

        var sawFresh = false
        emitter.progress { fresh ->
            sawFresh = fresh
            "now"
        }
        assertTrue(sawFresh, "no block was opened, so the next line still opens one")
    }
}
