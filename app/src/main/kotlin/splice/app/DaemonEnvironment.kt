// NEW: the daemon's shared environment as ONE value: where its state lives, its settings, its management
// key and its log sink. ControlPlane took these four as separate parameters; with its six other
// collaborators the constructor was 10 wide against the strict LongParameterList ceiling of 7.
//
// These four and not others: they are the collaborators every daemon subsystem reads together, and Daemon
// is the one place that builds them. The six that stay separate are each owned by one subsystem.
package splice.app

import splice.client.mcp.McpAccessKey
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.LogSink

internal data class DaemonEnvironment(
    val statePaths: StatePaths,
    val config: ConfigService,
    val mgmtKey: MgmtKey,
    val log: LogSink,
) {
    /** The daemon's one MCP access key: one owner of the publication, so first-use adoption runs once per process. */
    val mcpAccess: McpAccessKey by lazy { McpAccessKey(statePaths.stateDir, statePaths.mgmtKeyFile, log, mgmtKey::get) }
}
