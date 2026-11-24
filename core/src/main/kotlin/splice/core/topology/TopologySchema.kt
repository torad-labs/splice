// NEW: the TOML dialect/auth/wrapper schema types. Split from Topology.kt
// so the graph file is not billed for the leaf DTOs (concentration, 2026-08-19).
// Same-package FQCNs are unchanged.
// 2026-11-14: a provider table's header keys join them. People whose company gateway sits in front of a provider
// asked to pass on the headers their Claude Code is set to send (ANTHROPIC_CUSTOM_HEADERS), and for a gateway to see
// which splice, and which version, a request came through, so it can turn away an old one. forward_headers follows
// LiteLLM's and Portkey's named forwarding, off until a table names a header. send_version writes RFC 9110's Via line
// (section 7.6.3), the field a gateway that forwards a request is meant to add, and leaves the caller's own User-Agent
// as it was.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.GATEWAY_VERSION
import splice.core.capability.Capability
import splice.core.mode.ModeSetting
import splice.core.model.ReadableIds
import splice.core.prompt.SystemPromptMode
import splice.core.topology.desktop.DesktopConfig
import splice.core.topology.mods.ModsConfig

/** Operator-defined capability, mode, `[mods]` and `[desktop]` tables. The loader parses them separately
 *  from the canonical TOML tree that the console edits and attaches them together once well-formed. */
public data class TopologyExtensions(
    val capabilities: List<Capability> = emptyList(),
    val modes: Map<String, ModeSetting> = emptyMap(),
    val mods: ModsConfig = ModsConfig(),
    val desktop: DesktopConfig = DesktopConfig(),
)

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
    /** custom-provider (issue #680): with kind = "command", the shell command that prints the current credential, as
     *  `{"token": "...", "expires_at": "<RFC 3339>"}` or one bare line. Required with that kind, refused on any other. */
    @SerialName("credential_command") val credentialCommand: String? = null,
    /** custom-provider: with kind = "command", the interactive sign-in a person runs (a team CLI's browser or device
     *  OAuth). Optional, and refused on any other kind. */
    @SerialName("login_command") val loginCommand: String? = null,
) {
    /** True for the api-key scheme — [API_KEY_WIRE], declared once beside the registered kinds. */
    public val isApiKey: Boolean get() = kind == API_KEY_WIRE

    /** True for the command scheme — [COMMAND_AUTH_WIRE]: a command the team ships prints the key. */
    public val isCommand: Boolean get() = kind == COMMAND_AUTH_WIRE

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
    /** The names a Claude client may give this head's models besides its selectors
     *  (`readable = { prefix = "claude-xai--", strip = "grok-" }`), for one that refuses an id naming another lab
     *  ([ReadableIds]). Null: the head has none. */
    val readable: ReadableIds? = null,
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

/** The Via line send_version adds: the protocol the request reached splice on, splice's name, and its version as the
 *  comment RFC 9110 allows for naming the software. */
internal const val SPLICE_VIA: String = "1.1 splice (splice/$GATEWAY_VERSION)"

/** Headers that never ride on from a caller, whatever a table names, lowercase. A login, because the caller's would
 *  replace the provider's, and in a session splice launched it holds splice's own key. And the headers that describe
 *  the caller's hop to splice rather than splice's hop to the provider, which corrupt the request when copied. */
private val NEVER_FORWARDED: Set<String> = setOf(
    "authorization", "proxy-authorization", "x-api-key", "api-key", "x-goog-api-key", "cookie",
    "host", "content-length", "content-type", "content-encoding", "transfer-encoding", "accept-encoding",
    "connection", "keep-alive", "proxy-connection", "te", "trailer", "upgrade", "via",
)

/** A provider's `forward_headers`: the caller's headers, by exact name, that ride on to this provider, on any head and
 *  any dialect. Empty by default, so nothing a caller sends rides on unless the table names it. */
public class ForwardHeaders(configured: List<String>) {
    /** The named headers, trimmed, each name once whatever its case. */
    public val names: List<String> =
        configured.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

    /** The names that can never ride on, as the table wrote them. A finding at boot, so none is dropped quietly. */
    public val refused: List<String> get() = names.filter { it.lowercase() in NEVER_FORWARDED }

    /** The names that do ride on, lowercase. */
    public val carried: List<String> get() = names.map(String::lowercase).filterNot { it in NEVER_FORWARDED }

    /** The boot finding for provider [key] when its table names a header that never rides on, so the name refuses boot
     *  with every other finding instead of being dropped while the file reads as if it forwarded it. */
    public fun finding(key: String): TopologyFinding? = if (refused.isEmpty()) {
        null
    } else {
        TopologyFinding(
            "providers.$key.forward_headers",
            "names a login or connection header (Authorization, x-api-key, Host, Content-Length and the like), " +
                "which never rides on from a caller; remove it, or set a fixed value in extra_headers",
        )
    }
}
