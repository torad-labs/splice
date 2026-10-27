package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.RequestFeature
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesServiceTierSupport
import splice.upstream.RequestRefusal

/** The raw request controls AnthropicRequest intentionally does not model but Responses can carry. */
internal data class ResponsesWireRequestControls(
    val temperature: JsonElement? = null,
    val topP: JsonElement? = null,
    val serviceTier: String? = null,
)

private val sourceOnlyControls = mapOf(
    SOURCE_REQUEST_CONTAINER to RequestFeature.CONTAINER,
    SOURCE_REQUEST_DIAGNOSTICS to RequestFeature.DIAGNOSTICS,
    SOURCE_REQUEST_INFERENCE_GEO to RequestFeature.INFERENCE_GEO,
    SOURCE_REQUEST_USER_PROFILE_ID to RequestFeature.USER_PROFILE_ID,
    SOURCE_REQUEST_WORKSPACE_ID to RequestFeature.WORKSPACE_ID,
    // Responses has no native top-k or stop-sequence controls.
    SOURCE_REQUEST_TOP_K to RequestFeature.TOP_K,
    SOURCE_REQUEST_STOP_SEQUENCES to RequestFeature.STOP_SEQUENCES,
)

/** Admission and wire projection stay together so an unrepresentable raw control is never dropped. */
internal class ResponsesRequestControls(private val quirks: ResponsesQuirks) {

    fun refusal(raw: JsonObject): RequestRefusal? = refusedFeature(raw)?.let { feature ->
        RequestRefusal(FailureCause.Splice.Declined(Decline.Untranslatable(feature)))
    }

    fun wire(raw: JsonObject): ResponsesWireRequestControls = ResponsesWireRequestControls(
        temperature = value(raw, SOURCE_REQUEST_TEMPERATURE),
        topP = value(raw, SOURCE_REQUEST_TOP_P),
        serviceTier = serviceTier(raw),
    )

    private fun refusedFeature(raw: JsonObject): RequestFeature? =
        sourceOnlyControls.entries.firstOrNull { unsupportedSourceControl(raw, it.key) }?.value ?: refusedPolicy(raw)

    private fun unsupportedSourceControl(raw: JsonObject, key: String): Boolean {
        val control = value(raw, key) ?: return false
        return key != SOURCE_REQUEST_STOP_SEQUENCES || (control as? JsonArray)?.isEmpty() != true
    }

    private fun refusedPolicy(raw: JsonObject): RequestFeature? = when {
        !ResponsesCacheHints.accepts(value(raw, SOURCE_REQUEST_CACHE_CONTROL)) -> RequestFeature.CACHE_CONTROL
        JsonScalars.long(raw, SOURCE_REQUEST_MAX_TOKENS) == 0L -> RequestFeature.CACHE_ONLY_WARMUP
        value(raw, SOURCE_REQUEST_SERVICE_TIER) != null && serviceTier(raw) == null -> RequestFeature.SERVICE_TIER
        // Anthropic speed controls output throughput. Responses service_tier is a processing tier,
        // so its similarly named fast mode is not an equivalent control.
        value(raw, SOURCE_REQUEST_SPEED) != null -> RequestFeature.SPEED
        unsupportedReasoningContext(raw) -> RequestFeature.REASONING_CONTEXT
        // Source metadata remains opaque caller annotation. It does not become a Responses resource
        // metadata field, splice session identity, or a prompt-cache key.
        else -> null
    }

    private fun serviceTier(raw: JsonObject): String? = when (quirks.request.serviceTier) {
        ResponsesServiceTierSupport.OPENAI -> when (JsonScalars.str(value(raw, SOURCE_REQUEST_SERVICE_TIER))) {
            "auto" -> "auto"
            "standard_only" -> "default"
            else -> null
        }
        ResponsesServiceTierSupport.UNSUPPORTED -> null
    }

    private fun unsupportedReasoningContext(raw: JsonObject): Boolean {
        val reasoning = raw[SOURCE_REQUEST_REASONING] as? JsonObject ?: return false
        val context = value(reasoning, SOURCE_REQUEST_CONTEXT) ?: return false
        return JsonScalars.str(context) !in SUPPORTED_CONTEXTS
    }

    private fun value(raw: JsonObject, key: String): JsonElement? =
        raw[key]?.takeUnless { it is JsonNull }
}

// These are inbound Anthropic request fields, not fields authored by a Responses wire serializer.
private const val SOURCE_REQUEST_TEMPERATURE = "temperature"
private const val SOURCE_REQUEST_TOP_P = "top_p"
private const val SOURCE_REQUEST_TOP_K = "top_k"
private const val SOURCE_REQUEST_STOP_SEQUENCES = "stop_sequences"
private const val SOURCE_REQUEST_SERVICE_TIER = "service_tier"
private const val SOURCE_REQUEST_SPEED = "speed"
private const val SOURCE_REQUEST_CONTAINER = "container"
private const val SOURCE_REQUEST_DIAGNOSTICS = "diagnostics"
private const val SOURCE_REQUEST_INFERENCE_GEO = "inference_geo"
private const val SOURCE_REQUEST_USER_PROFILE_ID = "user_profile_id"
private const val SOURCE_REQUEST_WORKSPACE_ID = "workspace_id"
private const val SOURCE_REQUEST_CACHE_CONTROL = "cache_control"
private const val SOURCE_REQUEST_MAX_TOKENS = "max_tokens"
private const val SOURCE_REQUEST_REASONING = "reasoning"
private const val SOURCE_REQUEST_CONTEXT = "context"
private val SUPPORTED_CONTEXTS = setOf("auto", "current_turn", "all_turns")
