// NEW: the TOML topology schema (shape proven by spike P0-TOML incl. @SerialName mapping;
// gateway/spikes/results/ktoml.md). Loaded once at daemon start by :app — adding a
// provider or head is an operator action and implies a restart (no hot topology).
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
     *  (starter: head `openrouter`, command `claudeor`) — to matching topology keys. A topology-KEY
     *  match is exact and wins as the sole result; otherwise ALL heads whose wrapper command equals
     *  the name (a misconfigured topology can share one command across several heads). */
    public fun resolveHeadKeys(name: String): List<String> {
        if (name in heads) return listOf(name)
        return heads.entries.filter { (key, head) -> (head.claude.command ?: key) == name }.map { it.key }
    }

    /** The single topology key for [name], or null when unknown OR ambiguous (several heads share
     *  the wrapper command). Callers that must tell those apart use [resolveHeadKeys]. */
    public fun resolveHeadKey(name: String): String? = resolveHeadKeys(name).singleOrNull()
}

/** Distinct-from-"unknown-head" message for the ambiguous case: [keys] heads all map to [command].
 *  Naming both heads points the operator at the topology collision instead of a phantom head. */
public fun ambiguousHeadMessage(command: String, keys: List<String>): String =
    "ambiguous head '$command' — heads ${keys.joinToString(" and ")} both use that command; fix the topology"

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
)

@Serializable
public data class ProviderConfig(
    val dialect: Dialect,
    @SerialName("base_url") val baseUrl: String,
    val auth: AuthConfig,
    val quirks: QuirksConfig = QuirksConfig(),
    val models: List<ModelEntry> = emptyList(),
    @SerialName("extra_windows") val extraWindows: List<ExtraWindow> = emptyList(),
    @SerialName("window_rules") val windowRules: List<WindowRule> = emptyList(),
    @SerialName("default_context_window") val defaultContextWindow: Long = 0,
)

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
)

/** The api-key env var a head actually reads: the explicit [AuthConfig.env], else the derived
 *  `<KEY>_API_KEY` default the daemon synthesizes. One source for daemon wiring AND the CLI so a
 *  head on the derived default never reads as "not signed in" while the daemon serves it fine. */
public fun effectiveApiKeyEnv(key: String, auth: AuthConfig): String =
    auth.env ?: "${key.uppercase()}_API_KEY"

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
    /** openai-chat only: emit the `reasoning`/`reasoning_effort` request fields (default true,
     *  matching OpenRouter/xAI). Set false for strict OpenAI-compatible vendors (e.g. Fireworks)
     *  that reject unknown request fields; the reasoning mirror still reads `reasoning_content`
     *  from the response. */
    @SerialName("emit_reasoning") val emitReasoning: Boolean = true,
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

public fun ProviderConfig.catalogFor(head: HeadConfig, contextWindowOverride: Long? = null): ModelCatalog {
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

/**
 * Flat knob map from topology TOML for ConfigService's headOverrides layer.
 * Order: free-form [defaults] first, then explicit [daemon] fields (win on conflict).
 * Values are strings because ConfigService coerces by KnobKind.
 */
public fun Topology.configOverrides(): Map<String, String> {
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
private fun Topology.putLegacyProviderOverrides(out: MutableMap<String, String>) {
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

/** Reasoning-continuation fold knobs, split out so [configOverrides] stays under the complexity cap.
 *  The comma-joined model list is what the STRING knob coerces (SpliceConfig splits it back). */
private fun DaemonConfig.putFoldOverrides(out: MutableMap<String, String>) {
    foldReasoningModels?.let { out["foldReasoningModels"] = it.joinToString(",") }
    foldMaxContinue?.let { out["foldMaxContinue"] = it.toString() }
    foldMarkerText?.let { out["foldMarkerText"] = it }
    foldMaxTier?.let { out["foldMaxTier"] = it.toString() }
}

private const val DEFAULT_WINDOW_FLOOR: Long = 200_000
