// PORT-OF: the compaction pins from server/test/codex-proxy.test.mjs + invariants.test.mjs
// @ pre-public-port-baseline — the MARKER CANARY (verbatim sentence pinned), all five markers detected in
// system AND last-user positions, tools-agnostic detection, resume turns never match,
// last-user-only scanning, affordance regexes, shadow row fields + ring cap, stats round-trip.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.parse.AnthropicParse
import splice.gateway.compact.COMPACT_MARKER
import splice.gateway.compact.CompactClassifier
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.compact.compactMarkers
import java.nio.file.Path

private fun body(json: String) = AnthropicParse.parseAnthropicBody(json).typed

private val classifier = CompactClassifier()

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
                classifier.classifyCompact(
                    body("""{"model":"m","system":"You are $marker now.","messages":[]}"""),
                ).compact,
                "system: $marker",
            )
            assertTrue(
                classifier.classifyCompact(
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
            classifier.classifyCompact(
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
        assertFalse(classifier.classifyCompact(body(bigResume)).compact)
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
        assertFalse(classifier.classifyCompact(quoted).compact)
        assertFalse(classifier.markerPresent(quoted))
    }

    // DR-141 (dialect sweep, 2026-08-31): the invariant three code sites state — system prompt OR
    // the LAST user message, never the transcript — was not what lastUserTextOf did. It walked
    // backwards past every user message with no TEXT block, and a tool_result-only user message is
    // the DOMINANT shape in Claude Code's agentic loop, so a marker anywhere earlier re-triggered
    // compaction on ordinary tool turns. That is not cosmetic: passthrough drops tools and
    // tool_choice, chat sets emitTools=false, the mirror goes off and compact rows start recording,
    // so a mid-task turn silently becomes a tool-less summarizer turn whose only trace looks like a
    // normal compaction. Every pre-existing fixture here gave the last user message text, which is
    // exactly why none of them could catch it.
    @Test
    fun `a tool-result-only last user message never re-triggers compaction - DR-141`() {
        val toolTurn = body(
            """{"model":"m","messages":[
                {"role":"user","content":"$COMPACT_MARKER"},
                {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}
            ]}""",
        )
        assertFalse(classifier.classifyCompact(toolTurn).compact, "a tool-result turn must not compact")
        assertFalse(classifier.markerPresent(toolTurn), "the marker lives in history, not the last user turn")
    }

    @Test
    fun `explicit compaction affordances match`() {
        assertTrue(
            classifier.classifyCompact(
                body(
                    """{"model":"m","messages":[
                        {"role":"user","content":"The compaction agent should only produce TEXT."}]}""",
                ),
            ).compact,
        )
        assertTrue(
            classifier.classifyCompact(
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

    // CMP-001: the drift canary itself was untested — `compact-drift` appeared exactly once in the
    // repo, in Compact.kt. It fires on the PARTIAL drift: the pinned verbatim compactMarkers all
    // miss while a looser affordance regex still catches the turn as compact, which is what a
    // Claude Code summarizer-wording change looks like from here. Untested, the instrument built to
    // catch marker rot would have rotted silently with it.
    @Test
    fun `CMP-001 - a fallback-only match fires the compact-drift canary, a verbatim marker never does`() {
        val lines = mutableListOf<String>()
        val shadow = ShadowClassifier(log = { lines.add(it) }, clock = { 9L })

        val verbatim = body("""{"model":"m","system":"You are $COMPACT_MARKER.","messages":[]}""")
        val onMarker = shadow.record(verbatim, classifier.classifyCompact(verbatim))
        assertTrue(onMarker.compact)
        assertTrue(onMarker.hasMarker)
        assertEquals(0, lines.count { it.startsWith("[compact-drift]") }, "a pinned marker is not drift: $lines")

        // Matches compactionTextOnlyRe, carries none of the five verbatim marker sentences.
        val fallbackOnly = body(
            """{"model":"m","messages":[
                {"role":"user","content":"The compaction agent should only produce TEXT."}]}""",
        )
        val onDrift = shadow.record(fallbackOnly, classifier.classifyCompact(fallbackOnly))
        assertTrue(onDrift.compact, "the fallback regex must still classify this as compact")
        assertFalse(onDrift.hasMarker, "the pinned markers must all miss — that IS the drift signal")
        val drift = lines.filter { it.startsWith("[compact-drift]") }
        assertEquals(1, drift.size, "the canary must fire exactly once: $lines")
        assertTrue(drift.single().contains("has_marker=false, compact=true"), drift.single())
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
