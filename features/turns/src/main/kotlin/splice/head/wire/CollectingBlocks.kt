// NEW: the non-stream sink's content accumulator — WireSink ops plus the Anthropic
// content-array fold. Split from CollectingTerminal.kt so that class owns only the
// terminal envelope (concentration HIGH, 2026-08-19). Nested Blk is not billed as a
// type. closeBlock/closeAll stay no-ops on the terminal (protocol: nothing streams).
package splice.head.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.index.WireBlockIndex
import splice.core.util.JsonScalars
import splice.upstream.SyntheticToolIds
import splice.upstream.transport.BufferCapacity

private const val FIELD_TYPE = "type"
private const val FIELD_TEXT = "text"
private const val FIELD_THINKING = "thinking"

// FILE SCOPE ON PURPOSE: one shared immutable empty JsonObject. As a member it would allocate per
// CollectingTerminal, i.e. per non-stream turn, for a value that can never differ.
private val EMPTY_INPUT = JsonObject(emptyMap())

internal class CollectingBlocks {

    private sealed class Blk {
        class Text(val sb: StringBuilder = StringBuilder()) : Blk()
        class Thinking(val sb: StringBuilder = StringBuilder(), val sig: StringBuilder = StringBuilder()) : Blk()
        class Tool(
            val id: String,
            val name: String,
            val initialInput: JsonObject?,
            val args: StringBuilder = StringBuilder(),
        ) : Blk()
        class Redacted(val data: String) : Blk()

        /** [args] holds a raw block's streamed input (a server_tool_use's input_json_delta), which the stream path
         *  forwards as it comes and this body has to fold into the block's `input`. */
        class Raw(val payload: JsonObject, val args: StringBuilder = StringBuilder()) : Blk()
    }

    // Blocks in OPEN order — the Anthropic content array order. Index handles are list positions.
    private val blocks = mutableListOf<Blk>()
    internal val source = CollectedNativeFields()

    internal fun openRawBlock(payload: JsonObject): WireBlockIndex {
        blocks.add(Blk.Raw(payload))
        source.start(blocks.lastIndex)
        return WireBlockIndex(blocks.lastIndex)
    }

    // stream:false retains every tool fragment until the terminal body is assembled; cap the
    // aggregate across blocks, not merely each individual tool.
    private var bufferedToolArgsChars = 0L
    internal var toolInputCapacityExceeded: Boolean = false
        private set

    // HEAD-003: latched when a tool_use's accumulated input never parsed as JSON, OR (REG-001)
    // when a tool_use has no usable name and is dropped from content — either way the client must
    // never receive a turn whose stop_reason claims tool_use while content disagrees (dropped
    // silently) or carries the wrong (silently emptied) arguments.
    internal var malformedToolInput: Boolean = false
        private set

    // HEAD-004: ids for a tool_use whose upstream id was blank, unique across turns as the stream path's are.
    private val syntheticIds = SyntheticToolIds()

    internal fun openText(): WireBlockIndex {
        blocks.add(Blk.Text(StringBuilder(source.initial(FIELD_TEXT))))
        source.start(blocks.lastIndex)
        return WireBlockIndex(blocks.lastIndex)
    }

    internal fun openThinking(): WireBlockIndex {
        val thinking = StringBuilder(source.initial(FIELD_THINKING))
        val signature = StringBuilder(source.initial("signature"))
        blocks.add(Blk.Thinking(thinking, signature))
        source.start(blocks.lastIndex)
        return WireBlockIndex(blocks.lastIndex)
    }

    internal fun openTool(id: String, name: String): WireBlockIndex {
        blocks.add(Blk.Tool(id, name, source.initialInput()))
        source.start(blocks.lastIndex)
        return WireBlockIndex(blocks.lastIndex)
    }

    internal fun textDelta(index: WireBlockIndex, text: String) {
        val block = blocks.getOrNull(index.value) as? Blk.Text ?: return
        block.sb.append(text)
        source.delta(index)
    }

