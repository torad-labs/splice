// NEW: the TOML topology schema (shape proven by spike P0-TOML incl. @SerialName mapping;
// gateway/spikes/results/ktoml.md). Loaded once at daemon start by :app — adding a
// provider or head is an operator action and implies a restart (no hot topology).
// 2026-08-16 (HD-M8): the file's top-level functions were relocated without changing any body.
// The extensions on types THIS file owns became members of those types, so `provider.catalogFor(...)`
// and `topology.configOverrides()` read exactly as before; the three operator-facing diagnostics
// became members of TopologyMessages; effectiveApiKeyEnv became a member of AuthConfig, which is
// the type it interrogates.
// 2026-08-17 (HD-20): the last MEMBER extension here — `DaemonConfig.putFoldOverrides`, declared
// inside Topology — moved down onto DaemonConfig itself, the type it always read. Same body, same
// name, byte-identical call site.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.model.ExtraWindow
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule

@Serializable
public data class Topology(
    val daemon: DaemonConfig = DaemonConfig(),
    val claude: ClaudeSharingDefaults = ClaudeSharingDefaults(),
    val defaults: Map<String, String> = emptyMap(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
    val heads: Map<String, HeadConfig> = emptyMap(),
) {
    /** Resolve a user-supplied head name — the topology key or the installed wrapper command
     *  (starter: head `openrouter`, command `claude-openrouter`) — to matching topology keys. A topology-KEY
     *  match is exact and wins as the sole result; otherwise ALL heads whose wrapper command equals
     *  the name (a misconfigured topology can share one command across several heads). */
    public fun resolveHeadKeys(name: String): List<String> {
        if (name in heads) return listOf(name)
        return heads.entries.filter { (key, head) -> (head.claude.command ?: key) == name }.map { it.key }
    }

    /** The single topology key for [name], or null when unknown OR ambiguous (several heads share
     *  the wrapper command). Callers that must tell those apart use [resolveHeadKeys]. */
    public fun resolveHeadKey(name: String): String? = resolveHeadKeys(name).singleOrNull()

    /** JW-13: ports mapped to the >1 heads that share them — the port analogue of the
     *  wrapper-command collision install already validates. A copy-pasted [heads.X] with an
     *  unchanged port otherwise surfaces only as an opaque per-head "Address already in use". */
    public fun portCollisions(): Map<Int, List<String>> =
        heads.entries.groupBy({ it.value.port }, { it.key }).filterValues { it.size > 1 }

    /** CTL-005: heads whose port is outside the valid TCP range — 0, negative, or > 65535 all
     *  parse fine as an Int and otherwise surface only at bind time, as an opaque error that
     *  never names the offending [heads.X] entry. Same idiom as [portCollisions]. */
    public fun invalidPortHeads(): Map<String, Int> =
        heads.filterValues { it.port !in VALID_PORT_RANGE }.mapValues { it.value.port }

    /**
     * Flat knob map from topology TOML for ConfigService's headOverrides layer.
     * Order: free-form [defaults] first, then explicit [daemon] fields (win on conflict).
     * Values are strings because ConfigService coerces by KnobKind.
     */
    public fun configOverrides(): Map<String, String> {
        val out = LinkedHashMap(defaults)
        daemon.controlPort?.let { out["controlPort"] = it.toString() }
        daemon.showReasoning?.let { out["showReasoning"] = it }
        daemon.summary?.let { out["summary"] = it }
        daemon.effort?.let { out["effort"] = it }
        daemon.replayReasoning?.let { out["replayReasoning"] = it.toString() }
        daemon.mirrorReasoning?.let { out["mirrorReasoning"] = it.toString() }
        daemon.putFoldOverrides(out)
        putLegacyProviderOverrides(out)
        return out
    }

    /**
     * The management API retains the original codex/grok knob names. Seed those knobs from TOML so
     * their effective values describe the topology, then let state/env/runtime override them through
     * ConfigService's normal precedence.
     */
    private fun putLegacyProviderOverrides(out: MutableMap<String, String>) {
        val codex = heads.entries.firstOrNull { (_, head) ->
            providers[head.provider]?.auth?.kind == "chatgpt-oauth"
        }
        codex?.let { (_, head) ->
            val provider = providers.getValue(head.provider)
            out["port"] = head.port.toString()
            out["pinnedModel"] = head.pinnedModel
            out["chatgptApiBase"] = provider.baseUrl
            provider.auth.file?.let { out["codexAuthPath"] = it }
        }

        val grok = heads.entries.firstOrNull { (key, head) ->
            providers[head.provider]?.auth?.kind == "grok-oauth" || key.contains("grok", ignoreCase = true)
        }
        grok?.let { (_, head) ->
            val provider = providers.getValue(head.provider)
            out["grokPort"] = head.port.toString()
            out["grokModel"] = head.pinnedModel
            out["xaiApiBase"] = provider.baseUrl
            provider.auth.file?.let { out["grokAuthPath"] = it }
        }
    }
}

private const val MIN_TCP_PORT = 1
private const val MAX_TCP_PORT = 65535
private val VALID_PORT_RANGE = MIN_TCP_PORT..MAX_TCP_PORT

/** The operator-facing topology diagnostics — pure text over values the caller already holds, which
 *  is why they are a named object rather than members of [Topology]: every call site has the port,
 *  the key and the head list in hand but not always the topology (HD-M8, migration pattern 5). */
public object TopologyMessages {

    /** Names both heads and the port so the operator sees the collision, not a phantom bind error. */
    public fun portCollisionMessage(port: Int, keys: List<String>): String =
        "port $port is claimed by ${keys.joinToString(" and ")} — give each head its own port"

    /** Names the head and its out-of-range port so the operator sees the config problem, not a
     *  phantom bind error (CTL-005). */
    public fun invalidPortMessage(key: String, port: Int): String =
        "head '$key' has an invalid port $port (must be $VALID_PORT_RANGE) — fix [heads.$key] port in splice.toml"

    /** Distinct-from-"unknown-head" message for the ambiguous case: [keys] heads all map to [command].
     *  Naming both heads points the operator at the topology collision instead of a phantom head. */
    public fun ambiguousHeadMessage(command: String, keys: List<String>): String =
        "ambiguous head '$command' — heads ${keys.joinToString(" and ")} both use that command; fix the topology"
}

@Serializable
public data class DaemonConfig(
    // Nullable: an ABSENT control_port defers to env/state/knob default. A non-null default here
    // made SPLICE_CONTROL_PORT dead and let /api/config report a port nothing listens on
    // (audit 2026-07-18).
    @SerialName("control_port") val controlPort: Int? = null,
    @SerialName("state_dir") val stateDir: String? = null,
    // Reasoning display — edit these in ~/.config/splice/splice.toml (no code change).
    // Precedence: env > state config.json > [daemon] / [defaults] TOML > knob defaults.
    @SerialName("show_reasoning") val showReasoning: String? = null,
    val summary: String? = null,
    val effort: String? = null,
    @SerialName("replay_reasoning") val replayReasoning: Boolean? = null,
    @SerialName("mirror_reasoning") val mirrorReasoning: Boolean? = null,
    // Reasoning-continuation folding (codex 518n-2). A TOML array of upstream model ids that truncate
    // their chain-of-thought (default luna/terra/5.5); the caps + marker text tune the loop.
    @SerialName("fold_reasoning_models") val foldReasoningModels: List<String>? = null,
    @SerialName("fold_max_continue") val foldMaxContinue: Int? = null,
    @SerialName("fold_marker_text") val foldMarkerText: String? = null,
    @SerialName("fold_max_tier") val foldMaxTier: Int? = null,
) {
    /** Reasoning-continuation fold knobs, split out so [Topology.configOverrides] stays under the
     *  complexity cap. The comma-joined model list is what the STRING knob coerces (SpliceConfig
     *  splits it back). Was `private fun DaemonConfig.putFoldOverrides` inside [Topology] until HD-20
     *  banned extension declarations; it is now a MEMBER of the type it always read, so
     *  [Topology.configOverrides]'s `daemon.putFoldOverrides(out)` call site is byte-identical. It is
     *  `internal` rather than `private` only because [Topology] — a different class — is the caller. */
    internal fun putFoldOverrides(out: MutableMap<String, String>) {
        foldReasoningModels?.let { out["foldReasoningModels"] = it.joinToString(",") }
        foldMaxContinue?.let { out["foldMaxContinue"] = it.toString() }
        foldMarkerText?.let { out["foldMarkerText"] = it }
        foldMaxTier?.let { out["foldMaxTier"] = it.toString() }
    }
}

@Serializable
public data class ProviderConfig(
    val dialect: Dialect,
    @SerialName("base_url") val baseUrl: String,
    val auth: AuthConfig,
    val quirks: QuirksConfig = QuirksConfig(),
    /** Static vendor headers every upstream request carries (e.g. `anthropic-version`). Operator-
     *  owned and pure TOML, so an anthropic-compatible vendor needs no provider code at all. On a
     *  head that forwards the client's own headers, a forwarded value WINS over these defaults. */
    @SerialName("extra_headers") val extraHeaders: Map<String, String> = emptyMap(),
    val models: List<ModelEntry> = emptyList(),
    @SerialName("extra_windows") val extraWindows: List<ExtraWindow> = emptyList(),
    @SerialName("window_rules") val windowRules: List<WindowRule> = emptyList(),
    @SerialName("default_context_window") val defaultContextWindow: Long = 0,
) {
    /**
     * [extraHeaders] with TOML key quoting removed — THE accessor every consumer must use.
     *
     * `extra_headers = { "anthropic-version" = "..." }` is valid TOML and the natural thing to
     * write (a header name contains a dash), but ktoml hands back the key WITH its quote
     * characters, which would put a literally malformed name on the wire. Bare keys parse clean;
     * both forms must behave identically, so the quotes are stripped here rather than in each
     * consumer. Header names never legitimately contain a double quote.
     */
    public val staticHeaders: Map<String, String>
        get() = extraHeaders.mapKeys { (key, _) -> key.trim('"') }

    public fun catalogFor(head: HeadConfig, contextWindowOverride: Long? = null): ModelCatalog {
        val override = contextWindowOverride?.takeIf { it > 0 }
        return ModelCatalog(
            discoveryPrefix = head.discoveryPrefix,
            models = models.map { model ->
                if (override == null) model else model.copy(contextWindow = override)
            },
            extraWindows = extraWindows.map { extra ->
                if (override == null) extra else extra.copy(contextWindow = override)
            },
            windowRules = windowRules.map { rule ->
                if (override == null) rule else rule.copy(contextWindow = override)
            },
            defaultContextWindow = if (override != null) {
                override
            } else if (defaultContextWindow > 0) {
                defaultContextWindow
            } else {
                models.firstOrNull()?.contextWindow ?: DEFAULT_WINDOW_FLOOR
            },
        )
    }
}

@Serializable
public enum class Dialect {
    @SerialName("openai-responses")
    OPENAI_RESPONSES,

    @SerialName("openai-chat")
    OPENAI_CHAT,

    @SerialName("anthropic-passthrough")
    ANTHROPIC_PASSTHROUGH,
}

@Serializable
public data class AuthConfig(
    val kind: String,
    val file: String? = null,
    val env: String? = null,
) {
    /** The api-key env var a head actually reads: the explicit [env], else the derived
     *  `<KEY>_API_KEY` default the daemon synthesizes. One source for daemon wiring AND the CLI so a
     *  head on the derived default never reads as "not signed in" while the daemon serves it fine. */
    public fun effectiveApiKeyEnv(key: String): String = env ?: "${key.uppercase()}_API_KEY"
}

/** The finite quirk surface of the openai dialects — everything a vendor varies without code. */
@Serializable
public data class QuirksConfig(
    val store: Boolean = false,
    @SerialName("account_id_header") val accountIdHeader: Boolean = false,
    @SerialName("cache_key") val cacheKey: String = "first-message-hash",
    @SerialName("effort_ceiling") val effortCeiling: String = "max",
    @SerialName("summary_field") val summaryField: Boolean = true,
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
    /** zstd-compress upstream request bodies (CX-03). NULLABLE overlay — absent keeps the
     *  provider default (false: plaintext). Proven ONLY for ChatGPT, by codex-cli 0.145.0 itself
     *  (content-encoding: zstd, 2.7x measured); xAI 400d on a compressed body 2026-07-18, so this
     *  is opt-in per provider and never a global default. */
    @SerialName("zstd_request_body") val zstdRequestBody: Boolean? = null,
    /** openai-chat only: emit reasoning_effort/reasoning fields (DeepSeek/xAI/OpenRouter-style
     *  backends read them). null keeps the provider's own default (true); set false for strict
     *  OpenAI-compatible vendors (Fireworks — issue #21) that 400 on unrecognized fields. */
    @SerialName("reasoning_effort") val reasoningEffort: Boolean? = null,
    /** openai-responses only: the deferred tool surface (tool_search) for responses-lite turns.
     *  ABSENT TABLE = feature off — the reasoning_cache nullable-overlay idiom (Topology.kt:109-113). */
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
)

/** openai-responses only: the deferred tool surface (tool_search) for responses-lite turns.
 *  ABSENT TABLE = feature off — the reasoning_cache nullable-overlay idiom (Topology.kt:109-113). */
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

@Serializable
public data class HeadConfig(
    val provider: String,
    val port: Int,
    @SerialName("discovery_prefix") val discoveryPrefix: String,
    @SerialName("pinned_model") val pinnedModel: String,
    val overrides: Map<String, String> = emptyMap(),
    val claude: ClaudeWrapperConfig = ClaudeWrapperConfig(),
)

/** Per-head Claude Code wrapper policy: command name, config dir, share/isolate per item. */
@Serializable
public data class ClaudeWrapperConfig(
    val command: String? = null,
    @SerialName("config_dir") val configDir: String? = null,
    val isolate: List<String> = emptyList(),
)

@Serializable
public data class ClaudeSharingDefaults(
    val share: List<String> = listOf(
        "settings",
        "mcps",
        "skills",
        "hooks",
        "agents",
        "commands",
        "plugins",
        "claude_md",
    ),
)

private const val DEFAULT_WINDOW_FLOOR: Long = 200_000
