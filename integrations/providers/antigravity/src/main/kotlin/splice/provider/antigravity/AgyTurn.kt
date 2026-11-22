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

/** Draws agy's updates for one session as Claude turns on [out], each whole message written to [transcript]. */
internal class AgyTurn(
    private val frames: DesktopFrames,
    private val out: DesktopOut,
    private val transcript: ClaudeTranscript,
) {
    private val cards = ToolCards()
    private val tools = HashMap<String, ToolCard>()
    private val answered = HashSet<String>()

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

    /** The card of the call agy asks permission for, drawn first when agy has not shown it yet; the message that
     *  carries it is ended, so Desktop has it before it asks. */
    fun asking(call: JsonObject): ToolCard? {
        val id = JsonScalars.str(call, "toolCallId") ?: return null
        val card = tools[id] ?: started(id, call)
        finish(TOOL_USE_BLOCK)
        return card
    }

    /** [text] said whole as an assistant message: what went wrong, in the conversation. */
    fun say(text: String) {
        stream(TEXT_BLOCK, text)
        end(null)
    }

    /** Ends the message still open, with the turn's [stop]. */
    fun end(stop: String?) = finish(if (draw.callsTools) TOOL_USE_BLOCK else stop)

    /** An ACP `tool_call` or `tool_call_update`: its card when it is new, its result once it completed or failed. */
    private fun tool(call: JsonObject) {
        val id = JsonScalars.str(call, "toolCallId") ?: return
        if (id !in tools) started(id, call)
        val status = JsonScalars.str(call, "status")
        val over = status == TOOL_COMPLETED || status == TOOL_FAILED
        if (over && answered.add(id)) result(id, call, status == TOOL_FAILED)
    }

    /** A chunk of text or thought; after a tool call it begins a new message. */
    private fun stream(type: String, chunk: String) {
        if (chunk.isEmpty()) return
        if (draw.callsTools) finish(TOOL_USE_BLOCK)
        draw.stream(type, chunk)
    }

    private fun started(id: String, call: JsonObject): ToolCard {
        val card = cards.of(call)
        tools[id] = card
        draw.toolUse(id, card)
        return card
    }

    private fun result(id: String, call: JsonObject, failed: Boolean) {
        finish(TOOL_USE_BLOCK)
        val content = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", cards.resultText(call))
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
