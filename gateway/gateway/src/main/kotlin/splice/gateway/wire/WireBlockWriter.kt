// NEW: content-block lifecycle split out of SseEmitter.kt (concentration campaign, HD-24) — the
// whole of WireSink, the SPI's capability-scoped content grammar (deliberately terminal-less: a
// provider translator cannot fake a clean stop by construction). Giving that grammar its own
// implementor and leaving SseEmitter with only the terminal verbs makes the L3 split structural
// rather than conventional: the object that can describe content literally cannot end a turn.
package splice.gateway.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.index.WireBlockIndex
import splice.spi.WireSink

private const val TYPE = "type"

/** The content-block half of an Anthropic SSE stream: opens/closes blocks and deltas their
 *  content, delegating actual byte assembly to [frames]. Every field/value delta shape goes
 *  through the ONE private [hotDelta] choke point, which carries the open-guard
 *  (`if (index.value !in open) return`) beside the [open] set it reads — L3 block-pairing stays
 *  a property of the object that owns the state, not of caller discipline, and a delta shape
 *  added later inherits the guard instead of having to remember to copy it. [rawDelta] is the
 *  one non-field shape (a verbatim delta object) and carries the same guard beside the same set. */
internal class WireBlockWriter(
    private val frames: SseFrameWriter,
    private val start: MessageStart,
) : WireSink {
    private var nextBlockIndex = 0
    private val open = LinkedHashSet<Int>()

    private suspend fun openBlock(contentBlock: JsonObject): WireBlockIndex {
        start.ensureStart()
        val idx = nextBlockIndex++
        open.add(idx)
        frames.frame(
            "content_block_start",
            buildJsonObject {
                put(TYPE, "content_block_start")
                put("index", idx)
                put("content_block", contentBlock)
            },
        )
        return WireBlockIndex(idx)
    }

    override suspend fun openText(): WireBlockIndex =
        openBlock(
            buildJsonObject {
                put(TYPE, "text")
                put("text", "")
            },
        )

    override suspend fun openThinking(): WireBlockIndex =
        openBlock(
            buildJsonObject {
                put(TYPE, "thinking")
                put("thinking", "")
            },
        )

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        openBlock(
            buildJsonObject {
                put(TYPE, "tool_use")
                put("id", id)
                put("name", name)
                putJsonObject("input") {}
            },
        )

    /**
     * The single guarded entry to the hot content_block_delta path — every delta shape below goes
     * through here, and byte assembly is [SseFrameWriter.writeDeltaFrame]'s job. Guard symmetric
     * with [closeBlock]: never write a delta to a block that isn't open (a delta after
     * content_block_stop would corrupt the wire) — L3 block-pairing stays a property of THIS
     * writer, not of caller discipline.
     */
    private suspend fun hotDelta(index: WireBlockIndex, deltaType: String, field: String, value: String) {
        if (index.value !in open) return
        frames.writeDeltaFrame(index, deltaType, field, value)
    }

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        hotDelta(index, "text_delta", "text", text)
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        hotDelta(index, "thinking_delta", "thinking", thinking)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        hotDelta(index, "input_json_delta", "partial_json", partialJson)
    }

    // signature_delta rides the content_block_delta frame like the token deltas; hotDelta's
    // open-block guard makes a delta to a closed/unknown index a no-op (L3 block-pairing).
    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        hotDelta(index, "signature_delta", "signature", signature)
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        if (!open.remove(index.value)) return
        // Fixed shape, no user content — hand-built, no JsonObject.
        frames.writeRawFrame(
            "content_block_stop",
            "{\"type\":\"content_block_stop\",\"index\":${index.value}}",
        )
    }

    override suspend fun closeAll() {
        for (idx in open.toList()) closeBlock(WireBlockIndex(idx))
    }

    override suspend fun addTextBlock(text: String) {
        if (text.isEmpty()) return
        val idx = openText()
        textDelta(idx, text)
        closeBlock(idx)
    }

    override suspend fun addRedactedThinking(data: String) {
        if (data.isEmpty()) return
        val idx = openBlock(
            buildJsonObject {
                put(TYPE, "redacted_thinking")
                put("data", data)
            },
        )
        closeBlock(idx)
    }

    // DR-119: the content_block payload rides VERBATIM (server_tool_use / web_search_tool_result).
    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex = openBlock(contentBlock)

    // DR-119: a verbatim delta object (citations_delta). Not a [hotDelta] shape — the payload is a
    // whole JsonObject, not a field/value string — so it carries the SAME open-guard beside the
    // same [open] set; the L3 block-pairing law binds this entry exactly as it binds [hotDelta].
    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        if (index.value !in open) return
        frames.frame(
            "content_block_delta",
            buildJsonObject {
                put(TYPE, "content_block_delta")
                put("index", index.value)
                put("delta", delta)
            },
        )
    }
}
