// NEW: V4-220 item 3 (2026-09-25) — why an add was refused, as a value each surface renders. The CLI
// prints its sentences after `splice add: ` byte for byte as before; the console's form names no flag
// (it has no --name to pass) and carries no em-dash (the console copy gate). One decision, two texts:
// AddPrepare and AddWrite decide, and neither surface can add a refusal the other does not render.
package splice.configuration.add

/** A refusal decided before the first side effect. */
internal sealed class AddRefusal {
    data class KeyTaken(val key: String) : AddRefusal()

    data class CommandTaken(val command: String) : AddRefusal()

    /** The operator's file with the new tables appended does not parse; [detail] is SafeFailureText's. */
    data class Unparseable(val detail: String) : AddRefusal()

    data class NameRequired(val profile: String) : AddRefusal()

    data class BaseUrlRequired(val profile: String) : AddRefusal()

    /** A base URL or a command carrying a quote, a backslash or a control character. */
    data object QuotedValue : AddRefusal()

    data class LiveUnsupported(val profile: String) : AddRefusal()

    data class Models(val problem: AddModelProblem) : AddRefusal()
}

/** What makes a row set refusable (AddModelRows.problem), or a prompted window that never became valid. */
internal sealed class AddModelProblem {
    data object None : AddModelProblem()

    data object Quoted : AddModelProblem()

    data object NonPositiveWindow : AddModelProblem()

    data class Repeated(val id: String) : AddModelProblem()

    /** Three answers to the TTY's window prompt that were not a positive integer; the CLI's only. */
    data class PromptedWindow(val id: String) : AddModelProblem()
}

internal class AddRefusalText {

    /** The CLI's sentence, printed after `splice add: `. */
    fun cli(r: AddRefusal): String = when (r) {
        is AddRefusal.KeyTaken -> "'${r.key}' is already configured; pick another --name"
        is AddRefusal.CommandTaken -> "command '${r.command}' already belongs to a head"
        is AddRefusal.Unparseable -> "the candidate topology does not parse: ${r.detail}"
        is AddRefusal.NameRequired -> "--name is required for '${r.profile}' (lowercase letters, digits, dashes)"
        is AddRefusal.BaseUrlRequired -> "--base-url is required for '${r.profile}'"
        AddRefusal.QuotedValue -> "values must not contain quotes"
        is AddRefusal.LiveUnsupported ->
            "--live is only supported for api-key profiles; '${r.profile}' is exercised by its first launch, " +
                "then splice doctor; drop --live"
        is AddRefusal.Models -> cliModels(r.problem)
    }

    /** The console form's sentence: no flag, no em-dash. */
    fun console(r: AddRefusal): String = when (r) {
        is AddRefusal.KeyTaken -> "'${r.key}' is already configured; pick another name."
        is AddRefusal.CommandTaken -> "The command '${r.command}' already belongs to a head; pick another command."
        is AddRefusal.Unparseable -> "The new head does not parse together with your splice.toml: ${r.detail}"
        is AddRefusal.NameRequired -> "'${r.profile}' needs a name: lowercase letters, digits and dashes."
        is AddRefusal.BaseUrlRequired -> "'${r.profile}' needs a base URL."
        AddRefusal.QuotedValue -> "The base URL and the command must not contain quotes."
        is AddRefusal.LiveUnsupported -> "'${r.profile}' has no live turn to run: its first launch exercises it."
        is AddRefusal.Models -> consoleModels(r.problem)
    }

    /** The CLI's line after the file's path: nothing was written. */
    fun cliStale(r: AddWritten.Refused): String = when (r) {
        AddWritten.Changed -> "changed while this add was running; rerun"
        is AddWritten.Unreadable -> "could not be read again (${r.detail}); nothing written"
    }

    /** The console's sentence for the same refusal, naming the file. */
    fun consoleStale(path: String, r: AddWritten.Refused): String = when (r) {
        AddWritten.Changed -> "$path changed while this add was open, so nothing was saved; open the add again."
        is AddWritten.Unreadable -> "$path could not be read again (${r.detail}), so nothing was saved."
    }

    /** add-model's sentence for the same refusal, one for both surfaces: it names no flag and no step. */
    fun modelStale(path: String, r: AddWritten.Refused): String = when (r) {
        AddWritten.Changed -> "$path changed while add-model was open, so nothing was saved; add the models again."
        is AddWritten.Unreadable -> "$path could not be read again (${r.detail}), so nothing was saved."
    }

    private fun cliModels(p: AddModelProblem): String = when (p) {
        AddModelProblem.None -> "no models: pass --model <id>:<context_window> (repeatable)"
        AddModelProblem.Quoted -> "model ids must not contain quotes"
        AddModelProblem.NonPositiveWindow -> "context windows must be positive: --model ID:WINDOW"
        is AddModelProblem.Repeated -> "model '${p.id}' is given more than once"
        is AddModelProblem.PromptedWindow -> "context window for ${p.id} must be a positive integer (tokens)"
    }

    private fun consoleModels(p: AddModelProblem): String = when (p) {
        AddModelProblem.None -> "Add at least one model, with its context window."
        AddModelProblem.Quoted -> "Model ids and labels must not contain quotes."
        AddModelProblem.NonPositiveWindow -> "Each context window must be a positive number of tokens."
        is AddModelProblem.Repeated -> "The model '${p.id}' is listed more than once."
        is AddModelProblem.PromptedWindow -> "The context window for ${p.id} must be a positive number of tokens."
    }
}
