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

    /** No available loopback listener port in the searched inclusive range. */
    data class PortUnavailable(val from: Int, val to: Int) : AddRefusal()

    /** A base URL or a command carrying a quote, a backslash or a control character. */
    data object QuotedValue : AddRefusal()

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
        is AddRefusal.PortUnavailable -> "no free loopback head port in ${r.from}..${r.to}; free a port and retry"
        is AddRefusal.Models -> cliModels(r.problem)
        is AddRefusal.KeyTaken, is AddRefusal.CommandTaken, is AddRefusal.Unparseable, is AddRefusal.NameRequired,
        is AddRefusal.BaseUrlRequired,
        AddRefusal.QuotedValue,
        -> cliStandard(r)
    }

    private fun cliStandard(r: AddRefusal): String = when (r) {
        is AddRefusal.KeyTaken -> "'${r.key}' is already configured; pick another --name"
        is AddRefusal.CommandTaken -> "command '${r.command}' already belongs to a head"
        is AddRefusal.Unparseable -> "the candidate topology does not parse: ${r.detail}"
        is AddRefusal.NameRequired -> "--name is required for '${r.profile}' (lowercase letters, digits, dashes)"
        is AddRefusal.BaseUrlRequired -> "--base-url is required for '${r.profile}'"
        AddRefusal.QuotedValue -> "values must not contain quotes"
        is AddRefusal.PortUnavailable, is AddRefusal.Models -> error("handled by cli: ${r::class.simpleName}")
    }

    /** The console form's sentence: no flag, no em-dash. */
    fun console(r: AddRefusal): String = when (r) {
        is AddRefusal.PortUnavailable ->
            "No free loopback head port in ${r.from}..${r.to}; free a port and try again."
        is AddRefusal.Models -> consoleModels(r.problem)
        is AddRefusal.KeyTaken, is AddRefusal.CommandTaken, is AddRefusal.Unparseable, is AddRefusal.NameRequired,
        is AddRefusal.BaseUrlRequired,
        AddRefusal.QuotedValue,
        -> consoleStandard(r)
    }

    /** The editable input an opening refusal names, independent of either surface's sentence. */
    fun field(r: AddRefusal): String? = when (r) {
        is AddRefusal.KeyTaken, is AddRefusal.NameRequired -> "name"
        is AddRefusal.CommandTaken -> "command"
        is AddRefusal.BaseUrlRequired -> "base_url"
        is AddRefusal.Models -> "models"
        is AddRefusal.Unparseable, is AddRefusal.PortUnavailable, AddRefusal.QuotedValue -> null
    }

    private fun consoleStandard(r: AddRefusal): String = when (r) {
        is AddRefusal.KeyTaken -> "'${r.key}' is already configured; pick another name."
        is AddRefusal.CommandTaken -> "The command '${r.command}' already belongs to a head; pick another command."
        is AddRefusal.Unparseable -> "The new head does not parse together with your splice.toml: ${r.detail}"
        is AddRefusal.NameRequired -> "'${r.profile}' needs a name: lowercase letters, digits and dashes."
        is AddRefusal.BaseUrlRequired -> "'${r.profile}' needs a base URL."
        AddRefusal.QuotedValue -> "The base URL and the command must not contain quotes."
        is AddRefusal.PortUnavailable, is AddRefusal.Models -> error("handled by console: ${r::class.simpleName}")
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
