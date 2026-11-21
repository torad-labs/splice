// NEW: custom-provider (issue #680, cp-credential). A command credential is per provider: every head of one provider
// sends the same key and renews it together, so they share one [CommandCredential], held here by provider key for the
// life of the process (in memory alone). A provider whose command changed gets a new one.
package splice.core.auth

import java.util.concurrent.ConcurrentHashMap

public object CommandCredentials {
    private val held = ConcurrentHashMap<String, Pair<String, CommandCredential>>()

    /** The credential of provider [providerKey], whose `credential_command` is [command], run by [runs]. */
    public fun of(providerKey: String, command: String, runs: CredentialRuns): CommandCredential {
        val entry = held.compute(providerKey) { _, previous ->
            previous?.takeIf { it.first == command } ?: (command to CommandCredential(runs.of(command)))
        }
        return checkNotNull(entry).second
    }

    /** The credential provider [providerKey] holds now, or null before any of its heads was built. */
    public fun find(providerKey: String): CommandCredential? = held[providerKey]?.second
}
