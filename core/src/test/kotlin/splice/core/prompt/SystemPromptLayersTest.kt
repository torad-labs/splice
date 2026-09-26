// NEW: V4-124 — the composition WALL for the three system-prompt layers.
//
// WALLS FIRST (the row's own rule): this file is written against the API before the API exists, so
// it is RED on the tree it was authored on and stays the definition of done until it is green.
//
// WHAT IT PINS, and why each pin is here rather than in an integration test:
//   · ALL EIGHT append/replace combinations over the three layers. The rule is not "compose three
//     texts" — it is that a REPLACE at any layer replaces the client's field AND EVERY EARLIER
//     LAYER, while later appends still land after it. Eight combinations is the whole truth table
//     of a three-layer fold, so enumerating them is the only way the fold is not a guess.
//   · DEEPEST ROOT WINS, the nested-repo case: an operator with ~/work and ~/work/api configured
//     gets the api layer inside ~/work/api, not the outer one and not both.
//   · NO-CWD FALLBACK: a session whose cwd cannot be resolved gets the head layer ONLY, never a
//     project layer chosen by a guess.
//
// NEVER-BELOW-STATUS-QUO is pinned in its own test: with no projects configured the resolved list
// is exactly the single head layer, which is byte-for-byte what the tree sends today.
package splice.core.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.ProjectConfig
import splice.core.topology.ProjectHeadPrompt
import java.nio.file.Paths

class SystemPromptLayersTest {

    private val repo = "/work/api"

    private fun head(text: String, mode: SystemPromptMode) =
        HeadSystemPrompt(text = text, mode = mode, source = "head:codex")

    private fun layers(headMode: SystemPromptMode, projectMode: SystemPromptMode, headLayerMode: SystemPromptMode): List<EffectiveSystemPrompt> {
        val projects = mapOf(
            repo to ProjectConfig(
                systemPrompt = "P",
                systemPromptMode = projectMode,
                heads = mapOf("codex" to ProjectHeadPrompt(systemPrompt = "H", systemPromptMode = headLayerMode)),
            ),
        )
        return SystemPromptLayers(
            head = head("N", headMode),
            projects = projects,
            headKey = "codex",
            home = "/home/operator",
        ).resolve(Paths.get(repo, "src"))
    }

    private fun texts(resolved: List<EffectiveSystemPrompt>): List<String> = resolved.map { it.text }

    private fun modes(resolved: List<EffectiveSystemPrompt>): List<SystemPromptMode> = resolved.map { it.mode }

    // ── the truth table: eight combinations, and the fold is a fold ──────────────────────────────

    @Test
    fun `append everywhere stacks all three layers in order head then project then project-head`() {
        val resolved = layers(SystemPromptMode.APPEND, SystemPromptMode.APPEND, SystemPromptMode.APPEND)

        assertEquals(listOf("N", "P", "H"), texts(resolved))
        assertEquals(List(3) { SystemPromptMode.APPEND }, modes(resolved))
    }

    @Test
    fun `a replace at the head layer keeps every later append after it`() {
        val resolved = layers(SystemPromptMode.REPLACE, SystemPromptMode.APPEND, SystemPromptMode.APPEND)

        assertEquals(listOf("N", "P", "H"), texts(resolved))
        assertEquals(
            listOf(SystemPromptMode.REPLACE, SystemPromptMode.APPEND, SystemPromptMode.APPEND),
            modes(resolved),
        )
    }

    @Test
    fun `a replace at the project layer drops the head layer and keeps the project-head append`() {
        val resolved = layers(SystemPromptMode.APPEND, SystemPromptMode.REPLACE, SystemPromptMode.APPEND)

        assertEquals(listOf("P", "H"), texts(resolved))
        assertEquals(listOf(SystemPromptMode.REPLACE, SystemPromptMode.APPEND), modes(resolved))
    }

    @Test
    fun `a replace at the project-head layer drops both earlier layers`() {
        val resolved = layers(SystemPromptMode.APPEND, SystemPromptMode.APPEND, SystemPromptMode.REPLACE)

        assertEquals(listOf("H"), texts(resolved))
        assertEquals(listOf(SystemPromptMode.REPLACE), modes(resolved))
    }

    @Test
    fun `two replaces - head and project - leave the project replace and the later append`() {
        val resolved = layers(SystemPromptMode.REPLACE, SystemPromptMode.REPLACE, SystemPromptMode.APPEND)

        assertEquals(listOf("P", "H"), texts(resolved))
        assertEquals(listOf(SystemPromptMode.REPLACE, SystemPromptMode.APPEND), modes(resolved))
    }

    @Test
    fun `two replaces - head and project-head - leave only the deepest replace`() {
        val resolved = layers(SystemPromptMode.REPLACE, SystemPromptMode.APPEND, SystemPromptMode.REPLACE)

        assertEquals(listOf("H"), texts(resolved))
        assertEquals(listOf(SystemPromptMode.REPLACE), modes(resolved))
    }

