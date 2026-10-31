// NEW (Oct 29, 2026, splice-lead; Marcos: "fix everything"): `splice uninstall --all` takes away the systemd user units
// splice laid. Before, it removed only the command links, so splice.service stayed enabled and started again at every
// login from the jar left on disk, and since socket activation systemd also kept holding splice's ports and started
// the daemon again on the next request.
package splice.lifecycle.units

import splice.core.terminal.TerminalOutput

/** The units splice laid through [units], retired: stopped, disabled and removed from splice's own folder. A unit the
 *  user overrides in their own folder is theirs: systemd runs their file, so it is left running and named. */
public class UninstallUnits(private val units: SpliceUnits, private val output: TerminalOutput) {
    public fun retire() {
        val laid = units.marked()
        if (laid.isEmpty()) return
        val (overridden, ours) = laid.partition { units.overriding(it) != null }
        // Sockets go before the units they start: a request on a socket still held would start the daemon again.
        val (sockets, rest) = ours.partition { it.endsWith(SOCKET_SUFFIX) }
        listOf(sockets, rest).filter { it.isNotEmpty() }.forEach(::stop)
        laid.forEach(units::remove)
        if (units.systemctl(listOf("daemon-reload")) == null) {
            output.line(
                "splice: systemctl --user daemon-reload did not run; the user manager forgets splice's units " +
                    "when it next reloads or starts",
            )
        }
        overridden.forEach { name ->
            output.line(
                "splice: ${units.overriding(name)} overrides splice's $name, so it stays, and runs, until its " +
                    "owner removes it",
            )
        }
    }

    private fun stop(names: List<String>) {
        val stopped = units.systemctl(listOf("disable", "--now") + names) != null
        val list = names.joinToString(", ")
        output.line(
            if (stopped) {
                "splice: stopped and disabled $list"
            } else {
                "splice: systemd did not stop and disable $list; " +
                    "run: systemctl --user disable --now ${names.joinToString(" ")}"
            },
        )
    }
}

private const val SOCKET_SUFFIX = ".socket"
