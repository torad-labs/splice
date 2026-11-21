// NEW 2026-11-19 (reply-stream): reads one turn's Anthropic Messages frames, as splice wrote them to its client, into
// what a follower of the live route is sent. Every head and dialect reaches the client in these frames, so one reader
// serves them all. Only text, thinking and a tool call's start are read out; a signature, redacted thinking, a tool's
// input and usage never are. The non-stream answer is read here too, so no JSON body is taken apart anywhere else.
package splice.head.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.head.wire.BLOCK_DELTA
import splice.head.wire.BLOCK_START
import splice.head.wire.MESSAGE_DELTA
import splice.head.wire.MESSAGE_STOP

/** The error event's type and field, and what an error event reads as when it names no type of its own. */
private const val REPLY_ERROR_EVENT = "error"

private const val OPENING_EVENT = "message_start"

/** One turn's reply as read so far: the message it answers with, the stop reason the client got, and whether the
 *  response has ended (and with which error). A [ReplyReading] either [follow]s frames as they are written, answering
 *  each one's events, or [gather]s them, keeping each block whole for a follower that joins mid-reply. */
internal class ReplyReading(private val turn: String) {
    private enum class Kind { TEXT, THINKING, TOOL }

    private class Block(val kind: Kind) {
        val content = StringBuilder()
        var id: String? = null
            private set
        var name: String? = null
            private set

        /** [text] written to this block; a part of another kind under the same index is not this block's. */
        fun write(part: Kind, text: String) {
            if (part == kind) content.append(text)
        }

        fun named(toolId: String?, toolName: String?) {
            id = toolId
            name = toolName
        }
    }

    /** The id of the message the client was sent: its message_start's, or a JSON answer's. */
    var message: String? = null
        private set

    /** The stop reason the client got in its message_delta; null while none was sent. */
    var stopReason: String? = null
        private set

    /** Whether the client was sent the response's end: message_stop or an error event. */
    var ended: Boolean = false
        private set

    /** The type of the error event the client got, when it got one. */
    var error: String? = null
        private set

    private val blocks = sortedMapOf<Int, Block>()

    /** The follower events [frame] carries, in order, the turn's `end` included when the frame ends the response. */
    fun follow(frame: String): List<String> {
        val out = ArrayList<String>(1)
        val wasEnded = ended
        FrameEvents.of(frame).forEach { dispatch(it, out) }
        if (ended && !wasEnded) out += LiveWire.end(turn, stopReason, error)
        return out
    }

    /** Takes in [frame] without answering events: each block is kept whole, for [snapshot]. */
    fun gather(frame: String) {
        FrameEvents.of(frame).forEach { dispatch(it, null) }
    }

    /** One event per block read so far, in block-index order, each holding everything written to it. */
    fun snapshot(): List<String> = blocks.mapNotNull { (index, block) ->
        val content = block.content.toString()
        when {
            block.kind == Kind.TOOL -> LiveWire.tool(turn, index, block.id, block.name)
            content.isEmpty() -> null
            block.kind == Kind.TEXT -> LiveWire.text(turn, index, content)
            else -> LiveWire.thinking(turn, index, content)
        }
    }

    /** Takes [other]'s reading of the same frames as its own (the message, the stop reason, the ending), so following
     *  goes on from where a joiner's snapshot stopped. */
    fun adopt(other: ReplyReading) {
        message = other.message ?: message
        stopReason = other.stopReason
        ended = other.ended
        error = other.error
    }

    /** Reads only the message id off a message_start frame, for a turn nobody followed while it started. */
    fun opening(frame: String) {
        FrameEvents.of(frame).firstOrNull { it.type == OPENING_EVENT }?.let { opened(it.data) }
    }

    /** A non-stream answer, whole: one event per content block, then its `end`. An error answer carries no block and
     *  ends with its error's type. Its message id is read first, for the turn event that goes before these. */
    fun answer(body: JsonObject): List<String> {
        val out = ArrayList<String>()
        message = JsonScalars.str(body, "id")
        if (JsonScalars.str(body, "type") == REPLY_ERROR_EVENT) {
            failed(body)
        } else {
            (body["content"] as? JsonArray)?.forEachIndexed { index, block ->
                (block as? JsonObject)?.let { started(index, it, null) }
            }
            out += snapshot()
            stopReason = JsonScalars.str(body, "stop_reason")
            ended = true
        }
        out += LiveWire.end(turn, stopReason, error)
        return out
    }

    private fun dispatch(event: FrameEvent, out: MutableList<String>?) {
        if (ended) return
        val data = event.data
        when (event.type) {
            OPENING_EVENT -> opened(data)
            BLOCK_START, BLOCK_DELTA -> block(event, out)
            MESSAGE_DELTA -> (data["delta"] as? JsonObject)?.takeIf { it.containsKey("stop_reason") }?.let {
                stopReason = JsonScalars.str(it, "stop_reason")
            }
            MESSAGE_STOP -> ended = true
            REPLY_ERROR_EVENT -> failed(data)
        }
    }

    /** A block's start or one of its deltas. */
    private fun block(event: FrameEvent, out: MutableList<String>?) {
        val index = JsonScalars.int(event.data, "index") ?: return
        val start = event.type == BLOCK_START
        val part = event.data[if (start) "content_block" else "delta"] as? JsonObject ?: return
        if (start) started(index, part, out) else delta(index, part, out)
    }

    private fun opened(data: JsonObject) {
        if (message == null) message = JsonScalars.str(data["message"] as? JsonObject, "id")
    }

    private fun failed(data: JsonObject) {
        error = JsonScalars.str(data[REPLY_ERROR_EVENT] as? JsonObject, "type") ?: REPLY_ERROR_EVENT
        ended = true
    }

    private fun started(index: Int, block: JsonObject, out: MutableList<String>?) {
        when (JsonScalars.str(block, "type")) {
            "text" -> written(index, Kind.TEXT, JsonScalars.str(block, "text").orEmpty(), out)
            "thinking" -> written(index, Kind.THINKING, JsonScalars.str(block, "thinking").orEmpty(), out)
            "tool_use", "server_tool_use" ->
                tool(index, JsonScalars.str(block, "id"), JsonScalars.str(block, "name"), out)
        }
    }

    private fun tool(index: Int, id: String?, name: String?, out: MutableList<String>?) {
        if (out != null) {
            out += LiveWire.tool(turn, index, id, name)
            return
        }
        blocks.getOrPut(index) { Block(Kind.TOOL) }.named(id, name)
    }

    private fun delta(index: Int, delta: JsonObject, out: MutableList<String>?) {
        when (JsonScalars.str(delta, "type")) {
            "text_delta" -> written(index, Kind.TEXT, JsonScalars.str(delta, "text").orEmpty(), out)
            "thinking_delta" -> written(index, Kind.THINKING, JsonScalars.str(delta, "thinking").orEmpty(), out)
        }
    }

    private fun written(index: Int, kind: Kind, text: String, out: MutableList<String>?) {
        if (out == null) {
            blocks.getOrPut(index) { Block(kind) }.write(kind, text)
            return
        }
        if (text.isEmpty()) return
        out += if (kind == Kind.TEXT) LiveWire.text(turn, index, text) else LiveWire.thinking(turn, index, text)
    }
}
