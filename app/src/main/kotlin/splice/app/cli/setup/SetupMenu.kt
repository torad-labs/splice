// The questions of `splice setup` that need no I/O of their own: what was detected, the start options, which one is
// preselected, and the summary the operator confirms. Split from SetupCommand, which runs the flow.
package splice.app.cli.setup

import splice.configuration.add.AddProfiles
import splice.core.topology.AuthKindRegistry
import splice.terminal.SelectOption
import splice.terminal.SelectOutcome
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

    fun startOptions(facts: SetupFacts): List<SelectOption<SetupStart>> {
        val options = mutableListOf<SelectOption<SetupStart>>()
        // Each hint says what was found, in words: the counts they were ("1 keys", "0 credentials", "1 daemons")
        // could only ever be none or one.
        val key = if (facts.openRouterKey) "key found" else "no key yet"
        options.add(SelectOption(SetupStart.OpenRouter, "OpenRouter", key))
        for (kind in AuthKindRegistry.knownKinds().filter { it.isOAuth }) {
            val hint = when (kind.wire) {
                in facts.spliceOwned -> "signed in"
                in facts.vendorCli -> "signed in to its own app"
                else -> "not signed in"
            }
            options.add(SelectOption(SetupStart.OAuth(kind.wire), kind.signInLabel, hint))
        }
        val running = if (facts.daemonUp) "splice is running" else "splice is not running"
        options.add(SelectOption(SetupStart.Existing, "Existing topology", running))
        return options
    }

    fun initialIndex(facts: SetupFacts, options: List<SelectOption<SetupStart>>): Int {
        val i = options.indexOfFirst { it.value == facts.suggested }
        return if (i < 0) 0 else i
    }

    fun chosenStart(picked: SelectOutcome<SetupStart>): SetupStart? = when (picked) {
        is SelectOutcome.Chosen -> picked.value
        SelectOutcome.Cancelled -> null
    }

    fun summaryLines(
        start: SetupStart,
        path: Path,
        bin: Path,
        heads: List<String>,
        claudeLane: String,
    ): List<String> {
        // The summary lists what the install will do. The installer writes the file before setup runs, so until
        // Nov 11, 2026 every new install's summary opened on "No starter will be written because one is already
        // present": a line about nothing happening, in words ("starter") nobody had been given.
        val starter = "Starter topology (no plan) → $path".takeUnless { Files.exists(path) }
        val wrappers = "Wrapper commands under $bin"
        val suggested = when (start) {
            is SetupStart.OAuth -> profiles.catalog().firstOrNull { it.provider.authKind == start.kind }
            SetupStart.OpenRouter -> profiles.find("openrouter")
            SetupStart.Existing -> null
        }
        val extra = suggested?.takeUnless { it.name in heads }
            ?.let { listOf("Connect the chosen plan with: splice add ${it.name}") }.orEmpty()
        val adding = if (heads.isEmpty()) {
            emptyList()
        } else {
            listOf("Heads to add: ${heads.joinToString(", ")}")
        }
        val keys = if (heads.any { name -> profiles.find(name)?.provider?.authKind == API_KEY_KIND }) {
            listOf("API keys written by splice add (keys.toml, 0600)")
        } else {
            emptyList()
        }
        // The lane line only when the Claude head is actually being added: a summary that answered
        // a question nobody was asked is noise the operator has to parse past.
        val lane = if (CLAUDE_PROFILE in heads) listOf(claudeLane) else emptyList()
        return listOfNotNull(starter, wrappers) + extra + adding + lane + keys
    }
}
