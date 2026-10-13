// NEW: v0.4.0 spec section 1 — the two questions `splice add` asks that setup's `add --yes` skipped: which of a
// profile's models to write, with their context windows, and one optional short live turn once the head is up.
// Off a terminal nothing is asked: every head keeps its profile's models and no live turn runs, as before.
package splice.app.cli.setup

import splice.app.CommandLiveTurn
import splice.app.ConsolePrompter
import splice.configuration.add.AddModelChoice
import splice.configuration.add.AddProfiles
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.terminal.MultiSelectOutcome
import splice.terminal.SelectOption

/** What the person chose for one head: the `splice add` arguments that write it, and whether to check it live. */
internal data class HeadChoice(val addArgs: List<String>, val live: Boolean, val summary: String)

internal class SetupHeadChoices(
    private val prompts: SetupPrompts,
    private val profiles: AddProfiles,
    private val line: ConsolePrompter = ConsolePrompter(),
    private val liveTurn: CommandLiveTurn = CommandLiveTurn(),
) {
    // The last answers, kept for [checkLive] after the install.
    private var answers: Map<String, HeadChoice> = emptyMap()

    /** Asks, for each ticked head, its models and windows and whether to run the live turn. Empty off a terminal. */
    fun ask(heads: List<String>): Map<String, HeadChoice> {
        if (!prompts.hasConsole()) return emptyMap<String, HeadChoice>().also { answers = it }
        return heads.associateWith { name ->
            val offered = profiles.find(name)?.modelChoices.orEmpty()
            val picked = if (offered.isEmpty()) emptyList() else models(name, offered)
            val live = prompts.frame.confirm("Run one short live turn against '$name' once it is up?", false)
            val args = picked.flatMap { listOf("--model", "${it.id}:${it.contextWindow}") }
            val said = if (picked.isEmpty()) "its default models" else picked.joinToString(", ") { it.id }
            HeadChoice(args, live, "$name: $said${if (live) ", then a live turn" else ""}")
        }.also { answers = it }
    }

    /** The profile's models ticked by default, then each kept window or a new one. All of them when none is kept. */
    private fun models(name: String, offered: List<AddModelChoice>): List<AddModelChoice> {
        val options = offered.map { SelectOption(it.id, it.label, "${it.id} · ${it.contextWindow} tokens") }
        val outcome = prompts.pickModels("Models for $name", options, offered.map { it.id }.toSet())
        val chosen = when (outcome) {
            is MultiSelectOutcome.Chosen -> offered.filter { it.id in outcome.values }
            MultiSelectOutcome.Cancelled -> offered
        }.ifEmpty { offered }
        if (prompts.frame.confirm("Keep these context windows for $name?", true)) return chosen
        return chosen.map { it.copy(contextWindow = window(it)) }
    }

    /** A blank answer keeps the window; anything not a positive whole number is asked again, then kept. */
    private fun window(model: AddModelChoice): Long {
        repeat(WINDOW_ATTEMPTS) {
            val answer = line("Context window for ${model.id} (tokens):", model.contextWindow.toString())
            answer.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            println("  a context window is a positive whole number of tokens, not '$answer'")
        }
        return model.contextWindow
    }

    /** The live turn for each landed head that asked for one, once setup has restarted the daemon and signed in. */
    fun checkLive(landed: List<String>, topology: Topology, env: EnvReader) {
        landed.filter { answers[it]?.live == true }.forEach { key ->
            val head = topology.heads[key] ?: return@forEach
            val model = head.models.orEmpty().firstOrNull()?.id ?: return@forEach
            val result = Cancellables.runCatchingBestEffort { liveTurn(head.claude.command ?: key, model, env) }
            val said = result.fold({ it.detail }, { "the check could not run" })
            val ok = result.getOrNull()?.ok == true
            prompts.frame.step("${if (ok) "✓" else "✗"} live turn $key: $said")
        }
    }
}

private const val WINDOW_ATTEMPTS = 3
