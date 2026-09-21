// PORT-OF: the union-decoding behaviour of server/src/codex/translate-request.mjs +
// grok/translate-request.mjs @ pre-public-port-baseline — invariants: content is string OR block
// list; system is string OR text-block list; tool_result content is string OR block list; UNKNOWN
// block types must decode (never throw) so new client block kinds degrade gracefully.
// Split out of AnthropicRequest.kt (HD-25, 2026-08-18) unchanged: that file is the shape catalogue,
// these four objects are the only declarations in it that carry algorithm. Same package, so no
// consumer gained an import — the shapes they decode into are declared beside them.
package splice.core.wire

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `content` accepts a bare string or a block list; a bare string becomes one TextBlock. */
public object ContentSerializer : KSerializer<List<ContentBlock>> {
    private val listSerializer = ListSerializer(ContentBlockSerializer)
    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun deserialize(decoder: Decoder): List<ContentBlock> {
        val input = decoder as JsonDecoder
        return when (val element = input.decodeJsonElement()) {
            // SCH-005: JsonNull IS a JsonPrimitive (content == "null") — `content: null` must read
            // as no content, not as the literal text "null" landing in the transcript.
            is JsonNull -> emptyList()
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
            else -> jsonObjectListTexts(element)
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        error("system text is read-only on the ingress side")
    }

    private fun jsonObjectListTexts(element: JsonElement): String? {
        val arr = element as? JsonArray ?: return null
        // Byte-preserving concatenation with NO separator — Anthropic's own multi-block system
        // behavior joins text blocks back-to-back (verified 2026-07-23); a delimiter here invents a
        // character the client never sent and can break cache-control prefixes. Callers that want a
        // boundary put it in the block text (the fixtures carry their own trailing spaces).
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.content != "text") return@mapNotNull null
            // SCH-004: JsonNull IS a JsonPrimitive (content == "null") — a null "text" field must
            // read as absent, not as the literal word "null" injected into the system prompt.
            (obj["text"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        }.joinToString("")
    }
}

public object ContentBlockSerializer : JsonContentPolymorphicSerializer<ContentBlock>(ContentBlock::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ContentBlock> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "text" -> ContentBlock.TextBlock.serializer()
            "image" -> ContentBlock.ImageBlock.serializer()
            "document" -> ContentBlock.DocumentBlock.serializer()
            "thinking" -> ContentBlock.ThinkingBlock.serializer()
            "redacted_thinking" -> ContentBlock.RedactedThinkingBlock.serializer()
            "tool_use" -> ContentBlock.ToolUseBlock.serializer()
            "tool_result" -> ContentBlock.ToolResultBlock.serializer()
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
