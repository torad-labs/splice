// NEW: (no Node source — the one genuinely-new translator): Anthropic Messages → OpenAI Chat
// Completions request. This dialect covers Ollama / OpenRouter / LM Studio / DeepSeek and any
// OpenAI-compatible endpoint (the "new vendor = pure TOML, zero Kotlin" goal). Differs from the
// Responses dialect: `messages` (role/content) not `input` items; tool_calls not function_call;
// max_tokens IS honored (unlike the ChatGPT backend); reasoning is a plain field where supported.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicRequest
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.TextBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock

public data class ChatQuirks(
    val providerTag: String,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = true,
    /** Some vendors want `max_completion_tokens`, most want `max_tokens`. */
    val maxTokensField: String = "max_tokens",
    /**
     * When true, emit `reasoning_effort` (and/or `reasoning`) from Anthropic thinking budget so
     * DeepSeek/xAI-compatible chat backends return `reasoning_content` in the stream.
     */
    val emitReasoningEffort: Boolean = true,
)

public data class BuiltChatRequest(val req: JsonObject, val meta: TurnMeta)

public class ChatRequestBuilder(private val quirks: ChatQuirks) {

    public fun build(
        body: AnthropicRequest,
        upstreamModel: String,
        originalModel: String,
        compact: Boolean,
    ): BuiltChatRequest {
        val messages = buildJsonArray {
            body.system?.let { sys ->
                addJsonObject {
                    put(ROLE, "system")
                    put(CONTENT, sys)
                }
            }
            body.messages.forEach { msg -> appendMessage(this, msg.role, msg.content) }
        }
        val emitTools = quirks.supportsTools && !compact && body.tools.isNotEmpty()
        val effort = chatReasoningEffort(body, compact)
        // TIER-1 (#924): the request is a CLOSED ChatRequest DTO (see chatRequestObject) — a knob
        // that doesn't belong can't be added without a field.
        val req = chatRequestObject(upstreamModel, messages, emitTools, effort, body)
        val meta = TurnMeta(
            compact = compact,
            showReasoning = "text",
            stream = body.stream,
            originalModel = originalModel,
            upstreamModel = upstreamModel,
            clientMaxTokens = body.maxTokens?.takeIf { it > 0 },
            effort = effort ?: "n/a",
            summary = if (effort != null) "detailed" else null,
            budgetTokens = body.thinking?.budgetTokens,
        )
        return BuiltChatRequest(req, meta)
    }

