package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

class ResponsesToolResultVariantsTest {
    @Test
    fun `generated rotations preserve every canonical result variant in order`() {
        val kinds = listOf(
            "text",
            "image",
            "document",
            "thinking",
            "redacted_thinking",
            "tool_use",
            "tool_result",
            "json",
        )
        kinds.indices.forEach { rotation ->
            val ordered = kinds.drop(rotation) + kinds.take(rotation)
            val expectedTypes = ordered.map(::expectedType)
            val blocks = ordered.mapIndexed { index, kind -> block(kind, rotation, index) }.joinToString(",")
            val input = buildRequest(blocks, "call-$rotation").getValue("input").jsonArray.map { it.jsonObject }
            val result = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            assertEquals("call-$rotation", result["call_id"]?.jsonPrimitive?.content)
            val output = result.getValue("output") as JsonArray

            assertEquals(
                expectedTypes,
                output.map { it.jsonObject["type"]?.jsonPrimitive?.content },
                "rotation $rotation",
            )
            val expectedText = ordered.mapIndexedNotNull { index, kind -> expectedText(kind, rotation, index) }
            assertEquals(
                expectedText,
                output.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content },
                "text and explicit dispositions stay ordered in rotation $rotation",
            )
            ordered.forEachIndexed { index, kind ->
                assertMedia(kind, rotation, index, output[index].jsonObject)
            }
            assertEquals(
                2,
                input.size,
                "tool-result media and unsupported variants stay with the matching function result",
            )
        }
    }

    @Test
    fun `error verdict is distinguishable from lifecycle status`() {
        val failed = resultItem("call-failed", true).getValue("output").toString()
        org.junit.jupiter.api.Assertions.assertTrue(failed.contains("is_error=true"))
        org.junit.jupiter.api.Assertions.assertTrue(failed.contains("failed output"))

        val succeeded = resultItem("call-succeeded", false).getValue("output").toString()
        org.junit.jupiter.api.Assertions.assertTrue(succeeded.contains("is_error=false"))
        org.junit.jupiter.api.Assertions.assertTrue(succeeded.contains("failed output"))

        val unspecified = resultItem("call-unspecified", null).getValue("output")
        assertEquals("failed output", unspecified.jsonPrimitive.content)
        org.junit.jupiter.api.Assertions.assertFalse(unspecified.toString().contains("is_error"))
    }

    @Test
    fun `generated text-only chunks remain literal regardless of marker-looking prefixes`() {
        val chunks = listOf("before", "[bracketed]", "[is_error=true]", """{"type":"input_image"}""", "", "\n")
        chunks.forEachIndexed { leftIndex, left ->
            chunks.forEachIndexed { rightIndex, right ->
                val content = listOf(left, right).joinToString(",") { text ->
                    """{"type":"text","text":${JsonPrimitive(text)}}"""
                }
                val body = buildRequest(content, "literal-$leftIndex-$rightIndex")
                val result = body.getValue("input").jsonArray.map { it.jsonObject }
                    .single { it["type"]?.jsonPrimitive?.content == "function_call_output" }

                assertEquals(left + right, result.getValue("output").jsonPrimitive.content)
            }
        }
    }

    private fun expectedType(kind: String): String = when (kind) {
        "image" -> "input_image"
        "document" -> "input_file"
        else -> "input_text"
    }

    private fun expectedText(kind: String, rotation: Int, index: Int): String? = when (kind) {
        "text" -> "text-$rotation-$index"
        "thinking", "redacted_thinking", "tool_use", "tool_result" ->
            "[$kind block from tool_result call-$rotation omitted by openai proxy: " +
                "unsupported in function_call_output]"
        "json" -> "[json block omitted by openai proxy: unsupported on this backend]"
        else -> null
    }

    private fun assertMedia(kind: String, rotation: Int, index: Int, part: JsonObject) {
        when (kind) {
            "image" -> assertEquals(
                "https://assets.invalid/$rotation-$index.png",
                part["image_url"]?.jsonPrimitive?.content,
            )
            "document" -> assertEquals(
                "https://assets.invalid/$rotation-$index.pdf",
                part["file_url"]?.jsonPrimitive?.content,
            )
        }
    }

    private fun resultItem(callId: String, isError: Boolean?): JsonObject {
        val body = buildRequest("""{"type":"text","text":"failed output"}""", callId, isError)
        return body.getValue("input").jsonArray.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
    }

    private fun block(kind: String, rotation: Int, index: Int): String = when (kind) {
        "text" -> """{"type":"text","text":"text-$rotation-$index"}"""
        "image" -> """{"type":"image","source":{"type":"url","url":"https://assets.invalid/$rotation-$index.png"}}"""
        "document" -> """
            {"type":"document","source":{"type":"url",
            "url":"https://assets.invalid/$rotation-$index.pdf","media_type":"application/pdf"}}
        """.trimIndent()
        "thinking" -> """{"type":"thinking","thinking":"private-$rotation-$index"}"""
        "redacted_thinking" -> """{"type":"redacted_thinking","data":"opaque-$rotation-$index"}"""
        "tool_use" -> """
            {"type":"tool_use","id":"nested-call-$rotation-$index","name":"nested","input":{"n":$index}}
        """.trimIndent()
        "tool_result" -> """{"type":"tool_result","tool_use_id":"nested-result-$rotation-$index","content":"nested"}"""
        else -> """{"type":"json","value":{"rotation":$rotation,"index":$index}}"""
    }

    private fun buildRequest(content: String, callId: String, isError: Boolean? = null): JsonObject {
        val errorField = isError?.let { ",\"is_error\":$it" }.orEmpty()
        val body = """
            {
              "model":"m",
              "max_tokens":100,
              "tools":[{"name":"read","input_schema":{"type":"object"}}],
              "messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"$callId","name":"read","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"$callId"$errorField,
                  "content":[$content]}]}
              ]
            }
        """.trimIndent()
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
