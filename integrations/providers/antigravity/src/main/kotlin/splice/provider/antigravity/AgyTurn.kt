// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). One turn of agy's, drawn in Claude Desktop
// as a Claude Code turn: agy's thought and message chunks stream as thinking and text blocks of an assistant message
// (MessageDraw), each tool call is a `tool_use` block of it, and a call's result is the `user` message with its
// `tool_result`, which ends the message before it. Every whole message and result also goes to the transcript. The
// message's stop is `tool_use` when a tool ended it; otherwise it is the stop agy gave the turn, which the caller
// passes on.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.util.UUID

/** How a turn ended: normally, cancelled by the user, or aborted by an error. */
internal enum class TurnClose {
    NORMAL,
    CANCELLED,
    FAILED,
}

/** Draws agy's updates for one session as Claude turns on [out], each whole message written to [transcript]. */
internal class AgyTurn(
    private val frames: DesktopFrames,
    private val out: DesktopOut,
    private val transcript: ClaudeTranscript,
    mcpLookup: McpToolLookup = object : McpToolLookup {
        override fun claudeName(rawName: String): String? = null
    },
) {
    private val cards = ToolCards(mcpLookup)
    private val taskFrames = TaskFrames(frames.sessionId)
    private val tools = LinkedHashMap<String, ToolCard>()
    private val answered = HashSet<String>()
    private val openSubagents = LinkedHashMap<String, SubagentCall>()

    /** The message being drawn, the model it names and the context it reports. */
    val draw: MessageDraw = MessageDraw(frames, out)

    /** One of agy's `session/update`s of a running turn; what is no part of a turn's drawing is passed over. */
    fun update(update: JsonObject) {
        val text = JsonScalars.str(update["content"] as? JsonObject, "text").orEmpty()
        when (JsonScalars.str(update, "sessionUpdate")) {
            "agent_message_chunk" -> stream(TEXT_BLOCK, text)
            "agent_thought_chunk" -> stream(THINKING_BLOCK, text)
            "tool_call", "tool_call_update" -> tool(update)
        }
    }

    /** A `tool_call_update` arriving after the turn's `result`: closes an open subagent pane without starting or
     *  ending another turn or duplicating its `tool_result`. */
    fun afterTurn(update: JsonObject) {
        if (JsonScalars.str(update, "sessionUpdate") != "tool_call_update") return
        val id = JsonScalars.str(update, "toolCallId") ?: return
        val status = JsonScalars.str(update, "status")?.takeIf { it == TOOL_COMPLETED || it == TOOL_FAILED } ?: return
        if (openSubagents.remove(id) != null) out(taskFrames.notification(id, status, cards.resultText(update)))
    }

    /** The card of the call agy asks permission for, drawn first when agy has not shown it yet; the message that
     *  carries it is ended, so Desktop has it before it asks. */
    fun asking(call: JsonObject): ToolCard? {
        val id = JsonScalars.str(call, "toolCallId") ?: return null
        val card = tools[id] ?: started(id, call)
        finish(TOOL_USE_BLOCK)
        return card
    }

    /** [text] said whole as an assistant message: what went wrong, in the conversation. Any still-unanswered tool calls
     *  are paired with a failed `tool_result` first so the error message never separates a `tool_use` from its result. */
    fun say(text: String) {
        closePendingTools(UNFINISHED_TOOL_RESULT, failed = true)
        val chunk = if (draw.streamingText) "\n\n$text" else text
        stream(TEXT_BLOCK, chunk)
        finish(null)
    }

    /** Ends the message still open, with the turn's [stop], pairing any still-open tool call with its `tool_result`
     *  for `claude --resume` and closing open subagent panes as `stopped` when the turn was cancelled or failed. */
    fun end(stop: String?, close: TurnClose = TurnClose.NORMAL) {
        finish(if (draw.callsTools) TOOL_USE_BLOCK else stop)
        val (fallback, failed) = when (close) {
            TurnClose.NORMAL -> "" to false
            TurnClose.CANCELLED -> INTERRUPTED_RESULT to true
            TurnClose.FAILED -> UNFINISHED_TOOL_RESULT to true
        }
        closePendingTools(fallback, failed)
        if (failed) closeOpenSubagents()
    }

    /** Closes every still-open subagent pane as `stopped` (used on turn cancellation and when agy exits between
     *  turns while a background subagent was still running). */
    fun closeOpenSubagents() {
        openSubagents.keys.toList().forEach { id ->
            openSubagents.remove(id)
            out(taskFrames.notification(id, TASK_STOPPED, ""))
        }
    }

    private fun closePendingTools(fallback: String, failed: Boolean) {
        for (id in tools.keys) {
            if (answered.add(id)) emitResult(id, fallback, failed)
        }
    }

    /** An ACP `tool_call` or `tool_call_update`: its card when it is new, its result once it completed or failed. */
    private fun tool(call: JsonObject) {
        val id = JsonScalars.str(call, "toolCallId") ?: return
        if (id !in tools) started(id, call)
        val status = JsonScalars.str(call, "status") ?: return
        val over = status == TOOL_COMPLETED || status == TOOL_FAILED
        if (!over) return
        val text = cards.resultText(call)
        if (answered.add(id)) emitResult(id, text, status == TOOL_FAILED)
        if (openSubagents.remove(id) != null) out(taskFrames.notification(id, status, text))
    }

    /** A chunk of text or thought on the open message (or a new one once a tool result closed the previous). */
    private fun stream(type: String, chunk: String) {
        if (chunk.isEmpty()) return
        draw.stream(type, chunk)
    }

    private fun started(id: String, call: JsonObject): ToolCard {
        val card = cards.of(call)
        tools[id] = card
        draw.toolUse(id, card)
        card.subagent?.let { sub ->
            openSubagents[id] = sub
            out(taskFrames.started(id, sub))
        }
        return card
    }

    private fun emitResult(id: String, text: String, failed: Boolean) {
        finish(if (draw.callsTools) TOOL_USE_BLOCK else null)
        val body = if (failed && text.isBlank()) FAILED_TOOL_RESULT else text
        val content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", body)
                    put("is_error", failed)
                },
            )
        }
        val uuid = UUID.randomUUID().toString()
        out(frames.toolResult(content, uuid))
        transcript.toolResult(uuid, content)
    }

    /** The open message, if any, ended with [stop] and said whole. */
    private fun finish(stop: String?) {
        val whole = draw.finish(stop) ?: return
        val uuid = UUID.randomUUID().toString()
        out(frames.assistant(whole, uuid))
        transcript.assistant(uuid, whole)
    }
}

private const val TOOL_COMPLETED = "completed"
private const val TOOL_FAILED = "failed"
private const val TASK_STOPPED = "stopped"
private const val INTERRUPTED_RESULT = "Interrupted by user"
private const val UNFINISHED_TOOL_RESULT = "Tool did not finish"
private const val FAILED_TOOL_RESULT = "Tool failed"
