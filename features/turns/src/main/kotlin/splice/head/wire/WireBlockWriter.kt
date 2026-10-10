// NEW: content-block lifecycle split out of SseEmitter.kt (concentration campaign, HD-24) — the
// whole of WireSink, the SPI's capability-scoped content grammar (deliberately terminal-less: a
// provider translator cannot fake a clean stop by construction). Giving that grammar its own
// implementor and leaving SseEmitter with only the terminal verbs makes the L3 split structural
// rather than conventional: the object that can describe content literally cannot end a turn.
package splice.head.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.index.WireBlockIndex
import splice.core.util.JsonScalars
import splice.upstream.sse.BlockDeltas
import splice.upstream.sse.BlockEnding
import splice.upstream.sse.BlockOpening
import splice.upstream.sse.SourceFrameAction
import splice.upstream.sse.WholeBlocks
import splice.upstream.sse.WireSink
import java.util.concurrent.atomic.AtomicInteger

private const val TYPE = "type"
private const val INDEX = "index"
internal const val BLOCK_START = "content_block_start"
private const val BLOCK_DELTA = "content_block_delta"
private const val BLOCK_STOP = "content_block_stop"

/** The content-block half of an Anthropic SSE stream, in the three parts the SPI grammar splits into:
 *  [WireBlockOpens] opens blocks, [WireBlockWrites] deltas and closes them, and this class owns the verbs that
 *  belong to neither: the backend's opener, its source frames and its relayed events. Byte assembly stays with
 *  [SseFrameWriter]. */
internal class WireBlockWriter(
    private val frames: SseFrameWriter,
    private val start: MessageStart,
    /** The turn's ONE block-index source. Injected and atomic because a turn has a second writer
     *  since 2026-09-06 — the keepalive pinger's status-line writer (SseEmitter.progress), which
     *  runs on its own coroutine and must mint from the same sequence so no two blocks can share an
     *  index and the sequence stays dense. Only the index is shared: each writer keeps its own
     *  open set and its own frame buffer, so the TURN's writer — this file's hot delta path — did
     *  not become concurrent. The pinger's instance is reached by two coroutines and is guarded by
     *  ProgressWire.lock instead; nothing here is thread-safe on its own. */
    nextBlockIndex: AtomicInteger = AtomicInteger(0),
    /** The TURN's writer only: the pinger's notice, ended before this writer opens or closes any
     *  block, so the notice's content_block_stop precedes the block it waited for and Claude Code,
     *  which commits blocks in stop order, draws it above that block (V4-451). At these two choke
     *  points every open verb, present or added later, passes through. Null on the pinger's own
     *  writer, which is what ends the notice. */
    notice: ProgressWire? = null,
    private val writes: WireBlockWrites = WireBlockWrites(frames, notice),
    private val opens: WireBlockOpens = WireBlockOpens(frames, start, nextBlockIndex, notice, writes),
) : WireSink, BlockOpening by opens, WholeBlocks by opens, BlockDeltas by writes, BlockEnding by writes {
    override fun deferMessageStart() = start.defer()

    override suspend fun withSourceFrame(event: JsonObject, action: SourceFrameAction) {
        if (JsonScalars.strOrEmpty(event[TYPE]) == "message_start") start.acceptSource(event)
        writes.native.deliver(event, this, action)
    }

    override suspend fun relayEvent(event: JsonObject, index: WireBlockIndex?) {
        val type = JsonScalars.strOrEmpty(event[TYPE])
        if (type.isEmpty() || type in nativeProtocolEvents) return
        if (event.containsKey(INDEX)) {
            if (index == null) return
            if (index.value !in writes.seen) return
        }
        val payload = if (index == null) {
            event
        } else {
            buildJsonObject {
                event.forEach { (key, value) -> put(key, value) }
                put(INDEX, index.value)
            }
        }
        frames.frame(type, payload)
    }
}

