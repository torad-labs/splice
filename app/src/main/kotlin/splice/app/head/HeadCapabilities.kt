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
import splice.head.port.TurnHarness
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Path

/** [headsDir] is the state folder's `heads/`, under which each head keeps what it keeps across a restart; null keeps
 *  every thread's record in memory alone. [gatherers] are the heads the Context Getter starts its gatherer on. */
internal class HeadCapabilities(
    private val capabilities: List<Capability> = emptyList(),
    private val headsDir: Path? = null,
    private val gatherers: GathererHeads = GathererHeads { null },
) {
    private val keys = WorkerKeys { env -> ApiKeyAuthProvider(env).keyNow() }

    /** Head [key]'s harness, logging to [log], or none where splice.toml declares no capability. */
    fun forHead(key: String, log: LogSink): TurnHarness? = capabilities.takeIf { it.isNotEmpty() }?.let {
        val inputs = HarnessInputs(it, headsDir?.resolve(key)?.resolve(RECORDS_DIR), keys, gatherers)
        HeadHarness().of(key, inputs, log, ElapsedClock(MonoClock::nowMs))
    }
}

/** why: the head's folder holds what its provider keeps too; the harness's records sit in a folder of their own. */
private const val RECORDS_DIR = "capabilities"
