// NEW: `splice setup`'s MCP sharing question (docs/specs/mcp-hub.md, "Shared or per session, asked once"). It is asked
// only while splice.toml holds no [daemon] mcp_share, so a later setup keeps whatever the console has set since. Yes
// writes true and no writes false. The hub reads the key live, so the answer applies to the next connection. With no
// one watching, the default is taken: shared. A write that fails is said in a line and never fails the setup. In its
// own file because SetupCommand.kt is watched by the concentration wall.
package splice.app.cli.setup

import splice.core.config.StatePaths
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.Cancellables
import splice.terminal.WizardFrame
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

internal class SetupMcpShare(private val frame: WizardFrame) {
    /** The answer, or null when splice.toml already holds one and nothing is asked. */
    fun ask(path: Path): Boolean? = if (held(path) != null) null else frame.confirm(QUESTION, true)

    fun summary(answer: Boolean?): List<String> = when (answer) {
        null -> emptyList()
        true -> listOf("MCP servers shared between sessions, one copy each")
        false -> listOf("MCP servers started once per session")
    }

    /** Writes [answer] to [daemon] mcp_share in the file at [path], changing nothing else in it. */
    fun save(path: Path, answer: Boolean?) {
        answer ?: return
        val result = Cancellables.runCatchingBestEffort {
            val current = TopologyLoader.parse(Files.readString(path))
            TopologyWriter(path, StatePaths().configBackupsDir, TopologyParse(TopologyLoader::parse))
                .write(current.copy(daemon = current.daemon.copy(mcpShare = answer)))
        }
        val refused = result.getOrNull() as? TopologyWriteResult.Refused
        val why = refused?.findings?.joinToString("; ") { it.text() } ?: result.exceptionOrNull()?.message
        why?.let {
            System.err.println("splice: the MCP sharing answer was not saved ($it); set [daemon] mcp_share by hand")
        }
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
