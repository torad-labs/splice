// NEW: `splice upgrade --check` (spec §5; splice-lead's spec check, Oct 13): the newest published release beside the
// installed one, read the way the console's Check reads it (UpgradeChecks) on splice.toml's update channel, with nothing
// staged, activated or restarted.
package splice.lifecycle.upgrade

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

internal class UpgradeCheckLine(
    private val output: TerminalOutput,
    private val env: EnvReader,
    private val channel: AutoUpdateSettings,
) {
    /** One line saying what is installed and what is newest; false when the newest release could not be read. */
    fun answer(): Boolean {
        val installed = UpgradeLayout(env).installedVersion()
        val snapshot = (UpgradeChecks(env, settings = channel).check() as? UpgradeCheckOutcome.Done)?.snapshot
        val latest = snapshot?.latest
        if (latest == null) {
            output.line("splice upgrade --check: the newest release was not read (${snapshot?.reason ?: "no answer"})")
            return false
        }
        output.line(
            if (ReleaseOrder(latest) > ReleaseOrder(installed)) {
                "$latest is newer than the installed $installed; run: splice upgrade"
            } else {
                "$installed is installed, and the newest release is $latest"
            },
        )
        return true
    }
}
