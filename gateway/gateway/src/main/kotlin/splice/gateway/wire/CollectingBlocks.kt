// NEW: the non-stream sink's content accumulator — WireSink ops plus the Anthropic
// content-array fold. Split from CollectingTerminal.kt so that class owns only the
// terminal envelope (concentration HIGH, 2026-08-19). Nested Blk is not billed as a
// type. closeBlock/closeAll stay no-ops on the terminal (protocol: nothing streams).
package splice.gateway.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.index.WireBlockIndex
import splice.core.util.Cancellables

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
        class Tool(val id: String, val name: String, val args: StringBuilder = StringBuilder()) : Blk()
        class Redacted(val data: String) : Blk()
    }

    // Blocks in OPEN order — the Anthropic content array order. Index handles are list positions.
    private val blocks = mutableListOf<Blk>()

    // HEAD-003: latched when a tool_use's accumulated input never parsed as JSON, OR (REG-001)
    // when a tool_use has no usable name and is dropped from content — either way the client must
    // never receive a turn whose stop_reason claims tool_use while content disagrees (dropped
    // silently) or carries the wrong (silently emptied) arguments.
    internal var malformedToolInput: Boolean = false
        private set

    // HEAD-004: id fallback counter for a tool_use whose upstream id was blank.
    private var toolSynthCounter = 0

    internal fun openText(): WireBlockIndex {
        blocks.add(Blk.Text())
        return WireBlockIndex(blocks.lastIndex)
    }

    internal fun openThinking(): WireBlockIndex {
        blocks.add(Blk.Thinking())
        return WireBlockIndex(blocks.lastIndex)
    }

    internal fun openTool(id: String, name: String): WireBlockIndex {
        blocks.add(Blk.Tool(id, name))
        return WireBlockIndex(blocks.lastIndex)
    }

    internal fun textDelta(index: WireBlockIndex, text: String) {
        (blocks.getOrNull(index.value) as? Blk.Text)?.sb?.append(text)
    }

    internal fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        (blocks.getOrNull(index.value) as? Blk.Thinking)?.sb?.append(thinking)
    }

    internal fun signatureDelta(index: WireBlockIndex, signature: String) {
        (blocks.getOrNull(index.value) as? Blk.Thinking)?.sig?.append(signature)
    }

    internal fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        (blocks.getOrNull(index.value) as? Blk.Tool)?.args?.append(partialJson)
    }

    internal fun addTextBlock(text: String) {
        if (text.isNotEmpty()) blocks.add(Blk.Text(StringBuilder(text)))
    }

    internal fun addRedactedThinking(data: String) {
        if (data.isNotEmpty()) blocks.add(Blk.Redacted(data))
    }

    /** Finalize the accumulated blocks into Anthropic content items. Empty text/thinking blocks are
     *  dropped (the wire rejects an empty text block; matches the stream path's honesty gate). */
    internal fun contentBlocks(): List<JsonObject> = blocks.mapNotNull { blk ->
        when (blk) {
            is Blk.Text -> blk.sb.takeIf { it.isNotEmpty() }?.let { textBlock(FIELD_TEXT, it.toString()) }
            is Blk.Thinking -> blk.sb.takeIf { it.isNotEmpty() }?.let { thinkingBlock(it.toString(), blk.sig) }
            is Blk.Tool -> toolBlock(blk)
            is Blk.Redacted -> buildJsonObject {
                put(FIELD_TYPE, "redacted_thinking")
                put("data", blk.data)
            }
        }
    }

    private fun textBlock(type: String, value: String): JsonObject = buildJsonObject {
        put(FIELD_TYPE, type)
        put(type, value)
    }

    private fun thinkingBlock(thinking: String, sig: StringBuilder): JsonObject = buildJsonObject {
        put(FIELD_TYPE, FIELD_THINKING)
        put(FIELD_THINKING, thinking)
        if (sig.isNotEmpty()) put("signature", sig.toString())
    }

    // HEAD-004: a blank id is synthesized (opaque token, same idiom as
    // ResponsesStreamTranslator's toolu_synth_ fallback) — a blank name has no safe stand-in, so
    // the block is dropped from content. REG-001: dropping it silently left stop_reason="tool_use"
    // (computed upstream from the raw event, before this filtering) disagreeing with an empty
    // content array — protocol-invalid. Reuse the malformedToolInput honest-failure path (HEAD-003)
    // instead of shipping the contradiction.
    private fun toolBlock(tool: Blk.Tool): JsonObject? {
        if (tool.name.isBlank()) {
            malformedToolInput = true
            return null
        }
        val id = tool.id.ifBlank { "toolu_synth_${toolSynthCounter++}" }
        return buildJsonObject {
            put(FIELD_TYPE, "tool_use")
            put("id", id)
            put("name", tool.name)
            put("input", parseToolInput(tool.args.toString()))
        }
    }

    private fun parseToolInput(raw: String): JsonObject {
        if (raw.isBlank()) return EMPTY_INPUT // a tool with genuinely no args — not a parse failure
        val parsed = Cancellables.runCatchingCancellable { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (parsed == null) malformedToolInput = true // HEAD-003: non-blank input that never parsed
        return parsed ?: EMPTY_INPUT
    }
}