    // The CLOSED request object: the fixed fields via the ChatRequest DTO, plus max_tokens injected
    // by its vendor-dynamic key (max_tokens vs max_completion_tokens) — the one field a fixed DTO
    // can't name. Extracted so build() stays under the complexity gate.
    private fun chatRequestObject(
        upstreamModel: String,
        messages: JsonArray,
        emitTools: Boolean,
        effort: String?,
        body: AnthropicRequest,
    ): JsonObject {
        val dto = ChatRequest(
            model = upstreamModel,
            messages = messages,
            stream = true,
            tools = if (emitTools) toolsArray(body) else null,
            reasoningEffort = if (quirks.emitReasoningEffort) effort else null,
            reasoning = if (quirks.emitReasoningEffort && effort != null) {
                buildJsonObject { put("effort", effort) }
            } else {
                null
            },
        )
        val fields = (chatRequestJson.encodeToJsonElement(ChatRequest.serializer(), dto) as JsonObject).toMutableMap()
        body.maxTokens?.takeIf { it > 0 }?.let { fields[quirks.maxTokensField] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    // the content-block split is the mapping contract
    private fun appendMessage(
        sink: JsonArrayBuilder,
        role: String,
        content: List<splice.core.wire.ContentBlock>,
    ) {
        // tool_result blocks become their own `tool` role messages; text/images fold into one.
        val toolResults = content.filterIsInstance<ToolResultBlock>()
        val toolUses = content.filterIsInstance<ToolUseBlock>()
        // Dropped media leaves an HONEST MARKER (the v25 doctrine: screenshots silently
        // vanishing is the regression class; the model must know something was omitted).
        val markers = omissionMarkers(content)
        val textsRaw = content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
        val texts = (listOf(textsRaw) + markers).filter { it.isNotEmpty() }.joinToString("\n")
        val images = content.filterIsInstance<ImageBlock>().mapNotNull { imagePart(it.source) }

        if (toolUses.isNotEmpty()) {
            appendAssistantToolCalls(sink, toolUses, texts)
        }
        // A `tool` message must immediately follow the assistant message that carries its
        // tool_call_ids. Claude Code packs [tool_result, text] into one user message, so emit the
        // tool results BEFORE any sibling user text/images — an interposed `user` message is a 400
        // on strict OpenAI-compatible validators (and reorders the turn semantically everywhere).
        appendToolResults(sink, toolResults)
        val hasUserPayload = texts.isNotEmpty() || images.isNotEmpty()
        if (toolUses.isEmpty() && hasUserPayload) {
            appendUserContent(sink, role, texts, images)
        }
    }

    /** Honest markers for content this dialect cannot carry: documents always; images when the
     *  vendor has no vision. An image-only message still yields a marker — silently dropping the
     *  whole message breaks role alternation AND hides the omission from the model. */
    private fun omissionMarkers(content: List<splice.core.wire.ContentBlock>): List<String> {
        val out = mutableListOf<String>()
        content.filterIsInstance<DocumentBlock>().forEach { _ ->
            out.add("[document omitted by ${quirks.providerTag} proxy: unsupported on this backend]")
        }
        if (!quirks.supportsVision) {
            val n = content.count { it is ImageBlock }
            if (n > 0) out.add("[$n image(s) omitted by ${quirks.providerTag} proxy: backend has no vision]")
        }
        return out
    }

    private fun appendAssistantToolCalls(
        sink: JsonArrayBuilder,
        toolUses: List<ToolUseBlock>,
        texts: String,
    ) {
        sink.addJsonObject {
            put(ROLE, "assistant")
            if (texts.isNotEmpty()) put(CONTENT, texts) else put(CONTENT, null as String?)
            put(
                "tool_calls",
                buildJsonArray {
                    toolUses.forEach { tu ->
                        addJsonObject {
                            put("id", tu.id)
                            put(TYPE, FUNCTION)
                            putFunction(tu.name, tu.input.toString())
                        }
                    }
                },
            )
        }
    }

    private fun appendUserContent(
        sink: JsonArrayBuilder,
        role: String,
        texts: String,
        images: List<JsonObject>,
    ) {
        sink.addJsonObject {
            put(ROLE, role)
            if (images.isEmpty()) {
                put(CONTENT, texts)
            } else {
                put(
                    CONTENT,
                    buildJsonArray {
                        if (texts.isNotEmpty()) {
                            addJsonObject {
                                put(TYPE, TEXT)
                                put(TEXT, texts)
                            }
                        }
                        images.forEach { add(it) }
                    },
                )
            }
        }
    }

    private fun appendToolResults(
        sink: JsonArrayBuilder,
        toolResults: List<ToolResultBlock>,
    ) {
        toolResults.forEach { tr ->
            val out = tr.content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
            val images = tr.content.filterIsInstance<ImageBlock>().mapNotNull { imagePart(it.source) }
            val imageCount = tr.content.count { it is ImageBlock }
            sink.addJsonObject {
                put(ROLE, "tool")
                put("tool_call_id", tr.toolUseId)
                // string-only channel: dropped images (no vision) are declared IN the output.
                put(CONTENT, if (imageCount > 0 && images.isEmpty()) markerFold(out, imageCount) else out)
            }
            // v25 doctrine: chat `tool` messages are string-only, so tool_result images ride a
            // follow-up user message right after the tool output (same as the Responses builder).
            if (images.isNotEmpty()) {
                appendUserContent(sink, "user", "[images from tool_result ${tr.toolUseId}]", images)
            }
        }
    }

    private fun markerFold(out: String, imageCount: Int): String =
        (listOf(out) + "[$imageCount image(s) omitted by ${quirks.providerTag} proxy: backend has no vision]")
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun JsonObjectBuilder.putFunction(name: String, args: String) {
        put(
            FUNCTION,
            buildJsonObject {
                put(NAME, name)
                put("arguments", args)
            },
        )
    }

    private fun toolsArray(body: AnthropicRequest) = buildJsonArray {
        body.tools.forEach { t ->
            addJsonObject {
                put(TYPE, FUNCTION)
                put(
                    FUNCTION,
                    buildJsonObject {
                        put(NAME, t.name)
                        put("description", t.description ?: "")
                        put("parameters", t.inputSchema ?: buildJsonObject { put(TYPE, "object") })
                    },
                )
            }
        }
    }

    private fun imagePart(source: MediaSource?): JsonObject? = when {
        source == null || !quirks.supportsVision -> null
        source.type == "base64" && !source.data.isNullOrEmpty() -> {
            val mime = source.mediaType ?: "image/png"
            val data = source.data!!
            val dataUrl = StringBuilder(DATA_URL_PREFIX.length + mime.length + BASE64_SEPARATOR.length + data.length)
                .append(DATA_URL_PREFIX).append(mime).append(BASE64_SEPARATOR).append(data)
                .toString()
            buildJsonObject {
                put(TYPE, IMAGE_URL)
                put(IMAGE_URL, buildJsonObject { put(URL, dataUrl) })
            }
        }
        source.type == "url" && !source.url.isNullOrEmpty() -> buildJsonObject {
            put(TYPE, IMAGE_URL)
            put(IMAGE_URL, buildJsonObject { put(URL, source.url) })
        }
        else -> null
    }

    private companion object {
        // Chat wire field names — repeated across the message/tool/image mappings.
        const val ROLE = "role"
        const val CONTENT = "content"
        const val TYPE = "type"
        const val NAME = "name"
        const val TEXT = "text"
        const val URL = "url"
        const val FUNCTION = "function"
        const val IMAGE_URL = "image_url"
        const val DATA_URL_PREFIX = "data:"
        const val BASE64_SEPARATOR = ";base64,"
    }
}

/**
 * Map Anthropic thinking budget → chat `reasoning_effort`. Default high so backends that
 * support cleartext CoT actually emit `reasoning_content`. Compact turns stay low-cost.
 */
private fun chatReasoningEffort(body: AnthropicRequest, compact: Boolean): String? {
    if (body.thinking?.disabled == true) return null
    if (compact) return "low"
    val budget = body.thinking?.budgetTokens
    return when {
        budget == null -> "high"
        budget >= HIGH_BUDGET_FLOOR -> "high"
        budget >= MEDIUM_BUDGET_FLOOR -> "medium"
        budget > 0L -> "low"
        else -> "high"
    }
}

// /effort picker budget_tokens -> chat reasoning_effort tier floors
private const val HIGH_BUDGET_FLOOR = 32_000L
private const val MEDIUM_BUDGET_FLOOR = 8_000L
