// NEW (2026-10-25): a compaction chains by closing the tool calls its client dropped.
//
// Claude Code compacts by re-sending its previous request with the summary instruction appended, and drops the
// answer it just received. On the websocket the server still holds that answer with its tool call open, so the
// round used to full-send (frameAndEpoch's 2026-09-05 note), and on claudex a full-sent compaction missed the
// prompt cache far more often than a chained one. codex-rs gives a call that has no output the output "aborted"
// (context_manager/normalize.rs, ensure_call_outputs_present). This does the same, so the compaction continues
// the server's own context instead of asking the provider to find the prompt again.
package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

/** What a chained round sends, and the logical input it commits when that is not the request's own input. */
internal data class WsChainedSend(val send: List<JsonObject>, val logical: List<JsonObject>? = null) {
    /** The request to commit: [request] carrying [logical] as its input, or null to commit it as it was sent. */
    fun committed(request: JsonObject): JsonObject? =
        logical?.let { JsonObject(request + (FIELD_INPUT to JsonArray(it))) }
}

/** Bail-closed like every delta: the input must be [the chain's input][verified echoes of the answer][new client
 *  items], and the answer must leave at least one call open. Anything else returns null and the round full-sends.
 *  The closures are committed as part of the logical input, so a later request that does not carry them (and the
 *  client never sees them) cannot chain onto context its own input does not show. */
internal object WsCompactionClose {
    fun close(
        previous: List<String>,
        current: List<JsonObject>,
        pendingCalls: Set<String>,
        evidence: WsServerEvidence,
    ): WsChainedSend? {
        if (!WsChainingChecks.extendsPrefix(previous, current)) return null
        val suffix = current.drop(previous.size)
        val echoed = suffix.takeWhile(WsSuffixDisposition::isServerItem)
        val fresh = suffix.drop(echoed.size)
        val sent = mutableListOf<JsonObject>()
        // SERVER_HAS_IT after a client item accepts without sending; the size check bails on it.
        val clientNew = fresh.isNotEmpty() &&
            fresh.all { WsSuffixDisposition.accept(it, sent) } &&
            sent.size == fresh.size
        val closures = closures(fresh, pendingCalls, evidence)
            ?.takeIf { it.isNotEmpty() && clientNew && echoesInOrder(echoed, evidence) }
            ?: return null
        return WsChainedSend(send = closures + fresh, logical = current.take(previous.size) + echoed + closures + fresh)
    }

    /** One "aborted" output per call the answer left open, in the order the server emitted them, typed for the
     *  call's kind. Null when an output answers a call the server does not hold open, or an open call was never
     *  observed, since then its kind is unknown. */
    private fun closures(
        fresh: List<JsonObject>,
        pendingCalls: Set<String>,
        evidence: WsServerEvidence,
    ): List<JsonObject>? {
        val answered = fresh.filter { kindOf(it) in CALL_OUTPUT_OF.values }
            .map { JsonScalars.strOrEmpty(it[FIELD_CALL_ID]) }
        val open = pendingCalls.filterNot(answered::contains)
        val closures = evidence.calls.filterKeys(open::contains).mapNotNull { (id, call) ->
            CALL_OUTPUT_OF[kindOf(call)]?.let { kind ->
                buildJsonObject {
                    put(WS_FIELD_TYPE, kind)
                    put(FIELD_CALL_ID, id)
                    put("output", ABORTED_OUTPUT)
                }
            }
        }
        return closures.takeIf { pendingCalls.containsAll(answered) && it.size == open.size }
    }

    /** Every echoed item is the server's own bytes, each kind in the order the server emitted it. */
    private fun echoesInOrder(echoed: List<JsonObject>, evidence: WsServerEvidence): Boolean {
        val calls = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        echoed.forEach { evidence.trackObserved(it, calls, reasoning) }
        return echoed.all(evidence::verifies) &&
            inOrder(calls, evidence.calls.keys.toList()) &&
            inOrder(reasoning, evidence.reasoning.keys.toList())
    }

    private fun inOrder(seen: List<String>, emitted: List<String>): Boolean {
        val positions = seen.map(emitted::indexOf)
        return positions.none { it < 0 } && positions.zipWithNext().all { (a, b) -> a < b }
    }

    private fun kindOf(item: JsonObject): String? = JsonScalars.str(item[WS_FIELD_TYPE])
}

/** The output item that answers each client-executed call kind. */
private val CALL_OUTPUT_OF = mapOf(
    "function_call" to "function_call_output",
    "custom_tool_call" to "custom_tool_call_output",
)

/** codex-rs's output for a call that never ran (context_manager/normalize.rs). */
private const val ABORTED_OUTPUT = "aborted"
