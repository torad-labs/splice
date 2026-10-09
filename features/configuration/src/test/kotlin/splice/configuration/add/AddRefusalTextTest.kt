// One refusal, two texts: the CLI names its flags, the console has no flag to name and carries no em-dash
// (the console copy gate). Every refusal case is sampled and checked against the sealed hierarchies, so a
// new refusal fails here by name until both of its texts are checked.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddRefusalTextTest {

    private val texts = AddRefusalText()

    private val refusals: List<AddRefusal> = listOf(
        AddRefusal.KeyTaken("fw"),
        AddRefusal.CommandTaken("claudex"),
        AddRefusal.Unparseable("bad table"),
        AddRefusal.NameRequired("api-key"),
        AddRefusal.BaseUrlRequired("api-key"),
        AddRefusal.PortUnavailable(3100, 65535),
        AddRefusal.QuotedValue,
        AddRefusal.Models(AddModelProblem.None),
        AddRefusal.Models(AddModelProblem.Quoted),
        AddRefusal.Models(AddModelProblem.NonPositiveWindow),
        AddRefusal.Models(AddModelProblem.Repeated("m")),
        AddRefusal.Models(AddModelProblem.PromptedWindow("m")),
    )

    private val stale: List<AddWritten.Refused> = listOf(AddWritten.Changed, AddWritten.Unreadable("gone"))

    @Test
    fun `the console names no flag and carries no em-dash, and the CLI carries no em-dash`() {
        val console = refusals.map(texts::console) + stale.map { texts.consoleStale("/c/splice.toml", it) }
        val cli = refusals.map(texts::cli) + stale.map(texts::cliStale)
        console.forEach { sentence ->
            assertTrue('—' !in sentence && "--" !in sentence, "console text carries CLI copy: $sentence")
        }
        cli.forEach { sentence -> assertTrue('—' !in sentence && sentence.isNotBlank(), "CLI text: $sentence") }
    }

    @Test
    fun `every refusal is sampled`() {
        val sampled = refusals.map { it::class } + refusals.filterIsInstance<AddRefusal.Models>()
            .map { it.problem::class }
        val declared = AddRefusal::class.sealedSubclasses + AddModelProblem::class.sealedSubclasses
        assertEquals(declared.map { it.simpleName }.toSet(), sampled.map { it.simpleName }.toSet())
        val staleDeclared = AddWritten.Refused::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(staleDeclared, stale.map { it::class.simpleName }.toSet())
    }
}
