package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestControlQuirks

/**
 * Public Responses `FunctionCallOutputItemParam` accepts either a literal string or an ordered
 * `input_text` / `input_image` / `input_file` array. These tests exercise that public
 * request surface without selecting the Codex code-mode path.
 */
class ResponsesToolResultContentTest {

    @Test
    fun `text-only output stays literal and error disposition stays explicit content`() {
        val literal = """{"type":"input_image","image_url":"https://not-a-media-part.example/image.png"}"""
        val item = rendered(
            ToolResultBlock(
                toolUseId = "call-text-error",
                content = listOf(TextBlock("before"), TextBlock(literal), TextBlock("after")),
                isError = true,
            ),
        ).single().jsonObject

        assertEquals("function_call_output", item.getValue("type").jsonPrimitive.content)
        assertEquals("call-text-error", item.getValue("call_id").jsonPrimitive.content)
        assertFalse(item.containsKey("status"), "lifecycle status does not encode tool error state")
        val output = item.getValue("output")
        assertTrue(output is JsonPrimitive, "text-only results stay on the Responses string branch")
        assertEquals("[is_error=true]\nbefore${literal}after", output.jsonPrimitive.content)
    }

    @Test
    fun `mixed tool result content retains public type and source order`() {
        val items = rendered(
            ToolResultBlock(
                toolUseId = "call-mixed",
                content = listOf(
                    TextBlock("before"),
                    ImageBlock(MediaSource(type = "url", url = "https://media.example/first.png")),
                    TextBlock("between"),
                    DocumentBlock(
                        MediaSource(
                            type = "url",
                            mediaType = "application/pdf",
                            url = "https://media.example/report.pdf",
                        ),
                    ),
                    ImageBlock(MediaSource(type = "base64", mediaType = "image/png", data = "aW1hZ2U=")),
                    TextBlock("after"),
                ),
                isError = false,
            ),
        )

        assertEquals(1, items.size, "tool-result media belongs in its function_call_output, not follow-up messages")
        val item = items.single().jsonObject
        assertEquals("call-mixed", item.getValue("call_id").jsonPrimitive.content)
        assertFalse(item.containsKey("status"), "lifecycle status does not encode tool error state")
        val parts = item.getValue("output").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("input_text", "input_text", "input_image", "input_text", "input_file", "input_image", "input_text"),
            parts.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals("[is_error=false]", parts[0].getValue("text").jsonPrimitive.content)
        assertEquals("before", parts[1].getValue("text").jsonPrimitive.content)
        assertEquals("https://media.example/first.png", parts[2].getValue("image_url").jsonPrimitive.content)
        assertEquals("between", parts[3].getValue("text").jsonPrimitive.content)
        assertEquals("https://media.example/report.pdf", parts[4].getValue("file_url").jsonPrimitive.content)
        assertEquals("data:image/png;base64,aW1hZ2U=", parts[5].getValue("image_url").jsonPrimitive.content)
        assertEquals("after", parts[6].getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `media source forms retain data and URLs while unsupported forms remain ordered text`() {
        val item = rendered(
            ToolResultBlock(
                toolUseId = "call-sources",
                content = listOf(
                    ImageBlock(MediaSource(type = "base64", mediaType = "image/jpeg", data = "aW1hZ2U=")),
                    ImageBlock(MediaSource(type = "base64", data = "ZGVmYXVsdC1pbWFnZQ==")),
                    ImageBlock(MediaSource(type = "base64", mediaType = "image/png", data = "")),
                    ImageBlock(MediaSource(type = "url", url = "https://media.example/second.png")),
                    ImageBlock(MediaSource(type = "binary", data = "opaque")),
                    DocumentBlock(MediaSource(type = "base64", mediaType = "application/pdf", data = "JVBERi0x")),
                    DocumentBlock(MediaSource(type = "base64", data = "ZGVmYXVsdC1maWxl")),
                    DocumentBlock(MediaSource(type = "url", url = "https://media.example/notes.txt")),
                    DocumentBlock(null),
                    DocumentBlock(MediaSource(type = "binary", data = "opaque")),
                ),
            ),
        ).single().jsonObject

        assertFalse(item.containsKey("status"), "an absent Anthropic is_error remains absent")
        assertSourceParts(item.getValue("output").jsonArray.map { it.jsonObject })
    }

    private fun assertSourceParts(parts: List<JsonObject>) {
        assertEquals(
            listOf(
                "input_image", "input_image", "input_text", "input_image", "input_text",
                "input_file", "input_file", "input_file", "input_text", "input_text",
            ),
            parts.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals("data:image/jpeg;base64,aW1hZ2U=", parts[0].getValue("image_url").jsonPrimitive.content)
        assertEquals("data:image/png;base64,ZGVmYXVsdC1pbWFnZQ==", parts[1].getValue("image_url").jsonPrimitive.content)
        assertEquals(
            "[1 image(s) from tool_result call-sources omitted by openai proxy: unsupported source]",
            parts[2].getValue("text").jsonPrimitive.content,
        )
        assertEquals("https://media.example/second.png", parts[3].getValue("image_url").jsonPrimitive.content)
        assertEquals(
            "[1 image(s) from tool_result call-sources omitted by openai proxy: unsupported source]",
            parts[4].getValue("text").jsonPrimitive.content,
        )
        assertEquals("data:application/pdf;base64,JVBERi0x", parts[5].getValue("file_data").jsonPrimitive.content)
        assertEquals(
            "data:application/octet-stream;base64,ZGVmYXVsdC1maWxl",
            parts[6].getValue("file_data").jsonPrimitive.content,
        )
        assertEquals("https://media.example/notes.txt", parts[7].getValue("file_url").jsonPrimitive.content)
        assertEquals(
            "[document from tool_result call-sources omitted by openai proxy: unsupported source]",
            parts[8].getValue("text").jsonPrimitive.content,
        )
        assertEquals(
            "[document from tool_result call-sources omitted by openai proxy: unsupported source]",
            parts[9].getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `provider without structured tool output keeps an honest ordered string`() {
        val item = rendered(
            ToolResultBlock(
                toolUseId = "call-legacy",
                content = listOf(
                    TextBlock("before"),
                    ImageBlock(MediaSource(type = "url", url = "https://media.example/legacy.png")),
                    DocumentBlock(MediaSource(type = "url", url = "https://media.example/legacy.pdf")),
                    TextBlock("after"),
                ),
                isError = true,
            ),
            ResponsesQuirks(
                providerTag = "legacy",
                lite = ResponsesLiteQuirks(),
                request = ResponsesRequestControlQuirks(supportsStructuredToolOutput = false),
            ),
        ).single().jsonObject

        assertFalse(item.containsKey("status"))
        val output = item.getValue("output")
        assertTrue(output is JsonPrimitive)
        assertEquals(
            "[is_error=true]\n" +
                "before\n" +
                "[1 image(s) from tool_result call-legacy omitted by legacy proxy: " +
                "structured tool output unsupported]\n" +
                "[document from tool_result call-legacy omitted by legacy proxy: " +
                "structured tool output unsupported]\n" +
                "after",
            output.jsonPrimitive.content,
        )
    }

    @Test
    fun `provider without input files preserves surrounding structured result order`() {
        val item = rendered(
            ToolResultBlock(
                toolUseId = "call-fileless",
                content = listOf(
                    TextBlock("before"),
                    DocumentBlock(MediaSource(type = "url", url = "https://media.example/fileless.pdf")),
                    ImageBlock(MediaSource(type = "url", url = "https://media.example/fileless.png")),
                    TextBlock("after"),
                ),
            ),
            ResponsesQuirks(
                providerTag = "fileless",
                lite = ResponsesLiteQuirks(),
                request = ResponsesRequestControlQuirks(supportsInputFiles = false),
            ),
        ).single().jsonObject

        val parts = item.getValue("output").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("input_text", "input_text", "input_image", "input_text"),
            parts.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals("before", parts[0].getValue("text").jsonPrimitive.content)
        assertEquals(
            "[document from tool_result call-fileless omitted by fileless proxy: " +
                "input_file unsupported by this backend]",
            parts[1].getValue("text").jsonPrimitive.content,
        )
        assertEquals("https://media.example/fileless.png", parts[2].getValue("image_url").jsonPrimitive.content)
        assertEquals("after", parts[3].getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `every other canonical result block has an ordered explicit disposition`() {
        val item = rendered(
            ToolResultBlock(
                toolUseId = "call-canonical",
                content = listOf(
                    TextBlock("visible"),
                    ThinkingBlock("private reasoning"),
                    RedactedThinkingBlock("opaque reasoning"),
                    ToolUseBlock(id = "nested-call", name = "nested"),
                    ToolResultBlock(toolUseId = "nested-result", content = listOf(TextBlock("nested output"))),
                    UnknownBlock(buildJsonObject { put("type", "vendor_payload") }),
                ),
            ),
        ).single().jsonObject

        val parts = item.getValue("output").jsonArray.map { it.jsonObject }
        assertEquals(
            List(CANONICAL_PART_COUNT) { "input_text" },
            parts.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            listOf(
                "visible",
                "[thinking block from tool_result call-canonical omitted by openai proxy: " +
                    "unsupported in function_call_output]",
                "[redacted_thinking block from tool_result call-canonical omitted by openai proxy: " +
                    "unsupported in function_call_output]",
                "[tool_use block from tool_result call-canonical omitted by openai proxy: " +
                    "unsupported in function_call_output]",
                "[tool_result block from tool_result call-canonical omitted by openai proxy: " +
                    "unsupported in function_call_output]",
                "[vendor_payload block omitted by openai proxy: unsupported on this backend]",
            ),
            parts.map { it.getValue("text").jsonPrimitive.content },
        )
    }

    @Test
    fun `generated text image document sequences retain every position`() {
        repeat(GENERATED_SEQUENCE_COUNT) { seed ->
            val content = (0 until GENERATED_SEQUENCE_WIDTH).map { position ->
                when (seed / GENERATED_POWERS[position] % GENERATED_SOURCE_KINDS) {
                    TEXT_KIND -> TextBlock("text-$position")
                    IMAGE_KIND -> ImageBlock(
                        MediaSource(type = "url", url = "https://media.example/image-$position.png"),
                    )
                    else -> DocumentBlock(
                        MediaSource(type = "url", url = "https://media.example/file-$position.txt"),
                    )
                }
            }
            val item = rendered(ToolResultBlock(toolUseId = "generated-$seed", content = content)).single().jsonObject
            val output = item.getValue("output")

            if (content.all { it is TextBlock }) {
                assertTrue(output is JsonPrimitive, "seed $seed")
                assertEquals(
                    content.joinToString("") { (it as TextBlock).text },
                    output.jsonPrimitive.content,
                    "seed $seed",
                )
            } else {
                val parts = output.jsonArray.map { it.jsonObject }
                assertEquals(
                    content.map(::publicType),
                    parts.map { it.getValue("type").jsonPrimitive.content },
                    "seed $seed",
                )
                content.zip(parts).forEachIndexed { position, (source, part) ->
                    assertGeneratedPart(source, part, "seed $seed position $position")
                }
            }
        }
    }

    private fun assertGeneratedPart(source: ContentBlock, part: JsonObject, context: String) {
        if (source is TextBlock) {
            assertEquals(source.text, part.getValue("text").jsonPrimitive.content, context)
        } else if (source is ImageBlock) {
            assertEquals(source.source?.url, part.getValue("image_url").jsonPrimitive.content, context)
        } else if (source is DocumentBlock) {
            assertEquals(source.source?.url, part.getValue("file_url").jsonPrimitive.content, context)
        } else {
            error("generator emits only public tool-result content")
        }
    }

    private fun rendered(block: ToolResultBlock, quirks: ResponsesQuirks = OPENAI_STANDARD): JsonArray =
        buildJsonArray {
            ResponsesInputTools(quirks).appendToolResult(this, block)
        }

    private fun publicType(block: ContentBlock): String =
        if (block is TextBlock) {
            "input_text"
        } else if (block is ImageBlock) {
            "input_image"
        } else if (block is DocumentBlock) {
            "input_file"
        } else {
            error("generator emits only public tool-result content")
        }
}

private val OPENAI_STANDARD = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())

private const val CANONICAL_PART_COUNT = 6
private const val GENERATED_SEQUENCE_WIDTH = 4
private const val GENERATED_SOURCE_KINDS = 3
private const val GENERATED_SEQUENCE_COUNT = 81
private const val TEXT_KIND = 0
private const val IMAGE_KIND = 1
private val GENERATED_POWERS = intArrayOf(1, 3, 9, 27)