    internal fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        val block = blocks.getOrNull(index.value) as? Blk.Thinking ?: return
        block.sb.append(thinking)
        source.delta(index)
    }

    internal fun signatureDelta(index: WireBlockIndex, signature: String) {
        val block = blocks.getOrNull(index.value) as? Blk.Thinking ?: return
        block.sig.append(signature)
        source.delta(index)
    }

    internal fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        val args = when (val block = blocks.getOrNull(index.value)) {
            is Blk.Tool -> block.args
            is Blk.Raw -> block.args
            is Blk.Text, is Blk.Thinking, is Blk.Redacted, null -> return
        }
        if (toolInputCapacityExceeded) return
        val nextSize = bufferedToolArgsChars + partialJson.length
        if (nextSize >= BufferCapacity.MAX_BUFFERED_CHARS) {
            toolInputCapacityExceeded = true
            return
        }
        args.append(partialJson)
        bufferedToolArgsChars = nextSize
        source.delta(index)
    }

    internal fun addTextBlock(text: String) {
        if (text.isNotEmpty()) blocks.add(Blk.Text(StringBuilder(text)))
    }

    internal fun addRedactedThinking(data: String) {
        if (data.isNotEmpty()) blocks.add(Blk.Redacted(data))
    }

    /** Finalize the accumulated blocks into Anthropic content items. Empty text/thinking blocks are
     *  dropped (the wire rejects an empty text block; matches the stream path's honesty gate).
     *
     *  [cut] is true when the upstream ended the message on its output cap. A tool block whose arguments never
     *  parsed was then cut with the output: it is dropped rather than failing the turn as a torn tool, because
     *  the body says max_tokens and a re-send of the same request meets the same cap. HEAD-003 still holds, since
     *  no tool reaches the client with emptied arguments. */
    internal fun contentBlocks(cut: Boolean = false): List<JsonObject> = blocks.mapIndexedNotNull { index, blk ->
        val content = when (blk) {
            is Blk.Text -> blk.sb.takeIf { it.isNotEmpty() }?.let {
                buildJsonObject {
                    put(FIELD_TYPE, FIELD_TEXT)
                    put(FIELD_TEXT, it.toString())
                }
            }
            is Blk.Thinking -> blk.sb.takeIf { it.isNotEmpty() }?.let { thinkingBlock(it.toString(), blk.sig) }
            is Blk.Tool -> toolBlock(blk, cut)
            is Blk.Redacted -> buildJsonObject {
                put(FIELD_TYPE, "redacted_thinking")
                put("data", blk.data)
            }
            is Blk.Raw -> source.raw(index, blk.payload, blk.args)
        }
        content?.let { source.finish(index, it) }
    }

    private fun thinkingBlock(thinking: String, sig: StringBuilder): JsonObject = buildJsonObject {
        put(FIELD_TYPE, FIELD_THINKING)
        put(FIELD_THINKING, thinking)
        if (sig.isNotEmpty()) put("signature", sig.toString())
    }

    // HEAD-004: a blank id is synthesized by the minter every stream fold shares (SyntheticToolIds), so a
    // non-stream turn's id never repeats an earlier turn's — a blank name has no safe stand-in, so
    // the block is dropped from content. REG-001: dropping it silently left stop_reason="tool_use"
    // (computed upstream from the raw event, before this filtering) disagreeing with an empty
    // content array — protocol-invalid. Reuse the malformedToolInput honest-failure path (HEAD-003)
    // instead of shipping the contradiction.
    private fun toolBlock(tool: Blk.Tool, cut: Boolean): JsonObject? {
        if (tool.name.isBlank()) {
            malformedToolInput = true
            return null
        }
        val input = if (tool.args.isEmpty()) {
            tool.initialInput ?: EMPTY_INPUT
        } else {
            parseToolInput(tool.args.toString())
        }
        if (input == null) {
            // A cut message's last tool lost its arguments to the cap (see contentBlocks); anything else is HEAD-003.
            if (!cut) malformedToolInput = true
            return null
        }
        val id = tool.id.ifBlank { syntheticIds.mint() }
        return buildJsonObject {
            put(FIELD_TYPE, "tool_use")
            put("id", id)
            put("name", tool.name)
            put("input", input)
        }
    }

    /** The parsed arguments, or null for non-blank input that never parsed (HEAD-003). */
    private fun parseToolInput(raw: String): JsonObject? {
        if (raw.isBlank()) return EMPTY_INPUT // a tool with genuinely no args — not a parse failure
        return JsonScalars.objectOrNull(Json, raw)
    }
}
