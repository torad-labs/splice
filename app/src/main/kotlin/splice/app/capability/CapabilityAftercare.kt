// NEW: what `splice upgrade --aftercare` runs on this executable (UpgradeAftercare): the maintenance an install that
// predates the release needs, laid again each upgrade since each step only writes what differs, and the list of what
// the install has not set up yet (CapabilityGaps), which only an interactive upgrade prints.
package splice.app.capability

import splice.app.InstallWiring
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_COMMAND
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.GeminiShim
import splice.launch.install.GeminiShimLaid
import splice.launch.install.InstallCommand
import splice.lifecycle.upgrade.AftercareGaps
import splice.lifecycle.upgrade.AftercareStep
import splice.lifecycle.upgrade.UpgradeAftercare

internal class CapabilityAftercare(private val env: EnvReader) {
    private val quiet = TerminalOutput { }

    fun aftercare(output: TerminalOutput): UpgradeAftercare = UpgradeAftercare(
        output,
        listOf(AftercareStep { shim() }, AftercareStep { InstallWiring.refreshHub(env) }),
        AftercareGaps { gaps().afterUpgrade().map { it.text } },
    )

    /** The gaps of this machine as it is now. */
    fun gaps(): CapabilityGaps = CapabilityGaps(CapabilityLook(env).read(), claudeWrapped())

    /** The Antigravity shim install.sh lays (step 4a'), laid where it is missing or stale. */
    private fun shim(): List<String> = when (val laid = GeminiShim(env).lay()) {
        GeminiShimLaid.Current -> emptyList()
        is GeminiShimLaid.Written -> listOf("laid the Antigravity shim Claude Desktop runs for agy at ${laid.path}")
        is GeminiShimLaid.Failed -> listOf("could not lay the Antigravity shim (${laid.why})")
    }

    /** Whether plain `claude` runs splice's launch shim: an older `splice setup` wrapped it. */
    private fun claudeWrapped(): Boolean = Cancellables.runCatchingBestEffort {
        InstallCommand(quiet, quiet).runsShim(CLAUDE_COMMAND, env)
    }.getOrDefault(false)
}
