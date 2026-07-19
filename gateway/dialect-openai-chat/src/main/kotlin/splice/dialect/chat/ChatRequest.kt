// NEW: the CLOSED typed Chat Completions request (#924 Phase 2, tier-1). Same rationale as
// ResponsesRequest: every fixed field the backend accepts is a NAMED property, so a knob that
// doesn't belong (stream_options on backends that 400 on it, logprobs, n, frequency_penalty, …)
// cannot be added without a field — a reviewable type change. The ONE field a fixed DTO can't name
// is max_tokens: its KEY is vendor-dynamic (max_tokens vs max_completion_tokens), so
// ChatRequestBuilder injects it by name after serializing this closed set. No `extras`; arbitrary
// client keys are forwarded only by PassthroughRequestBuilder, never through this closed dialect.
package splice.dialect.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: JsonArray,
    val stream: Boolean,
    val tools: JsonArray? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val reasoning: JsonObject? = null,
    /** Session-pinned server-side prompt cache (xAI honors it on /chat/completions: 135k tokens
     *  at 1.7-2.8s TTFB, 99.97% cached — probed 2026-07-19; same knob kimi-cli sends). */
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
    /** {"include_usage": true} — without it xAI's chat stream carries NO usage frame at all
     *  (the in_tokens=0 blindness of the 2026-07-18 chat-dialect attempt). */
    // ast-grep-ignore: kt-no-stream-options-request — include_usage needed on chat (probed 2026-07-19)
    @SerialName("stream_options") val streamOptions: JsonObject? = null,
)

// explicitNulls=false: null optionals (tools, reasoning_effort, reasoning) are omitted, exactly like
// the builder's conditional puts.
internal val chatRequestJson: Json = Json { explicitNulls = false }
