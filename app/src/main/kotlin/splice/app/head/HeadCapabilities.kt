// NEW: the harness (docs/specs/harness.md). What each head's harness is built from: the capabilities splice.toml
// declares, the folder its threads are recorded in under the head's own state folder, and a worker's key, read from
// the variable its `auth_env` names the way a provider's API key is (the environment, a key file, splice's key store).
package splice.app.head

import splice.core.capability.Capability
import splice.head.harness.HarnessInputs
import splice.head.harness.WorkerKeys
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Path

/** [headsDir] is the state folder's `heads/`, under which each head keeps what it keeps across a restart; null keeps
 *  every thread's record in memory alone. */
internal class HeadCapabilities(
    private val capabilities: List<Capability> = emptyList(),
    private val headsDir: Path? = null,
) {
    private val keys = WorkerKeys { env -> ApiKeyAuthProvider(env).keyNow() }

    /** Head [key]'s harness, or none where splice.toml declares no capability. */
    fun forHead(key: String): HarnessInputs? = capabilities.takeIf { it.isNotEmpty() }?.let {
        HarnessInputs(it, headsDir?.resolve(key)?.resolve(RECORDS_DIR), keys)
    }
}

/** why: the head's folder holds what its provider keeps too; the harness's records sit in a folder of their own. */
private const val RECORDS_DIR = "capabilities"
