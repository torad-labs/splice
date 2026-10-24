// NEW: macOS parity — the generated hook asks a portable JVM helper to inspect and cancel owned sign-ins.
package splice.app.cli

import splice.client.login.PendingLogins
import splice.core.config.StatePaths
import splice.core.process.LaunchOwners
import java.nio.file.Path

/** Internal hook protocol: a machine-readable outcome, never a provider sign-in itself. */
internal class PendingLoginCommand(private val args: List<String>) : Command() {
    override suspend fun run(): Int {
        val outcome = PendingLogins(LaunchOwners(StatePaths().stateDir)).cancel(Path.of(args.first()), args.drop(1))
        println(outcome.name)
        return 0
    }
}
