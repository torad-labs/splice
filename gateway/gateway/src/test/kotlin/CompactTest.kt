// PORT-OF: the compaction pins from server/test/codex-proxy.test.mjs + invariants.test.mjs
// @ 4ca99f7 — the MARKER CANARY (verbatim sentence pinned), all five markers detected in
// system AND last-user positions, tools-agnostic detection, resume turns never match,
// last-user-only scanning, affordance regexes, shadow row fields + ring cap, stats round-trip.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.parse.parseAnthropicBody
import splice.gateway.compact.COMPACT_MARKER
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.compact.classifyCompact
import splice.gateway.compact.compactMarkers
import splice.gateway.compact.markerPresent
import java.nio.file.Path

private fun body(json: String) = parseAnthropicBody(json).typed

class CompactTest {

    @Test
    fun `marker canary - the verbatim v2 1 207 sentence is pinned`() {
        // If this breaks, Claude Code drifted the summarizer prompt: update compactMarkers
        // AND the fixture together (the doctrine).
        assertEquals("tasked with summarizing conversations", COMPACT_MARKER)
        assertTrue(compactMarkers.contains(COMPACT_MARKER))
        assertEquals(5, compactMarkers.size)
    }

    @Test
    fun `every marker detects in the system prompt and in the last user message`() {
        for (marker in compactMarkers) {
            assertTrue(
                classifyCompact(body("""{"model":"m","system":"You are $marker now.","messages":[]}""")).compact,
                "system: $marker",
            )
            assertTrue(
                classifyCompact(
                    body(
                        """{"model":"m","messages":[{"role":"user","content":"Please: ${marker.uppercase()}"}]}""",
                    ),
                ).compact,
                "last user: $marker",
            )
        }
    }

    @Test
    fun `detection is tools-agnostic - real compactions carry tools`() {
        assertTrue(
            classifyCompact(
                body(
                    """{"model":"m","system":"$COMPACT_MARKER",
                        "tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[]}""",
                ),
            ).compact,
        )
    }

    @Test
    fun `resume turns and size never match - the v13-v24 misfire class stays dead`() {
        val bigResume = buildString {
            append("""{"model":"m","messages":[{"role":"user","content":"This session is being continued """)
            append("x".repeat(50_000))
            append(""""}]}""")
        }
        assertFalse(classifyCompact(body(bigResume)).compact)
    }

    @Test
    fun `only the LAST user message is scanned - quoted history never re-triggers`() {
        val quoted = body(
            """{"model":"m","messages":[
                {"role":"user","content":"earlier: $COMPACT_MARKER"},
                {"role":"assistant","content":"noted"},
                {"role":"user","content":"now do normal work"}
            ]}""",
        )
        assertFalse(classifyCompact(quoted).compact)
        assertFalse(markerPresent(quoted))
    }

    @Test
    fun `explicit compaction affordances match`() {
        assertTrue(
            classifyCompact(
                body(
                    """{"model":"m","messages":[
                        {"role":"user","content":"The compaction agent should only produce TEXT."}]}""",
                ),
            ).compact,
        )
        assertTrue(
            classifyCompact(
                body(
                    """{"model":"m","messages":[
                        {"role":"user","content":"Tool use is not allowed during compaction."}]}""",
                ),
            ).compact,
        )
    }

    @Test
    fun `shadow classifier records the instrument fields and caps the ring`() {
        val lines = mutableListOf<String>()
        val shadow = ShadowClassifier(log = { lines.add(it) }, clock = { 42L })
        val row = shadow.record(
            body(
                """{"model":"gpt-5.6-sol","system":"sys","tools":[{"name":"t","input_schema":{}}],
                    "messages":[{"role":"user","content":"hi"}]}""",
            ),
            compact = false,
        )
        assertEquals(false, row.compact)
        assertEquals(false, row.hasMarker)
        assertEquals(1, row.toolCount)
        assertEquals(3, row.sysLen)
        assertTrue(lines.single().startsWith("[shadow-compact] compact=false has_marker=false tool_count=1 sys_len=3"))
        repeat(600) { shadow.record(body("""{"model":"m","messages":[]}"""), compact = false) }
        assertEquals(100, shadow.tail(100).size)
        assertTrue(shadow.tail(1000).size <= 500)
    }

    @Test
    fun `compact stats jsonl round-trip with outcome grouping`(@TempDir tmp: Path) {
        val stats = CompactStats(tmp.resolve("claudex-compact-stats.jsonl"), clock = { 7L })
        stats.record(mapOf("outcome" to "model_text", "chars" to 120, "ms" to 900L))
        stats.record(mapOf("outcome" to "model_text", "chars" to 80))
        stats.record(mapOf("outcome" to "empty_model", "error" to "api_error"))
        val summary = stats.read(tailN = 2)
        assertEquals(3, summary.total)
        assertEquals(mapOf("model_text" to 2, "empty_model" to 1), summary.byOutcome)
        assertEquals(2, summary.tail.size)
        assertTrue(summary.tail.last().toString().contains("empty_model"))
    }
}
