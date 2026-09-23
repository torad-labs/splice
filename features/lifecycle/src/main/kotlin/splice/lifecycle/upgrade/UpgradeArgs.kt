// NEW: v0.4.0 FEATURES.md §5 — the `splice upgrade` command line as data, its parser and usage.
// Split from UpgradeCommand.kt (concentration, 2026-09-13).
package splice.lifecycle.upgrade

import splice.core.terminal.TerminalOutput

internal data class UpgradeArgs(val to: String? = null, val now: Boolean = false, val rollback: Boolean = false)

internal class UpgradeArgParser(private val output: TerminalOutput) {
    fun parse(args: List<String>): UpgradeArgs? {
        var parsed = UpgradeArgs()
        var i = 0
        while (i < args.size) {
            parsed = when (args[i]) {
                "--to" -> parsed.copy(to = args.getOrNull(i + 1) ?: return null).also { i++ }
                "--now" -> parsed.copy(now = true)
                "--rollback" -> parsed.copy(rollback = true)
                else -> return null
            }
            i++
        }
        return parsed
    }

    fun usage(): Boolean {
        output.line("usage: splice upgrade [--to vX.Y.Z] [--now] [--rollback]")
        return false
    }
}
