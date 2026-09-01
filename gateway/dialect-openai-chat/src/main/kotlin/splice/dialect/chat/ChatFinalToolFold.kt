// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: reconciling a whole-copy
// final tool_calls array against already-streamed deltas is a different job from streaming them
// (ChatToolCalls), so it stays its own type; the findings-3/4/5a/5b comment block travels verbatim.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.WireSink

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
 *  KNOWN LIMITATIONS (non-standard vendors only — not codex/grok/kimi; a name+args suppressor
 *  would risk dropping a legitimate distinct call, so both are left as documented gaps):
 *    • a call STREAMED without an id (synth "toolu_<n>" slot) but echoed WITH an id can't be
 *      matched back, so the echo mints a duplicate tool_use (finding 4);
 *    • an id-matched echo is suppressed wholesale, so if the stream UNDER-delivered a call's
 *      arguments the final's complete copy is discarded (finding 5b). */
internal class ChatFinalToolFold(private val toolCalls: ChatToolCalls) {

    internal suspend fun foldFinalToolCalls(msg: JsonObject, sink: WireSink) {
        val calls = msg["tool_calls"] as? JsonArray ?: return
        calls.forEach { tc -> (tc as? JsonObject)?.let { applyFinalToolCall(it, sink) } }
    }

    private suspend fun applyFinalToolCall(obj: JsonObject, sink: WireSink) {
        val id = JsonScalars.strOrEmpty(obj["id"])
        if (id.isNotEmpty() && id in toolCalls.openedToolIds) return // echo of an already-open block
        val fn = obj["function"] as? JsonObject
        val slot = if (id.isEmpty()) null else toolCalls.pendingTools.entries.firstOrNull { it.value.id == id }
        if (slot == null) {
            // A call present ONLY in the final array (never an open block — those returned at the
            // top) — emit it, even when it carries no name (openPendingTool falls back to "tool",
            // finding 5a), else it is silently lost while the turn reports tool_use.
            toolCalls.applyToolCall(obj, sink)
        } else {
            // Pending slot from deltas that never carried a name — adopt the echo's name by id, and
            // take its arguments too only when the deltas buffered none (name AND args both final-
            // only, finding 3); a non-empty buffer is never double-appended (append("") is a no-op).
            val pending = slot.value
            val name = JsonScalars.strOrEmpty(fn?.get("name"))
            if (name.isNotEmpty() && pending.name.isEmpty()) {
                pending.name = name
                if (pending.args.isEmpty()) pending.args.append(JsonScalars.strOrEmpty(fn?.get("arguments")))
                toolCalls.openPendingTool(slot.key, pending, sink)
            }
        }
    }
}
