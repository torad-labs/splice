// NEW: the harness (docs/specs/harness.md, decision 13). Where :app tells the harness which MCP servers a session
// loads: the plan the hub makes for it (MCP hub step 1), over the config home its head runs Claude Code under and the
// folder it works in, so a server the folder's own `.mcp.json` declares is found there, and one the hub hosts for every
// session is the address the head's own home carries for it.
package splice.app.head

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpLaunchPlanner
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.harness.WorkerMcpServers
import java.nio.file.Path

/** [configDirs] are the heads' config homes, by head key; [statePaths] is where the planner reads its registrations. */
internal class HeadMcpServers(private val configDirs: Map<String, Path>, statePaths: StatePaths) : WorkerMcpServers {
    private val planner = McpLaunchPlanner(
        UserHome.dir(),
        temple = TempleMcpEntry(UserHome.dir(), paths = statePaths),
        registry = splice.core.mcp.McpUserRegistry(statePaths),
    )

    override fun servers(head: String, folder: Path): Map<String, JsonObject> {
        val configDir = configDirs[head] ?: return emptyMap()
        return planner.plan(configDir, folder).servers.associate { it.name to it.entry }
    }
}
