package splice.core.harness

import kotlinx.serialization.Serializable
import splice.core.mcp.McpHarness
import splice.core.mcp.McpRegistryNames
import splice.core.topology.TopologyFinding
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A local extension to the shipped harness inventory, declared in splice.toml. */
@Serializable
public data class HarnessConfig(
    val name: String? = null,
    val description: String = "",
    val protocol: String? = null,
    val argv: List<String> = emptyList(),
)

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
)

/** The shipped harnesses and locally declared ACP additions. Invalid local declarations refuse daemon boot. */
public class HarnessCatalog(configured: Map<String, HarnessConfig> = emptyMap()) {
    private val builtIns = McpHarness.entries.map(McpHarness::definition)
    private val mcpNames = McpRegistryNames()
    private val checked = configured.mapValues { (id, config) -> check(id, config) }
    private val local = configured.filterKeys { checked.getValue(it).isEmpty() }

    public val findings: List<TopologyFinding> = checked.values.flatten()

    public val entries: List<HarnessDefinition> = builtIns + local.map { (id, config) ->
        HarnessDefinition(id, checkNotNull(config.name), config.description, HarnessProtocol.ACP)
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
        if (!absolute(config.argv.firstOrNull()) || config.argv.any { '\u0000' in it }) {
            add(TopologyFinding("$path.argv", "name an absolute executable path followed by its arguments"))
        }
    }

    private fun absolute(executable: String?): Boolean = try {
        executable != null && Path.of(executable).isAbsolute
    } catch (_: InvalidPathException) {
        false
    }
}

private val ID = Regex("[a-z][a-z0-9-]*")
