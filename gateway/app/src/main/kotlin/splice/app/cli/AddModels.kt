// NEW: v0.4.0 FEATURES.md §1 — the model rows `splice add` writes: `--model id:window` specs, the
// profile's own rows, or what the operator types in (TTY only), and what makes a row set refusable
// (empty, quotes, a non-positive window, a repeated id). Split from AddPrepare.kt (concentration,
// 2026-09-14).
package splice.app.cli

import splice.app.TopologyLoader
import splice.app.cli.prompt.KeyReader
import splice.app.cli.prompt.MultiSelectOutcome
import splice.app.cli.prompt.MultiSelectPrompt
import splice.app.cli.prompt.SelectOption
import splice.app.cli.prompt.SelectOutcome
import splice.app.cli.prompt.SelectPrompt
import splice.app.cli.prompt.TerminalMode
import splice.core.topology.Topology
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
        val providerKey = topology.heads.getValue(headKey).provider
        val present = topology.providers.getValue(providerKey).models.map { it.id }.toSet()
        val remaining = profile.models.filter { it.id !in present }
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
        return Planned(providerKey, remaining.filter { it.id in ids })
    }

    private fun write(path: Path, existing: String, planned: Planned) {
        val key = planned.providerKey
        val extra = planned.models.flatMap { model ->
            listOf(
                "[[providers.$key.models]]",
                "id = \"${model.id}\"",
                "label = \"${model.label}\"",
                "context_window = ${model.contextWindow}",
            )
        }.joinToString("\n", prefix = "\n", postfix = "\n")
        val tmp = path.resolveSibling(path.fileName.toString() + ".add-model-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, existing.trimEnd('\n') + extra)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private data class Planned(val providerKey: String, val models: List<AddModel>)
}
