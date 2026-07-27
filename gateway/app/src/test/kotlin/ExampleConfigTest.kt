// NEW: the committed config/splice.example.toml is a TESTED artifact, not aspirational docs — it
// must parse and yield the three documented heads with the right dialects/auth. If someone edits
// the example into an invalid shape, this fails.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.TopologyLoader
import splice.core.config.Knob
import splice.core.topology.Dialect
import java.nio.file.Files
import java.nio.file.Paths

class ExampleConfigTest {

    private fun exampleToml(): String {
        // walk up from the gateway module dir to the repo root
        var dir = Paths.get("").toAbsolutePath()
        repeat(4) {
            val candidate = dir.resolve("config").resolve("splice.example.toml")
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent ?: return@repeat
        }
        error("config/splice.example.toml not found from ${Paths.get("").toAbsolutePath()}")
    }

    @Test
    fun `example topology parses into the documented heads`() {
        val topology = TopologyLoader.parse(exampleToml())
        assertEquals(setOf("claudex", "claude-grok", "openrouter", "fireworks", "claude-kimi"), topology.heads.keys)
        assertEquals(3096, topology.daemon.controlPort)

        val codex = topology.providers[topology.heads["claudex"]!!.provider]!!
        assertEquals(Dialect.OPENAI_RESPONSES, codex.dialect)
        assertEquals("chatgpt-oauth", codex.auth.kind)

        val xai = topology.providers[topology.heads["claude-grok"]!!.provider]!!
        assertEquals(Dialect.OPENAI_RESPONSES, xai.dialect)
        assertEquals("grok-oauth", xai.auth.kind)
        assertEquals("session-id", xai.quirks.cacheKey)
        // reasoning_cache round-trip (review 2026-07-24): a NON-default value must reach the
        // parsed field — the 2026-07-18 audit found five decorative quirks; never again silently
        assertEquals(false, xai.quirks.reasoningCache)

        val openrouter = topology.providers[topology.heads["openrouter"]!!.provider]!!
        assertEquals(Dialect.OPENAI_CHAT, openrouter.dialect)
        assertEquals("api-key", openrouter.auth.kind)
        assertEquals(null, openrouter.quirks.reasoningEffort)

        val fireworks = topology.providers[topology.heads["fireworks"]!!.provider]!!
        assertEquals(Dialect.OPENAI_CHAT, fireworks.dialect)
        assertEquals("api-key", fireworks.auth.kind)
        // reasoning_effort round-trip (issue #21): a NON-default value must reach the parsed field —
        // the reasoning_cache precedent (2026-07-18 audit) is exactly this failure mode recurring.
        assertEquals(false, fireworks.quirks.reasoningEffort)

        val kimi = topology.providers[topology.heads["claude-kimi"]!!.provider]!!
        assertEquals(Dialect.ANTHROPIC_PASSTHROUGH, kimi.dialect)
        assertEquals("kimi-oauth", kimi.auth.kind)
        assertEquals("k3[1m]", topology.heads["claude-kimi"]!!.pinnedModel)

        // the isolate override survives the round-trip
        assertTrue(topology.heads["claude-grok"]!!.claude.isolate.contains("commands"))
        assertEquals("claudex", topology.heads["claudex"]!!.claude.command)
    }

    // 2026-07-26 review: the per-head block documented knob names in prose, and the prose had
    // drifted (it leaned three times on a [defaults] table the file never defined). Prose rots
    // silently; this makes the example's knob vocabulary answer to the Knob enum mechanically.
    // Covers BOTH the live override tables and the names the comments teach operators to use.
    @Test
    fun `every knob the example uses or names is a real Knob key`() {
        val toml = exampleToml()
        val topology = TopologyLoader.parse(toml)

        // 1. Live [heads.<key>.overrides] tables — a typo here ships a silently-ignored knob.
        val used = topology.heads.values.flatMap { it.overrides.keys }.toSet()
        assertTrue(used.isNotEmpty(), "the example must keep demonstrating per-head overrides")
        used.forEach { assertTrue(Knob.byKey.containsKey(it), "example sets unknown knob '$it'") }

        // 2. Knob names the COMMENTS teach. Anything camelCase-and-backticked-or-listed in the
        //    per-head doc block must resolve; that is what went stale and got hand-fixed once.
        //
        //    2026-07-27 review: this used to pre-filter to `startsWith("max") || endsWith("Ms")`,
        //    which was lossless only by accident — the example happens to name only the
        //    timeout-and-capacity family today. `toolSurface`, `usageWarnPct`, `pinnedModel`,
        //    `foldMarkerText` and `contextWindowOverride` are all live Knob keys the filter would
        //    have silently dropped, and silent skipping is the exact drift this test exists to
        //    catch. Every camelCase token now resolves against the enum; prose words that are not
        //    knobs go in an EXPLICIT allowlist, so they stay visible instead of vanishing into a
        //    shape rule.
        val docNames = Regex("\\b([a-z]+[A-Z][A-Za-z]*)\\b")
            .findAll(toml.lines().filter { it.trimStart().startsWith("#") }.joinToString("\n"))
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(docNames.isNotEmpty(), "the example must keep teaching knob names in comments")

        // Prose, not knobs. An entry here that IS a knob would silence a real drift, so the
        // allowlist is asserted disjoint from the enum rather than trusted.
        val prose = setOf("xAI")
        prose.forEach {
            assertTrue(
                !Knob.byKey.containsKey(it),
                "'$it' is a real knob — remove it from the prose allowlist",
            )
        }

        (docNames - prose).forEach {
            assertTrue(
                Knob.byKey.containsKey(it),
                "example comment names unknown knob '$it' " +
                    "(add it to the prose allowlist only if it is not a knob)",
            )
        }
    }
}
