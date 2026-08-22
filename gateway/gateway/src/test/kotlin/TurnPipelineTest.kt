// CX-09: the honesty floor and the promote floor left a band uncovered. A non-compact turn with
// no text, no tools and thinking in [MIRROR_MIN_CHARS, PROMOTE_MIN_CHARS) is too short to promote
// and too long to trip the empty-model error, so the ONLY thing that could still put content on
// the wire was the mirror — and the mirror is gated on an operator knob (mirror_reasoning) and on
// showReasoning==TEXT. With either gate shut, the turn reached the client as a clean, EMPTY
// success: the L3 violation this campaign exists to remove. Pinned here: the error fires exactly
// when the mirror will NOT cover the turn, and never when it will.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.MIRROR_MIN_CHARS
import splice.core.turn.PROMOTE_MIN_CHARS
import splice.core.turn.ReasoningDisplayParser
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.compact.CompactStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.wire.TurnTerminal
import java.nio.file.Path

/** Records the ending and any one-shot text; every other verb is inert for these cases. */
private class RecTerminal : TurnTerminal {
    val texts = mutableListOf<String>()
    var ending: String? = null
    var errorType: ErrorType? = null
    var errorMessage: String = ""
    override var hasEnded: Boolean = false
        private set

    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        ending = "terminal"
        hasEnded = true
    }

    override suspend fun emitError(type: ErrorType, message: String) {
        ending = "error"
        errorType = type
        errorMessage = message
        hasEnded = true
    }

    override fun abandon() {
        ending = "abandon"
        hasEnded = true
    }

    override suspend fun addTextBlock(text: String) {
        texts.add(text)
    }

    override suspend fun openText(): WireBlockIndex = WireBlockIndex(0)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(0)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

class TurnPipelineTest {

    @TempDir
    lateinit var tmp: Path

    private fun pipeline(mirrorReasoning: Boolean) = TurnPipeline(
        compactStats = CompactStats(tmp.resolve("compact.jsonl")),
        log = {},
        clampOutput = { it },
        mirrorReasoning = mirrorReasoning,
    )

    private fun meta(showReasoning: String, compact: Boolean = false) = TurnMeta(
        compact = compact,
        showReasoning = ReasoningDisplayParser.from(showReasoning),
        stream = true,
        originalModel = "claude-codex--gpt-5.6-sol",
        upstreamModel = "gpt-5.6-sol",
        clientMaxTokens = null,
        effort = "medium",
        summary = null,
        budgetTokens = null,
    )

    /** No text, no tools — the shape that reaches the promote/honesty branch. */
    private fun outcome(thinking: String, emittedThinking: Boolean = false) = TurnOutcome.Success(
        hasToolUse = false,
        incomplete = false,
        usage = Usage(0, 0, 0),
        thinkingText = thinking,
        bodyText = "",
        emittedText = false,
        // The CX-09 axis: false models the harvest fallback (buffer refilled from the completed
        // response, sink never touched); true models every translator that streamed a real
        // thinking block. Defaulting to false keeps the original cases meaning what they meant.
        emittedThinking = emittedThinking,
    )

    /** In the uncovered band by construction: too short to promote, too long for the old check. */
    private val bandThinking = "x".repeat((MIRROR_MIN_CHARS + PROMOTE_MIN_CHARS) / 2)

    private suspend fun run(
        mirrorReasoning: Boolean,
        showReasoning: String,
        thinking: String,
        emittedThinking: Boolean = false,
    ): RecTerminal {
        val rec = RecTerminal()
        pipeline(mirrorReasoning)
            .finishStream(rec, outcome(thinking, emittedThinking), meta(showReasoning), elapsedMs = 1)
        return rec
    }

    /** [run]'s compact sibling, returning the pipeline's own OUTCOME TAG alongside the terminal:
     *  `empty_compact` and `empty_model` both reach the wire as the same API_ERROR shape, so the
     *  terminal alone cannot tell the compact gate from the non-compact one. */
    private suspend fun runCompact(thinking: String, mirrorReasoning: Boolean = true): Pair<RecTerminal, String> {
        val rec = RecTerminal()
        val tag = pipeline(mirrorReasoning)
            .finishStream(rec, outcome(thinking), meta("text", compact = true), elapsedMs = 1)
        return rec to tag
    }

    /** The compact-stats JSONL the pipeline appended this turn (a fresh reader over the same file). */
    private fun recordedCompact() = CompactStats(tmp.resolve("compact.jsonl")).read()

    @Test
    fun `the band is an honest error when the operator disabled the mirror`() = runTest {
        val rec = run(mirrorReasoning = false, showReasoning = "text", thinking = bandThinking)
        assertEquals("error", rec.ending)
        assertEquals(ErrorType.API_ERROR, rec.errorType)
        assertTrue(rec.texts.isEmpty(), "nothing may reach the wire when the turn errors")
    }

    @Test
    fun `the band is an honest error when reasoning is not displayed as text`() = runTest {
        val rec = run(mirrorReasoning = true, showReasoning = "hide", thinking = bandThinking)
        assertEquals("error", rec.ending)
        assertEquals(ErrorType.API_ERROR, rec.errorType)
    }

