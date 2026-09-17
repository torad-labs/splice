// CX-09: the honesty floor and the promote floor left a band uncovered. With transcript mirroring
// now operator-locked off, a non-compact turn in that band must be an honest error unless native
// thinking already reached the client. These tests pin both halves: empty harvest fallback errors,
// while an emitted native thinking block remains a clean success without any synthetic mirror.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
import splice.core.util.AsyncFileIo
import splice.gateway.compact.CompactStats
import splice.gateway.compact.CompactStatsSummary
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

    private fun pipeline(mirrorReasoning: Boolean = false) = TurnPipeline(
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
    private fun outcome(
        thinking: String,
        emittedThinking: Boolean = false,
        messageClosed: Boolean = false,
    ) = TurnOutcome.Success(
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
        messageClosed = messageClosed,
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
     *  `empty_compact` and `empty_model` both reach the wire as the same OVERLOADED shape, so the
     *  terminal alone cannot tell the compact gate from the non-compact one. */
    private suspend fun runCompact(thinking: String, mirrorReasoning: Boolean = false): Pair<RecTerminal, String> {
        val rec = RecTerminal()
        val tag = pipeline(mirrorReasoning)
            .finishStream(rec, outcome(thinking), meta("text", compact = true), elapsedMs = 1)
        return rec to tag
    }

    /** Compact stats append on the process-wide file lane AFTER finishStream returns. Drain it
     *  before JUnit deletes the temp dir, or the append lands in a directory being swept and the
     *  test fails on teardown ("Failed to delete temp directory", CI 2026-09-07). */
    @AfterEach
    fun drainFileLane() {
        AsyncFileIo.drain()
    }

    /** The compact-stats JSONL the pipeline appended this turn (a fresh reader over the same file). */
    private fun recordedCompact(): CompactStatsSummary {
        AsyncFileIo.drain()
        return CompactStats(tmp.resolve("compact.jsonl")).read()
    }

    /** V4-59's wall. NO text block and NO error message splice emits may be JSON.
     *
     *  The operator's complaint is the reason this is not a style rule: outcome.message is the raw
     *  upstream body, so a vendor's error payload was rendered into his conversation as literal
     *  braces, and splice did it to itself — the rate-limit fail-fast throws a hand-built
     *  {"detail":...}, and UpstreamFailureClassifier reads error.message/message but never detail,
     *  so its own body fell back to raw and travelled on intact.
     *
     *  THE ASSERTION IS ABOUT THE BYTES THE CLIENT RENDERS, NOT ABOUT A COUNT. Like V4-56, no edit to
     *  a number can satisfy it — the only way to pass is to stop sending payloads. The denominator is
     *  ErrorType.entries, read by reflection, so a failure class added later is covered the day it
     *  exists rather than the day someone remembers this file.
     *
     *  MUTATION PROOF: delete the presentation call in TurnPipeline and feed the body below — every
     *  case here reddens, because the raw JSON is exactly what arrives. */
    @Test
    fun `no text block or error message splice emits is json`() = runTest {
        val payload = """{"detail":"Rate limit exceeded — this gateway is holding retries for 120s"}"""
        val offending = mutableListOf<String>()
        for (type in ErrorType.entries) {
            // Both endings: the deterministic one is the visible leak, and the error event carries
            // the identical body — presenting one and not the other leaves most failures leaking.
            val explained = RecTerminal()
            pipeline().finishStream(
                explained,
                TurnOutcome.Failure(type, payload, deterministic = true),
                meta("text"),
                elapsedMs = 1,
            )
            offending += inspect("text block for $type", explained.texts.joinToString(" "))

            val errored = RecTerminal()
            pipeline().finishStream(errored, TurnOutcome.Failure(type, payload), meta("text"), elapsedMs = 1)
            offending += inspect("error event for $type", errored.errorMessage)
        }
        assertTrue(offending.isEmpty(), "JSON reached the client:\n" + offending.joinToString("\n"))
    }

    /** One rendered line's violations, as a list so a failure names EVERY case rather than the first. */
    private fun inspect(what: String, rendered: String): List<String> = buildList {
        if (rendered.contains("{")) add("$what: opens a brace -> $rendered")
        if (rendered.contains("\"detail\"")) add("$what: carries a detail key -> $rendered")
        if (rendered.contains("\"error\"")) add("$what: carries an error key -> $rendered")
        // The positive half: a stripped body would also pass the checks above, so the sentence the
        // operator should actually read must SURVIVE — and the code must be present to grep for.
        if (!rendered.contains("Rate limit exceeded")) add("$what: dropped the human sentence -> $rendered")
        if (!rendered.contains("SPLICE-")) add("$what: has no greppable code -> $rendered")
    }

    /** A body that is valid JSON but carries no human field is DESCRIBED. It is the one case where
     *  the payload's own text is exactly what a reader cannot use, so reproducing it is not a
     *  fallback — it is the defect wearing a fallback's name.
     *
     *  NOT markup: mapping a vendor's error PAGE here was tried and reverted, because an existing
     *  test pins that a human-readable auth page keeps its text. This layer lifts a field or
     *  describes; it does not judge whether prose is pretty. */
    @Test
    fun `a json body with no human field is described rather than dumped`() = runTest {
        val rec = RecTerminal()
        pipeline().finishStream(
            rec,
            TurnOutcome.Failure(ErrorType.API_ERROR, """{"foo":1,"bar":[1,2,3],"baz":null}""", deterministic = true),
            meta("text"),
            elapsedMs = 1,
        )
        val text = rec.texts.single()
        assertTrue(text.contains("could not be read"), "an unreadable body must be described: $text")
        assertTrue(!text.contains("{"), "no body may be reproduced as braces: $text")
        assertTrue(!text.contains("baz"), "no field of an unreadable body may be reproduced: $text")
    }

    /** A deterministic failure (a verdict no retry can change) ends in words, not an error event;
     *  every other failure keeps the honestly-typed error the client is right to retry. */
    @Test
    fun `a deterministic failure ends as an explained text block, a transient one as an error event`() = runTest {
        val explained = RecTerminal()
        val tag = pipeline().finishStream(
            explained,
            TurnOutcome.Failure(ErrorType.API_ERROR, "code-mode cell is unavailable", deterministic = true),
            meta("text"),
            elapsedMs = 1,
        )
        assertEquals("terminal", explained.ending)
        // V4-59: the verb is unchanged \u2014 still a text block, still marked as the proxy speaking \u2014
        // and what changed is the words: the failure now names its stable code before the sentence.
        assertEquals(listOf("\u26A0 splice [SPLICE-API-ERROR] code-mode cell is unavailable"), explained.texts)
        assertEquals("failure:api_error", tag)

        val retried = RecTerminal()
        pipeline().finishStream(
            retried,
            TurnOutcome.Failure(ErrorType.API_ERROR, "upstream stream ended without response.completed"),
            meta("text"),
            elapsedMs = 1,
        )
        assertEquals("error", retried.ending)
        assertEquals(ErrorType.API_ERROR, retried.errorType)
        assertTrue(retried.texts.isEmpty())
    }

    /** V4-42: a zero-content turn must reach the client RETRYABLE. The operator's journal caught one
     *  at 07:05:30 on 2026-09-16 (tag=empty_model, between healthy turns, so not a quota artifact) —
     *  a muse 200 with stop_reason max_tokens and no content blocks.
     *
     *  Zero content means nothing reached the client, so the turn is indistinguishable from a
     *  transient overload and nothing the client read is at stake. The TYPE decides recovery: before
     *  content Claude Code re-sends an api_error IDENTICALLY until it gives up — the 2.1.x behaviour
     *  recorded on TurnOutcome.Failure.deterministic, 87 and 47 identical turns on 2026-09-07 —
     *  so it reproduces the same empty turn rather than recovering, while overloaded_error is
     *  retried on a backoff. The WORDS stay, because they are the diagnosis. */
    @Test
    fun `a zero-content turn ends as overloaded, not api_error, so the client retries it`() = runTest {
        val rec = RecTerminal()
        pipeline().finishStream(rec, outcome(thinking = ""), meta("text"), elapsedMs = 1)

        assertEquals("error", rec.ending, "a zero-content turn is an error ending, not a clean one")
        assertEquals(
            ErrorType.OVERLOADED,
            rec.errorType,
            "an empty turn BEFORE content must be retryable with backoff, not identically re-sent",
        )
        assertTrue(
            rec.errorMessage.contains("no content"),
            "the honest words must survive the type change: ${rec.errorMessage}",
        )
    }

    @Test
    fun `the default pipeline keeps the reasoning mirror locked off`() = runTest {
        val rec = RecTerminal()
        pipeline().finishStream(rec, outcome(bandThinking), meta("text"), elapsedMs = 1)
        assertEquals("error", rec.ending)
        assertEquals(ErrorType.OVERLOADED, rec.errorType) // V4-42: the empty_model branch, retryable
        assertTrue(rec.texts.isEmpty(), "nothing may reach the wire when the turn errors")
    }

    @Test
    fun `the band is an honest error when reasoning is not displayed as text`() = runTest {
        val rec = run(mirrorReasoning = false, showReasoning = "hide", thinking = bandThinking)
        assertEquals("error", rec.ending)
        assertEquals(ErrorType.OVERLOADED, rec.errorType) // V4-42: the empty_model branch, retryable
    }

    @Test
    fun `an explicit direct attempt to enable the mirror is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { pipeline(mirrorReasoning = true) }
    }

    // THE REGRESSION CELL. anthropic-passthrough pins showReasoning=THINKING for EVERY turn,
    // precisely because it streams native thinking blocks and the text mirror must not
    // double-render them. A gate that reads that sentinel as "nothing covers this turn" turned
    // every thinking-only kimi turn into an API_ERROR *after* its content had already reached the
    // client. Caught in adversarial review before it shipped; this cell is why it cannot return.
    @Test
    fun `a native thinking block covers the turn even though the text mirror will not fire`() = runTest {
        val rec = run(
            mirrorReasoning = false,
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
        assertEquals(ErrorType.OVERLOADED, rec.errorType) // V4-42: the empty_model branch, retryable
    }

    // THE ASTRA CELL (2026-09-05). Thirteen retry storms in one evening, every one the same shape:
    // the model gave its final answer, a Stop hook echoed an end-of-turn report back as a
    // continuation, and the model closed a message with nothing in it — a finished answer, which is
    // how codex ends the turn. The honesty gate read it as "no content", the client retried the
    // identical request eleven times per incident. A CLOSED message ends clean and is tagged so the
    // perf row still names the class; a round with no message item at all stays the honest error.
    @Test
    fun `a message the model closed empty ends clean and is tagged, never empty_model`() = runTest {
        val rec = RecTerminal()
        val tag = pipeline().finishStream(
            rec,
            outcome("", messageClosed = true),
            meta("text"),
            elapsedMs = 1,
        )
        assertEquals("terminal", rec.ending, "a closed empty message is a finished answer")
        assertEquals("empty_message", tag, "the log must still name the class")
        assertTrue(rec.texts.isEmpty(), "nothing is invented for the wire: ${rec.texts}")
    }

    @Test
    fun `a closed empty message on a compact turn is still an empty_compact error`() = runTest {
        // Compaction needs a summary in the text channel; "nothing to add" is not one.
        val rec = RecTerminal()
        val tag = pipeline().finishStream(
            rec,
            outcome("", messageClosed = true),
            meta("text", compact = true),
            elapsedMs = 1,
        )
        assertEquals("empty_compact", tag)
        assertEquals("error", rec.ending)
    }

    @Test
    fun `below the mirror floor is an honest error with the lock enforced`() = runTest {
        val short = "x".repeat(MIRROR_MIN_CHARS - 1)
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

    // DR-126: the (compact, no-text, tooled) shape fell through BOTH recorders — the promote
    // branch skips on hasToolUse and the model_text arm requires emittedText — so the compact
    // JSONL drift instrument was blind for exactly the anomalous class (a compact turn has no
    // tools to call; a model calling one anyway is the drift worth a row). The turn itself must
    // flow on unchanged: recorded, not rewritten.
    @Test
    fun `a tooled no-text compact turn records a row naming the shape - DR-126`() = runTest {
        val rec = RecTerminal()
        val tooled = TurnOutcome.Success(
            hasToolUse = true,
            incomplete = false,
            usage = Usage(0, 0, 0),
            thinkingText = "",
            bodyText = "",
            emittedText = false,
        )
        val tag = pipeline(mirrorReasoning = false)
            .finishStream(rec, tooled, meta("text", compact = true), elapsedMs = 1)
        assertEquals(mapOf("tooled_no_text" to 1), recordedCompact().byOutcome, "the shape must get a row")
        assertEquals("ok", tag, "recorded, not rewritten — the turn flows to the normal terminal")
    }

    @Test
    fun `an empty compact turn is an empty_compact error, never an empty success`() = runTest {
        val (rec, tag) = runCompact(thinking = "")
        assertEquals("empty_compact", tag)
        assertEquals("error", rec.ending)
        assertEquals(ErrorType.OVERLOADED, rec.errorType) // V4-42: retryable, like empty_model
        assertTrue(rec.errorMessage.contains("compact returned no content"), rec.errorMessage)
        assertTrue(rec.texts.isEmpty(), "a blank summary must never reach the wire: ${rec.texts}")
        val stats = recordedCompact()
        assertEquals(mapOf("empty_model" to 1), stats.byOutcome, "recordCompact must have fired")
        assertTrue(stats.tail.single().toString().contains("\"error\":\"api_error\""), "${stats.tail}")
    }

    @Test
    fun `the former mirror band stays an empty_compact error`() = runTest {
        // This is the band the transcript mirror used to rescue. With the lock enforced, the same
        // reasoning covers nothing on a compact turn and the honesty gate fires.
        val (rec, tag) = runCompact(thinking = bandThinking)
        assertEquals("empty_compact", tag)
        assertEquals(ErrorType.OVERLOADED, rec.errorType) // V4-42: retryable, like empty_model
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
