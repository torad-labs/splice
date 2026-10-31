package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.provider.codex.stream.source.CodeModeItemPrefix

class CodeModeItemPrefixTest {
    @Test
    fun `a streamed preface remains before its completed exec when no message done arrived`() {
        val call = call("first")
        val preface = listOf(ResponsesAssistantText.item("suffix ", AssistantPhase.COMMENTARY))
        val outcome = TurnOutcome.Success(
            false,
            false,
            Usage(),
            handoffs = RoundHandoffs(
                customCalls = listOf(call),
                nativePrefix = preface,
                nativeOutput = listOf(call.raw),
            ),
        )
        val sealed = checkNotNull(CodeModeItemPrefix.of(outcome, call))
        assertEquals(preface, sealed.handoffs.nativePrefix)
        assertEquals(listOf(call.raw), sealed.handoffs.nativeOutput)
    }

    @Test
    fun `legacy terminal text and native suffix keep their positions without duplicating a sibling`() {
        val first = call("first")
        val second = call("second")
        val after = listOf(ResponsesAssistantText.item("later", AssistantPhase.COMMENTARY), second.raw)
        val plain = TurnOutcome.Success(
            false,
            false,
            Usage(),
            handoffs = RoundHandoffs(customCalls = listOf(first, second), nativeAfterExec = after),
        )
        val outcome = plain.copy(text = plain.text.copy(bodyText = "before", emittedText = true))
        val expected = listOf(ResponsesAssistantText.item("before", AssistantPhase.COMMENTARY), first.raw) + after
        assertEquals(expected, CodeModeItemPrefix.items(outcome))
        assertEquals(expected.take(2), checkNotNull(CodeModeItemPrefix.of(outcome, first)).handoffs.nativeOutput)
    }

    private fun call(id: String): GatewayCustomCall {
        val raw: JsonObject = Json.parseToJsonElement(
            """{"type":"custom_tool_call","call_id":"$id","name":"exec","input":"text('$id')"}""",
        ).jsonObject
        return GatewayCustomCall(id, CODE_MODE_TOOL_NAME, "text('$id')", raw)
    }
}
