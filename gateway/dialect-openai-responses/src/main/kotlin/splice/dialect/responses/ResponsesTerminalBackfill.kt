// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the terminal-OBJECT
// reads that backfill the streamed buffers when SSE deltas were sparse — harvest fallback runs
// AFTER driveTurn's loop ends, never mid-stream.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

internal class ResponsesTerminalBackfill {

    private val harvest = ResponsesHarvest()
    private val frames = ResponsesFrameParse()

    fun harvestFallback(state: ResponsesTurnState) {
        val resp = state.finalResponse ?: return
        harvestRefusalParts(state, resp)
        val harvested = harvest.harvestResponsesOutput(resp)
        // CharSequence checks avoid an intermediate toString() when the streamed buffer is large
        // and the harvest path is a no-op (the common case once deltas have been flowing).
        if (frames.shouldPreferHarvestedText(state.textBuf, harvested.text)) {
            state.textBuf = StringBuilder(harvested.text)
        }
        if (harvested.thinking.length > state.thinkingBuf.length) {
            state.thinkingBuf = StringBuilder(harvested.thinking)
        }
    }

    /** W4-A: the completed response's `refusal`-typed content parts — the third refusal carrier,
     *  and the only one a backend that streams neither `response.refusal.delta` nor
     *  `response.refusal.done` uses. A stream that never reaches a terminal has no
     *  [ResponsesTurnState.finalResponse] and already fails as truncated, so reading the terminal
     *  object here loses nothing. The part object carries `refusal` and no `delta`, so
     *  [ResponsesTurnState.addRefusal] routes it to the whole-copy branch on its own. */
    private fun harvestRefusalParts(state: ResponsesTurnState, resp: JsonObject) {
        val output = resp["output"] as? JsonArray ?: return
        output.forEach { item ->
            val content = (item as? JsonObject)?.get("content") as? JsonArray ?: return@forEach
            content.forEach { part ->
                val obj = part as? JsonObject ?: return@forEach
                if (JsonScalars.strOrEmpty(obj["type"]) == "refusal") state.addRefusal(obj)
            }
        }
    }
}
