package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import splice.core.parse.AnthropicParse
import splice.core.parse.lenientJson
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonWire
import splice.core.wire.AnthropicRequest
import splice.core.wire.DocumentBlock
import splice.core.wire.MessageFragmentInputs
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

/**
 * A document's title and context remain literal, ordered text before its body, never a filename or MIME guess.
 * Anthropic cache breakpoints and citation configuration stay source-side. Enabled citations remain visible
 * for the request-admission refusal; a foreign uploaded file is explicitly unavailable, never an OpenAI ID.
 */
class ResponsesDocumentMetadataTest {
    @TestFactory
    fun `generated titles and contexts stay adjacent to every public document body`(): List<DynamicTest> =
        titles.flatMapIndexed { seed, title ->
            val context = contexts[seed % contexts.size]
            val bodies = documentBodies(seed)
            bodies.flatMapIndexed { form, (source, expected) ->
                listOf(false, true).map { toolResult ->
                    DynamicTest.dynamicTest("metadata seed $seed source $form tool result $toolResult") {
                        val document = document(source, title, context)
                        assertEquals(
                            listOf(text("before")) + metadata(title, context) + expected + text("after"),
                            render(listOf(blockText("before"), document, blockText("after")), toolResult),
                        )
                    }
                }
            }
        }

    private fun documentBodies(seed: Int): List<Pair<JsonObject, List<JsonObject>>> = listOf(
        source("text", "data", JsonPrimitive("body-$seed"), "text/plain") to listOf(text("body-$seed")),
        source("content", "content", JsonPrimitive("""{"literal":$seed}""")) to
            listOf(text("""{"literal":$seed}""")),
        source(
            "content",
            "content",
            JsonArray(
                listOf(
                    obj("type" to JsonPrimitive("text"), "text" to JsonPrimitive("first-$seed")),
                    obj(
                        "type" to JsonPrimitive("image"),
                        "source" to source("url", "url", JsonPrimitive("https://media.example/$seed.png")),
                    ),
                    obj("type" to JsonPrimitive("text"), "text" to JsonPrimitive("last-$seed")),
                ),
            ),
        ) to listOf(
            text("first-$seed"),
            obj(
                "type" to JsonPrimitive("input_image"),
                "image_url" to JsonPrimitive("https://media.example/$seed.png"),
            ),
            text("last-$seed"),
        ),
        source("base64", "data", JsonPrimitive("JVBERi0x-$seed"), "application/pdf") to listOf(
            obj(
                "type" to JsonPrimitive("input_file"),
                "file_data" to JsonPrimitive("data:application/pdf;base64,JVBERi0x-$seed"),
            ),
        ),
        source("url", "url", JsonPrimitive("https://media.example/$seed.pdf")) to listOf(
            obj(
                "type" to JsonPrimitive("input_file"),
                "file_url" to JsonPrimitive("https://media.example/$seed.pdf"),
            ),
        ),
    )

