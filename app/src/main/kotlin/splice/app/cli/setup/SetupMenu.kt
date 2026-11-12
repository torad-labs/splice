// The parts of `splice setup` that need no I/O of their own: what was detected, and the summary the operator
// confirms. Split from SetupCommand, which runs the flow.
package splice.app.cli.setup

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
        if (bits.isNotEmpty()) frame.step("Found here: ${bits.joinToString(", ")}")
    }

    fun summaryLines(path: Path, bin: Path, heads: List<String>, claudeLane: String): List<String> {
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
            listOf("Plans to connect: ${heads.joinToString(", ")}")
        }
        val keys = if (heads.any { name -> profiles.find(name)?.provider?.authKind == API_KEY_KIND }) {
            listOf("API keys written by splice add (keys.toml, 0600)")
        } else {
            emptyList()
        }
        // The lane line only when the Claude head is actually being added: a summary that answered
        // a question nobody was asked is noise the operator has to parse past.
        val lane = if (CLAUDE_PROFILE in heads) listOf(claudeLane) else emptyList()
        return listOfNotNull(starter, wrappers) + adding + lane + keys
    }
}
