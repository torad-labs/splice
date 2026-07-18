// PORT-OF: the streaming scenario assertions from server/test/codex-proxy.test.mjs @ 4ca99f7,
// driven end-to-end over real HTTP: CIO client -> mock upstream -> sseJsonEvents -> machine.
// idle/prefill/drip/refresh scenarios are exercised with the watchdog/gate/auth wiring at
// P3-HEAD (they need the full turn pipeline); this suite pins the reader+machine composition.
package mock

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.dialect.responses.EmitEncryptedReasoning
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.sseJsonEvents

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioIntegrationTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun ctx(emit: Boolean = false) = StreamTurnContext(
        compact = false,
        emitEncryptedReasoning = EmitEncryptedReasoning(emit),
        encodeReasoningEnvelope = { "env:${it["id"]?.jsonPrimitive?.content}" },
        clientGone = { false },
        watchdogFired = { null },
        streamIdleMsForMessage = 180_000,
        upstreamTimeoutMsForMessage = 900_000,
    )

    private fun drive(scenario: String, replay: Boolean = false): Pair<TurnOutcome, RecordingSink2> {
        var outcome: TurnOutcome? = null
        val sink = RecordingSink2()
        runTest {
            client.preparePost("${mock.baseUrl}/responses") {
                setBody("""{"model":"m","instructions":"You are a test. SCENARIO:$scenario","input":[]}""")
            }.execute { resp ->
                val events = sseJsonEvents(resp.bodyAsChannel())
                outcome = ResponsesStreamTranslator(ctx(replay)).driveTurn(events, sink)
            }
        }
        return outcome!! to sink
    }

    @Test
    fun `multipart - one thinking block, paragraph join, then text`() {
        val (outcome, sink) = drive("multipart")
        val s = outcome as TurnOutcome.Success
        assertEquals("Part one.\n\nPart two.", s.thinkingText)
        assertEquals("Answer text.", s.bodyText)
        assertEquals(1, sink.opens.count { it == "thinking" })
        assertEquals(1, sink.opens.count { it == "text" })
        assertEquals(10, s.usage.inputTokens)
    }

    @Test
    fun `toolcall - eager open, args assembled on one index`() {
        val (outcome, sink) = drive("toolcall")
        assertTrue((outcome as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("""{"a":""", "1}"), sink.jsonDeltas)
    }

    @Test
    fun `failed - honest classified failure`() {
        val (outcome, _) = drive("failed")
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, f.type)
        assertTrue(f.message.contains("boom upstream"))
    }

    @Test
    fun `overflow_sse - rewrites to prompt is too long via the shared classifier`() {
        val (outcome, _) = drive("overflow_sse")
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.INVALID_REQUEST, f.type)
        assertTrue(f.message.contains("prompt is too long"))
    }

    @Test
    fun `truncated - overloaded failure, never a clean end`() {
        val (outcome, _) = drive("truncated")
        val f = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, f.type)
        assertTrue(f.message.contains("truncated"))
    }

    @Test
    fun `nonstream_tool - multibyte split inside the checkmark decodes intact via harvest`() {
        val (outcome, _) = drive("nonstream_tool")
        val s = outcome as TurnOutcome.Success
        assertEquals("héllo — ✓ done", s.bodyText)
        assertTrue(s.thinkingText.startsWith("Because reasons"))
    }

    @Test
    fun `compactish - thinking-only turn leaves text empty for the promote step`() {
        val (outcome, _) = drive("compactish")
        val s = outcome as TurnOutcome.Success
        assertTrue(s.thinkingText.contains("Goal: port the proxy"))
        assertEquals("", s.bodyText)
        assertEquals(false, s.emittedText)
    }

    @Test
    fun `replaystream - redacted block emitted in position when replay on`() {
        val (outcome, sink) = drive("replaystream", replay = true)
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(listOf("env:rs_stream"), sink.redacted)
        assertTrue(sink.opens.contains("thinking") && sink.opens.contains("text"))
    }

    @Test
    fun `replaystream - no redacted block when replay off (the measured default)`() {
        val (outcome, sink) = drive("replaystream", replay = false)
        assertTrue(outcome is TurnOutcome.Success)
        assertTrue(sink.redacted.isEmpty())
    }

    @Test
    fun `bigout - usage rides through for the clamp step`() {
        val (outcome, _) = drive("bigout")
        assertEquals(200_000, (outcome as TurnOutcome.Success).usage.outputTokens)
    }

    @Test
    fun `basic - minimal text turn`() {
        val (outcome, _) = drive("basic")
        assertEquals("ok after auth", (outcome as TurnOutcome.Success).bodyText)
    }
}

class RecordingSink2 : splice.spi.WireSink {
    val opens = mutableListOf<String>()
    val jsonDeltas = mutableListOf<String>()
    val redacted = mutableListOf<String>()
    private var next = 0

    override suspend fun openText(): splice.core.index.WireBlockIndex {
        opens.add("text")
        return splice.core.index.WireBlockIndex(next++)
    }

    override suspend fun openThinking(): splice.core.index.WireBlockIndex {
        opens.add("thinking")
        return splice.core.index.WireBlockIndex(next++)
    }

    override suspend fun openTool(id: String, name: String): splice.core.index.WireBlockIndex {
        opens.add("tool:$name")
        return splice.core.index.WireBlockIndex(next++)
    }

    override suspend fun textDelta(index: splice.core.index.WireBlockIndex, text: String) = Unit

    override suspend fun thinkingDelta(index: splice.core.index.WireBlockIndex, thinking: String) = Unit

    override suspend fun inputJsonDelta(index: splice.core.index.WireBlockIndex, partialJson: String) {
        jsonDeltas.add(partialJson)
    }

    override suspend fun closeBlock(index: splice.core.index.WireBlockIndex) = Unit

    override suspend fun closeAll() = Unit

    override suspend fun addTextBlock(text: String) = Unit

    override suspend fun addRedactedThinking(data: String) {
        redacted.add(data)
    }
}
