// NEW: v0.4.0 FEATURES.md §1 — the model rows `splice add` writes: `--model id:window` specs, the
// profile's own rows, or what the operator types in (TTY only), and what makes a row set refusable
// (empty, quotes, a non-positive window, a repeated id). Split from AddPrepare.kt (concentration,
// 2026-09-14).
package splice.app.cli.add

import splice.app.cli.prompt.KeyReader
import splice.app.cli.prompt.MultiSelectOutcome
import splice.app.cli.prompt.MultiSelectPrompt
import splice.app.cli.prompt.SelectOption
import splice.app.cli.prompt.SelectOutcome
import splice.app.cli.prompt.SelectPrompt
import splice.app.cli.prompt.TerminalMode
import splice.core.topology.Topology
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val DEFAULT_WINDOW = 128_000L
private const val MAX_PROMPTED_MODELS = 8
private const val WINDOW_ATTEMPTS = 3

/** A TOML value `splice add` will write bare: no quote, no backslash, no control character. */
internal val addValuePattern = Regex("[^\"\\\\\\p{Cntrl}]+")

internal class AddModelRows(private val prompt: AddPrompter) {

    /** The catalog keys models by id, so a repeated id would keep only the last window (review 2026-09-14). */
    fun problem(models: List<AddModel>): String? {
        val quoted = models.any { !addValuePattern.matches(it.id) || !addValuePattern.matches(it.label) }
        val duplicate = models.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        return when {
            models.isEmpty() -> "no models: pass --model <id>:<context_window> (repeatable)"
            quoted -> "model ids must not contain quotes"
            models.any { it.contextWindow <= 0 } -> "context windows must be positive: --model ID:WINDOW"
            duplicate != null -> "model '$duplicate' is given more than once"
            else -> null
        }
    }

    /** `--model id:window` rows, else the profile's own, else what the operator types in (TTY only). */
    fun resolve(args: AddArgs, profile: AddProfile): List<AddModel> {
        val given = args.models.map { spec ->
            // A model id may itself carry colons (ollama: qwen3:4b), so the window is the LAST segment
            // and only when it is a number; a non-positive number is refused below, never defaulted.
            val window = spec.substringAfterLast(':', "").toLongOrNull()
            val id = if (window == null) spec else spec.substringBeforeLast(':')
            AddModel(id, id, window ?: DEFAULT_WINDOW)
        }
        if (given.isNotEmpty() || profile.models.isNotEmpty()) return given.ifEmpty { profile.models }
        val typed = mutableListOf<AddModel>()
        while (typed.size < MAX_PROMPTED_MODELS) {
            val id = prompt("model id (blank when done):", "")
            if (id.isEmpty()) break
            typed += AddModel(id, id, window(id))
        }
        return typed
    }

    /** A blank answer takes the default (the prompter returns it); "32k", a decimal or a number past
     *  Long is asked again, never silently 128000 — the endpoint's model list does not validate a
     *  window, so a wrong one would be saved (review 2026-09-14). Three misses refuse the add. */
    private fun window(id: String): Long {
        repeat(WINDOW_ATTEMPTS) {
            val answer = prompt("context window for $id:", DEFAULT_WINDOW.toString())
            val window = answer.toLongOrNull()
            if (window != null && window > 0) return window
            println("  context window for $id must be a positive integer (tokens), not '$answer'")
        }
        throw AddRefused("context window for $id must be a positive integer (tokens)")
    }
}

