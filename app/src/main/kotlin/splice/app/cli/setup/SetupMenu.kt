// The parts of `splice setup` that need no I/O of their own: what was detected, and the summary the operator
// confirms. Split from SetupCommand, which runs the flow.
package splice.app.cli.setup

import splice.app.capability.Capabilities
import splice.app.capability.DesktopFacts
import splice.app.capability.WrapperState
import splice.app.desktop.setup.DesktopMode
import splice.configuration.add.AddProfiles
import splice.core.topology.AuthKindRegistry
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

internal class SetupMenu(private val frame: WizardFrame, private val profiles: AddProfiles) {
    /** What setup found on this machine before it asks anything, in the words the rest of the wizard uses. Until
     *  Nov 11, 2026 it printed the config's own identifiers ("Detected chatgpt-oauth, daemon"). */
    fun printDetected(facts: SetupFacts) {
        val bits = mutableListOf<String>()
        for (kind in AuthKindRegistry.knownKinds().filter { it.isOAuth }) {
            when (kind.wire) {
                in facts.spliceOwned -> bits.add("${kind.signInLabel} signed in")
                in facts.vendorCli -> bits.add("${kind.signInLabel} signed in to its own app")
            }
        }
        if (facts.openRouterKey) bits.add("an OpenRouter key")
        if (facts.daemonUp) bits.add("splice running")
        facts.capabilities?.let { bits.addAll(tools(it)) }
        if (bits.isNotEmpty()) frame.step("Found here: ${bits.joinToString(", ")}")
    }

    /** The optional programs found, each in a few words: Claude Desktop with the mode splice has it in, agy and codex,
     *  and a managed processWrapper where Claude Code's managed settings name one. */
    private fun tools(facts: Capabilities): List<String> = buildList {
        val desktop = facts.desktop
        if (desktop.program != null) add("Claude Desktop (${mode(desktop)})")
        if (facts.harness.agy != null) add("agy")
        if (facts.harness.codex != null) add("codex")
        when (desktop.wrapper) {
            WrapperState.SPLICE -> add("a managed processWrapper on splice's desktop-launch")
            WrapperState.OTHER -> add("a managed processWrapper naming another program")
            WrapperState.UNREADABLE -> add("a managed settings file splice could not read")
            WrapperState.NONE -> Unit
        }
    }

    /** Claude Desktop's mode in the words the rest of the wizard uses. */
    private fun mode(desktop: DesktopFacts): String = when (desktop.mode) {
        DesktopMode.APP -> "all of it on splice"
        DesktopMode.STANDARD -> "on splice's older Code-tab door"
        DesktopMode.OTHER -> "on another configuration"
        DesktopMode.OFF -> if (desktop.sideBySideApp) "Claude (Splice) beside it" else "not on splice"
    }

    fun summaryLines(path: Path, bin: Path, heads: List<String>, launchLines: List<String>): List<String> {
        // The summary lists what the install will do. The installer writes the file before setup runs, so until
        // Nov 11, 2026 every new install's summary opened on "No starter will be written because one is already
        // present": a line about nothing happening, in words ("starter") nobody had been given.
        val starter = "Starter topology (no plan) → $path".takeUnless { Files.exists(path) }
        val wrappers = "Wrapper commands under $bin"
        // Until Nov 11, 2026 a "Starting point" menu came before the list, and its pick did one thing: this summary
        // said "Connect the chosen plan with: splice add openrouter" for a plan the person had not ticked, on a
        // summary of what the install will do. The list is where a plan is chosen, so the menu and its line are gone.
        val adding = if (heads.isEmpty()) {
            emptyList()
        } else {
            // Said before the confirm: setup retires each plan's command once it is added (SetupHeads.addAll).
            listOf(
                "Plans to connect: ${heads.joinToString(", ")}",
                "Their models run in `claude` (`/model`); custom head commands are deprecated, so none is kept " +
                    "(`splice claude restore <name>` brings one back)",
            )
        }
        val keys = if (heads.any { name -> profiles.find(name)?.provider?.authKind == API_KEY_KIND }) {
            listOf("API keys written by splice add (keys.toml, 0600)")
        } else {
            emptyList()
        }
        return listOfNotNull(starter, wrappers) + adding + launchLines + keys
    }
}
