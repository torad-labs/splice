// NEW: (no Node source — the one genuinely-new translator): Anthropic Messages → OpenAI Chat
// Completions request. This dialect covers Ollama / OpenRouter / LM Studio / DeepSeek and any
// OpenAI-compatible endpoint (the "new vendor = pure TOML, zero Kotlin" goal). Differs from the
// Responses dialect: `messages` (role/content) not `input` items; tool_calls not function_call;
// max_tokens IS honored (unlike the ChatGPT backend); reasoning is a plain field where supported.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
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

@Suppress("StringLiteralDuplication") // chat wire field names are inherently repeated
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
                    put("role", "system")
                    put("content", sys)
                }
            }
            body.messages.forEach { msg -> appendMessage(this, msg.role, msg.content) }
        }
        val req = buildJsonObject {
            put("model", upstreamModel)
            put("messages", messages)
            put("stream", true)
            body.maxTokens?.takeIf { it > 0 }?.let { put(quirks.maxTokensField, it) }
            @Suppress("ComplexCondition")
            if (quirks.supportsTools && !compact && body.tools.isNotEmpty()) {
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
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod")
    private fun appendMessage(
        sink: kotlinx.serialization.json.JsonArrayBuilder,
        role: String,
        content: List<splice.core.wire.ContentBlock>,
    ) {
        // tool_result blocks become their own `tool` role messages; text/images fold into one.
        val toolResults = content.filterIsInstance<ToolResultBlock>()
        val toolUses = content.filterIsInstance<ToolUseBlock>()
        val texts = content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
        val images = content.filterIsInstance<ImageBlock>().mapNotNull { imagePart(it.source) }

        if (toolUses.isNotEmpty()) {
            sink.addJsonObject {
                put("role", "assistant")
                if (texts.isNotEmpty()) put("content", texts) else put("content", null as String?)
                put(
                    "tool_calls",
                    buildJsonArray {
                        toolUses.forEach { tu ->
                            addJsonObject {
                                put("id", tu.id)
                                put("type", "function")
                                putFunction(tu.name, tu.input.toString())
                            }
                        }
                    },
                )
            }
        } else if (texts.isNotEmpty() || images.isNotEmpty()) {
            sink.addJsonObject {
                put("role", role)
                if (images.isEmpty()) {
                    put("content", texts)
                } else {
                    put(
                        "content",
                        buildJsonArray {
                            if (texts.isNotEmpty()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", texts)
                                }
                            }
                            images.forEach { add(it) }
                        },
                    )
                }
            }
        }
        toolResults.forEach { tr ->
            val out = tr.content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
            sink.addJsonObject {
                put("role", "tool")
                put("tool_call_id", tr.toolUseId)
                put("content", out)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putFunction(name: String, args: String) {
        put(
            "function",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
        )
    }

    private fun toolsArray(body: AnthropicRequest) = buildJsonArray {
        body.tools.forEach { t ->
            addJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", t.name)
                        put("description", t.description ?: "")
                        put("parameters", t.inputSchema ?: buildJsonObject { put("type", "object") })
                    },
                )
            }
        }
    }

    private fun imagePart(source: MediaSource?): JsonObject? = when {
        source == null || !quirks.supportsVision -> null
        source.type == "base64" && !source.data.isNullOrEmpty() -> buildJsonObject {
            put("type", "image_url")
            val dataUrl = "data:${source.mediaType ?: "image/png"};base64,${source.data}"
            put("image_url", buildJsonObject { put("url", dataUrl) })
        }
        source.type == "url" && !source.url.isNullOrEmpty() -> buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", source.url) })
        }
        else -> null
    }
}