    @TestFactory
    fun `provider file exceptions retain the document title and context in position`(): List<DynamicTest> =
        titles.flatMapIndexed { seed, title ->
            listOf(false, true).map { toolResult ->
                DynamicTest.dynamicTest("fileless metadata seed $seed tool result $toolResult") {
                    val context = contexts[seed % contexts.size]
                    val source = source("base64", "data", JsonPrimitive("JVBERi0x"), "application/pdf")
                    val reason = if (toolResult) "input_file unsupported by this backend" else "application/pdf"
                    val marker = documentMarker("fileless", toolResult, reason)
                    assertEquals(
                        listOf(text("before")) + metadata(title, context) + text(marker) + text("after"),
                        render(
                            listOf(blockText("before"), document(source, title, context), blockText("after")),
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

    @TestFactory
    fun `text only tool results retain metadata and mark only unavailable document media`(): List<DynamicTest> =
        titles.mapIndexed { seed, title ->
            DynamicTest.dynamicTest("legacy metadata seed $seed") {
                val context = contexts[seed % contexts.size]
                val source = source("base64", "data", JsonPrimitive("JVBERi0x"), "application/pdf")
                val expected = listOfNotNull(
                    "before",
                    title,
                    context,
                    documentMarker("legacy", true, "structured tool output unsupported"),
                    "after",
                ).joinToString("\n")
                assertEquals(
                    listOf(text(expected)),
                    render(
                        listOf(blockText("before"), document(source, title, context), blockText("after")),
                        true,
                        standard.copy(
                            providerTag = "legacy",
                            request = standard.request.copy(supportsStructuredToolOutput = false),
                        ),
                    ),
                )
            }
        }

    @TestFactory
    fun `foreign uploaded file references never become destination file IDs`(): List<DynamicTest> =
        titles.flatMapIndexed { seed, title ->
            listOf(false, true).map { toolResult ->
                DynamicTest.dynamicTest("foreign file seed $seed tool result $toolResult") {
                    val context = contexts[seed % contexts.size]
                    val source = source("file", "file_id", JsonPrimitive("file_anthropic_$seed"))
                    assertEquals(
                        metadata(title, context) + text(documentMarker("openai", toolResult, FOREIGN_FILE_REASON)),
                        render(listOf(document(source, title, context)), toolResult),
                    )
                }
            }
        }

    @TestFactory
    fun `title and context changes cannot hit a fragment cached under a colliding hash`(): List<DynamicTest> =
        listOf("title", "context").flatMap { field ->
            listOf(false, true).map { toolResult ->
                DynamicTest.dynamicTest("metadata cache $field tool result $toolResult") {
                    val builder = ResponsesRequestBuilder(standard)
                    listOf("Aa", "BB", "Aa").forEach { value ->
                        val title = if (field == "title") value else "title"
                        val context = if (field == "context") value else "context"
                        val source = source("text", "data", JsonPrimitive("body"), "text/plain")
                        assertEquals(
                            metadata(title, context) + text("body"),
                            render(listOf(document(source, title, context)), toolResult, builder = builder),
                        )
                    }
                }
            }
        }

    @TestFactory
    fun `cache hints and citation configuration stay source side without invented fields`(): List<DynamicTest> =
        listOf(false, true).flatMap { enabled ->
            listOf("5m", "1h").map { ttl ->
                DynamicTest.dynamicTest("source annotations citations $enabled ttl $ttl") {
                    val hints = obj("type" to JsonPrimitive("ephemeral"), "ttl" to JsonPrimitive(ttl))
                    val citations = obj("enabled" to JsonPrimitive(enabled))
                    val document = JsonObject(
                        document(
                            source("text", "data", JsonPrimitive("body"), "text/plain"),
                            "title",
                            "context",
                        ) + mapOf("citations" to citations, "cache_control" to hints),
                    )
                    val typed = AnthropicParse.parseAnthropicBody(
                        JsonWire.string(request(listOf(document), false)),
                    ).typed
                    val actual = typed.messages.single().content.single() as DocumentBlock
                    val retained = lenientJson.encodeToJsonElement(DocumentBlock.serializer(), actual).jsonObject
                    assertEquals(document["title"], retained["title"])
                    assertEquals(document["context"], retained["context"])
                    assertEquals(
                        citations,
                        retained["citations"],
                        "enabled citations must remain visible for admission",
                    )
                    assertEquals(hints, retained["cache_control"])
                    if (!enabled) {
                        assertEquals(
                            listOf(text("title"), text("context"), text("body")),
                            render(listOf(document), false),
                        )
                    }
                }
            }
        }

    @TestFactory
    fun `retained document metadata is owned and contributes to the fragment memory budget`(): List<DynamicTest> =
        listOf("citations", "cache_control").map { field ->
            DynamicTest.dynamicTest("document annotation ownership $field") {
                val backing = linkedMapOf<String, JsonElement>(
                    if (field == "citations") "enabled" to JsonPrimitive(false) else "ttl" to JsonPrimitive("5m"),
                )
                if (field == "cache_control") backing["type"] = JsonPrimitive("ephemeral")
                val source = source("text", "data", JsonPrimitive("body"), "text/plain")
                val document = JsonObject(document(source, "title", "context") + (field to JsonObject(backing)))
                val raw = request(listOf(document), false)
                val decoded = lenientJson.decodeFromJsonElement<AnthropicRequest>(raw).messages.single()
                val block = decoded.content.single() as DocumentBlock
                // Model a Kotlin caller retaining an actual annotation alias, independently of decoder ownership.
                val live = if (field == "citations") {
                    block.copy(citations = JsonObject(backing))
                } else {
                    block.copy(cacheControl = JsonObject(backing))
                }
                val typed = decoded.copy(content = listOf(live))
                val snapshot = MessageFragmentInputs.freeze(typed)
                val original = AnthropicParse.parseAnthropicBody(JsonWire.string(raw)).typed.messages.single()
                val plain = AnthropicParse.parseAnthropicBody(
                    JsonWire.string(
                        request(listOf(document(source, null, null)), false),
                    ),
                ).typed.messages.single()
                assertTrue(MessageFragmentInputs.bytes(snapshot) > MessageFragmentInputs.bytes(plain))
                backing[if (field == "citations") "enabled" else "ttl"] =
                    if (field == "citations") JsonPrimitive(true) else JsonPrimitive("1h")
                assertFalse(
                    MessageFragmentInputs.same(snapshot, typed),
                    "mutable source metadata cannot rewrite a cache entry",
                )
                assertTrue(MessageFragmentInputs.same(snapshot, original))
            }
        }

    private fun render(
        blocks: List<JsonObject>,
        toolResult: Boolean,
        quirks: ResponsesQuirks = standard,
        builder: ResponsesRequestBuilder = ResponsesRequestBuilder(quirks),
    ): List<JsonObject> {
        val parsed = AnthropicParse.parseAnthropicBody(JsonWire.string(request(blocks, toolResult)))
        val items = builder.build(parsed.typed, parsed.raw, options).req.getValue("input").jsonArray
        if (toolResult) {
            val result = items.map { it.jsonObject }
                .single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            val output = result.getValue("output")
            return if (output is JsonArray) output.map { it.jsonObject } else listOf(text(output.jsonPrimitive.content))
        }
        return items.flatMap {
            val content = it.jsonObject.getValue("content")
            if (content is JsonArray) {
                content.map { part -> part.jsonObject }
            } else {
                listOf(text(content.jsonPrimitive.content))
            }
        }
    }

    private fun request(blocks: List<JsonObject>, toolResult: Boolean): JsonObject {
        val userContent = if (toolResult) {
            listOf(
                obj(
                    "type" to JsonPrimitive("tool_result"),
                    "tool_use_id" to JsonPrimitive("call-document"),
                    "content" to JsonArray(blocks),
                ),
            )
        } else {
            blocks
        }
        val user = obj("role" to JsonPrimitive("user"), "content" to JsonArray(userContent))
        val messages = if (toolResult) {
            listOf(
                obj(
                    "role" to JsonPrimitive("assistant"),
                    "content" to JsonArray(
                        listOf(
                            obj(
                                "type" to JsonPrimitive("tool_use"),
                                "id" to JsonPrimitive("call-document"),
                                "name" to JsonPrimitive("Read"),
                                "input" to obj(),
                            ),
                        ),
                    ),
                ),
                user,
            )
        } else {
            listOf(user)
        }
        return obj("model" to JsonPrimitive("m"), "messages" to JsonArray(messages))
    }

    private fun document(source: JsonObject, title: String?, context: String?): JsonObject = buildJsonObject {
        put("type", "document")
        put("source", source)
        title?.let { put("title", it) }
        context?.let { put("context", it) }
    }

    private fun source(
        type: String,
        field: String,
        value: JsonElement,
        mime: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put(field, value)
        mime?.let { put("media_type", it) }
    }

    private fun metadata(title: String?, context: String?): List<JsonObject> = listOfNotNull(title, context).map(::text)
    private fun blockText(value: String): JsonObject =
        obj("type" to JsonPrimitive("text"), "text" to JsonPrimitive(value))
    private fun text(value: String): JsonObject =
        obj("type" to JsonPrimitive("input_text"), "text" to JsonPrimitive(value))
    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*fields))
    private fun documentMarker(provider: String, toolResult: Boolean, reason: String): String {
        val subject = if (toolResult) "document from tool_result call-document" else "document"
        return "[$subject omitted by $provider proxy: $reason]"
    }

    private val titles = listOf(null, "", "report", "../not-a-file.exe", """{"type":"image"}""", "雪.pdf.png")
    private val contexts = listOf(
        null,
        "",
        "context\nwith a newline",
        """[{"type":"text","text":"literal"}]""",
        "雪",
        "context",
    )
    private val standard = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())
    private val options = BuildOptions(
        compact = false,
        models = ModelIds(original = "openai--gpt-4.1", upstream = "gpt-4.1"),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
        handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
    )
}

private const val FOREIGN_FILE_REASON = "Anthropic file references are not available on this backend"
