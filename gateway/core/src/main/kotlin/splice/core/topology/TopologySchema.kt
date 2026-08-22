// NEW: the TOML dialect/auth/wrapper schema types. Split from Topology.kt
// so the graph file is not billed for the leaf DTOs (concentration, 2026-08-19).
// Same-package FQCNs are unchanged.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
