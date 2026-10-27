// NEW: the CLOSED typed Responses request (#924 Phase 2, tier-1). ResponsesRequestBuilder used to
// assemble the request as a hand-built JsonObject via put() — stream_options.include_usage was one
// stray put() away and nothing typed stopped it (the codex-breaking incident, 2026-07-18). Every
// field the Responses backend accepts is a NAMED property here; a Chat-only knob (logprobs, n,
// frequency_penalty, max_tokens, …) cannot be expressed without ADDING a field — a reviewable type
// change, not a mid-builder line. DELIBERATELY has no `extras` passthrough: arbitrary client keys
// are forwarded only by PassthroughRequestBuilder, never through this closed dialect.
package splice.dialect.responses.request

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable(with = ResponsesRequestCodec::class)
internal data class ResponsesRequest(
    val model: String,
    val input: JsonArray,
    val store: Boolean,
    val stream: Boolean,
    val include: List<String>? = null,
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
    @SerialName("prompt_cache_retention") val promptCacheRetention: String? = null,
    /** Nullable since responses-lite (2026-07-19): lite turns move the instructions text into a
     *  developer-role input item and OMIT this field, exactly as codex-rs serves the 5.6 family. */
    val instructions: String? = null,
    val tools: JsonArray? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    val reasoning: JsonObject? = null,
    /** Public output formats share this block with provider-selected verbosity. */
    val text: JsonObject? = null,
    /** Related serialization controls stay one construction input while their public wire fields
     *  remain explicit in [ResponsesRequestCodec]. This keeps the closed DTO narrow and reviewable. */
    internal val serializationControls: ResponsesRequestSerializationControls = ResponsesRequestSerializationControls(),
) {
    val clientMetadata: JsonObject?
        get() = serializationControls.clientMetadata

    val streamOptions: JsonObject?
        get() = serializationControls.streamOptions

    val maxOutputTokens: Long?
        get() = serializationControls.maxOutputTokens

    val temperature: JsonElement?
        get() = serializationControls.temperature

    val topP: JsonElement?
        get() = serializationControls.topP

    val serviceTier: String?
        get() = serializationControls.serviceTier
}

/** Values whose public Responses names are emitted together by [ResponsesRequestCodec]. */
internal data class ResponsesRequestSerializationControls(
    val clientMetadata: JsonObject? = null,
    val streamOptions: JsonObject? = null,
    val maxOutputTokens: Long? = null,
    val temperature: JsonElement? = null,
    val topP: JsonElement? = null,
    val serviceTier: String? = null,
)

/** The closed DTO keeps every legal wire field visible while related controls share one constructor input. */
internal object ResponsesRequestCodec : KSerializer<ResponsesRequest> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("splice.ResponsesRequest")

    override fun deserialize(decoder: Decoder): ResponsesRequest {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Responses requests require a JSON decoder")
        val raw = input.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Responses request must be an object")
        return RequestReader(raw).request()
    }

    override fun serialize(encoder: Encoder, value: ResponsesRequest) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("Responses requests require a JSON encoder")
        output.encodeJsonElement(requestJson(value))
    }

    private fun requestJson(request: ResponsesRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("input", request.input)
        put("store", request.store)
        put("stream", request.stream)
        writeContext(this, request)
        writeSurface(this, request)
        writeControls(this, request.serializationControls)
    }

    private fun writeContext(target: JsonObjectBuilder, request: ResponsesRequest) {
        request.include?.let { names -> target.put("include", JsonArray(names.map(::JsonPrimitive))) }
        request.promptCacheKey?.let { target.put("prompt_cache_key", it) }
        request.promptCacheRetention?.let { target.put("prompt_cache_retention", it) }
        request.instructions?.let { target.put("instructions", it) }
    }

    private fun writeSurface(target: JsonObjectBuilder, request: ResponsesRequest) {
        request.tools?.let { target.put("tools", it) }
        request.toolChoice?.let { target.put("tool_choice", it) }
        request.parallelToolCalls?.let { target.put("parallel_tool_calls", it) }
        request.reasoning?.let { target.put("reasoning", it) }
        request.text?.let { target.put("text", it) }
    }

    private fun writeControls(target: JsonObjectBuilder, controls: ResponsesRequestSerializationControls) {
        controls.clientMetadata?.let { target.put("client_metadata", it) }
        controls.streamOptions?.let { target.put("stream_options", it) }
        controls.maxOutputTokens?.let { target.put("max_output_tokens", it) }
        controls.temperature?.let { target.put("temperature", it) }
        controls.topP?.let { target.put("top_p", it) }
        controls.serviceTier?.let { target.put("service_tier", it) }
    }

    /** Reads the same closed fields without coupling decoding helpers to the wire writer. */
    private class RequestReader(private val raw: JsonObject) {
        fun request(): ResponsesRequest = ResponsesRequest(
            model = requiredString("model"),
            input = requiredArray("input"),
            store = requiredBoolean("store"),
            stream = requiredBoolean("stream"),
            include = stringList("include"),
            promptCacheKey = string("prompt_cache_key"),
            promptCacheRetention = string("prompt_cache_retention"),
            instructions = string("instructions"),
            tools = array("tools"),
            toolChoice = element("tool_choice"),
            parallelToolCalls = boolean("parallel_tool_calls"),
            reasoning = obj("reasoning"),
            text = obj("text"),
            serializationControls = ResponsesRequestSerializationControls(
                clientMetadata = obj("client_metadata"),
                streamOptions = obj("stream_options"),
                maxOutputTokens = long("max_output_tokens"),
                temperature = element("temperature"),
                topP = element("top_p"),
                serviceTier = string("service_tier"),
            ),
        )

        private fun requiredString(name: String): String =
            string(name) ?: throw SerializationException("Responses request needs string $name")

        private fun requiredArray(name: String): JsonArray =
            array(name) ?: throw SerializationException("Responses request needs array $name")

        private fun requiredBoolean(name: String): Boolean =
            boolean(name) ?: throw SerializationException("Responses request needs boolean $name")

        private fun stringList(name: String): List<String>? = array(name)?.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: throw SerializationException("Responses request $name must contain strings")
        }

        private fun string(name: String): String? =
            (element(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

        private fun array(name: String): JsonArray? = element(name) as? JsonArray

        private fun obj(name: String): JsonObject? = element(name) as? JsonObject

        private fun boolean(name: String): Boolean? {
            val value = element(name) as? JsonPrimitive ?: return null
            return value.takeUnless(JsonPrimitive::isString)?.content?.toBooleanStrictOrNull()
        }

        private fun long(name: String): Long? {
            val value = element(name) as? JsonPrimitive ?: return null
            return value.takeUnless(JsonPrimitive::isString)?.longOrNull
        }

        private fun element(name: String): JsonElement? = raw[name]?.takeUnless { it is JsonNull }
    }
}

// explicitNulls=false: a null optional field is OMITTED, exactly matching the builder's conditional
// put()s. Field order stays explicit in the codec, so the bytes remain identical
// (ResponsesRequestBuilderTest pins it).
internal val responsesRequestJson: Json = Json { explicitNulls = false }

internal const val ENCRYPTED_CONTENT_INCLUDE: String = "reasoning.encrypted_content"
