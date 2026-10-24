// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: reconciling a whole-copy
// final tool_calls array against already-streamed deltas is a different job from streaming them
// (ChatToolCalls), so it stays its own type; the findings-3/4/5a/5b comment block travels verbatim.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.sse.WireSink

/** Non-stream / final-message shape: tool_calls land on `message`, not `delta`. Classified PER
 *  CALL, not per turn — a turn-global gap-fill flag dropped a call present only in the final
 *  array when it rode alongside an echo of a streamed call (review 2026-07-23). Three cases:
 *    ECHO of an already-opened block (matched by id) — SUPPRESS: re-applying appends the full
 *      arguments onto the open block or mints a duplicate tool_use (final-shape calls carry no
 *      `index`, so resolveToolIndex cannot map an echo back to its streamed slot).
 *    PENDING slot buffered from deltas that never carried function.name — adopt the echo's name
 *      by id, else flushPendingTools opens it under the "tool" fallback. Take the echo's args
 *      too ONLY when the deltas buffered none (name AND args both final-only, finding 3); a
 *      non-empty buffer is never appended twice.
 *    NEW — never streamed, present ONLY in the final consolidated message (including when NO
 *      deltas streamed any tool call) — emit it, even when it carries no name (opened under the
 *      "tool" fallback, finding 5a), or it is silently lost while the turn reports tool_use.
 *  Two vendor shapes the match covers:
 *    • a call STREAMED without an id (synth "toolu_<n>" slot) but echoed WITH an id is matched back by its
 *      position in the final array and its name, so the echo is not minted a second time (finding 4);
 *    • an echo whose arguments are longer than what was streamed, and extend it, sends the rest, so the
 *      client gets the complete input and not the truncated deltas (finding 5b). Arguments that are not an
 *      extension of the streamed ones cannot be corrected on the wire and are left to terminal validation. */
internal class ChatFinalToolFold(private val toolCalls: ChatToolCalls) {

    internal suspend fun foldFinalToolCalls(msg: JsonObject, sink: WireSink) {
        val calls = msg["tool_calls"] as? JsonArray ?: return
        val streamed = toolCalls.streamedIndices() // the calls the stream carried, before this fold adds any
        calls.forEachIndexed { position, tc ->
            (tc as? JsonObject)?.let { applyFinalToolCall(it, position, streamed, sink) }
        }
    }

    private suspend fun applyFinalToolCall(obj: JsonObject, position: Int, streamed: List<Int>, sink: WireSink) {
        val id = JsonScalars.strOrEmpty(obj["id"])
        val fn = obj["function"] as? JsonObject
        val name = JsonScalars.strOrEmpty(fn?.get("name"))
        val args = JsonScalars.strOrEmpty(fn?.get("arguments"))
        val echoed = toolCalls.echoedIndex(id, name, position, streamed)
        if (echoed == null) {
            // A call present ONLY in the final array — emit it, even when it carries no name
            // (openPendingTool falls back to "tool", finding 5a), else it is silently lost while the
            // turn reports tool_use.
            toolCalls.applyToolCall(obj, sink)
            return
        }
        val pending = toolCalls.pendingTools[echoed]
        if (pending == null) {
            // ECHO of an already-open block: never opened twice, and its arguments are completed when the stream
            // delivered only a prefix of them.
            toolCalls.completeOpenedArgs(echoed, args, sink)
            return
        }
        // Pending slot from deltas that never carried a name — adopt the echo's name, and the rest of its
        // arguments (all of them when the deltas buffered none: name AND args both final-only, finding 3).
        if (name.isNotEmpty() && pending.name.isEmpty()) {
            pending.name = name
            toolCalls.completePendingArgs(pending, args)
            toolCalls.openPendingTool(echoed, pending, sink)
        }
    }
}
