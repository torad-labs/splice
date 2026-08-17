// NEW: frame assembly split out of SseEmitter.kt (concentration campaign, HD-24) — the owner of
// the `event: X\ndata: {json}\n\n` byte format the golden differential diffs. All three
// frame-producing paths stay on ONE object so they keep sharing ONE reused StringBuilder: it keeps
// whatever capacity the largest frame needed, so steady-state hot-delta writes never realloc — a
// property of a single shared buffer that would silently change if split further.
package splice.gateway.wire

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex

// Reused frame buffer: sized to hold a typical delta frame without a regrow; it keeps
// whatever capacity the largest frame needed, so steady-state hot-delta writes never realloc.
private const val FRAME_BUF_CAPACITY = 256

/** Writes SSE frames to [write], reusing one [frameBuf] across every frame shape this emitter
 *  produces — structural frames via [frame]/[writeRawFrame], and the hand-built hot-delta shape
 *  via [hotDelta] (no JsonObject map on the token-per-token path). */
internal class SseFrameWriter(private val write: FrameWrite) {

    // Reused for every frame assembly — never escapes the writer; not concurrent.
    private val frameBuf = StringBuilder(FRAME_BUF_CAPACITY)
    private val escaper = JsonStringEscaper()

    internal suspend fun frame(event: String, data: JsonObject) {
        frameBuf.setLength(0)
        frameBuf.append("event: ").append(event).append("\ndata: ").append(data).append("\n\n")
        write(frameBuf.toString())
    }

    /** Hand-built SSE frame for a fixed-shape hot frame (content_block_stop) — no JsonObject map. */
    internal suspend fun writeRawFrame(event: String, dataJson: String) {
        frameBuf.setLength(0)
        frameBuf.append("event: ").append(event).append("\ndata: ").append(dataJson).append("\n\n")
        write(frameBuf.toString())
    }

    /**
     * Hot-path delta: hand-build
     * `{"type":"content_block_delta","index":N,"delta":{"type":"<deltaType>","<field>":"<escaped>"}}`
     * without a JsonObject map. JSON string escaping is applied to [value] only. The caller (a
     * [WireBlockWriter] delta method) is the one open-block guard — a delta to a block that isn't
     * open would corrupt the wire, and that guard lives beside the `open` set it reads (L3
     * block-pairing stays a property of the object that owns the state).
     */
    internal suspend fun hotDelta(index: WireBlockIndex, deltaType: String, field: String, value: String) {
        // Assemble the WHOLE frame directly into the reused frameBuf — one toString() per token, no
        // throwaway builder/String (the old path built the payload in a fresh buildString THEN copied
        // it into frameBuf and toString()'d again — two Strings/token on the hottest path, defeating
        // the "one reused StringBuilder" this file's header promises).
        frameBuf.setLength(0)
        frameBuf.append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":")
            .append(index.value)
            .append(",\"delta\":{\"type\":\"")
            .append(deltaType)
            .append("\",\"")
            .append(field)
            .append("\":\"")
        // HD-20: appendJsonEscaped's StringBuilder receiver became its first parameter, so the
        // fluent chain breaks here. Same builder, same order, same bytes — only the call shape moved.
        escaper.appendJsonEscaped(frameBuf, value)
        frameBuf.append("\"}}\n\n")
        write(frameBuf.toString())
    }
}
