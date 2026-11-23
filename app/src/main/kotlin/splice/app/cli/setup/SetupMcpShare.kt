// NEW: `splice setup`'s MCP sharing question (docs/specs/mcp-hub.md, "Shared or per session, asked once"),
// plus the optional interactive MCP import & multi-harness sync step ([SetupMcpSync]). Keeping both
// behind this collaborator leaves SetupCommand's own concern budget unchanged.
package splice.app.cli.setup

import splice.core.config.StatePaths
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.lifecycle.hub.HubUnits
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** Combined MCP wizard choices collected by [SetupMcpShare]. */
internal data class McpSetupAnswer(
    val share: Boolean?,
    val sync: McpSyncChoice = McpSyncChoice(),
)

/** [units] are the MCP hub's units on this machine, or null where the hub is not installed. */
internal class SetupMcpShare(
    private val prompts: SetupPrompts,
    private val units: HubUnits? = null,
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private val frame = prompts.frame
    private val sync = SetupMcpSync(env)

    /** Returns the user's MCP sharing + harness sync choices, or null when cancelled. */
    fun ask(path: Path): McpSetupAnswer? {
        val share = if (held(path) != null) null else frame.confirm(QUESTION, true)
        val syncChoice = sync.offer(prompts) ?: return null
        return McpSetupAnswer(share, syncChoice)
    }

    fun summary(answer: McpSetupAnswer): List<String> = buildList {
        when (answer.share) {
            null -> Unit
            true -> add("MCP servers shared between sessions, one copy each")
            false -> add("MCP servers started once per session")
        }
        addAll(sync.summaryLines(answer.sync))
    }

    /** Writes [answer]'s share flag to `[daemon] mcp_share`, changing nothing else in [path]. */
    fun save(path: Path, answer: McpSetupAnswer) {
        val share = answer.share ?: return
        val result = Cancellables.runCatchingBestEffort {
            val current = TopologyLoader.parse(Files.readString(path))
            val writer = TopologyWriter(path, StatePaths().configBackupsDir, TopologyParse(TopologyLoader::parse))
            writer.write(current.copy(daemon = current.daemon.copy(mcpShare = share)), writer.tree(current))
        }
        val refused = result.getOrNull() as? TopologyWriteResult.Refused
        val why = refused?.findings?.joinToString("; ") { it.text() } ?: result.exceptionOrNull()?.message
        why?.let {
            System.err.println("splice: the MCP sharing answer was not saved ($it); set [daemon] mcp_share by hand")
        }
    }

    /** Turns the hub on when sharing is enabled and applies any opted-in MCP import/harness sync. */
    suspend fun hub(path: Path, answer: McpSetupAnswer) {
        if ((answer.share ?: held(path)) == true) units?.enable()
        sync.apply(answer.sync, frame)
    }

    private fun held(path: Path): Boolean? =
        if (!Files.exists(path)) {
            null
        } else {
            Cancellables.runCatchingBestEffort { TopologyLoader.parse(Files.readString(path)).daemon.mcpShare }
                .getOrNull()
        }
}

private const val QUESTION = "Share MCP servers between sessions? It uses less memory."
