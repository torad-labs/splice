// NEW: the harness (docs/specs/harness.md, decision 11). Where :app wires the harness module to the turn: what each
// head's harness is built from (the capabilities splice.toml declares, the folder its threads are recorded in under
// the head's own state folder, and a worker's key, read from the variable its `auth_env` names the way a provider's
// API key is: the environment, a key file, splice's key store), and the one the head's turns reach through their port.
package splice.app.head

import splice.core.capability.Capability
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.harness.GathererHeads
import splice.harness.HarnessInputs
import splice.harness.HeadHarness
import splice.harness.WorkerKeys
import splice.harness.WorkerMcpServers
import splice.harness.agent.AgentToolPort
import splice.harness.agent.NoAgentTools
import splice.harness.hook.ModeHookPort
import splice.harness.hook.NoModeHooks
import splice.head.port.TurnHarness
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Path

/** [headsDir] is the state folder's `heads/`, under which each head keeps what it keeps across a restart; null keeps
 *  every thread's record in memory alone. [gatherers] are the heads the Context Getter starts its gatherer on. */
internal class HeadCapabilities(
    private val capabilities: List<Capability> = emptyList(),
    private val headsDir: Path? = null,
    private val gatherers: GathererHeads = GathererHeads { null },
    private val mcpServers: WorkerMcpServers = WorkerMcpServers { _, _ -> emptyMap() },
    /** The door modes as each head's agent tools (modes/README.md); none where splice carries no door mode. */
    private val tools: HeadAgentTools = HeadAgentTools { null },
    /** The modes' hooks at the door's moments for each head (modes/README.md, "Hooks"); none where no mode hooks. */
    private val hooks: HeadModeHooks = HeadModeHooks { null },
) {
    private val keys = WorkerKeys { env -> ApiKeyAuthProvider(env).keyNow() }

    /** Head [key]'s harness, logging to [log], or none where splice.toml declares no capability and splice carries
     *  no mode tool or hook. */
    fun forHead(key: String, log: LogSink): TurnHarness? {
        val port = tools.forHead(key)
        val hookPort = hooks.forHead(key)
        val anyMode = port != null || hookPort != null
        if (capabilities.isEmpty() && !anyMode) return null
        val records = headsDir?.resolve(key)?.resolve(RECORDS_DIR)
        val inputs = HarnessInputs(
            capabilities,
            records,
            keys,
            gatherers,
            mcpServers,
            port ?: NoAgentTools,
            hookPort ?: NoModeHooks,
        )
        return HeadHarness().of(key, inputs, log, ElapsedClock(MonoClock::nowMs))
    }
}

/** The agent tools beside the capabilities at head [forHead]'s key, or null where it has none. */
internal fun interface HeadAgentTools {
    fun forHead(key: String): AgentToolPort?
}

/** The modes' hooks for head [forHead]'s key, or null where no mode hooks. */
internal fun interface HeadModeHooks {
    fun forHead(key: String): ModeHookPort?
}

/** why: the head's folder holds what its provider keeps too; the harness's records sit in a folder of their own. */
private const val RECORDS_DIR = "capabilities"
