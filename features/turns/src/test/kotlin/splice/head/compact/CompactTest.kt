// PORT-OF: the compaction pins from server/test/codex-proxy.test.mjs + invariants.test.mjs
// @ pre-public-port-baseline — the MARKER CANARY (verbatim sentence pinned), all five markers detected in
// system AND last-user positions, tools-agnostic detection, resume turns never match,
// last-user-only scanning, affordance regexes, shadow row fields + ring cap, stats round-trip.
package splice.head.compact

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.parse.AnthropicParse
import splice.core.util.AsyncFileIo
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
    fun `a tool-result-only last user message never re-triggers compaction`() {
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

    // Spec section 7: detection is the verbatim summarizer marker and nothing looser. Prose that merely
    // talks about compaction is an ordinary turn, so a wording drift shows as has_marker=false in the shadow
    // row, never as a guessed compaction.
    @Test
    fun `text about compaction without a verbatim marker is an ordinary turn`() {
        listOf("The compaction agent should only produce TEXT.", "Tool use is not allowed during compaction.")
            .forEach { text ->
                val probe = classifier.classifyCompact(
                    body("""{"model":"m","messages":[{"role":"user","content":"$text"}]}"""),
                )
                assertFalse(probe.compact, text)
                assertFalse(probe.hasMarker, text)
            }
    }

    @Test
    fun `compact stats jsonl round-trip with outcome grouping`(@TempDir tmp: Path) {
        val stats = CompactStats(tmp.resolve("claudex-compact-stats.jsonl"), clock = { 7L })
        stats.record(mapOf("outcome" to "model_text", "chars" to 120, "ms" to 900L))
        stats.record(mapOf("outcome" to "model_text", "chars" to 80))
        stats.record(mapOf("outcome" to "empty_model", "error" to "api_error"))
        assertTrue(AsyncFileIo.drain())
        val summary = stats.read(tailN = 2)
        assertEquals(3, summary.total)
        assertEquals(mapOf("model_text" to 2, "empty_model" to 1), summary.byOutcome)
        assertEquals(2, summary.tail.size)
        assertTrue(summary.tail.last().toString().contains("empty_model"))
    }

    // Console review 2026-09-24: the page showed 33% failed compactions, all of them 10-70 days old,
    // because the counts carried no span. The summary now says which rows it counted and what the
    // last seven days alone hold.
    @Test
    fun `the stats say which span their counts cover and what the last seven days hold`(@TempDir tmp: Path) {
        val day = 86_400_000L
        val now = 100 * day
        var t = now - 10 * day
        val stats = CompactStats(tmp.resolve("claudex-compact-stats.jsonl"), clock = { t })
        stats.record(mapOf("outcome" to "empty_model"))
        t = now - 2 * day
        stats.record(mapOf("outcome" to "model_text"))
        t = now - day
        stats.record(mapOf("outcome" to "stream_error"))
        t = now

        assertTrue(AsyncFileIo.drain())
        val span = stats.read().span
        assertEquals(now - 10 * day, span?.firstTs)
        assertEquals(now - day, span?.lastTs)
        assertEquals(mapOf("model_text" to 1, "stream_error" to 1), span?.recent)
        assertEquals(null, CompactStats(tmp.resolve("none.jsonl"), clock = { now }).read().span)
    }
}
