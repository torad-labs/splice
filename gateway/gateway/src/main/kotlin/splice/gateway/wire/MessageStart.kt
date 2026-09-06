// NEW: the lazy turn-opening latch split out of SseEmitter.kt (concentration campaign, HD-24) —
// one responsibility with one flag, shared: both SseEmitter.emitTerminal and the block writer's
// openBlock must call it, and idempotence is a property of that ONE shared `started` boolean, now
// held by the single collaborator both hold rather than duplicated per caller.
package splice.gateway.wire

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TYPE = "type"
private const val MESSAGE = "message"

/** The ping frame, byte-for-byte what building `{"type":"ping"}` produced — as a CONSTANT, because
 *  the turn's opening ping and the keepalive heartbeat (SseEmitter.heartbeat, written from the
 *  pinger's own coroutine) both emit it, and a frame assembled on [SseFrameWriter]'s one reused
 *  buffer from two coroutines is a corrupt frame. Fixed bytes are safe from either. */
internal const val PING_FRAME: String = "event: ping\ndata: {\"type\":\"ping\"}\n\n"

/** Opens the turn on the wire NOW, before any content exists — message_start needs nothing from
 *  upstream (id, model and a zeroed usage payload are all known at build time), and message_start
 *  is followed by ping. Neither literal is walled — only message_stop/message_delta/end_turn are,
 *  which is why they stay in SseEmitter.kt. Idempotent via [hasStarted]. */
internal class MessageStart(
    private val frames: SseFrameWriter,
    private val model: String,
    private val messageId: String,
    private val usagePayload: UsagePayloadBuilder,
) {
    // Volatile: written by the turn's own coroutine, READ by the keepalive pinger's — the pinger
    // reaches this object through its own block writer (WireBlockWriter.openBlock calls ensureStart).
    @Volatile private var started = false

    // Set only once BOTH opener frames are on the wire, and the gate the keepalive pinger asks.
    // NOT [started]: that one is the re-entrancy latch and flips BEFORE message_start is written,
    // so a pinger gating on it could put its ping ahead of the opener, which is not a legal stream.
    @Volatile private var opened = false

    /** message_start and its ping are written — the pinger may speak. */
    internal val hasOpened: Boolean get() = opened

    internal suspend fun ensureStart() {
        if (started) return
        started = true
        frames.frame(
            "message_start",
            buildJsonObject {
                put(TYPE, "message_start")
                putJsonObject(MESSAGE) {
                    put("id", messageId)
                    put(TYPE, MESSAGE)
                    put("role", "assistant")
                    putJsonArray("content") {}
                    put("model", model)
                    put("stop_reason", null as String?)
                    put("stop_sequence", null as String?)
                    put("usage", usagePayload(null))
                }
            },
        )
        frames.writeVerbatim(PING_FRAME)
        opened = true
    }
}
