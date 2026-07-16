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
    @SerialName("control_port") val controlPort: Int = 3096,
    @SerialName("state_dir") val stateDir: String? = null,
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

private const val DEFAULT_WINDOW_FLOOR: Long = 200_000
