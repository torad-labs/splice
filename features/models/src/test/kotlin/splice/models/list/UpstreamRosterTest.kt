// NEW: 2026-09-22 — the pure half of `splice models`: reading a provider's published list, and
// comparing it to the rows splice.toml declares.
//
// Every fixture below is a REAL body shape, recorded from the live endpoints on 2026-09-22 and
// trimmed to the fields under test: xAI's `data[]` with aliases and `context_length`, Moonshot's
// `data[]` with `display_name`, DeepSeek's `data[]` with neither, llama-server's `models[]`.
package splice.models.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ModelEntry
import splice.core.model.ModelTierSuffix
import splice.core.topology.Dialect

/** The Codex backend's base, named once: two cases assert against it and a wrapped call would put
 *  the same literal on two lines apiece. */
private const val CODEX_BASE = "https://chatgpt.com/backend-api/codex"

class UpstreamRosterTest {

    private val parser = UpstreamRosterParser()
    private val diff = RosterDiff()

    private fun published(body: String): List<UpstreamModel> {
        val roster = parser.parse(body, "https://example.test/v1/models")
        assertTrue(roster is UpstreamRoster.Published, "expected a published roster, got $roster")
        return (roster as UpstreamRoster.Published).models
    }

    private fun entry(id: String, window: Long) = ModelEntry(id = id, label = id, contextWindow = window)

    private fun verdict(rows: List<RosterRow>, id: String): RosterVerdict =
        rows.first { it.id == id }.verdict

    // ── the list URL ────────────────────────────────────────────────────────────

    @Test
    fun `each dialect names where it publishes, and an override outranks every default`() {
        assertEquals(
            "https://api.x.ai/v1/models",
            UpstreamRosterUrl.of(Dialect.OPENAI_CHAT, "https://api.x.ai/v1", null),
        )
        assertEquals(
            "https://api.kimi.com/coding/v1/models",
            UpstreamRosterUrl.of(Dialect.ANTHROPIC_PASSTHROUGH, "https://api.kimi.com/coding", null),
        )
        // A trailing slash on base_url must not produce a doubled one.
        assertEquals(
            "https://api.x.ai/v1/models",
            UpstreamRosterUrl.of(Dialect.OPENAI_CHAT, "https://api.x.ai/v1/", null),
        )
        // DeepSeek: the list is off the dialect's path, so the override is the whole point.
        assertEquals(
            "https://api.deepseek.com/models",
            UpstreamRosterUrl.of(
                Dialect.ANTHROPIC_PASSTHROUGH,
                "https://api.deepseek.com/anthropic",
                "https://api.deepseek.com/models",
            ),
        )
    }

    @Test
    fun `the responses dialect has no list until one is named`() {
        assertEquals(
            null,
            UpstreamRosterUrl.of(Dialect.OPENAI_RESPONSES, CODEX_BASE, null),
        )
        assertEquals(
            "$CODEX_BASE/models?client_version=1.2.0",
            UpstreamRosterUrl.of(Dialect.OPENAI_RESPONSES, CODEX_BASE, "$CODEX_BASE/models?client_version=1.2.0"),
        )
        // A blank override is not an answer — it must fall through to the dialect's own verdict.
        assertEquals(
            null,
            UpstreamRosterUrl.of(Dialect.OPENAI_RESPONSES, CODEX_BASE, "  "),
        )
    }

    // ── parsing ─────────────────────────────────────────────────────────────────

