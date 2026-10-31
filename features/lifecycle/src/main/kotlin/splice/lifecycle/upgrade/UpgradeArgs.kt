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
    val check: Boolean = false,
) {
    /** --auto always names the release it found and never rolls back; --check stands alone. */
    val coherent: Boolean get() = autoNamed && (!check || this == UpgradeArgs(check = true))

    private val autoNamed: Boolean get() = !auto || (to != null && !rollback)
}

/** What a flag that takes no value sets on the parsed command line. */
private fun interface FlagSetting {
    fun applyTo(args: UpgradeArgs): UpgradeArgs
}

/** The flags that take no value, each with what it sets. */
private val FLAGS: Map<String, FlagSetting> = mapOf(
    "--now" to FlagSetting { it.copy(now = true) },
    "--rollback" to FlagSetting { it.copy(rollback = true) },
    "--auto" to FlagSetting { it.copy(auto = true) },
    "--check" to FlagSetting { it.copy(check = true) },
)

internal class UpgradeArgParser(private val output: TerminalOutput) {
    fun parse(args: List<String>): UpgradeArgs? {
        var parsed = UpgradeArgs()
        var i = 0
        while (i < args.size) {
            parsed = if (args[i] == "--to") {
                parsed.copy(to = args.getOrNull(i + 1) ?: return null).also { i++ }
            } else {
                FLAGS[args[i]]?.applyTo(parsed) ?: return null
            }
            i++
        }
        return parsed.takeIf { it.coherent }
    }

    fun usage(): Boolean {
        output.line("usage: splice upgrade [--to vX.Y.Z] [--now] [--rollback] | splice upgrade --check")
        return false
    }
}
