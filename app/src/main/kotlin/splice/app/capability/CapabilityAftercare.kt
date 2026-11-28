// NEW: what `splice upgrade --aftercare` runs on this executable (UpgradeAftercare): the maintenance an install that
// predates the release needs, laid again each upgrade since each step only writes what differs, and the list of what
// the install has not set up yet (CapabilityGaps), which only an interactive upgrade prints.
package splice.app.capability

import splice.app.seatd.SeatdAdvice
import splice.app.seatd.SeatdChoice
import splice.app.seatd.SeatdConsent
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_COMMAND
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.GeminiShim
import splice.launch.install.GeminiShimLaid
import splice.launch.install.InstallCommand
import splice.lifecycle.seatd.SeatdCompanion
import splice.lifecycle.seatd.SeatdInstaller
import splice.lifecycle.upgrade.aftercare.AftercareGaps
import splice.lifecycle.upgrade.aftercare.AftercareStep
import splice.lifecycle.upgrade.aftercare.UpgradeAftercare

/** [hub] lays the MCP hub's units and `mcp-front.env` again where they differ (InstallWiring.refreshHub), handed in by
 *  the wiring that owns the units. */
internal class CapabilityAftercare(
    private val env: EnvReader,
    private val hub: AftercareStep,
    private val seatd: SeatdCompanion = SeatdInstaller(env),
) {
    private val quiet = TerminalOutput { }

    fun aftercare(output: TerminalOutput): UpgradeAftercare = UpgradeAftercare(
        output,
        listOf(AftercareStep(::refreshSeatd), AftercareStep { shim() }, hub),
        AftercareGaps { gaps().afterUpgrade().map { it.text } },
    )

    /** The gaps of this machine as it is now. */
    private fun gaps(): CapabilityGaps = CapabilityGaps(CapabilityLook(env, seatd = seatd).read(), claudeWrapped())

    /** This runs in the activated jar, where the installer resolves that release's own pin and URL. */
    private fun refreshSeatd(auto: Boolean): List<String> {
        if (SeatdConsent(env).choice() != SeatdChoice.OPTED_IN) return emptyList()
        if (seatd.installed() != null || !seatd.hasInstallation()) return emptyList()
        val result = seatd.install(SeatdAdvice.SETUP)
        return if (auto) emptyList() else SeatdAdvice.lines(result)
    }

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
