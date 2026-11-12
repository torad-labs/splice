package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import splice.core.parse.AnthropicParse
import splice.core.parse.lenientJson
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonWire
import splice.core.wire.AnthropicRequest
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Public document bodies retain their readable content, not an invented serialization or an omission. */
class ResponsesDocumentSourcesTest {
    @TestFactory
    fun `generated public document sources preserve literal content and surrounding order`(): List<DynamicTest> =
        (0 until GENERATED_DOCUMENT_COUNT).flatMap { seed ->
            val literal = literals[seed % literals.size]
            val image = "https://media.example/document-$seed.png"
            val cases = listOf(
                DocumentCase(
                    source("text", "data", JsonPrimitive(literal), "text/plain"),
                    listOf(text(literal)),
                ),
                DocumentCase(source("content", "content", JsonPrimitive(literal)), listOf(text(literal))),
                DocumentCase(
                    source(
                        "content",
                        "content",
                        JsonArray(
                            listOf(
                                blockText(literal),
                                blockImage(source("url", "url", JsonPrimitive(image))),
                                blockText("tail-$seed"),
                            ),
                        ),
                    ),
                    listOf(text(literal), image(image), text("tail-$seed")),
                ),
                DocumentCase(
                    source("base64", "data", JsonPrimitive("JVBERi0x-$seed"), "application/pdf"),
                    listOf(file("file_data", "data:application/pdf;base64,JVBERi0x-$seed")),
                ),
                DocumentCase(
                    source("url", "url", JsonPrimitive("https://media.example/document-$seed.pdf")),
                    listOf(file("file_url", "https://media.example/document-$seed.pdf")),
                ),
            )
            cases.flatMapIndexed { form, case ->
                listOf(false, true).map { toolResult ->
                    DynamicTest.dynamicTest("source $form seed $seed tool result $toolResult") {
                        val blocks = listOf(blockText("before-$seed"), document(case.source), blockText("after-$seed"))
                        val actual = render(request(blocks, toolResult), toolResult)
                        assertEquals(
                            listOf(text("before-$seed")) + case.expected + text("after-$seed"),
                            actual,
                        )
                    }
                }
            }
        }

    @TestFactory
    fun `generated document content sequences preserve every text and image position`(): List<DynamicTest> =
        (0 until GENERATED_SEQUENCE_COUNT).flatMap { seed ->
            val variants = (0 until GENERATED_SEQUENCE_WIDTH).map { position ->
                val kind = (seed shr (position * BITS_PER_VARIANT)) and VARIANT_MASK
                val literal = literals[(seed + position) % literals.size]
                val url = "https://media.example/$seed-$position.png"
                when (kind) {
                    0 -> blockText(literal) to text(literal)
                    1 -> blockImage(source("url", "url", JsonPrimitive(url))) to image(url)
                    2 -> blockImage(source("base64", "data", JsonPrimitive("payload-$seed-$position"), "image/png")) to
                        image("data:image/png;base64,payload-$seed-$position")
                    else -> blockText("tail-$position") to text("tail-$position")
                }
            }
            listOf(false, true).map { toolResult ->
                DynamicTest.dynamicTest("document sequence $seed tool result $toolResult") {
                    val source = source("content", "content", JsonArray(variants.map { it.first }))
                    assertEquals(
                        variants.map { it.second },
                        render(request(listOf(document(source)), toolResult), toolResult),
                    )
                }
            }
        }

    @TestFactory
    fun `generated document text remains readable when provider input files are disabled`(): List<DynamicTest> =
        literals.flatMapIndexed { seed, literal ->
            val cases = listOf(
                DocumentCase(source("text", "data", JsonPrimitive(literal), "text/plain"), listOf(text(literal))),
                DocumentCase(source("content", "content", JsonPrimitive(literal)), listOf(text(literal))),
                DocumentCase(
                    source(
                        "content",
                        "content",
                        JsonArray(
                            listOf(
                                blockText(literal),
                                blockImage(source("url", "url", JsonPrimitive("https://media.example/$seed.png"))),
                            ),
                        ),
                    ),
                    listOf(text(literal), image("https://media.example/$seed.png")),
                ),
            )
            cases.flatMapIndexed { form, case ->
                listOf(false, true).map { toolResult ->
                    DynamicTest.dynamicTest("fileless source $form seed $seed tool result $toolResult") {
                        assertEquals(
                            case.expected,
                            render(
                                request(listOf(document(case.source)), toolResult),
                                toolResult,
                                standard.copy(
                                    providerTag = "fileless",
                                    request = standard.request.copy(supportsInputFiles = false),
                                ),
                            ),
                        )
                    }
                }
            }
        }

