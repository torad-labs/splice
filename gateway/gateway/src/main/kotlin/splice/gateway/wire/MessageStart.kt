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
    private var started = false

    internal val hasStarted: Boolean get() = started

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
        frames.frame("ping", buildJsonObject { put(TYPE, "ping") })
    }
}
