package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

private val OPENAI_STANDARD = ResponsesQuirks(
    providerTag = "openai",
    lite = ResponsesLiteQuirks(),
)

private fun buildInput(json: String) =
    AnthropicParse.parseAnthropicBody(json).let { parsed ->
        ResponsesRequestBuilder(OPENAI_STANDARD).build(
            parsed.typed,
            parsed.raw,
            BuildOptions(
                compact = false,
                models = ModelIds(original = "openai--gpt-4.1", upstream = "gpt-4.1"),
                reasoning = RequestedReasoning(
                    effort = "high",
                    summary = "detailed",
                    display = ReasoningDisplay.TEXT,
                ),
                handoff = ReasoningHandoff(
                    replay = InjectPriorReasoning(false),
                    decode = { null },
                ),
            ),
        ).req.getValue("input").jsonArray.map { it.jsonObject }
    }

class ResponsesDocumentInputTest {
    @Test
    fun `a user document becomes a public input_file part instead of an omission marker`() {
        val input = buildInput(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"JVBERi0x"}}
            ]}]}""",
        )

        val content = assertInstanceOf(JsonArray::class.java, input.single().getValue("content"))
        val part = content.single().jsonObject
        assertEquals("input_file", part.getValue("type").jsonPrimitive.content)
        assertEquals(
            "data:application/pdf;base64,JVBERi0x",
            part.getValue("file_data").jsonPrimitive.content,
        )
    }

    @Test
    fun `tool results retain ordered text image and document content parts`() {
        val input = buildInput(
            """{"model":"m","messages":[
                {"role":"assistant","content":[
                    {"type":"tool_use","id":"call-1","name":"Read","input":{}}
                ]},
                {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"call-1","content":[
                        {"type":"text","text":"report"},
                        {"type":"document","source":{"type":"url","url":"https://files.example/report.pdf","media_type":"application/pdf"}},
                        {"type":"image","source":{"type":"url","url":"https://files.example/page.png"}}
                    ]}
                ]}
            ]}""",
        )

        val result = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("call-1", result.getValue("call_id").jsonPrimitive.content)
        val output = assertInstanceOf(JsonArray::class.java, result.getValue("output"))
        val parts = output.map { it.jsonObject }
        assertEquals(
            listOf("input_text", "input_file", "input_image"),
            parts.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            "https://files.example/report.pdf",
            parts[1].getValue("file_url").jsonPrimitive.content,
        )
        assertEquals(
            "https://files.example/page.png",
            parts[2].getValue("image_url").jsonPrimitive.content,
        )
    }
}
