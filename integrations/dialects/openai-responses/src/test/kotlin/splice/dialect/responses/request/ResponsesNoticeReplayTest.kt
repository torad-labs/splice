package splice.dialect.responses.request

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.SpliceNotice
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

class ResponsesNoticeReplayTest {
    @Test
    fun `Responses input drops a marked wait notice without dropping the answer`() {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","messages":[{"role":"assistant","content":[
                {"type":"thinking","thinking":"[splice] holding this turn open.",
                    "signature":"${SpliceNotice.SIGNATURE}"},
                {"type":"thinking","thinking":"model reasoning","signature":"opaque-real"},
                {"type":"text","text":"the answer"}
            ]}]}""",
        )
        val options = BuildOptions(
            compact = false,
            originalModel = "claudex--m",
            upstreamModel = "m",
            configEffort = null,
            configSummary = null,
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = InjectPriorReasoning(false),
            decodeReasoningEnvelope = { null },
        )
        val request = ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex"))
            .build(parsed.typed, parsed.raw, options).req
        val input = request["input"]!!.jsonArray
        assertEquals(1, input.size, request.toString())
        assertEquals("the answer", input.single().jsonObject.getValue("content").jsonPrimitive.content)
        assertFalse(request.toString().contains(SpliceNotice.SIGNATURE), request.toString())
        assertFalse(request.toString().contains("[splice] holding"), request.toString())
    }
}
