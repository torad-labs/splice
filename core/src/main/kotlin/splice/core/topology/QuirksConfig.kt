// PORT-OF: splice/core/topology/Topology.kt (QuirksConfig, ToolSurfaceConfig) @ a941c17 —
// invariants unchanged: every declaration, every default and every @SerialName byte-for-byte, so
// this is still one `[providers.X.quirks]` table decoded by the same Toml call and reached through
// ProviderConfig.quirks. Split out 2026-08-18 (HD-25).
//
// WHY IT IS NOT PART OF THE TOPOLOGY GRAPH: Topology.kt never READS a quirk. The only occurrences
// there were the `quirks` field declaration and these two type declarations; the schema invariants
// that keep the rest of that file together (HeadConfig.provider being a key into Topology.providers,
// catalogFor joining provider models with the head's discoveryPrefix) touch nothing here. The real
// consumer is app/provider/QuirksOverlay.kt, one module up.
//
// WHY IT STILL LIVES IN :core.topology rather than a dialect module: the semantic owner of a quirk
// is arguably the dialect that honours it, but the module direction law makes that impossible —
// :core depends on nothing (splice.module-law.gradle.kts), so a type :core's own ProviderConfig
// holds as a field cannot be moved into :dialect-*. Package placement here is a floor, not a
// preference.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The finite quirk surface of the openai dialects — everything a vendor varies without code. */
@Serializable
public data class QuirksConfig(
    val store: Boolean = false,
    @SerialName("account_id_header") val accountIdHeader: Boolean = false,
    @SerialName("cache_key") val cacheKey: String = "first-message-hash",
    @SerialName("effort_ceiling") val effortCeiling: String = "max",
    @SerialName("summary_field") val summaryField: Boolean = true,
    /** RETIRED 2026-09-05 (operator law): a compaction is built exactly like a turn and inherits
     *  the session's model and effort — any pin moves the reasoning off the session's and the
     *  backend's prompt cache misses the whole transcript on the most expensive turn class there
     *  is. The key is still parsed so a config that sets it fails LOUDLY at load (init below)
     *  instead of being ignored in silence. */
    @SerialName("compact_effort") val compactEffort: String? = null,
    @SerialName("tool_choice") val toolChoice: Boolean = false,
    /** openai-responses only: the gateway-held reasoning cache for tool round-trips (RC-5,
     *  2026-07-24). NULLABLE like every overlay knob — absent keeps the provider's own default
     *  (codex: on; grok: off — xai returns no envelopes), so the overlay can't stomp it. False
     *  restores the pre-cache behavior (per-tool-result amnesia). */
    @SerialName("reasoning_cache") val reasoningCache: Boolean? = null,
    /** openai-responses only: the VALUE sent for parallel_tool_calls on responses-lite turns (the
     *  field always rides; a lite request without it 400s). NULLABLE overlay like reasoning_cache —
     *  absent keeps the provider's own default (false), so it can't stomp a provider default the
     *  way the non-nullable summary_field above does. true lets the model batch tool calls into one
     *  turn instead of one per turn; UNTESTED against the live backend, see ResponsesQuirks. */
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    /** openai-responses only: serve rounds over the Responses WebSocket with previous_response_id
     *  chaining (ws-transport). NULLABLE overlay — absent keeps the provider default (false), so
     *  the feature is invisible until an operator opts in. Any failure degrades to the SSE path. */
    @SerialName("websocket") val webSocket: Boolean? = null,
    /** ChatGPT responses only: splice-owned JavaScript bridge. Absent = on for that shape (0.4.0). */
    @SerialName("code_mode") val codeMode: Boolean? = null,
    /** code-mode only: the JAR-bundled GraalJS worker pool size (JvmCodeModeRuntime maxWorkers).
     *  NULLABLE overlay — absent keeps the code default (4). */
    @SerialName("code_mode_workers") val codeModeWorkers: Int? = null,
    /** code-mode only: how long one worker exchange may wait for the child's answer, in milliseconds
     *  (JvmCodeModeRuntime advanceTimeoutMs). NULLABLE overlay — absent keeps the code default (5000). */
    @SerialName("code_mode_timeout_ms") val codeModeTimeoutMs: Long? = null,
    /** code-mode only: each child JVM's heap, in MB (the -Xmx). NULLABLE overlay — absent keeps the
     *  code default (128). */
    @SerialName("code_mode_heap") val codeModeHeapMb: Int? = null,
    /** zstd-compress upstream request bodies (CX-03). NULLABLE overlay — absent keeps the
     *  provider default (false: plaintext). Proven ONLY for ChatGPT, by codex-cli 0.145.0 itself
     *  (content-encoding: zstd, 2.7x measured); xAI 400d on a compressed body 2026-07-18, so this
     *  is opt-in per provider and never a global default. */
    @SerialName("zstd_request_body") val zstdRequestBody: Boolean? = null,
    /** openai-chat only: emit reasoning_effort/reasoning fields (DeepSeek/xAI/OpenRouter-style
     *  backends read them). null keeps the provider's own default (true); set false for strict
     *  OpenAI-compatible vendors (Fireworks — issue #21) that 400 on unrecognized fields. */
    @SerialName("reasoning_effort") val reasoningEffort: Boolean? = null,
    /** openai-chat only: send stream_options.include_usage, so the stream ends with a usage frame.
     *  null keeps the provider's own default — ON for a LOCAL runtime (V4-163) and for grok-oauth,
     *  OFF for every other vendor, whose tolerance for extra fields is unknown. Without the frame a
     *  head reports zero tokens per turn, Claude Code never reaches its auto-compact threshold, and
     *  the session runs into the context wall instead. Set false for a local runtime that refuses
     *  the field, true for a hosted vendor that accepts it. */
    @SerialName("stream_usage") val streamUsage: Boolean? = null,
    /** openai-chat only, for a llama-server runtime: keep each conversation on one server slot by
     *  sending id_slot (V4-165). llama-server picks slots by prompt similarity and skips empty ones,
     *  so a new session sharing the ~30K Claude Code preamble takes an idle conversation's slot and
     *  that conversation re-prefills from zero. null/false = off (id_slot is llama.cpp's own field;
     *  other runtimes have no slots to pin). */
    @SerialName("slot_affinity") val slotAffinity: Boolean? = null,
    /** openai-responses only: the deferred tool surface (tool_search) for responses-lite turns.
     *  ABSENT TABLE = feature off — the nullable-overlay idiom of [reasoningCache] above. */
    @SerialName("tool_surface") val toolSurface: ToolSurfaceConfig? = null,
    // ── anthropic-passthrough only ────────────────────────────────────────────────────────────
    // The dialect forwards the client's bytes faithfully by DEFAULT; each knob below opts into one
    // vendor deformation (campaign claude-head). NULLABLE like every overlay knob here — absent
    // keeps the head's BASE profile (neutral for a plain api-key/client head, Kimi's deformation
    // set for a kimi-oauth head), so a splice.toml written before these existed keeps working.
    // Kimi's example entry declares them explicitly anyway, as documentation.
    /** Rewrite tool `input_schema` into Moonshot-Flavored JSON Schema (and drop `strict` / invent an
     *  empty `description`). Leave OFF for an upstream that accepts full JSON Schema: the sanitizer
     *  discards `format`, `prefixItems`, `$ref` siblings and tuple `items`, changing tool semantics. */
    @SerialName("mfjs") val mfjs: Boolean? = null,
    /** Content-block types the upstream accepts; every other block is DROPPED. ABSENT = every block
     *  rides. Kimi's list comes from its own 400 and excludes `redacted_thinking`, which a client
     *  round-trips — dropping it corrupts the thinking chain. */
    @SerialName("block_allowlist") val blockAllowlist: List<String>? = null,
    /** Deep-strip every `cache_control` marker. Against an upstream WITH prompt caching this is a
     *  silent cold read on every turn (no error, just cost), so it stays opt-in. */
    @SerialName("strip_cache_control") val stripCacheControl: Boolean? = null,
    /** Synthesize ONE thinking-block signature when the upstream sent none. Required for Kimi (never
     *  signs; Claude Code discards unsigned thinking blocks), WRONG for an upstream that verifies. */
    @SerialName("synthesize_signatures") val synthesizeSignatures: Boolean? = null,
    /** Remap Anthropic thinking config into Kimi's adaptive-thinking + output_config.effort ladder.
     *  OFF forwards the client's `thinking` verbatim and leaves `output_config` to the client. */
    @SerialName("map_thinking_adaptive") val mapThinkingAdaptive: Boolean? = null,
    /** Drop temperature/top_p/top_k when a live probe shows the endpoint rejects them. */
    @SerialName("strip_sampling_params") val stripSamplingParams: Boolean? = null,
    /** V4-41: may a mid-stream re-anchor resume this upstream by APPENDING the salvaged answer as a
     *  trailing assistant message? A VENDOR FACT, so it is measured per provider and never assumed:
     *  deepseek and kimi continue from such a prefill (probed 2026-09-16), while muse 400s on it
     *  outright — "assistant prefill is not supported by this server" — which would turn a retryable
     *  overloaded_error into an invalid_request_error the client will NOT retry. ABSENT = off, so an
     *  unmeasured vendor keeps exactly today's behaviour (the whole-stream restart still applies when
     *  nothing visible was salvaged) and measurement is what earns the upgrade. */
    @SerialName("reanchor_prefill") val reanchorPrefill: Boolean? = null,
    /** anthropic-passthrough only: cap a forwarded tool NAME at this many characters, shortening
     *  deterministically via ToolNameShortener. MUSE ONLY — api.meta.ai rejects a tool name over 64
     *  characters where Anthropic accepts it (V4-32), and Claude Code's MCP names run past 80.
     *  NULLABLE overlay — absent keeps the head's BASE profile (muse's built-in 64; 0 = no cap for
     *  every other passthrough head), so the muse cap is overridable rather than hardcoded. */
    @SerialName("tool_name_cap") val toolNameCap: Int? = null,
) {
    init {
        require(compactEffort == null) {
            "[providers.*.quirks] compact_effort = '$compactEffort' is retired: a compaction is built " +
                "exactly like a turn and inherits the session's model and effort (any pin misses the " +
                "prompt cache on the whole transcript); remove the key"
        }
    }
}

/** openai-responses only: the deferred tool surface (tool_search) for responses-lite turns.
 *  ABSENT TABLE = feature off — the nullable-overlay idiom of [QuirksConfig.reasoningCache]. */
@Serializable
public data class ToolSurfaceConfig(
    val enabled: Boolean = true,
    @SerialName("defer_prefixes") val deferPrefixes: List<String> = listOf("mcp__"),
    val defer: List<String> = emptyList(),
    val eager: List<String> = emptyList(),
    @SerialName("min_deferred") val minDeferred: Int = 8,
    @SerialName("search_limit") val searchLimit: Int = 8,
    @SerialName("search_rounds") val searchRounds: Int = 3,
)