/** V4-34: add OpenRouter model rows through the prompt toolkit, never a hand-rolled readline. */
internal class AddModelVerb(
    private val select: SelectPrompt = SelectPrompt(
        KeyReader(System.`in`),
        TerminalMode(),
        System.out,
    ),
    private val multi: MultiSelectPrompt = MultiSelectPrompt(
        KeyReader(System.`in`),
        TerminalMode(),
        System.out,
    ),
    // The roster edit as a seam (the DR-66 StarterWrite precedent): the fail-closed re-parse below
    // is only testable on the production path if a test can hand write() a composition that does
    // not parse. Production always passes the real editor.
    private val roster: RosterEditor = RosterEditor(HeadModelArray()::withAdded),
) {
    fun add(path: Path): Boolean {
        val existing = Files.readString(path)
        val planned = plan(TopologyLoader.loadOrMaterialize(path)) ?: return false
        if (planned.models.isEmpty()) return false
        write(path, existing, planned)
        return true
    }

    private fun plan(topology: Topology): Planned? {
        val profile = AddProfiles().find("openrouter") ?: return null
        val heads = topology.heads.filter { it.value.provider == profile.headKey }.keys.toList()
        if (heads.isEmpty()) return null
        return pick(profile, topology, heads)
    }

    private fun pick(profile: AddProfile, topology: Topology, heads: List<String>): Planned? {
        val headPick = select.ask("Which head?", heads.map { SelectOption(it, it) }, 0)
        val headKey = (headPick as? SelectOutcome.Chosen)?.value ?: return null
        val head = topology.heads.getValue(headKey)
        val providerKey = head.provider
        val providerIds = topology.providers.getValue(providerKey).models.map { it.id }.toSet()
        // REACHABLE, not merely present. A head that declares `models = [...]` is a ROSTER:
        // Topology.modelsFor returns it verbatim and ignores every other provider row, so a model
        // already in the provider table but absent from the array is still invisible on /v1/models
        // and must stay on offer (V4-34 redo 2026-09-17).
        val reachable = head.models?.map { it.id }?.toSet() ?: providerIds
        val remaining = profile.models.filter { it.id !in reachable }
        val ids = if (remaining.isEmpty()) {
            emptyList()
        } else {
            val picked = multi.ask(
                "Add OpenRouter models",
                remaining.map { SelectOption(it.id, it.label, it.id) },
                initiallySelected = emptySet(),
                minimum = 0,
            )
            (picked as? MultiSelectOutcome.Chosen)?.values ?: return null
        }
        return Planned(providerKey, headKey, head.models != null, providerIds, remaining.filter { it.id in ids })
    }

    private fun write(path: Path, existing: String, planned: Planned) {
        val key = planned.providerKey
        // Only ids the provider table does not already carry: on the shipped starter every curated
        // id is already a provider row and the roster is what was missing, so a second copy here
        // would be a duplicate the catalog silently collapses. No rows means the file keeps exactly
        // its trailing newline rather than gaining a blank line (V4-34 redo 2026-09-17).
        val rows = planned.models.filter { it.id !in planned.providerIds }.flatMap { model ->
            listOf(
                "[[providers.$key.models]]",
                "id = \"${model.id}\"",
                "label = \"${model.label}\"",
                "context_window = ${model.contextWindow}",
            )
        }
        val extra = if (rows.isEmpty()) "\n" else rows.joinToString("\n", prefix = "\n", postfix = "\n")
        val rostered = if (planned.headDeclaresModels) {
            roster(existing, planned.headKey, planned.models.map { it.id })
        } else {
            existing
        }
        val composed = rostered.trimEnd('\n') + extra
        refuseUnparseable(composed)
        val tmp = path.resolveSibling(path.fileName.toString() + ".add-model-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, composed)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** FAIL CLOSED (review 2026-09-17 (2)): the composition is parsed by the loader `splice` itself
     *  boots with BEFORE any byte reaches the operator's file, so a corrupted edit refuses instead
     *  of riding ATOMIC_MOVE over a working splice.toml. Nothing is written on the refusal — not
     *  even the temp file, which is created after this returns. */
    private fun refuseUnparseable(composed: String) {
        val failure = splice.core.util.Cancellables
            .runCatchingCancellable { TopologyLoader.parse(composed) }
            .exceptionOrNull() ?: return
        throw AddRefused(
            "the roster edit does not parse, so splice.toml was left untouched: " +
                splice.core.util.SafeFailureText.render(failure),
        )
    }

    private data class Planned(
        val providerKey: String,
        val headKey: String,
        val headDeclaresModels: Boolean,
        val providerIds: Set<String>,
        val models: List<AddModel>,
    )
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
