// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). One assistant message drawn for Claude
// Desktop as Anthropic's raw stream events: `message_start`, then each thinking, text or `tool_use` block as its
// `content_block_start`, deltas and `content_block_stop`, then the message whole for its `assistant` line. The stream
// carries no `message_delta` or `message_stop` events: only the turn's wire emitter (SseEmitter) writes those (L3), and
// Desktop takes each message whole from its `assistant` line.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonWire
import splice.core.wire.ANTHROPIC_ASSISTANT_ROLE
import java.util.UUID

/** Where the translator's lines for Desktop go, in order. */
internal fun interface DesktopOut {
    operator fun invoke(line: JsonObject)
}

/** The message being drawn: its blocks so far, the one still streaming, and whether it called a tool. */
private class Draft(val id: String) {
    val blocks = mutableListOf<JsonObject>()
    var streaming: String? = null
    val text = StringBuilder()
    var calledTools = false

    /** The block still streaming, kind [type], as a whole content block. */
    fun block(type: String, text: String): JsonObject = buildJsonObject {
        put("type", type)
        if (type == TEXT_BLOCK) {
            put("text", text)
        } else {
            put("thinking", text)
            put("signature", "")
        }
    }
}

/** Draws assistant messages one at a time on [out] through [frames]. */
internal class MessageDraw(private val frames: DesktopFrames, private val out: DesktopOut) {
    private var draft: Draft? = null

    /** The model Desktop named for the session, which each message names. */
    var model: String = ""

    /** The tokens agy says the session's context holds, which each message reports as its input. */
    var used: Long = 0

    /** Whether the open message called a tool, so text after it belongs to a new one. */
    val callsTools: Boolean get() = draft?.calledTools == true

    /** [chunk] of a block of kind [type] (`text` or `thinking`), opening the message and the block as needed. */
    fun stream(type: String, chunk: String) {
        val open = draft ?: started()
        if (open.streaming != type) {
            endBlock(open)
            open.streaming = type
            event("content_block_start") {
                put(BLOCK_INDEX, open.blocks.size)
                put("content_block", open.block(type, ""))
            }
        }
        open.text.append(chunk)
        event("content_block_delta") {
            put(BLOCK_INDEX, open.blocks.size)
            putJsonObject("delta") {
                if (type == TEXT_BLOCK) {
                    put("type", "text_delta")
                    put("text", chunk)
                } else {
                    put("type", "thinking_delta")
                    put("thinking", chunk)
                }
            }
        }
    }

    /** Tool call [id] on [card], drawn whole as a `tool_use` block. */
    fun toolUse(id: String, card: ToolCard) {
        val open = draft ?: started()
        endBlock(open)
        val index = open.blocks.size
        val use = buildJsonObject {
            put("type", TOOL_USE_BLOCK)
            put("id", id)
            put("name", card.name)
            put("input", card.input)
        }
        event("content_block_start") {
            put(BLOCK_INDEX, index)
            put("content_block", JsonObject(use + ("input" to JsonObject(emptyMap()))))
        }
        event("content_block_delta") {
            put(BLOCK_INDEX, index)
            putJsonObject("delta") {
                put("type", "input_json_delta")
                put("partial_json", JsonWire.string(card.input))
            }
        }
        event("content_block_stop") { put(BLOCK_INDEX, index) }
        open.blocks += use
        open.calledTools = true
    }

    /** The open message ended with [stop], whole; null when none is open. */
    fun finish(stop: String?): JsonObject? {
        val open = draft ?: return null
        endBlock(open)
        draft = null
        return whole(open, stop)
    }

    private fun started(): Draft {
        val open = Draft("msg_" + UUID.randomUUID().toString().replace("-", ""))
        draft = open
        event("message_start") { put("message", whole(open, null)) }
        return open
    }

    private fun endBlock(open: Draft) {
        val type = open.streaming ?: return
        event("content_block_stop") { put(BLOCK_INDEX, open.blocks.size) }
        open.blocks += open.block(type, open.text.toString())
        open.streaming = null
        open.text.clear()
    }

    private fun whole(open: Draft, stop: String?): JsonObject = buildJsonObject {
        put("id", open.id)
        put("type", "message")
        put("role", ANTHROPIC_ASSISTANT_ROLE)
        put("model", model)
        put("content", JsonArray(open.blocks.toList()))
        put("stop_reason", stop)
        put("stop_sequence", JsonNull)
        putJsonObject("usage") {
            put("input_tokens", used)
            put("output_tokens", 0)
            put("cache_creation_input_tokens", 0)
            put("cache_read_input_tokens", 0)
        }
    }

    private inline fun event(type: String, body: JsonObjectBuilder.() -> Unit) {
        val event = buildJsonObject {
            put("type", type)
            body()
        }
        out(frames.streamEvent(event))
    }
}

/** A text block's kind, and the kind of a chunk agy says to the person. */
internal const val TEXT_BLOCK: String = "text"

/** A thinking block's kind, and the kind of a chunk of agy's thought. */
internal const val THINKING_BLOCK: String = "thinking"

/** A tool call's block kind, and the stop of a message a tool call ended. */
internal const val TOOL_USE_BLOCK: String = "tool_use"

/** Where a stream event's block sits in its message. */
private const val BLOCK_INDEX = "index"
