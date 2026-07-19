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
)

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

public fun ProviderConfig.catalogFor(head: HeadConfig): ModelCatalog =
    ModelCatalog(
        discoveryPrefix = head.discoveryPrefix,
        models = models,
        extraWindows = extraWindows,
        windowRules = windowRules,
        defaultContextWindow = if (defaultContextWindow > 0) {
            defaultContextWindow
        } else {
            models.firstOrNull()?.contextWindow ?: DEFAULT_WINDOW_FLOOR
        },
    )

/**
 * Flat knob map from topology TOML for ConfigService's headOverrides layer.
 * Order: free-form [defaults] first, then explicit [daemon] fields (win on conflict).
 * Values are strings because ConfigService coerces by KnobKind.
 */
public fun Topology.configOverrides(): Map<String, String> {
    val out = LinkedHashMap(defaults)
    daemon.showReasoning?.let { out["showReasoning"] = it }
    daemon.summary?.let { out["summary"] = it }
    daemon.effort?.let { out["effort"] = it }
    daemon.replayReasoning?.let { out["replayReasoning"] = it.toString() }
    daemon.mirrorReasoning?.let { out["mirrorReasoning"] = it.toString() }
    daemon.putFoldOverrides(out)
    return out
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
