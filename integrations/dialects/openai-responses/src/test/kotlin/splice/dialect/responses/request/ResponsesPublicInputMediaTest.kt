package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

class ResponsesPublicInputMediaTest {

    @Test
    fun `generated message block order survives text image and file conversion`() {
        val variants = listOf("text", "image", "document")
        val orders = listOf(
            variants,
            listOf("document", "text", "image"),
            listOf("image", "document", "text"),
            listOf("text", "document", "image"),
            listOf("document", "image", "text"),
            listOf("image", "text", "document"),
        )

        orders.forEachIndexed { index, order ->
            val blocks = order.mapIndexed { blockIndex, kind ->
                when (kind) {
                    "text" -> """{"type":"text","text":"text-$index-$blockIndex"}"""
                    "image" -> """
                        {"type":"image","source":{"type":"url",
                        "url":"https://assets.invalid/$index-$blockIndex.png"}}
                    """.trimIndent()
                    else -> """
                        {"type":"document","source":{"type":"url",
                        "url":"https://assets.invalid/$index-$blockIndex.pdf","media_type":"application/pdf"}}
                    """.trimIndent()
                }
            }.joinToString(",")
            val request = build(
                """{"model":"m","max_tokens":100,"messages":[{"role":"user","content":[$blocks]}]}""",
            )
            val actual = request.getValue("input").jsonArray.map { item -> inputKind(item as JsonObject) }
            assertEquals(order, actual, "source order $order")
        }
    }

    @Test
    fun `tool result preserves text image file order identity and explicit error verdict`() {
        val request = build(
            """{"model":"m","max_tokens":100,"messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"call-7","name":"read","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"call-7","is_error":true,"content":[
                    {"type":"text","text":"before"},
                    {"type":"image","source":{"type":"url","url":"https://assets.invalid/screen.png"}},
                    {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"JVBERi0xLjQ="}},
                    {"type":"text","text":"after"}
                ]}]}
            ]}""",
        )
        val input = request.getValue("input").jsonArray.map { it.jsonObject }
        val call = input.single { it["type"]?.jsonPrimitive?.content == "function_call" }
        val output = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }

        assertEquals("call-7", call["call_id"]?.jsonPrimitive?.content)
        assertEquals("call-7", output["call_id"]?.jsonPrimitive?.content)
        assertTrue("status" !in output, "lifecycle status does not represent is_error")
        val parts = output.getValue("output").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("input_text", "input_text", "input_image", "input_file", "input_text"),
            parts.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals("[is_error=true]", parts[0]["text"]?.jsonPrimitive?.content)
        assertEquals("before", parts[1]["text"]?.jsonPrimitive?.content)
        assertEquals("https://assets.invalid/screen.png", parts[2]["image_url"]?.jsonPrimitive?.content)
        assertEquals(
            "data:application/pdf;base64,JVBERi0xLjQ=",
            parts[3]["file_data"]?.jsonPrimitive?.content,
        )
        assertEquals("after", parts[4]["text"]?.jsonPrimitive?.content)
        assertEquals(2, input.size, "multimodal function output must not become detached user messages")
    }

    private fun inputKind(item: JsonObject): String {
        val content = item["content"]
        if (content is kotlinx.serialization.json.JsonPrimitive) return "text"
        val part = (content as JsonArray).first().jsonObject
        return when (part["type"]?.jsonPrimitive?.content) {
            "input_image" -> "image"
            "input_file" -> "document"
            else -> error("unexpected content part $part")
        }
    }

    private fun build(body: String): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(body)
        val options = BuildOptions(
            compact = false,
            models = ModelIds(original = "claude-openai--gpt-test", upstream = "gpt-test"),
            reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
            handoff = ReasoningHandoff(
                replay = InjectPriorReasoning(false),
                decode = { null },
            ),
        )
        return ResponsesRequestBuilder(
            ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks()),
        ).build(parsed.typed, parsed.raw, options).req
    }
}
