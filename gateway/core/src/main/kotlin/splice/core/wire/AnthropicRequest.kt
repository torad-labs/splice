// PORT-OF: the Anthropic Messages request shapes READ by server/src/codex/translate-request.mjs
// + grok/translate-request.mjs @ pre-public-port-baseline — invariants: content is string OR block list; system is
// string OR text-block list; tool_result content is string OR block list; tool input/input_schema
// stay opaque JsonObject; UNKNOWN block types must decode (never throw) so new client block kinds
// degrade gracefully; thinking.type disabled/disabled_thinking disables reasoning.
package splice.core.wire

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
public data class AnthropicRequest(
    val model: String = "",
    val messages: List<AnthropicMessage> = emptyList(),
    @Serializable(with = SystemTextSerializer::class)
    val system: String? = null,
    val tools: List<ToolDefinition> = emptyList(),
    @SerialName("tool_choice") val toolChoice: ToolChoice? = null,
    val thinking: ThinkingConfig? = null,
    @SerialName("max_tokens") val maxTokens: Long? = null,
    val stream: Boolean = false,
)

@Serializable
public data class AnthropicMessage(
    val role: String,
    @Serializable(with = ContentSerializer::class)
    val content: List<ContentBlock> = emptyList(),
)

@Serializable(with = ContentBlockSerializer::class)
public sealed class ContentBlock

@Serializable
public data class TextBlock(val text: String = "") : ContentBlock()

@Serializable
public data class ImageBlock(val source: MediaSource? = null) : ContentBlock()

@Serializable
public data class DocumentBlock(val source: MediaSource? = null) : ContentBlock()

@Serializable
public data class ThinkingBlock(val thinking: String = "") : ContentBlock()

@Serializable
public data class RedactedThinkingBlock(val data: String = "") : ContentBlock()

@Serializable
public data class ToolUseBlock(
    val id: String = "",
    val name: String = "",
    val input: JsonObject = JsonObject(emptyMap()),
) : ContentBlock()

@Serializable
public data class ToolResultBlock(
    @SerialName("tool_use_id") val toolUseId: String = "",
    @Serializable(with = ContentSerializer::class)
    val content: List<ContentBlock> = emptyList(),
) : ContentBlock()

/** Unknown block kinds decode losslessly instead of throwing (forward compatibility). */
@Serializable(with = UnknownBlockSerializer::class)
public data class UnknownBlock(val raw: JsonObject) : ContentBlock() {
    public val type: String get() = raw["type"]?.let { (it as? JsonPrimitive)?.content } ?: ""
}

@Serializable
public data class MediaSource(
    val type: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    val data: String? = null,
    val url: String? = null,
)

@Serializable
public data class ToolDefinition(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject? = null,
    val strict: Boolean? = null,
)

@Serializable
public data class ToolChoice(
    val type: String = "auto",
    val name: String? = null,
    @SerialName("disable_parallel_tool_use") val disableParallelToolUse: Boolean? = null,
)

@Serializable
public data class ThinkingConfig(
    val type: String = "",
    @SerialName("budget_tokens") val budgetTokens: Long? = null,
) {
    public val disabled: Boolean get() = type == "disabled" || type == "disabled_thinking"
}

/** `content` accepts a bare string or a block list; a bare string becomes one TextBlock. */
public object ContentSerializer : KSerializer<List<ContentBlock>> {
    private val listSerializer = ListSerializer(ContentBlockSerializer)
    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun deserialize(decoder: Decoder): List<ContentBlock> {
        val input = decoder as JsonDecoder
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> listOf(TextBlock(element.content))
            else -> input.json.decodeFromJsonElement(listSerializer, element)
        }
    }

    override fun serialize(encoder: Encoder, value: List<ContentBlock>) {
        listSerializer.serialize(encoder, value)
    }
}

/** `system` accepts a bare string or [{type:"text",text}] blocks; joins text blocks. */
public object SystemTextSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("splice.SystemText")

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as JsonDecoder
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> element.jsonObjectListTexts()
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        error("system text is read-only on the ingress side")
    }

    private fun JsonElement.jsonObjectListTexts(): String? {
        val arr = this as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.content == "text") obj["text"]?.jsonPrimitive?.content else null
        }.joinToString("\n")
    }
}

public object ContentBlockSerializer : JsonContentPolymorphicSerializer<ContentBlock>(ContentBlock::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ContentBlock> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "text" -> TextBlock.serializer()
            "image" -> ImageBlock.serializer()
            "document" -> DocumentBlock.serializer()
            "thinking" -> ThinkingBlock.serializer()
            "redacted_thinking" -> RedactedThinkingBlock.serializer()
            "tool_use" -> ToolUseBlock.serializer()
            "tool_result" -> ToolResultBlock.serializer()
            else -> UnknownBlockSerializer
        }
}

public object UnknownBlockSerializer : KSerializer<UnknownBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("splice.UnknownBlock")

    override fun deserialize(decoder: Decoder): UnknownBlock {
        val input = decoder as JsonDecoder
        return UnknownBlock(input.decodeJsonElement().jsonObject)
    }

    override fun serialize(encoder: Encoder, value: UnknownBlock) {
        (encoder as JsonEncoder).encodeJsonElement(value.raw)
    }
}
