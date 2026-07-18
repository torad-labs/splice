// NEW: (no Node source — the one genuinely-new translator): Anthropic Messages → OpenAI Chat
// Completions request. This dialect covers Ollama / OpenRouter / LM Studio / DeepSeek and any
// OpenAI-compatible endpoint (the "new vendor = pure TOML, zero Kotlin" goal). Differs from the
// Responses dialect: `messages` (role/content) not `input` items; tool_calls not function_call;
// max_tokens IS honored (unlike the ChatGPT backend); reasoning is a plain field where supported.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.TurnMeta
import splice.core.wire.AnthropicRequest
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
        val req = buildJsonObject {
            put("model", upstreamModel)
            put("messages", messages)
            put("stream", true)
            body.maxTokens?.takeIf { it > 0 }?.let { put(quirks.maxTokensField, it) }
            if (emitTools) {
                put("tools", toolsArray(body))
            }
        }
        val meta = TurnMeta(
            compact = compact,
            showReasoning = "text",
            stream = body.stream,
            originalModel = originalModel,
            upstreamModel = upstreamModel,
            clientMaxTokens = body.maxTokens?.takeIf { it > 0 },
            effort = "n/a",
            summary = null,
            budgetTokens = body.thinking?.budgetTokens,
        )
        return BuiltChatRequest(req, meta)
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
        val texts = content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
        val images = content.filterIsInstance<ImageBlock>().mapNotNull { imagePart(it.source) }

        if (toolUses.isNotEmpty()) {
            appendAssistantToolCalls(sink, toolUses, texts)
        } else if (texts.isNotEmpty() || images.isNotEmpty()) {
            appendUserContent(sink, role, texts, images)
        }
        appendToolResults(sink, toolResults)
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
            sink.addJsonObject {
                put(ROLE, "tool")
                put("tool_call_id", tr.toolUseId)
                put(CONTENT, out)
            }
        }
    }

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
        source.type == "base64" && !source.data.isNullOrEmpty() -> buildJsonObject {
            put(TYPE, IMAGE_URL)
            val dataUrl = "data:${source.mediaType ?: "image/png"};base64,${source.data}"
            put(IMAGE_URL, buildJsonObject { put(URL, dataUrl) })
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
    }
}
