// NEW: the TOML dialect/auth/wrapper schema types, and the `[share]` table (2026-10-22). Split from Topology.kt
// so the graph file is not billed for the leaf DTOs (concentration, 2026-08-19).
// Same-package FQCNs are unchanged.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.prompt.SystemPromptMode
import splice.core.util.Cancellables
import java.net.URI

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
    /** `splice-remote` only: the `[remotes.<name>]` machine whose head this provider relays to. */
    val remote: String? = null,
) {
    /** True for the api-key scheme — [API_KEY_WIRE], declared once beside the registered kinds. */
    public val isApiKey: Boolean get() = kind == API_KEY_WIRE

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
        "sessions",
        "projects",
    ),
)

/** LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). `[share]` in splice.toml: this machine serves its
 *  heads to the person's other machines on a private network, through ONE listener the daemon opens only when
 *  [enabled]. Turns are relayed to the head itself, so every credential, its refresh, its account pool and its
 *  cooldowns stay on this machine. Absent table = off, today's bytes. Moved here from its own file on 2026-10-22
 *  (concentration): a leaf DTO of the schema like the tables above, and a file of its own thinned this package's
 *  median under its neighbours. */
@Serializable
public data class ShareConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_SHARE_PORT,
    /** The heads offered, by topology key. Absent: every head that holds its own credential. */
    val heads: List<String>? = null,
    /** Names a client may reach this machine by besides its own host name (a Tailscale name, say). */
    val hostnames: List<String> = emptyList(),
) {
    /** The heads this machine offers. A client-auth head forwards the caller's own Claude login and holds
     *  nothing to share, and a splice-remote head relays to the machine it came from, which offers it itself, so
     *  neither is ever offered, named or not. */
    public fun sharedHeads(topology: Topology): List<String> =
        (heads ?: topology.heads.keys.toList())
            .filter { key -> key in topology.heads && unshareable(topology, key) == null }

    /** Why [key] cannot be offered, in words, or null when it can. */
    public fun unshareable(topology: Topology, key: String): String? = when (authOf(topology, key)) {
        AuthKind.Client.wire -> "it forwards the caller's own Claude login, so there is nothing to share"
        AuthKind.SpliceRemote.wire -> "it relays to another machine, which shares it itself"
        else -> null
    }

    /** What boot refuses about this table, all at once like every other finding; the daemon's re-read of splice.toml
     *  leaves sharing as it is on any of them. */
    public fun findings(topology: Topology): List<TopologyFinding> {
        if (!enabled) return emptyList()
        val range = if (port in validPortRange) {
            emptyList()
        } else {
            listOf(TopologyFinding(SHARE_PORT_OWNER, "port $port is outside $validPortRange; use a port in that range"))
        }
        val named = heads.orEmpty().mapNotNull { key ->
            when {
                key !in topology.heads ->
                    TopologyFinding("share.heads", "'$key' is not a head in [heads]; name one that is")
                else -> unshareable(topology, key)?.let { TopologyFinding("share.heads", "'$key': $it; remove it") }
            }
        }
        return range + named
    }

    /** Why the listener could not open on [port] in [topology], in the words boot refuses it with when sharing is on;
     *  empty when it could. The Sharing page checks a port it saves by this while sharing is off too (round 17 of PR
     *  #419's review), so turning sharing on later never meets a port it took without a word. */
    public fun unopenable(topology: Topology): List<String> {
        if (port !in validPortRange) return listOf("port $port is outside $validPortRange; use a port in that range")
        val owners = topology.copy(share = copy(enabled = true)).portCollisions()[port].orEmpty()
        if (SHARE_PORT_OWNER !in owners) return emptyList()
        return listOf("port $port is shared by ${owners.joinToString(", ")}; give each its own port")
    }

    private fun authOf(topology: Topology, key: String): String? =
        topology.heads[key]?.provider?.let { topology.providers[it] }?.auth?.kind
}

/** `[remotes.<name>]`: another of the person's machines this one joined (`splice remote`), whose heads this
 *  machine's `splice-remote` providers relay to (docs/specs/lan-share.md). The token that machine gave is kept in
 *  `<state>/share/remotes.json`, never here: splice.toml holds no secret. */
@Serializable
public data class RemoteConfig(
    /** Where its share listener answers, `https://torad-server.lan:3097`. */
    val url: String,
    /** The SHA-256 of the certificate it presented when this machine joined, `sha256:<64 hex>`. A relay that meets any
     *  other certificate is refused. Required: boot refuses an entry without one (RemoteFindings). */
    val fingerprint: String? = null,
)

/** What boot refuses about `[remotes]` and the providers that relay to them, all at once like every other finding. */
internal class RemoteFindings(private val topology: Topology) {
    private val fingerprintShape = Regex("^sha256:[0-9a-f]{64}$")

    fun all(): List<TopologyFinding> = remotes() + relays()

    private fun remotes(): List<TopologyFinding> = topology.remotes.flatMap { (name, remote) ->
        // A url that does not parse is the finding itself, so its failure is answered here rather than logged.
        val uri = Cancellables.runCatchingCancellable { URI.create(remote.url) }
            .fold(onSuccess = { it }, onFailure = { null })
        // Shares are TLS only since phase 2, and every relay trusts the one certificate pinned here.
        val url = if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
            null
        } else {
            TopologyFinding("remotes.$name.url", "not an https address with a host; run `splice remote` again")
        }
        val pin = when {
            remote.fingerprint == null ->
                TopologyFinding("remotes.$name.fingerprint", "no certificate is pinned; run `splice remote` again")
            !fingerprintShape.matches(remote.fingerprint) -> TopologyFinding(
                "remotes.$name.fingerprint",
                "not `sha256:` and 64 hex digits; run `splice remote` again",
            )
            else -> null
        }
        listOfNotNull(url, pin)
    }

    private fun relays(): List<TopologyFinding> = topology.providers.mapNotNull { (key, provider) ->
        val auth = provider.auth
        when {
            auth.kind != AuthKind.SpliceRemote.wire -> null
            auth.remote == null ->
                TopologyFinding("providers.$key.auth.remote", "names no machine; set it to a key of [remotes]")
            auth.remote !in topology.remotes -> TopologyFinding(
                "providers.$key.auth.remote",
                "'${auth.remote}' is not in [remotes]; run `splice remote add` for that machine",
            )
            else -> null
        }
    }
}

/** The share listener's port when `[share]` names none: between the control plane (3096) and the first port
 *  `splice add` gives a head (3098 and up). */
public const val DEFAULT_SHARE_PORT: Int = 3097

/** How the share listener appears in a port collision: the key path an operator fixes. */
internal const val SHARE_PORT_OWNER: String = "share.port"