/** Opening a block: reserves the next index, marks it open and writes content_block_start. */
internal class WireBlockOpens(
    private val frames: SseFrameWriter,
    private val start: MessageStart,
    private val nextBlockIndex: AtomicInteger,
    private val notice: ProgressWire?,
    private val writes: WireBlockWrites,
) : BlockOpening, WholeBlocks {
    private suspend fun openBlock(contentBlock: JsonObject): WireBlockIndex {
        notice?.endNoticeAtBoundary()
        start.ensureStart()
        val idx = nextBlockIndex.getAndIncrement()
        writes.open.add(idx)
        writes.seen.add(idx)
        val native = writes.native
        val sourceBlock = native.current(BLOCK_START)?.get("content_block") as? JsonObject
        val initial = native.merge(contentBlock, sourceBlock ?: contentBlock)
        val normalized = if (JsonScalars.strOrEmpty(contentBlock[TYPE]) == "tool_use") {
            native.merge(initial, native.extensions(contentBlock, "input"))
        } else {
            initial
        }
        val owned = buildJsonObject {
            put(TYPE, BLOCK_START)
            put(INDEX, idx)
            put("content_block", normalized)
        }
        frames.frame(BLOCK_START, native.enrich(BLOCK_START, owned))
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

    // DR-119: the content_block payload rides VERBATIM (server_tool_use / web_search_tool_result).
    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex = openBlock(contentBlock)

    override suspend fun addTextBlock(text: String) {
        if (text.isEmpty()) return
        val idx = openText()
        writes.textDelta(idx, text)
        writes.closeBlock(idx)
    }

    override suspend fun addRedactedThinking(data: String) {
        if (data.isEmpty()) return
        val idx = openBlock(
            buildJsonObject {
                put(TYPE, "redacted_thinking")
                put("data", data)
            },
        )
        writes.closeBlock(idx)
    }
}

/** Writing into and closing blocks. Every field/value delta shape goes through the ONE private [hotDelta] choke point,
 *  which carries the open-guard (`if (index.value !in open) return`) beside the [open] set it reads — L3 block-pairing
 *  stays a property of the object that owns the state, not of caller discipline, and a delta shape added later
 *  inherits the guard instead of having to remember to copy it. [rawDelta] is the one non-field shape (a verbatim
 *  delta object) and carries the same guard beside the same set. */
internal class WireBlockWrites(
    private val frames: SseFrameWriter,
    private val notice: ProgressWire?,
) : BlockDeltas, BlockEnding {
    val open = LinkedHashSet<Int>()
    val seen = LinkedHashSet<Int>()
    val native = NativeFields()

    /**
     * The single guarded entry to the hot content_block_delta path — every delta shape below goes
     * through here, and byte assembly is [SseFrameWriter.writeDeltaFrame]'s job. Guard symmetric
     * with [closeBlock]: never write a delta to a block that isn't open (a delta after
     * content_block_stop would corrupt the wire) — L3 block-pairing stays a property of THIS
     * writer, not of caller discipline.
     */
    private suspend fun hotDelta(index: WireBlockIndex, deltaType: String, field: String, value: String) {
        if (index.value !in open) return
        if (!native.has(BLOCK_DELTA)) {
            frames.writeDeltaFrame(index, deltaType, field, value)
            return
        }
        val delta = buildJsonObject {
            put(TYPE, deltaType)
            put(field, value)
        }
        rawDelta(index, delta)
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

    // DR-119: a verbatim delta object (citations_delta). Not a [hotDelta] shape — the payload is a
    // whole JsonObject, not a field/value string — so it carries the SAME open-guard beside the
    // same [open] set; the L3 block-pairing law binds this entry exactly as it binds [hotDelta].
    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        if (index.value !in open) return
        val owned = buildJsonObject {
            put(TYPE, BLOCK_DELTA)
            put(INDEX, index.value)
            put("delta", delta)
        }
        frames.frame(BLOCK_DELTA, native.enrich(BLOCK_DELTA, owned))
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        if (!open.remove(index.value)) return
        notice?.endNoticeAtBoundary()
        if (native.has(BLOCK_STOP)) {
            val owned = buildJsonObject {
                put(TYPE, BLOCK_STOP)
                put(INDEX, index.value)
            }
            frames.frame(BLOCK_STOP, native.enrich(BLOCK_STOP, owned))
        } else {
            frames.writeRawFrame(
                BLOCK_STOP,
                "{\"type\":\"content_block_stop\",\"index\":${index.value}}",
            )
        }
    }

    override suspend fun closeAll() {
        for (idx in open.toList()) closeBlock(WireBlockIndex(idx))
    }
}
