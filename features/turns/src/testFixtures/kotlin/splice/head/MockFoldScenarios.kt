// Reasoning-continuation fold scenarios (codex 518n-2). A round is a CONTINUATION when its input carries a
// replayed reasoning item; "fold" then serves a clean round, "foldcap" always truncates so the head hits its
// continuation cap.
//
// sequential_cutoff realism (2026-08-26 codex-parity port): the live backend streams summary DELTAS and then a
// reasoning_summary_text.done carrying the COMPLETE part text under the item's id. Cutoff-mode heads render ONLY
// the done events, so every reasoning scenario here sends both — which also pins that dropping the deltas loses
// no text.
package splice.head

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// DR-7 foldstall: comfortably past the acceptance head's 1s streamIdle, short enough that a
// wrongly-unreaped turn still fails the test in seconds rather than hanging the suite.
internal const val STALL_SLEEP_MS = 6_000L

private const val ROUND_ONE_SUMMARY = "Thinking round one."
private const val ROUND_TWO_SUMMARY = "Thinking round two."
private const val TENTATIVE_ANSWER = "TENTATIVE ANSWER"
private const val FINAL_ANSWER = "FINAL ANSWER"
private const val TRUNCATED_ITEM = "rs_trunc"
private const val ENCRYPTED_TRUNCATED = "ENC-TRUNC"
private const val SECOND_OUTPUT = 1

internal class MockFoldScenarios(private val wire: MockSseWire) {
    private val e = wire.events

    /** Plays [scenario] when it is a fold one, and says whether it was. */
    fun play(scenario: String, body: JsonObject?): Boolean {
        val continuation = isContinuationRound(body)
        when (scenario) {
            "fold" -> if (continuation) cleanRound() else truncatedRound(ROUND_ONE_SUMMARY)
            "foldsummary" -> if (continuation) summaryCleanRound() else truncatedRound(SUMMARY_SECTION_A)
            "foldcap" -> truncatedRound(ROUND_ONE_SUMMARY)
            "foldstall" -> if (continuation) cleanRound() else stalledRound()
            else -> return false
        }
        return true
    }

    private fun isContinuationRound(body: JsonObject?): Boolean =
        body?.get("input")?.jsonArray?.any {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "reasoning"
        } ?: false

    /** A reasoning item with one summary part, closed with the encrypted content a replay carries. */
    private fun reasoningItem(id: String, encrypted: String, summary: String) {
        wire.sse(e.reasoningAdded(id))
        wire.sse(e.summaryDelta(summary))
        wire.sse(e.summaryDone(id, summaryIndex = 0, text = summary))
        wire.sse(
            """{"type":"response.output_item.done","output_index":0,""" +
                """"item":{"type":"reasoning","id":"$id","encrypted_content":"$encrypted"}}""",
        )
    }

    // ADDED (2026-07-26, turn-scoped summary dedup): a fold whose CONTINUATION round re-titles the round-1 summary
    // section verbatim before adding a new one — exactly what sequential_cutoff does when a continuation
    // re-requests the detailed summary over already-summarized reasoning.
    private fun truncatedRound(summary: String) {
        reasoningItem(TRUNCATED_ITEM, ENCRYPTED_TRUNCATED, summary)
        wire.sse(e.messageAdded(SECOND_OUTPUT))
        wire.sse(e.textDelta(TENTATIVE_ANSWER, SECOND_OUTPUT))
        wire.sse(e.itemDone(SECOND_OUTPUT))
        wire.sse(e.completedWithReasoning("rt", input = 100, output = 600, reasoning = 516))
    }

    // DR-7: round 1 streams REAL reasoning and then goes silent forever — a mid-part stall, not a truncation. The
    // head has seen bytes (so the watchdog is on its streamIdle tier) and holds a partial summary, which is exactly
    // the state the old code threw away: the watchdog cancelled the whole turn, the outcome carried no partial, and
    // both continuation gates vetoed on watchdogFired. The sleep outlives the head's idle cap; the write that
    // follows it lands on a socket the head has already hung up, which is the expected IOException.
    private fun stalledRound() {
        reasoningItem("rs_stall", "ENC-STALL", ROUND_ONE_SUMMARY)
        wire.pause(STALL_SLEEP_MS)
        wire.sse(e.textDelta("NEVER REACHES THE CLIENT", SECOND_OUTPUT))
    }

    private fun cleanRound() {
        wire.sse(e.reasoningAdded("rs_clean"))
        wire.sse(e.summaryDelta(ROUND_TWO_SUMMARY))
        wire.sse(e.summaryDone("rs_clean", summaryIndex = 0, text = ROUND_TWO_SUMMARY))
        finalAnswer()
    }

    private fun summaryCleanRound() {
        // output_index restarts at 0 for the continuation round, as the real backend does.
        wire.sse(e.reasoningAdded("rs_clean2"))
        wire.sse(e.summaryDelta(SUMMARY_SECTION_A))
        wire.sse(e.summaryDone("rs_clean2", summaryIndex = 0, text = SUMMARY_SECTION_A))
        wire.sse(e.summaryDelta(SUMMARY_SECTION_B))
        wire.sse(e.summaryDone("rs_clean2", summaryIndex = 1, text = SUMMARY_SECTION_B))
        finalAnswer()
    }

    /** Closes the reasoning item and streams the clean continuation's answer. */
    private fun finalAnswer() {
        wire.sse(e.itemDone())
        wire.sse(e.messageAdded(SECOND_OUTPUT))
        wire.sse(e.textDelta(FINAL_ANSWER, SECOND_OUTPUT))
        wire.sse(e.itemDone(SECOND_OUTPUT))
        wire.sse(e.completedWithReasoning("rf", input = 150, output = 800, reasoning = 800))
    }
}
