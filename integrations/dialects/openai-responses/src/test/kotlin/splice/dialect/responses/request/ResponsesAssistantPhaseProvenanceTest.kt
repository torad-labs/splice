package splice.dialect.responses.request

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.buildResponsesTestRequest
import kotlin.random.Random

class ResponsesAssistantPhaseProvenanceTest {
    @Test
    fun `a known public assistant phase survives generated input shapes`() {
        val random = Random(430)
        repeat(PHASE_CASES) { index ->
            val phase = if (index % 2 == 0) "commentary" else "final_answer"
            val text = "phase-${random.nextLong().toString(36)}"
            val tool = if (phase == "final_answer") {
                ",{\"type\":\"tool_use\",\"id\":\"call_$index\",\"name\":\"Read\",\"input\":{}}"
            } else {
                ""
            }
            val body = AnthropicParse.parseAnthropicBody(
                """{"model":"m","messages":[
                    {"role":"user","content":"Go."},
                    {"type":"message","role":"assistant","phase":"$phase","content":[
                        {"type":"text","text":"$text"}$tool
                    ]}
                ]}""",
            )

            val item = buildResponsesTestRequest(
                ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks()),
                body,
                "gpt-4.1",
            )["input"]!!.jsonArray
                .map { it.jsonObject }
                .single { it["role"]?.jsonPrimitive?.content == "assistant" }

            assertEquals(phase, item["phase"]?.jsonPrimitive?.content, "source phase=$phase, text=$text")
        }
    }
}

private const val PHASE_CASES = 32
