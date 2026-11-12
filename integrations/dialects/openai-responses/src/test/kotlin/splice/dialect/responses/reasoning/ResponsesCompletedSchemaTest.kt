package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.parse.AnthropicParse
import splice.core.turn.TurnOutcome
import splice.upstream.TurnSignals
import java.nio.file.Path

class ResponsesCompletedSchemaTest(@param:TempDir private val state: Path) {
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })
    private val completeJson = """{"ok":true}"""
    private val request = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Return the result."}],""" +
            """"tools":[{"name":"StructuredOutput","input_schema":{"type":"object",""" +
            """"properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}}],""" +
            """"tool_choice":{"type":"tool","name":"StructuredOutput"}}""",
    )

    @Test
    fun `completed JSON repairs the buffered prefix before sparse terminal schema delivery`() = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(request, false, "session")
        val sink = MessageWireProbe()
        val complete = DeliveryFrames.message("msg_schema", completeJson)
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.delta("""{"ok":"""),
                DeliveryFrames.done(complete),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        assertStructured(outcome, sink)
    }

    @Test
    fun `an already complete streamed schema object is delivered once when completion repeats it`() = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(request, false, "session")
        val sink = MessageWireProbe()
        val complete = DeliveryFrames.message("msg_schema", completeJson)
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.delta(completeJson),
                DeliveryFrames.done(complete),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        assertStructured(outcome, sink)
    }

    private fun assertStructured(outcome: TurnOutcome, sink: MessageWireProbe) {
        val success = checkNotNull(outcome as? TurnOutcome.Success)
        assertTrue(success.hasToolUse)
        assertEquals(listOf("StructuredOutput"), sink.toolNames)
        assertEquals(completeJson, sink.toolInput.toString())
        assertEquals(
            Json.parseToJsonElement(completeJson).jsonObject,
            Json.parseToJsonElement(sink.toolInput.toString()),
        )
        assertEquals("", sink.text)
        assertEquals("", success.text.bodyText)
        assertTrue(sink.textBlocks.isEmpty())
        assertEquals(0, sink.opaqueBlocks)
    }
}
