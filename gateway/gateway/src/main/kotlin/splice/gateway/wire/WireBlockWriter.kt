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
 *  content, delegating actual byte assembly to [frames]. The delta open-guard
 *  (`if (index.value !in open) return`) lives here beside the [open] set it reads — L3
 *  block-pairing stays a property of the object that owns the state, not of caller discipline. */
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

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        if (index.value !in open) return
        frames.hotDelta(index, "text_delta", "text", text)
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        if (index.value !in open) return
        frames.hotDelta(index, "thinking_delta", "thinking", thinking)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        if (index.value !in open) return
        frames.hotDelta(index, "input_json_delta", "partial_json", partialJson)
    }

    // signature_delta rides the content_block_delta frame like the token deltas; the open-block
    // guard makes a delta to a closed/unknown index a no-op (L3 block-pairing).
    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        if (index.value !in open) return
        frames.hotDelta(index, "signature_delta", "signature", signature)
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
}