    @Test
    fun `two replaces - project and project-head - leave only the deepest replace`() {
        val resolved = layers(SystemPromptMode.APPEND, SystemPromptMode.REPLACE, SystemPromptMode.REPLACE)

        assertEquals(listOf("H"), texts(resolved))
        assertEquals(listOf(SystemPromptMode.REPLACE), modes(resolved))
    }

    @Test
    fun `a replace at every layer leaves exactly one layer - the last one stands`() {
        val resolved = layers(SystemPromptMode.REPLACE, SystemPromptMode.REPLACE, SystemPromptMode.REPLACE)

        assertEquals(listOf("H"), texts(resolved))
        assertEquals(listOf(SystemPromptMode.REPLACE), modes(resolved))
    }

    // ── deepest root wins ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the deepest configured root that is an ancestor of the cwd wins`() {
        val projects = mapOf(
            "/work" to ProjectConfig(systemPrompt = "OUTER"),
            "/work/api" to ProjectConfig(systemPrompt = "INNER"),
        )
        val resolved = SystemPromptLayers(
            head = head("N", SystemPromptMode.APPEND),
            projects = projects,
            headKey = "codex",
        ).resolve(Paths.get("/work/api/src/main"))

        assertEquals(listOf("N", "INNER"), texts(resolved))
    }

    @Test
    fun `a configured root that is not an ancestor of the cwd does not apply`() {
        val projects = mapOf("/elsewhere" to ProjectConfig(systemPrompt = "OTHER"))
        val resolved = SystemPromptLayers(
            head = head("N", SystemPromptMode.APPEND),
            projects = projects,
            headKey = "codex",
        ).resolve(Paths.get("/work/api"))

        assertEquals(listOf("N"), texts(resolved))
    }

    @Test
    fun `the cwd itself counts as its own project root`() {
        val resolved = SystemPromptLayers(
            head = head("N", SystemPromptMode.APPEND),
            projects = mapOf("/work/api" to ProjectConfig(systemPrompt = "P")),
            headKey = "codex",
        ).resolve(Paths.get("/work/api"))

        assertEquals(listOf("N", "P"), texts(resolved))
    }

    // ── no-cwd fallback, and never-below-status-quo ──────────────────────────────────────────────

    @Test
    fun `a session with no resolvable cwd gets the head layer only`() {
        val resolved = SystemPromptLayers(
            head = head("N", SystemPromptMode.APPEND),
            projects = mapOf(repo to ProjectConfig(systemPrompt = "P")),
            headKey = "codex",
        ).resolve(null)

        assertEquals(listOf("N"), texts(resolved))
    }

    @Test
    fun `with no projects configured the result is exactly the single head layer`() {
        val resolved = SystemPromptLayers(
            head = head("N", SystemPromptMode.APPEND),
            projects = emptyMap(),
            headKey = "codex",
        ).resolve(Paths.get(repo))

        assertEquals(listOf("N"), texts(resolved))
        assertEquals("head:codex append", resolved.single().source)
    }

    @Test
    fun `a head with no prompt and no projects resolves nothing at all`() {
        val resolved = SystemPromptLayers(
            head = HeadSystemPrompt(source = "head:codex"),
            projects = emptyMap(),
            headKey = "codex",
        ).resolve(Paths.get(repo))

        assertTrue(resolved.isEmpty())
    }

    // ── load: roots, quoting, and files relative to the project ──────────────────────────────────

    @Test
    fun `a relative project root is a config error at load naming the table`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SystemPromptLayers(
                head = head("N", SystemPromptMode.APPEND),
                projects = mapOf("work/api" to ProjectConfig(systemPrompt = "P")),
            )
        }

        assertTrue(failure.message.orEmpty().contains("[projects.\"work/api\"]"), failure.message)
    }

    @Test
    fun `a quoted key and a home-relative root resolve, and a relative file reads under the root`() {
        val read = mutableListOf<String>()
        val resolved = SystemPromptLayers(
            head = HeadSystemPrompt(source = "head:codex"),
            projects = mapOf("\"~/api\"" to ProjectConfig(systemPromptFile = ".splice/prompt.md")),
            headKey = "codex",
            home = "/home/operator",
            readFile = SystemPromptFileRead { path ->
                read += path.toString()
                "FROM FILE"
            },
        ).resolve(Paths.get("/home/operator/api"))

        assertEquals(listOf("/home/operator/api/.splice/prompt.md"), read)
        assertEquals(listOf("FROM FILE"), texts(resolved))
    }

    // ── a project layer for a head that configures none contributes nothing ──────────────────────

    @Test
    fun `a project-head layer for a DIFFERENT head does not apply`() {
        val projects = mapOf(
            repo to ProjectConfig(systemPrompt = "P", heads = mapOf("grok" to ProjectHeadPrompt(systemPrompt = "G"))),
        )
        val resolved = SystemPromptLayers(
            head = head("N", SystemPromptMode.APPEND),
            projects = projects,
            headKey = "codex",
        ).resolve(Paths.get(repo))

        assertEquals(listOf("N", "P"), texts(resolved))
    }
}
