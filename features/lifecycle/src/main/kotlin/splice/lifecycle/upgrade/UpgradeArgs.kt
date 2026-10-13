// NEW: v0.4.0 FEATURES.md §5 — the `splice upgrade` command line as data, its parser and usage.
// Split from UpgradeCommand.kt (concentration, 2026-09-13).
package splice.lifecycle.upgrade

import splice.core.terminal.TerminalOutput

/** [auto] is the daemon updater's run (UpgradeAuto): it checks the release against its sha256sums.txt only, and after the
 *  bounded wait for turns it restarts anyway. */
internal data class UpgradeArgs(
    val to: String? = null,
    val now: Boolean = false,
    val rollback: Boolean = false,
    val auto: Boolean = false,
) {
    /** --auto always names the release it found, and never rolls back. */
    val coherent: Boolean get() = !auto || (to != null && !rollback)
}

internal class UpgradeArgParser(private val output: TerminalOutput) {
    fun parse(args: List<String>): UpgradeArgs? {
        var parsed = UpgradeArgs()
        var i = 0
        while (i < args.size) {
            parsed = when (args[i]) {
                "--to" -> parsed.copy(to = args.getOrNull(i + 1) ?: return null).also { i++ }
                "--now" -> parsed.copy(now = true)
                "--rollback" -> parsed.copy(rollback = true)
                "--auto" -> parsed.copy(auto = true)
                else -> return null
            }
            i++
        }
        return parsed.takeIf { it.coherent }
    }

    fun usage(): Boolean {
        output.line("usage: splice upgrade [--to vX.Y.Z] [--now] [--rollback]")
        return false
    }
}
