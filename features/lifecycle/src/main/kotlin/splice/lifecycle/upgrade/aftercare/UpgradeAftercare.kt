// NEW: what an upgrade does once the new release passed its checks: `splice upgrade --aftercare [--auto]`, run by
// UpgradeCommand on the NEW jar, so the release that knows what it added is the one that lays it. Two halves:
//   - maintenance, idempotent and best effort: the files an install that predates this release lacks are laid or
//     refreshed (the Antigravity shim install.sh lays, the MCP hub's units and mcp-front.env where the hub is
//     installed). A step that fails is said and never fails the upgrade, which has already passed.
//   - on an interactive upgrade only, a short "What's new / action needed" list of what the install has not set up
//     yet, pointing at `splice setup --update`. `--auto` (the daemon's updater) prints no list and asks nothing.
// The executable holds what both halves read (Desktop's mode, the hub, the harnesses), so it hands them in.
package splice.lifecycle.upgrade.aftercare

import splice.core.terminal.BOLD
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.lifecycle.upgrade.UPGRADE_PAD

/** One maintenance step: what it did, a line each (none when there was nothing to do). May throw; the aftercare says
 *  the failure and goes on. */
public fun interface AftercareStep {
    public operator fun invoke(): List<String>
}

/** What the install has not set up yet, a line each, in the words of what to do. */
public fun interface AftercareGaps {
    public operator fun invoke(): List<String>
}

/** The aftercare [steps] and [gaps] the executable hands in; none of either by default. */
public class UpgradeAftercare(
    private val output: TerminalOutput,
    private val steps: List<AftercareStep> = emptyList(),
    private val gaps: AftercareGaps = AftercareGaps { emptyList() },
) {
    /** Every step, then on an interactive run ([auto] false) the list of gaps. Always true: nothing here can undo an
     *  upgrade that passed. */
    public fun run(auto: Boolean): Boolean {
        steps.forEach { step ->
            Cancellables.runCatchingBestEffort { step() }.fold(
                onSuccess = { lines -> lines.forEach { output.line("  ${"aftercare".padEnd(UPGRADE_PAD)} $it") } },
                onFailure = {
                    val why = SafeFailureText.render(it)
                    output.line("  $YELLOW!$RESET ${"aftercare".padEnd(UPGRADE_PAD)} a step did not run ($why)")
                },
            )
        }
        if (!auto) checklist()
        return true
    }

    private fun checklist() {
        val found = Cancellables.runCatchingBestEffort { gaps() }.getOrDefault(emptyList())
        if (found.isEmpty()) return
        output.line("")
        output.line("${BOLD}What's new / action needed$RESET")
        found.forEach { output.line("  - $it") }
        output.line("  Run `splice setup --update` to set these up; it asks only about what is new.")
    }
}

/** The verb the new jar answers after an upgrade, and the flag that keeps it quiet for the daemon's updater. */
internal const val AFTERCARE_FLAG = "--aftercare"
internal const val AFTERCARE_AUTO_FLAG = "--auto"