    @TestFactory
    fun `generated nested document images retain positions and the vendor floor exception`(): List<DynamicTest> =
        (1..FLOOR_PLUS_ONE).flatMap { edge ->
            listOf(false, true).map { toolResult ->
                DynamicTest.dynamicTest("document image edge $edge tool result $toolResult") {
                    val data = png(edge)
                    val source = source(
                        "content",
                        "content",
                        JsonArray(
                            listOf(
                                blockText("before"),
                                blockImage(source("base64", "data", JsonPrimitive(data), "image/png")),
                                blockImage(
                                    source("url", "url", JsonPrimitive("https://media.example/unknown-size.png")),
                                ),
                                blockText("after"),
                            ),
                        ),
                    )
                    val middle = if (edge < IMAGE_FLOOR) {
                        val subject = if (toolResult) "1 image(s) from tool_result call-document" else "image"
                        text("[$subject omitted by floor proxy: $FLOOR_REASON]")
                    } else {
                        image("data:image/png;base64,$data")
                    }
                    assertEquals(
                        listOf(text("before"), middle, image("https://media.example/unknown-size.png"), text("after")),
                        render(
                            request(listOf(document(source)), toolResult),
                            toolResult,
                            standard.copy(
                                providerTag = "floor",
                                request = standard.request.copy(minImageEdgePx = IMAGE_FLOOR),
                            ),
                        ),
                    )
                }
            }
        }

    @TestFactory
    fun `generated text only providers preserve document text and mark only unsupported media`(): List<DynamicTest> =
        literals.mapIndexed { seed, literal ->
            DynamicTest.dynamicTest("text only document seed $seed") {
                val content = source(
                    "content",
                    "content",
                    JsonArray(
                        listOf(
                            blockText(literal),
                            blockImage(source("url", "url", JsonPrimitive("https://media.example/$seed.png"))),
                            blockText("tail-$seed"),
                        ),
                    ),
                )
                val input = build(
                    request(listOf(blockText("before"), document(content), blockText("after")), true),
                    ResponsesRequestBuilder(
                        standard.copy(
                            providerTag = "legacy",
                            request = standard.request.copy(supportsStructuredToolOutput = false),
                        ),
                    ),
                )
                val result = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                assertEquals(2, input.size, "no document media becomes a detached user message")
                assertEquals(
                    "before\n$literal\n" +
                        "[1 image(s) from tool_result call-document omitted by legacy proxy: " +
                        "structured tool output unsupported]\n" +
                        "tail-$seed\nafter",
                    result.getValue("output").jsonPrimitive.content,
                )
            }
        }

    @TestFactory
    fun `generated document source updates cannot reuse stale cached fragments`(): List<DynamicTest> =
        (0 until GENERATED_CACHE_COUNT).flatMap { seed ->
            listOf(false, true).map { toolResult ->
                DynamicTest.dynamicTest("document cache source seed $seed tool result $toolResult") {
                    val builder = ResponsesRequestBuilder(standard)
                    val values = listOf("Aa", "BB", "Aa")
                    values.forEach { suffix ->
                        val literal = "document-$seed-$suffix"
                        val source = source("content", "content", JsonArray(listOf(blockText(literal))))
                        assertEquals(
                            listOf(text(literal)),
                            render(request(listOf(document(source)), toolResult), toolResult, builder = builder),
                        )
                    }
                }
            }
        }

    @TestFactory
    fun `generated mutable document sources cannot rewrite a retained cache input`(): List<DynamicTest> =
        (0 until GENERATED_CACHE_COUNT).flatMap { seed ->
            listOf(false, true).map { toolResult ->
                DynamicTest.dynamicTest("document cache ownership seed $seed tool result $toolResult") {
                    val blocks = mutableListOf<JsonElement>(blockText("document-$seed-Aa"))
                    val source = source("content", "content", JsonArray(blocks))
                    val raw = request(listOf(document(source)), toolResult)
                    val typed = lenientJson.decodeFromJsonElement<AnthropicRequest>(raw)
                    val builder = ResponsesRequestBuilder(standard)
                    assertEquals(
                        listOf(text("document-$seed-Aa")),
                        normalized(builder.build(typed, raw, options).req.getValue("input").jsonArray, toolResult),
                    )
                    // Aa and BB have the same hash. Cache equality must compare an owned snapshot, not this alias.
                    blocks[0] = blockText("document-$seed-BB")
                    assertEquals(
                        listOf(text("document-$seed-BB")),
                        normalized(builder.build(typed, raw, options).req.getValue("input").jsonArray, toolResult),
                    )
                }
            }
        }