    @Test
    fun `the band stays a mirrored success when the mirror will actually cover it`() = runTest {
        val rec = run(mirrorReasoning = true, showReasoning = "text", thinking = bandThinking)
        assertEquals("terminal", rec.ending)
        assertTrue(
            rec.texts.any { it.contains("[reasoning summary]") && it.contains(bandThinking) },
            "the mirror must be what covers this turn: ${rec.texts}",
        )
    }

    // THE REGRESSION CELL. anthropic-passthrough pins showReasoning=THINKING for EVERY turn,
    // precisely because it streams native thinking blocks and the text mirror must not
    // double-render them. A gate that reads that sentinel as "nothing covers this turn" turned
    // every thinking-only kimi turn into an API_ERROR *after* its content had already reached the
    // client. Caught in adversarial review before it shipped; this cell is why it cannot return.
    @Test
    fun `a native thinking block covers the turn even though the text mirror will not fire`() = runTest {
        val rec = run(
            mirrorReasoning = true,
            showReasoning = "thinking",
            thinking = bandThinking,
            emittedThinking = true,
        )
        assertEquals("terminal", rec.ending, "the client already received a thinking block")
        assertTrue(rec.texts.isEmpty(), "passthrough must not double-render reasoning as text")
    }

    @Test
    fun `an emitted thinking block covers the turn with the operator mirror off`() = runTest {
        val rec = run(false, "text", bandThinking, emittedThinking = true)
        assertEquals("terminal", rec.ending)
    }

    @Test
    fun `the harvest-fallback shape is still an honest error - buffer full, wire empty`() = runTest {
        // thinkingText non-empty but nothing ever reached the sink: the ONE genuinely empty turn.
        val rec = run(mirrorReasoning = false, showReasoning = "thinking", thinking = bandThinking)
        assertEquals("error", rec.ending)
        assertEquals(ErrorType.API_ERROR, rec.errorType)
    }

    @Test
    fun `below the mirror floor is an error regardless of the mirror`() = runTest {
        val short = "x".repeat(MIRROR_MIN_CHARS - 1)
        assertEquals("error", run(true, "text", short).ending)
        assertEquals("error", run(false, "text", short).ending)
    }

    @Test
    fun `above the promote floor still promotes to text and never errors`() = runTest {
        val long = "x".repeat(PROMOTE_MIN_CHARS + 5)
        val rec = run(mirrorReasoning = false, showReasoning = "text", thinking = long)
        assertEquals("terminal", rec.ending)
        assertTrue(rec.texts.any { it == long }, "promote-to-text must emit the thinking verbatim")
    }

    // THE COMPACT HALF OF THE HONESTY GATE. Every case above builds `compact = false`, so only the
    // `empty_model` arm was ever exercised — the `empty_compact` arm beside it had no coverage of
    // any kind. It is the higher-stakes of the two: an empty SUCCESS on a compact turn makes Claude
    // Code store a blank summary and lose the conversation thread, silently and unrecoverably. The
    // gate must therefore fire on `compact` REGARDLESS of the mirror band the cases above map out,
    // because the mirror is off for compact by construction (Mirror.willMirror: "compact is a
    // text-only summarizer turn").

    @Test
    fun `an empty compact turn is an empty_compact error, never an empty success`() = runTest {
        val (rec, tag) = runCompact(thinking = "")
        assertEquals("empty_compact", tag)
        assertEquals("error", rec.ending)
        assertEquals(ErrorType.API_ERROR, rec.errorType)
        assertTrue(rec.errorMessage.contains("compact returned no content"), rec.errorMessage)
        assertTrue(rec.texts.isEmpty(), "a blank summary must never reach the wire: ${rec.texts}")
        val stats = recordedCompact()
        assertEquals(mapOf("empty_model" to 1), stats.byOutcome, "recordCompact must have fired")
        assertTrue(stats.tail.single().toString().contains("\"error\":\"api_error\""), "${stats.tail}")
    }

    @Test
    fun `the mirror band never rescues a compact turn - it is still empty_compact`() = runTest {
        // The band is exactly where a non-compact turn stays a mirrored success (see above). On a
        // compact turn the mirror is shut, so the same reasoning covers nothing and the gate fires.
        val (rec, tag) = runCompact(thinking = bandThinking, mirrorReasoning = true)
        assertEquals("empty_compact", tag)
        assertEquals(ErrorType.API_ERROR, rec.errorType)
        assertTrue(rec.texts.isEmpty(), "compact must not emit a mirror block: ${rec.texts}")
    }

    @Test
    fun `a compact turn with promotable reasoning promotes and ends clean`() = runTest {
        // The never-when half: the gate keys on "nothing to put in the text channel", not on
        // `compact` itself — a promotable summary is what compaction is FOR.
        val summary = "x".repeat(PROMOTE_MIN_CHARS + 5)
        val (rec, tag) = runCompact(thinking = summary)
        assertEquals("ok", tag)
        assertEquals("terminal", rec.ending)
        assertTrue(rec.texts.any { it == summary }, "the summary must reach the wire: ${rec.texts}")
        assertEquals(mapOf("model_thinking" to 1), recordedCompact().byOutcome)
    }
}
