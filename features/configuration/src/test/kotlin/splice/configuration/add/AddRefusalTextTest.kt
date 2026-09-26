// NEW: V4-220 item 3 — one refusal, two texts. The CLI's sentences are pinned byte for byte as
// AddPrepare and AddWrite printed them before the refusals became values; the console's carry no flag
// (the form has no --name to pass) and no em-dash (the console copy gate). Every case is sampled, and
// the samples are checked against the sealed hierarchies themselves, so a new refusal fails here by
// name until both of its texts are pinned.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddRefusalTextTest {

    private val texts = AddRefusalText()

    /** Each refusal with its CLI sentence (no em dash since V4-238) and the console's. */
    private val refusals: Map<AddRefusal, Pair<String, String>> = mapOf(
        AddRefusal.KeyTaken("fw") to
            ("'fw' is already configured; pick another --name" to "'fw' is already configured; pick another name."),
        AddRefusal.CommandTaken("claudex") to (
            "command 'claudex' already belongs to a head" to
                "The command 'claudex' already belongs to a head; pick another command."
            ),
        AddRefusal.Unparseable("bad table") to (
            "the candidate topology does not parse: bad table" to
                "The new head does not parse together with your splice.toml: bad table"
            ),
        AddRefusal.NameRequired("api-key") to (
            "--name is required for 'api-key' (lowercase letters, digits, dashes)" to
                "'api-key' needs a name: lowercase letters, digits and dashes."
            ),
        AddRefusal.BaseUrlRequired("api-key") to
            ("--base-url is required for 'api-key'" to "'api-key' needs a base URL."),
        AddRefusal.QuotedValue to
            ("values must not contain quotes" to "The base URL and the command must not contain quotes."),
        AddRefusal.LiveUnsupported("codex") to (
            "--live is only supported for api-key profiles; 'codex' is exercised by its first launch, " +
                "then splice doctor; drop --live" to
                "'codex' has no live turn to run: its first launch exercises it."
            ),
        AddRefusal.Models(AddModelProblem.None) to (
            "no models: pass --model <id>:<context_window> (repeatable)" to
                "Add at least one model, with its context window."
            ),
        AddRefusal.Models(AddModelProblem.Quoted) to
            ("model ids must not contain quotes" to "Model ids and labels must not contain quotes."),
        AddRefusal.Models(AddModelProblem.NonPositiveWindow) to (
            "context windows must be positive: --model ID:WINDOW" to
                "Each context window must be a positive number of tokens."
            ),
        AddRefusal.Models(AddModelProblem.Repeated("m")) to
            ("model 'm' is given more than once" to "The model 'm' is listed more than once."),
        AddRefusal.Models(AddModelProblem.PromptedWindow("m")) to (
            "context window for m must be a positive integer (tokens)" to
                "The context window for m must be a positive number of tokens."
            ),
    )

    private val stale: Map<AddWritten.Refused, Pair<String, String>> = mapOf(
        AddWritten.Changed to (
            "changed while this add was running; rerun" to
                "/c/splice.toml changed while this add was open, so nothing was saved; open the add again."
            ),
        AddWritten.Unreadable("gone") to (
            "could not be read again (gone); nothing written" to
                "/c/splice.toml could not be read again (gone), so nothing was saved."
            ),
    )

    @Test
    fun `the CLI prints every refusal as it did before the refusals became values`() {
        refusals.forEach { (refusal, text) -> assertEquals(text.first, texts.cli(refusal), "$refusal") }
        stale.forEach { (refused, text) -> assertEquals(text.first, texts.cliStale(refused), "$refused") }
    }

    @Test
    fun `the console names no flag and carries no em-dash`() {
        refusals.forEach { (refusal, text) -> assertEquals(text.second, texts.console(refusal), "$refusal") }
        stale.forEach { (refused, text) ->
            assertEquals(text.second, texts.consoleStale("/c/splice.toml", refused), "$refused")
        }
        val console = refusals.values.map { it.second } + stale.values.map { it.second }
        console.forEach { sentence ->
            assertTrue('—' !in sentence && "--" !in sentence, "console text carries CLI copy: $sentence")
        }
    }

    @Test
    fun `every refusal is sampled`() {
        val sampled = refusals.keys.map { it::class } + refusals.keys.filterIsInstance<AddRefusal.Models>()
            .map { it.problem::class }
        val declared = AddRefusal::class.sealedSubclasses + AddModelProblem::class.sealedSubclasses
        assertEquals(declared.map { it.simpleName }.toSet(), sampled.map { it.simpleName }.toSet())
        val staleDeclared = AddWritten.Refused::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(staleDeclared, stale.keys.map { it::class.simpleName }.toSet())
    }
}
