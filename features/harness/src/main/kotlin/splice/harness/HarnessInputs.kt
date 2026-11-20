// NEW: the harness (docs/specs/harness.md). What a head's harness is built from, as the daemon hands it over: the
// capabilities splice.toml declares, the folder the head's threads are recorded in, and how a worker's key is read.
package splice.harness

import kotlinx.serialization.json.JsonObject
import splice.core.capability.Capability
import splice.harness.agent.AgentToolPort
import splice.harness.agent.NoAgentTools
import java.nio.file.Path

/** The key a worker's `auth_env` names, read the way a provider's API key is; null when none is set. */
public fun interface WorkerKeys {
    public fun key(env: String): String?
}

/** The servers the MCP hub gives a session of head [head] working in [folder], each by the name its tools carry, with
 *  the entry that declares it as Claude Code reads it: the ones Claude Code would load there, as the hub plans them
 *  (decision 13). A head the daemon does not serve has none. */
public fun interface WorkerMcpServers {
    public fun servers(head: String, folder: Path): Map<String, JsonObject>
}

/** [recordsDir] null keeps the threads' records in memory alone (a test's head). [gatherers] are the heads the Context
 *  Getter can start its gatherer on (decision 9); a harness with none never gathers. */
public data class HarnessInputs(
    val capabilities: List<Capability>,
    val recordsDir: Path?,
    val keys: WorkerKeys,
    val gatherers: GathererHeads = GathererHeads { null },
    val mcpServers: WorkerMcpServers = WorkerMcpServers { _, _ -> emptyMap() },
    /** The tools beside the capabilities the model may call (a mode's, modes/README.md). */
    val tools: AgentToolPort = NoAgentTools,
)
