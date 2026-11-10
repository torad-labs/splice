// NEW: the TOML dialect/auth/wrapper schema types. Split from Topology.kt
// so the graph file is not billed for the leaf DTOs (concentration, 2026-08-19).
// Same-package FQCNs are unchanged.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.prompt.SystemPromptMode

/** V4-124: a `[projects."ROOT"]` table — standing instructions for every session whose working
 *  directory is ROOT or below it, whatever head serves it. The same three keys as a head's
 *  (`system_prompt`, `system_prompt_file`, `system_prompt_mode`); a relative `system_prompt_file`
 *  resolves against ROOT, so the prompt can live in the repo it governs. [heads] narrows a further
 *  layer to one head inside this project. Resolution and composition: SystemPromptLayers. */
@Serializable
public data class ProjectConfig(
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("system_prompt_file") val systemPromptFile: String? = null,
    @SerialName("system_prompt_mode") val systemPromptMode: SystemPromptMode? = null,
    val heads: Map<String, ProjectHeadPrompt> = emptyMap(),
)

/** V4-124: a `[projects."ROOT".heads.KEY]` table — the project layer for ONE head of that project. */
@Serializable
public data class ProjectHeadPrompt(
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("system_prompt_file") val systemPromptFile: String? = null,
    @SerialName("system_prompt_mode") val systemPromptMode: SystemPromptMode? = null,
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

/** Wire spelling of [Dialect]: the @SerialName on the enum, not a second table. */
public object DialectWires {
    public fun name(dialect: Dialect): String =
        Dialect.serializer().descriptor.getElementName(dialect.ordinal)
}

@Serializable
public data class AuthConfig(
    val kind: String,
    val file: String? = null,
    val env: String? = null,
) {
    /** True for the api-key scheme — [API_KEY_WIRE], declared once beside the registered kinds. */
    public val isApiKey: Boolean get() = kind == API_KEY_WIRE

    /** The api-key env var a head actually reads: the explicit [env], else the derived
     *  `<KEY>_API_KEY` default the daemon synthesizes. One source for daemon wiring AND the CLI so a
     *  head on the derived default never reads as "not signed in" while the daemon serves it fine. */
    public fun effectiveApiKeyEnv(key: String): String = env ?: "${key.uppercase()}_API_KEY"

    /** The credential file this login lives in, unexpanded: the declared [file], else the registered kind's
     *  splice-owned default; null for a kind with neither. The ONE answer for the daemon's arms, `splice login`,
     *  status, doctor and the console (audit, Nov 9, 2026: the daemon let the legacy codexAuthPath knob, an env var
     *  or a console PATCH move the codex head's file while login and status kept the declared one, so a login
     *  landed in a file the head never read). */
    public val loginFile: String? get() = file ?: AuthKindRegistry.defaultAuthFileFor(kind)
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
        "sessions",
        "projects",
    ),
)
