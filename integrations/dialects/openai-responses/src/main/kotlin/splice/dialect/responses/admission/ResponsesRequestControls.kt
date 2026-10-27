package splice.dialect.responses.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.RequestFeature
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesServiceTierSupport
import splice.dialect.responses.request.ResponsesCacheHints
import splice.upstream.RequestRefusal
import java.security.MessageDigest
import java.util.HexFormat

/** The raw request controls AnthropicRequest intentionally does not model but Responses can carry. */
internal data class ResponsesWireRequestControls(
    val temperature: JsonElement? = null,
    val topP: JsonElement? = null,
    val serviceTier: String? = null,
    val safetyIdentifier: String? = null,
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
        safetyIdentifier = safetyIdentifier(raw),
    )

    private fun safetyIdentifier(raw: JsonObject): String? {
        if (!quirks.request.emitSafetyIdentifier) return null
        val sourceId = ((raw["metadata"] as? JsonObject)?.get("user_id") as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)?.content ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(personOf(sourceId).toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    /** The part of Claude Code's user_id that names the person: its account, else its device. Its session id changes
     *  every session, so it never enters the digest; an id in any other shape is taken whole. */
    private fun personOf(sourceId: String): String {
        val parts = LEGACY_USER_ID.matchEntire(sourceId)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: envelopeOf(sourceId)
        return parts?.let { (device, account) -> account.ifEmpty { device } }?.ifEmpty { null } ?: sourceId
    }

    /** The device and account of Claude Code's JSON user_id, or null when [sourceId] is not one. */
    private fun envelopeOf(sourceId: String): Pair<String, String>? {
        if (!sourceId.startsWith("{") || sourceId.length > MAX_ENVELOPE_CHARS) return null
        val envelope = Cancellables.runCatchingBestEffort {
            JsonWire.requireRequestNesting(sourceId)
            Json.parseToJsonElement(sourceId)
        }.getOrNull() as? JsonObject ?: return null
        return JsonScalars.str(envelope["device_id"]).orEmpty() to JsonScalars.str(envelope["account_uuid"]).orEmpty()
    }

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
        // Only explicit source user attribution is projected, separately from resource metadata.
        // Other caller annotation becomes neither splice session identity nor a prompt-cache key.
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

// Claude Code's user_id before its JSON envelope: user_<device digest>_account_<account uuid, or empty>_session_<uuid>.
private val LEGACY_USER_ID = Regex("user_([0-9a-f]+)_account_([0-9a-f-]*)_session_[0-9a-f-]+")

// why: Claude Code's envelope is three ids, far under this; an id past it is hashed whole.
private const val MAX_ENVELOPE_CHARS = 4_096
