// NEW: V4-136 — the pin that keeps rules() and resolve() from drifting.
//
// rules() exists so the console's route does not enumerate the compaction table a second time. That
// only holds if the enumeration cannot disagree with the lookup, so the assertion here is not "the
// list has these entries" (a list checked against itself) but "every outcome resolve() actually
// returns is IN the list, by scope and by source" — the denominator comes from resolve, over a
// matrix of (model, project) pairs, rather than from a hand-written expectation.
package splice.core.compaction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

private const val GLOBAL_TEXT = "global instructions"
private const val MODEL_TEXT = "model instructions"
private const val PROJECT_TEXT = "project instructions"
private const val PROJECT_MODEL_TEXT = "project model instructions"

class CompactionRulesTest {

    private val dir: Path = Files.createTempDirectory("v4136")
    private val projectA: Path = Files.createDirectories(dir.resolve("work/alpha"))
    private val projectB: Path = Files.createDirectories(dir.resolve("work/beta"))

    private fun instructions(readFile: CompactionFileRead = CompactionFileRead { Files.readString(it) }) =
        CompactionInstructions(
            config = CompactionConfig(
                instructions = GLOBAL_TEXT,
                model = listOf(CompactionModelConfig(model = "m1", instructions = MODEL_TEXT)),
                project = listOf(
                    CompactionProjectConfig(path = projectA.toString(), instructions = PROJECT_TEXT),
                    CompactionProjectConfig(
                        path = projectA.toString(),
                        model = "m1",
                        instructions = PROJECT_MODEL_TEXT,
                    ),
                    CompactionProjectConfig(path = projectB.toString(), file = "missing.txt"),
                ),
            ),
            configDir = dir,
            readFile = readFile,
            log = { },
        )

    /** Every (model, project) the resolution can take a different branch on. */
    private fun matrix(): List<Pair<String, Path?>> = listOf(
        "m1" to projectA,
        "m1" to projectB,
        "m2" to projectA,
        "m2" to null,
        "m1" to null,
        "m2" to dir.resolve("elsewhere"),
    )

    @Test
    fun `every resolve outcome appears in rules, by scope and by source`() {
        val subject = instructions()
        val listed = subject.rules()
        matrix().forEach { (model, project) ->
            val outcome = subject.resolve(model, project)
            if (outcome.scope == CompactionScope.CLIENT) return@forEach
            assertTrue(
                listed.any { it.scope == outcome.scope && it.source == outcome.source },
                "resolve($model, $project) returned ${outcome.scope}/${outcome.source}, which rules() does not list",
            )
        }
    }

    @Test
    fun `rules lists every configured scope, in the precedence order resolve applies`() {
        val scopes = instructions().rules().map { it.scope }
        // project-model and project are both present and the model-bearing one comes first: that
        // ORDER is the tie-break resolve applies when both match the same path. There are TWO
        // PROJECT entries because the fixture configures two project paths (alpha and beta) — a
        // per-scope list, not a per-tier one, and the first version of this expectation forgot beta
        // and failed against correct code.
        assertEquals(
            listOf(
                CompactionScope.PROJECT_MODEL,
                CompactionScope.PROJECT,
                CompactionScope.PROJECT,
                CompactionScope.MODEL,
                CompactionScope.GLOBAL,
            ),
            scopes,
        )
    }

    @Test
    fun `an unreadable file rule is listed with null text, never a silent zero`() {
        val missing = instructions().rules().single { it.source.startsWith("project:$projectB") }
        assertNull(missing.text, "a file that cannot be read has no text — the route renders that as null, not 0")
    }

    // rulesFor(project) is the project page's answer, and the same pin shape as above but BOTH ways:
    // the list must hold every rule resolve() picks for that project over every configured model and
    // one no rule names, and nothing resolve() never picks there. A rule a longer path or a
    // project-wide rule shadows is exactly what a flat filter of rules() would wrongly show.
    @Test
    fun `rulesFor a project is exactly the rules resolve picks there, shadowed ones left out`() {
        val work = dir.resolve("work")
        val subject = CompactionInstructions(
            config = CompactionConfig(
                instructions = GLOBAL_TEXT,
                model = listOf(
                    CompactionModelConfig(model = "m1", instructions = MODEL_TEXT),
                    CompactionModelConfig(model = "m2", instructions = MODEL_TEXT),
                ),
                project = listOf(
                    CompactionProjectConfig(path = projectA.toString(), instructions = PROJECT_TEXT),
                    CompactionProjectConfig(
                        path = projectA.toString(),
                        model = "m1",
                        instructions = PROJECT_MODEL_TEXT,
                    ),
                    CompactionProjectConfig(path = work.toString(), model = "m2", instructions = PROJECT_MODEL_TEXT),
                    CompactionProjectConfig(path = projectB.toString(), file = "missing.txt"),
                ),
            ),
            configDir = dir,
            readFile = CompactionFileRead { Files.readString(it) },
            log = { },
        )
        val models = listOf("m1", "m2", "named-by-no-rule")
        val projects = listOf(
            Files.createDirectories(projectA.resolve("src")),
            projectB,
            Files.createDirectories(work.resolve("gamma")),
            Files.createDirectories(dir.resolve("elsewhere")),
            Path.of("relative/checkout"),
        )
        projects.forEach { project ->
            val picked = models.map { subject.resolve(it, project) }.map { it.scope to it.source }.toSet()
            val listed = subject.rulesFor(project).map { it.scope to it.source }
            assertEquals(picked, listed.toSet(), "rulesFor($project) against resolve over $models")
            assertEquals(listed.size, listed.toSet().size, "rulesFor($project) lists a rule twice: $listed")
        }
        // In precedence order: the per-model project rules first, then what the rest resolve to.
        assertEquals(
            listOf(CompactionScope.PROJECT_MODEL, CompactionScope.PROJECT_MODEL, CompactionScope.PROJECT),
            subject.rulesFor(projectA.resolve("src")).map { it.scope },
            "under alpha the project-wide rule shadows both model rules and the global one",
        )
        assertEquals(
            listOf(CompactionScope.PROJECT_MODEL, CompactionScope.MODEL, CompactionScope.GLOBAL),
            subject.rulesFor(work.resolve("gamma")).map { it.scope },
            "m2's project rule takes m2, so only m1's model rule and the global one remain",
        )
    }

    @Test
    fun `rulesFor is empty when nothing is configured, which is the client's own instructions`() {
        val subject = CompactionInstructions(configDir = dir, log = { })
        assertEquals(emptyList<EffectiveCompactionInstructions>(), subject.rulesFor(projectA))
        assertEquals(CompactionScope.CLIENT, subject.resolve("m1", projectA).scope)
    }

    @Test
    fun `an explicit opt-out is listed with empty text, which is a zero length and not an absence`() {
        val subject = CompactionInstructions(
            config = CompactionConfig(instructions = ""),
            configDir = dir,
            readFile = CompactionFileRead { Files.readString(it) },
            log = { },
        )
        val globalRule = subject.rules().single()
        assertEquals("", globalRule.text, "empty is an explicit opt-out, distinct from unreadable")
        assertEquals(0, globalRule.text?.length)
    }
}
