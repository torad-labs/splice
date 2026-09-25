// NEW: v0.4.0 FEATURES.md §1 — the model rows `splice add` writes: `--model id:window` specs, the
// profile's own rows, or what the operator types in (TTY only), and what makes a row set refusable
// (empty, quotes, a non-positive window, a repeated id). Split from AddPrepare.kt (concentration,
// 2026-09-14).
package splice.configuration.add

import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.terminal.MultiSelectOutcome
import splice.terminal.MultiSelectPrompt
import splice.terminal.SelectOption
import splice.terminal.SelectOutcome
import splice.terminal.SelectPrompt
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

internal const val DEFAULT_WINDOW = 128_000L
private const val MAX_PROMPTED_MODELS = 8
private const val WINDOW_ATTEMPTS = 3

/** A TOML value `splice add` will write bare: no quote, no backslash, no control character. */
internal val addValuePattern = Regex("[^\"\\\\\\p{Cntrl}]+")

internal class AddModelRows(private val output: TerminalOutput, private val prompt: AddPrompter) {

    /** The catalog keys models by id, so a repeated id would keep only the last window (review 2026-09-14). */
    fun problem(models: List<AddModel>): AddModelProblem? {
        val quoted = models.any { !addValuePattern.matches(it.id) || !addValuePattern.matches(it.label) }
        val duplicate = models.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        return when {
            models.isEmpty() -> AddModelProblem.None
            quoted -> AddModelProblem.Quoted
            models.any { it.contextWindow <= 0 } -> AddModelProblem.NonPositiveWindow
            duplicate != null -> AddModelProblem.Repeated(duplicate)
            else -> null
        }
    }

    /** `--model id:window` rows, else the profile's own, else what the operator types in (TTY only). */
    fun resolve(args: AddArgs, profile: AddProfile): AddRows {
        val given = args.models.map { spec ->
            // A model id may itself carry colons (ollama: qwen3:4b), so the window is the LAST segment
            // and only when it is a number; a non-positive number is refused below, never defaulted.
            val window = spec.substringAfterLast(':', "").toLongOrNull()
            val id = if (window == null) spec else spec.substringBeforeLast(':')
            AddModel(id, id, window ?: DEFAULT_WINDOW)
        }
        if (given.isNotEmpty() || profile.models.isNotEmpty()) return AddRows.Resolved(given.ifEmpty { profile.models })
        val typed = mutableListOf<AddModel>()
        while (typed.size < MAX_PROMPTED_MODELS) {
            val id = prompt("model id (blank when done):", "")
            if (id.isEmpty()) break
            val window = window(id) ?: return AddRows.Refused(AddModelProblem.PromptedWindow(id))
            typed += AddModel(id, id, window)
        }
        return AddRows.Resolved(typed)
    }

    /** A blank answer takes the default (the prompter returns it); "32k", a decimal or a number past
     *  Long is asked again, never silently 128000 — the endpoint's model list does not validate a
     *  window, so a wrong one would be saved (review 2026-09-14). Three misses are null: the add refuses. */
    private fun window(id: String): Long? {
        repeat(WINDOW_ATTEMPTS) {
            val answer = prompt("context window for $id:", DEFAULT_WINDOW.toString())
            val window = answer.toLongOrNull()
            if (window != null && window > 0) return window
            output.line("  context window for $id must be a positive integer (tokens), not '$answer'")
        }
        return null
    }
}

/** [AddModelRows.resolve]'s answer: the rows, or the prompted window that never became valid. */
internal sealed class AddRows {
    data class Resolved(val models: List<AddModel>) : AddRows()

    data class Refused(val problem: AddModelProblem) : AddRows()
}

/** V4-34: add OpenRouter model rows through the prompt toolkit, never a hand-rolled readline. The two
 *  prompts come from app (AddWiring), which owns the terminal they read. What is on offer and what is
 *  written are AddModelOffers' and AddModelCompose's, which the console's add-model shares. */
internal class AddModelVerb(
    private val select: SelectPrompt,
    private val multi: MultiSelectPrompt,
    roster: RosterEditor = RosterEditor(HeadModelArray()::withAdded),
) {
    private val compose = AddModelCompose(roster)

    /** The file is read before the prompts and written through AddWrite's re-read, so an edit made while
     *  a picker was open refuses the add instead of being renamed over (V4-220). */
    fun add(path: Path): Boolean {
        val existing = Files.readString(path)
        val planned = plan(TopologyLoader.loadOrMaterialize(path)) ?: return false
        if (planned.models.isEmpty()) return false
        return when (val written = AddWrite().replace(path, existing, compose(existing, planned))) {
            AddWritten.Written -> true
            is AddWritten.Refused -> throw AddRefused(AddRefusalText().modelStale(path.toString(), written))
        }
    }

    private fun plan(topology: Topology): AddModelPlan? {
        val offers = AddModelOffers().of(topology)
        if (offers.isEmpty()) return null
        val headPick = select.ask("Which head?", offers.map { SelectOption(it.headKey, it.headKey) }, 0)
        val headKey = (headPick as? SelectOutcome.Chosen)?.value ?: return null
        return pick(offers.first { it.headKey == headKey })
    }

    private fun pick(offer: AddModelOffer): AddModelPlan? {
        if (offer.remaining.isEmpty()) return AddModelPlan(offer, emptyList())
        val picked = multi.ask(
            "Add OpenRouter models",
            offer.remaining.map { SelectOption(it.id, it.label, it.id) },
            initiallySelected = emptySet(),
            minimum = 0,
        )
        val ids = (picked as? MultiSelectOutcome.Chosen)?.values ?: return null
        return AddModelPlan(offer, offer.remaining.filter { it.id in ids })
    }
}

/** V4-34 redo (2026-09-17): the `models = [...]` array on `[heads.KEY]` is the head's ROSTER, not a
 *  hint — Topology.modelsFor (Topology.kt:206) returns it verbatim and ignores every other provider
 *  row. The shipped starter declares one, so `splice add-model` writing only a
 *  `[[providers.KEY.models]]` table left the added id invisible on /v1/models. This edits the array
 *  in place: every byte OUTSIDE the brackets is preserved, and an id the array already names is a
 *  no-op, so a repeated add is idempotent.
 *
 *  Review 2026-09-17 (2): structure is found on the MASK — TomlStructureMasker blanks comments and
 *  string bodies at the SAME offsets — and every edit is applied to the original text at those
 *  offsets. Raw-text scanning read a commented-out `# { id = "..." }` as present (the add became a
 *  silent no-op) and let a `]` inside a comment close the array early, splicing the file mid-array
 *  and moving the corruption over splice.toml. */
