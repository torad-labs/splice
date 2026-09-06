import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.ToolSearchCall
import splice.core.turn.ToolSearchCallId
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.ResponsesCodeModeInput
import splice.dialect.responses.ResponsesCodeModeProjection
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.ToolDeferralPolicy
import splice.spi.ToolSearchRound

class ResponsesCodeModeProjectionTest {
    @Test
    fun `builder declaration alias at native search offset remains callback owned`() {
        val actual = actualHistory()
        val native = actual.native.nativeSegments.single()
        val alias = actual.callback.replayItems.single()

        assertEquals(native.logicalOffset, alias.logicalOffset)
        assertEquals(null, native.callbackId)
        assertEquals("callback", alias.callbackId)
    }

    @Test
    fun `native search and builder alias retain ownership after shared-offset reconstruction`() {
        val actual = actualHistory()
        val native = actual.native.nativeSegments.single()
        val alias = actual.callback.replayItems.single()
        val restored = actual.projection.rebuild(
            ResponsesCodeModeInput(
                actual.callback.logicalItems,
                listOf(alias, native),
            ),
        )
        val reprojected = actual.projection.project(restored)

        assertEquals(null, reprojected.replayItems.single { it.items == native.items }.callbackId)
        assertEquals("callback", reprojected.replayItems.single { it.items == alias.items }.callbackId)
    }

    private fun actualHistory(): ActualHistory {
        val builder = ResponsesRequestBuilder(
            ResponsesQuirks(
                providerTag = "test",
                toolSurface = ToolDeferralPolicy(minDeferred = 1),
            ),
        )
        val cold = build(builder, request(history = false))
        val nativeCall = buildJsonObject {
            put("type", "tool_search_call")
            put("call_id", "native-search")
            put("arguments", buildJsonObject { put("query", "release lookup") })
        }
        val search = ToolSearchCall(ToolSearchCallId("native-search"), "release lookup", 1, nativeCall)
        val searched = checkNotNull(
            cold.toolSearch?.continuationForSearch(
                ToolSearchRound(
                    cold.req,
                    TurnOutcome.Success(false, false, Usage(), toolSearches = listOf(search)),
                    0,
                ),
            ),
        )
        val warm = build(builder, request(history = true))
        val projection = ResponsesCodeModeProjection()
        return ActualHistory(
            projection,
            projection.project(searched.getValue("input").jsonArray),
            projection.project(warm.req.getValue("input").jsonArray),
        )
    }

    private fun build(builder: ResponsesRequestBuilder, body: String) =
        AnthropicParse.parseAnthropicBody(body).let { parsed ->
            builder.build(
                parsed.typed,
                parsed.raw,
                BuildOptions(
                    compact = false,
                    originalModel = "gpt-6-astra",
                    upstreamModel = "gpt-6-astra",
                    configEffort = null,
                    configSummary = null,
                    showReasoning = ReasoningDisplay.OFF,
                    replayReasoning = InjectPriorReasoning(false),
                    decodeReasoningEnvelope = { null },
                ),
            )
        }

    private fun request(history: Boolean): String {
        val messages = if (history) {
            """
            {"role":"user","content":"lookup"},
            {"role":"assistant","content":[
              {"type":"tool_use","id":"callback","name":"mcp__release__lookup","input":{}}
            ]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"callback","content":"0.4.0"}]}
            """.trimIndent()
        } else {
            """{"role":"user","content":"lookup"}"""
        }
        return """
            {
              "model":"gpt-6-astra",
              "tools":[
                {"name":"Read","input_schema":{"type":"object"}},
                {"name":"mcp__release__lookup","input_schema":{"type":"object"}}
              ],
              "messages":[$messages]
            }
        """.trimIndent()
    }

    private data class ActualHistory(
        val projection: ResponsesCodeModeProjection,
        val native: ResponsesCodeModeInput,
        val callback: ResponsesCodeModeInput,
    )
}
