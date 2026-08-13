// NEW: the CLOSED typed Responses request (#924 Phase 2, tier-1). ResponsesRequestBuilder used to
// assemble the request as a hand-built JsonObject via put() — stream_options.include_usage was one
// stray put() away and nothing typed stopped it (the codex-breaking incident, 2026-07-18). Every
// field the Responses backend accepts is a NAMED property here; a Chat-only knob (stream_options,
// logprobs, n, frequency_penalty, max_tokens, …) cannot be expressed without ADDING a field — a
// reviewable type change, not a mid-builder line. DELIBERATELY has no `extras` passthrough: arbitrary
// client keys are forwarded only by PassthroughRequestBuilder, never through this closed dialect.
package splice.dialect.responses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ResponsesRequest(
    val model: String,
    val input: JsonArray,
    val store: Boolean,
    val stream: Boolean,
    val include: List<String>? = null,
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
    /** Nullable since responses-lite (2026-07-19): lite turns move the instructions text into a
     *  developer-role input item and OMIT this field, exactly as codex-rs serves the 5.6 family. */
    val instructions: String? = null,
    val tools: JsonArray? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    val reasoning: JsonObject? = null,
    /** codex parity, measured from codex-cli 0.145.0 (2026-08-11): the real client sends
     *  `text: {"verbosity": ...}`. Lite-only, which is where it was observed. */
    val text: JsonObject? = null,
    /** codex sends client_metadata with ITS identifiers (installation/window/thread/turn). We send
     *  SPLICE's own — never impersonate the codex CLI: the backend may meter or route on these,
     *  and claiming to be a client we are not is both dishonest and fragile. */
    @SerialName("client_metadata") val clientMetadata: JsonObject? = null,
    /** Responses-native summary delivery (codex-rs sends it whenever a summary is requested);
     *  NOT the Chat-only stream_options.include_usage that broke codex 2026-07-18 — this named
     *  field is exactly the reviewable type change the closed DTO exists to force. */
    @SerialName("stream_options") val streamOptions: JsonObject? = null,
)

// explicitNulls=false: a null optional field is OMITTED, exactly matching the builder's conditional
// put()s. Field order = declaration order = the original put() order, so the bytes are identical
// (ResponsesRequestBuilderTest pins it).
internal val responsesRequestJson: Json = Json { explicitNulls = false }

internal const val ENCRYPTED_CONTENT_INCLUDE: String = "reasoning.encrypted_content"
