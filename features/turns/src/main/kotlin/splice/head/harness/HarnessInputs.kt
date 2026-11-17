// NEW: the harness (docs/specs/harness.md). What a head's harness is built from, as the daemon hands it over: the
// capabilities splice.toml declares, the folder the head's threads are recorded in, and how a worker's key is read.
package splice.head.harness

import splice.core.capability.Capability
import java.nio.file.Path

/** The key a worker's `auth_env` names, read the way a provider's API key is; null when none is set. */
public fun interface WorkerKeys {
    public fun key(env: String): String?
}

/** [recordsDir] null keeps the threads' records in memory alone (a test's head). */
public data class HarnessInputs(
    val capabilities: List<Capability>,
    val recordsDir: Path?,
    val keys: WorkerKeys,
)
