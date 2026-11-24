package splice.core.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.mcp.McpHarness
import splice.core.mcp.McpRegistryNames
import splice.core.topology.TopologyFinding
import splice.core.wire.NETWORK_PORT_MAX
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A local extension to the shipped harness inventory, declared in splice.toml. */
@Serializable
public data class HarnessConfig(
    val name: String? = null,
    val description: String = "",
    val protocol: String? = null,
    val argv: List<String> = emptyList(),
    val peer: HarnessPeerConfig? = null,
)

/** Optional peer delivery for a locally declared harness. */
@Serializable
public data class HarnessPeerConfig(
    val protocol: String? = null,
    @SerialName("auto_subscribe") val autoSubscribe: Boolean = false,
    @SerialName("deliver_cmd") val deliverCmd: List<String> = emptyList(),
    @SerialName("webhook_url") val webhookUrl: String? = null,
) {
    public val driver: HarnessPeerProtocol? get() = HarnessPeerProtocol.entries.find { it.wire == protocol }

    /** The same URL check is used at boot and before each POST. */
    public fun loopbackWebhook(): URI? {
        val uri = try {
            webhookUrl?.let(::URI)
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        val httpLoopback = uri.scheme.equals("http", ignoreCase = true) && uri.host?.lowercase() in LOOPBACK_HOSTS
        val safeAuthority = uri.rawUserInfo == null && uri.rawFragment == null
        val validPort = uri.port == -1 || uri.port in 1..NETWORK_PORT_MAX
        return uri.takeIf { httpLoopback && safeAuthority && validPort }
    }
}

public enum class HarnessPeerProtocol(public val wire: String) {
    NATIVE("native-cc-socks"),
    CODEX("codex-app-server"),
    ACP("acp"),
    COMMAND("command"),
    WEBHOOK("webhook"),
}

/** The protocol a harness speaks, independent of the model or provider it serves. */
public enum class HarnessProtocol(public val wire: String) {
    CLAUDE_CODE("claude-code"),
    CODEX_APP_SERVER("codex-app-server"),
    ACP("acp"),
}

/** A harness as callers know it. The resolved Desktop document exports the display fields only. */
public data class HarnessDefinition(
    val wire: String,
    val title: String,
    val description: String,
    val protocol: HarnessProtocol,
    val peer: HarnessPeerConfig? = null,
)

/** The shipped harnesses and locally declared ACP additions. Invalid local declarations refuse daemon boot. */
public class HarnessCatalog(configured: Map<String, HarnessConfig> = emptyMap()) {
    private val builtIns = McpHarness.entries.map(McpHarness::definition)
    private val mcpNames = McpRegistryNames()
    private val checked = configured.mapValues { (id, config) -> check(id, config) }
    private val local = configured.filterKeys { checked.getValue(it).isEmpty() }

    public val findings: List<TopologyFinding> = checked.values.flatten()

    public val entries: List<HarnessDefinition> = builtIns + local.map { (id, config) ->
        HarnessDefinition(id, checkNotNull(config.name), config.description, HarnessProtocol.ACP, config.peer)
    }

    /** A local ACP command, taken from the file as argv and never interpreted by a shell. */
    public fun acpCommand(id: String): List<String>? = local[id]?.argv

    private fun check(id: String, config: HarnessConfig): List<TopologyFinding> = buildList {
        val path = "harnesses.$id"
        if (!ID.matches(id)) add(TopologyFinding(path, "use a lowercase id of letters, digits and hyphens"))
        if (mcpNames.harness(id) != null) {
            add(TopologyFinding(path, "this id belongs to a shipped harness; name a new one"))
        }
        if (config.name.isNullOrBlank()) add(TopologyFinding("$path.name", "name the harness"))
        if (config.protocol != HarnessProtocol.ACP.wire) add(TopologyFinding("$path.protocol", "use acp"))
        if (!validArgv(config.argv)) {
            add(TopologyFinding("$path.argv", "name an absolute executable path followed by its arguments"))
        }
        config.peer?.let { addAll(peerFindings("$path.peer", it)) }
    }

    private fun validArgv(argv: List<String>): Boolean = absolute(argv.firstOrNull()) && argv.none { '\u0000' in it }

    private fun peerFindings(path: String, peer: HarnessPeerConfig): List<TopologyFinding> = buildList {
        val driver = peer.driver
        peerProtocolFinding(driver)?.let { add(TopologyFinding("$path.protocol", it)) }
        val checksCommand = driver == HarnessPeerProtocol.COMMAND || peer.deliverCmd.isNotEmpty()
        if (checksCommand && !validArgv(peer.deliverCmd)) {
            add(TopologyFinding("$path.deliver_cmd", "name an absolute executable path followed by its arguments"))
        }
        val checksWebhook = peer.driver == HarnessPeerProtocol.WEBHOOK || peer.webhookUrl != null
        if (checksWebhook && peer.loopbackWebhook() == null) {
            add(TopologyFinding("$path.webhook_url", "use an HTTP loopback URL without userinfo or a fragment"))
        }
    }

    private fun peerProtocolFinding(driver: HarnessPeerProtocol?): String? = when (driver) {
        HarnessPeerProtocol.NATIVE, HarnessPeerProtocol.CODEX -> "use acp, command or webhook for a local harness"
        HarnessPeerProtocol.ACP, HarnessPeerProtocol.COMMAND, HarnessPeerProtocol.WEBHOOK -> null
        null -> "use a known peer protocol"
    }

    private fun absolute(executable: String?): Boolean = try {
        executable != null && Path.of(executable).isAbsolute
    } catch (_: InvalidPathException) {
        false
    }
}

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "[::1]", "localhost")

private val ID = Regex("[a-z][a-z0-9-]*")
