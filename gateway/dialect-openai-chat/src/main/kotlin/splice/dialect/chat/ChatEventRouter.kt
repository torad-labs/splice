// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: chat-frame SHAPE knowledge
// (which key holds the error, which holds choices/delta/message/finish_reason), the only remaining
// reader of the raw event object in the driver path. Referenced only by driveTurn — a strict DAG,
// no back-reference to the translator.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.WireSink

/** Dispatches one OpenAI chat SSE frame to its owning collaborators. [applyDelta] and
 *  [applyFinalMessage] keep their exact statement ORDER (reasoning, then content, then tool_calls,
 *  then refusal) so the wire sequence is byte-identical to the pre-split translator. */
internal class ChatEventRouter(
    private val prose: ChatProseChannels,
    private val toolCalls: ChatToolCalls,
    private val finalToolFold: ChatFinalToolFold,
    private val terminal: ChatTerminalState,
    private val usage: ChatUsage,
) {
    // CX-08's refusal fold is stateless — no reason to share the instance the prose channels use.
    private val refusal = ChatProseFold()

    internal suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        (evt["error"] as? JsonObject)?.let {
            terminal.failure = JsonScalars.strOrEmpty(it["message"]).ifEmpty { "error" }
            return
        }
        usage.usage(evt)
        val choice = (evt["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (choice["delta"] as? JsonObject)?.let { applyDelta(it, sink) }
        // Non-stream / final-message shape: reasoning lands on `message` instead of `delta`.
        (choice["message"] as? JsonObject)?.let { applyFinalMessage(it, sink) }
        // A null finish_reason would trip `finished` and let a truncated stream masquerade as a
        // clean end (L3) — strOrEmpty (core JsonScalars) filters the JsonNull first (review 2026-07-22 round 3).
        JsonScalars.strOrEmpty(choice["finish_reason"]).takeIf { it.isNotEmpty() }?.let { terminal.onFinish(it) }
    }

    /** The final-message fold: only fills channels the streamed deltas left empty/unseen. */
    internal suspend fun applyFinalMessage(msg: JsonObject, sink: WireSink) {
        if (!toolCalls.hasToolUse) prose.foldFinalProse(msg, sink)
        finalToolFold.foldFinalToolCalls(msg, sink)
        refusal.appendRefusal(terminal.refusalBuf, msg, isDelta = false) // CX-08: the whole-copy final-message carrier
    }

    internal suspend fun applyDelta(delta: JsonObject, sink: WireSink) {
        // DR-153: prose still runs BEFORE tool_calls, so a frame carrying both emits its prose and
        // THEN closes it as the tool opens — the order is unchanged. What changes is prose arriving
        // AFTER a tool block is live: it is dropped rather than opened. OpenAI chat has no per-tool
        // stop event, so the only way to open a prose block would be to close the tool first, and a
        // later argument delta for that tool would then land on a closed block and vanish. Dropping
        // late prose loses the prose; closing the tool loses the CALL. Neither is good and this one
        // is recoverable — the turn still carries every argument byte.
        if (!toolCalls.hasToolUse) prose.applyDeltaProse(delta, sink)
        (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
            toolCalls.applyToolCall(tc as? JsonObject ?: return@forEach, sink)
        }
        refusal.appendRefusal(terminal.refusalBuf, delta, isDelta = true) // CX-08: the incremental streamed carrier
    }
}