    private fun render(
        raw: JsonObject,
        toolResult: Boolean,
        quirks: ResponsesQuirks = standard,
        builder: ResponsesRequestBuilder = ResponsesRequestBuilder(quirks),
    ): List<JsonObject> = normalized(JsonArray(build(raw, builder)), toolResult)

    private fun build(raw: JsonObject, builder: ResponsesRequestBuilder): List<JsonObject> {
        val parsed = AnthropicParse.parseAnthropicBody(JsonWire.string(raw))
        return builder.build(parsed.typed, parsed.raw, options).req.getValue("input").jsonArray.map { it.jsonObject }
    }

    private fun normalized(input: JsonArray, toolResult: Boolean): List<JsonObject> {
        if (toolResult) {
            val result = input.map { it.jsonObject }
                .single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            assertEquals("call-document", result.getValue("call_id").jsonPrimitive.content)
            assertFalse(result.containsKey("status"))
            return result.getValue("output").jsonArray.map { it.jsonObject }
        }
        return input.flatMap { item ->
            val content = item.jsonObject.getValue("content")
            if (content is JsonArray) content.map { it.jsonObject } else listOf(text(content.jsonPrimitive.content))
        }
    }

    private fun request(blocks: List<JsonObject>, toolResult: Boolean): JsonObject = buildJsonObject {
        put("model", "m")
        put(
            "messages",
            buildJsonArray {
                if (toolResult) {
                    add(
                        buildJsonObject {
                            put("role", "assistant")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", "call-document")
                                            put("name", "Read")
                                            put("input", buildJsonObject {})
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            if (toolResult) {
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_result")
                                            put("tool_use_id", "call-document")
                                            put("content", JsonArray(blocks))
                                        },
                                    )
                                }
                            } else {
                                JsonArray(blocks)
                            },
                        )
                    },
                )
            },
        )
    }

    private fun source(type: String, field: String, value: JsonElement, mime: String? = null): JsonObject =
        buildJsonObject {
            put("type", type)
            mime?.let { put("media_type", it) }
            put(field, value)
        }

    private fun document(source: JsonObject): JsonObject = buildJsonObject {
        put("type", "document")
        put("source", source)
    }

    private fun blockText(value: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    private fun blockImage(source: JsonObject): JsonObject = buildJsonObject {
        put("type", "image")
        put("source", source)
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "input_text")
        put("text", value)
    }

    private fun image(value: String): JsonObject = buildJsonObject {
        put("type", "input_image")
        put("image_url", value)
    }

    private fun file(field: String, value: String): JsonObject = buildJsonObject {
        put("type", "input_file")
        put(field, value)
    }

    private fun png(edge: Int): String = ByteArrayOutputStream().use { bytes ->
        ImageIO.write(BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB), "png", bytes)
        Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private data class DocumentCase(val source: JsonObject, val expected: List<JsonObject>)

    private val literals = listOf(
        "",
        " ",
        "null",
        """{"type":"image","source":{"type":"url","url":"literal"}}""",
        """[{"type":"text","text":"still literal"}]""",
        "quotes \" backslash \\ newline\n雪",
    )
    private val standard = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())
    private val options = BuildOptions(
        compact = false,
        models = ModelIds(original = "openai--gpt-4.1", upstream = "gpt-4.1"),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
        handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
    )
}

private const val GENERATED_DOCUMENT_COUNT = 12
private const val GENERATED_CACHE_COUNT = 4
private const val GENERATED_SEQUENCE_COUNT = 64
private const val GENERATED_SEQUENCE_WIDTH = 3
private const val BITS_PER_VARIANT = 2
private const val VARIANT_MASK = 3
private const val IMAGE_FLOOR = 8
private const val FLOOR_PLUS_ONE = IMAGE_FLOOR + 1
private const val FLOOR_REASON = "image edge below this backend's 8px minimum"
