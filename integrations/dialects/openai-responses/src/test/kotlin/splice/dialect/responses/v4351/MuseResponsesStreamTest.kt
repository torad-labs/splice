package splice.dialect.responses.v4351

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.dialect.responses.stream.RecordingSink
import splice.dialect.responses.stream.ResponsesStreamTranslator
import splice.dialect.responses.stream.ctx
import splice.upstream.ToolNameShortener

private const val MUSE_CAP = 64
private const val TOOL_NAME = "mcp__plugin_some_long_server_name__a_long_tool_name_from_claude_code_123456789"

class MuseResponsesStreamTest {
    @Test
    fun `Meta summary deltas are visible while encrypted reasoning and tool identity survive`() = runTest {
        val names = ToolNameShortener(MUSE_CAP) { }
        val alias = names.shorten(TOOL_NAME)
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx(collect = true), names)
            .driveTurn(museEvents(alias).asFlow(), sink)
        val success = outcome as TurnOutcome.Success
        assertEquals("Checking the tool arguments", success.thinkingText)
        assertEquals(1, success.reasoningEnvelopes.count { it.contains("rs_muse") })
        assertTrue(sink.calls.any { it.contains("think#") && it.contains("Checking the tool arguments") })
        assertTrue(sink.calls.any { it.contains("openTool") && it.contains(TOOL_NAME) })
        assertTrue(sink.calls.none { it.contains("openTool") && it.contains(alias) })
    }
}

private fun museEvents(alias: String): List<JsonObject> = listOf(
    event(
        """{"type":"response.output_item.added","output_index":0,
            "item":{"type":"reasoning","id":"rs_muse"}}""",
    ),
    event("""{"type":"response.reasoning_summary_part.added","output_index":0}"""),
    event(
        """{"type":"response.reasoning_summary_text.delta","output_index":0,
            "delta":"Checking the tool arguments"}""",
    ),
    event(
        """{"type":"response.reasoning_summary_text.done","output_index":0,
            "text":"Checking the tool arguments"}""",
    ),
    event(
        """{"type":"response.output_item.done","output_index":0,
            "item":{"type":"reasoning","id":"rs_muse","encrypted_content":"opaque"}}""",
    ),
    event(
        """{"type":"response.output_item.added","output_index":1,
            "item":{"type":"function_call","call_id":"toolu_1","name":"$alias"}}""",
    ),
    event("""{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{}"}"""),
    event(
        """{"type":"response.output_item.done","output_index":1,
            "item":{"type":"function_call","call_id":"toolu_1","name":"$alias","arguments":"{}"}}""",
    ),
    event(
        """{"type":"response.completed","response":{"id":"resp_muse","model":"muse-spark-1.3",
            "usage":{"input_tokens":100,"output_tokens":7,"output_tokens_details":{"reasoning_tokens":3}}}}""",
    ),
)

private fun event(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
