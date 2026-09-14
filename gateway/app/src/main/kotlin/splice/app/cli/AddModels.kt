// NEW: v0.4.0 FEATURES.md §1 — the model rows `splice add` writes: `--model id:window` specs, the
// profile's own rows, or what the operator types in (TTY only), and what makes a row set refusable
// (empty, quotes, a non-positive window, a repeated id). Split from AddPrepare.kt (concentration,
// 2026-09-14).
package splice.app.cli

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