    @Test
    fun `the xai shape yields ids, windows and every alias`() {
        val models = published(
            """
            {"data":[
              {"id":"grok-4.7","context_length":500000},
              {"id":"grok-4.5","aliases":["grok-4.5-latest","grok-build-latest"],"context_length":500000}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("grok-4.7", "grok-4.5"), models.map { it.id })
        assertEquals(500_000L, models[0].contextWindow)
        assertEquals(listOf("grok-4.5", "grok-4.5-latest", "grok-build-latest"), models[1].spellings)
    }

    @Test
    fun `a display name and a models envelope are both read`() {
        val moonshot = published(
            """{"data":[{"id":"kimi-for-coding","display_name":"K2.8 Preview","context_length":1048576}]}""",
        )
        assertEquals("K2.8 Preview", moonshot.single().label)
        assertEquals(1_048_576L, moonshot.single().contextWindow)
        // llama-server answers under `models`, and a row without a window is "not published", not zero.
        val local = published("""{"models":[{"id":"/packs/bonsai.gguf"}]}""")
        assertEquals("/packs/bonsai.gguf", local.single().id)
        assertEquals(null, local.single().contextWindow)
    }

    @Test
    fun `a body that is not a model list is unreadable, and says which url`() {
        val notAList = parser.parse("""{"error":{"message":"nope"}}""", "https://example.test/v1/models")
        assertTrue(notAList is UpstreamRoster.Unreadable)
        assertTrue((notAList as UpstreamRoster.Unreadable).detail.contains("https://example.test/v1/models"))
        assertTrue(parser.parse("<html>sign in</html>", "https://example.test/v1/models") is UpstreamRoster.Unreadable)
        // An empty list IS an answer: the endpoint served a roster and it has nothing in it.
        assertEquals(emptyList<UpstreamModel>(), published("""{"data":[]}"""))
    }

    // ── the tier-suffix grammar, shared with the wire path ──────────────────────

    @Test
    fun `only a numeric tier hint is a splice spelling`() {
        assertEquals("grok-4.3", ModelTierSuffix.strip("grok-4.3[1m]"))
        assertEquals("k3", ModelTierSuffix.strip("k3[500K]"))
        assertTrue(ModelTierSuffix.present("k3[1m]"))
        // A genuine vendor bracket is not a tier and must survive untouched.
        assertEquals("model[preview]", ModelTierSuffix.strip("model[preview]"))
        assertFalse(ModelTierSuffix.present("model[preview]"))
    }

    // ── the comparison ──────────────────────────────────────────────────────────

    @Test
    fun `a declared row is matched through an alias and through its tier suffix`() {
        val upstream = published(
            """
            {"data":[
              {"id":"grok-4.5","aliases":["grok-build-latest"],"context_length":500000},
              {"id":"grok-4.3","context_length":1000000}
            ]}
            """.trimIndent(),
        )
        val rows = diff.of(listOf(entry("grok-build-latest", 500_000), entry("grok-4.3[1m]", 1_000_000)), upstream)
        assertEquals(RosterVerdict.SERVED, verdict(rows, "grok-build-latest"))
        assertEquals(RosterVerdict.SERVED, verdict(rows, "grok-4.3[1m]"))
        // Matched rows are not ALSO reported as undeclared — that is the alias join doing its job.
        assertFalse(rows.any { it.verdict == RosterVerdict.NEW })
    }

    @Test
    fun `a window over the published ceiling is a fault and a window under it is a cap`() {
        val upstream = published(
            """{"data":[{"id":"grok-4.6","context_length":500000},{"id":"grok-4.3","context_length":1000000}]}""",
        )
        val rows = diff.of(listOf(entry("grok-4.6", 1_000_000), entry("grok-4.3", 256_000)), upstream)
        assertEquals(RosterVerdict.OVER_CEILING, verdict(rows, "grok-4.6"))
        assertEquals(RosterVerdict.CAPPED, verdict(rows, "grok-4.3"))
        assertEquals(500_000L, rows.first { it.id == "grok-4.6" }.upstreamWindow)
        assertTrue(rows.first { it.id == "grok-4.6" }.note.contains("500000"))
    }

    @Test
    fun `an id the endpoint does not list is unserved, and a model no row declares is new`() {
        val upstream = published(
            """{"data":[{"id":"grok-4.7","context_length":500000},{"id":"grok-4.20","context_length":1000000}]}""",
        )
        val rows = diff.of(listOf(entry("grok-4.6", 500_000)), upstream)
        assertEquals(RosterVerdict.UNSERVED, verdict(rows, "grok-4.6"))
        assertEquals(RosterVerdict.NEW, verdict(rows, "grok-4.7"))
        assertEquals(RosterVerdict.NEW, verdict(rows, "grok-4.20"))
        assertEquals(1_000_000L, rows.first { it.id == "grok-4.20" }.upstreamWindow)
    }

    @Test
    fun `a local runtime serves whatever it loaded, so an unlisted id is not a fault there`() {
        val upstream = published("""{"models":[{"id":"/packs/bonsai.gguf"}]}""")
        val declared = listOf(entry("bonsai-2-27b", 245_760))
        // The SAME input is a fault on a remote provider and not on a local one — that contrast is
        // the whole content of the `local` flag, so both halves are asserted together.
        assertEquals(RosterVerdict.UNSERVED, verdict(diff.of(declared, upstream), "bonsai-2-27b"))
        assertEquals(RosterVerdict.SERVED, verdict(diff.of(declared, upstream, local = true), "bonsai-2-27b"))
    }

    @Test
    fun `an endpoint that publishes no window leaves the declared number unchecked, never wrong`() {
        val upstream = published("""{"data":[{"id":"deepseek-v4-pro"}]}""")
        val rows = diff.of(listOf(entry("deepseek-v4-pro", 1_000_000)), upstream)
        assertEquals(RosterVerdict.SERVED, verdict(rows, "deepseek-v4-pro"))
        assertEquals(null, rows.single().upstreamWindow)
        assertTrue(rows.single().note.contains("unchecked"))
    }
}
