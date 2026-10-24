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
    fun printDetected(facts: SetupFacts) {
        val bits = mutableListOf<String>()
        if (facts.spliceOwned.isNotEmpty()) bits.add(facts.spliceOwned.sorted().joinToString(", "))
        if (facts.vendorCli.isNotEmpty()) bits.add(facts.vendorCli.sorted().joinToString(", "))
        if (facts.openRouterKey) bits.add("OPENROUTER_API_KEY")
        if (facts.daemonUp) bits.add("daemon")
        if (bits.isNotEmpty()) frame.step("Detected ${bits.joinToString(", ")}")
    }

    fun startOptions(facts: SetupFacts): List<SelectOption<SetupStart>> {
        val options = mutableListOf<SelectOption<SetupStart>>()
        val keyCount = if (facts.openRouterKey) 1 else 0
        options.add(SelectOption(SetupStart.OpenRouter, "OpenRouter", "$keyCount keys"))
        for (kind in AuthKindRegistry.knownKinds().filter { it.isOAuth }) {
            val n = listOf(kind.wire in facts.spliceOwned, kind.wire in facts.vendorCli).count { it }
            options.add(SelectOption(SetupStart.OAuth(kind.wire), kind.signInLabel, "$n credentials"))
        }
        val daemons = if (facts.daemonUp) 1 else 0
        options.add(SelectOption(SetupStart.Existing, "Existing topology", "$daemons daemons"))
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
        val starter = if (Files.exists(path)) {
            "No starter will be written because one is already present"
        } else {
            "Starter topology (no plan) → $path"
        }
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
        return listOf(starter, wrappers) + extra + adding + lane + keys
    }
}
