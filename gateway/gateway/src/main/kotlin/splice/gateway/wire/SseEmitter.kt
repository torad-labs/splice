// PORT-OF: server/src/anthropic/sse.mjs @ 4ca99f7 — invariants (L3, structural): this file is
// the SOLE Anthropic wire emitter; a clean stop is reachable ONLY via emitTerminal (which owns
// stop_reason derivation: tool_use > max_tokens(incomplete) > end_turn — no caller ever holds
// the literal); failures ONLY via emitError (an SSE error event, so Claude Code retries
// honestly); client-gone seals via abandon() with nothing on the wire. message_start is lazy
// (first block/terminal), followed by ping. Frames are `event: X\ndata: {json}\n\n` exactly —
// the golden differential diffs bytes. The non-stream terminal message builder lives HERE for
// the same reason (its stop_reason literal). Ended-idempotence guards double terminals.
@file:Suppress("StringLiteralDuplication") // a wire emitter IS its literals; extraction would hurt frame readability

package splice.gateway.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.spi.WireSink
import java.util.concurrent.atomic.AtomicBoolean

/** Builds the (non-standard) usage payload Claude Code reads from gateways — injected so the
 *  emitter stays hud-agnostic; the real builder lands with the usage port (P3-USE). */
public typealias UsagePayloadBuilder = (Usage?) -> JsonObject

public class SseEmitter internal constructor(
    private val write: suspend (String) -> Unit,
    private val model: String,
    private val usagePayload: UsagePayloadBuilder,
    private val messageId: String,
) : WireSink {

    private var started = false
    private val ended = AtomicBoolean(false)
    private var nextBlockIndex = 0
    private val open = LinkedHashSet<Int>()

    public val hasStarted: Boolean get() = started
    public val hasEnded: Boolean get() = ended.get()

    private suspend fun frame(event: String, data: JsonObject) {
        write("event: $event\ndata: $data\n\n")
    }

    public suspend fun ensureStart() {
        if (started) return
        started = true
        frame(
            "message_start",
            buildJsonObject {
                put(TYPE, "message_start")
                putJsonObject("message") {
                    put("id", messageId)
                    put(TYPE, "message")
                    put("role", "assistant")
                    putJsonArray("content") {}
                    put("model", model)
                    put("stop_reason", null as String?)
                    put("stop_sequence", null as String?)
                    put("usage", usagePayload(null))
                }
            },
        )
        frame("ping", buildJsonObject { put(TYPE, "ping") })
    }

    private suspend fun openBlock(contentBlock: JsonObject): WireBlockIndex {
        ensureStart()
        val idx = nextBlockIndex++
        open.add(idx)
        frame(
            "content_block_start",
            buildJsonObject {
                put(TYPE, "content_block_start")
                put("index", idx)
                put("content_block", contentBlock)
            },
        )
        return WireBlockIndex(idx)
    }

    private suspend fun delta(index: WireBlockIndex, deltaObj: JsonObject) {
        frame(
            "content_block_delta",
            buildJsonObject {
                put(TYPE, "content_block_delta")
                put("index", index.value)
                put("delta", deltaObj)
            },
        )
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
        delta(
            index,
            buildJsonObject {
                put(TYPE, "text_delta")
                put("text", text)
            },
        )
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        delta(
            index,
            buildJsonObject {
                put(TYPE, "thinking_delta")
                put("thinking", thinking)
            },
        )
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        delta(
            index,
            buildJsonObject {
                put(TYPE, "input_json_delta")
                put("partial_json", partialJson)
            },
        )
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        if (!open.remove(index.value)) return
        frame(
            "content_block_stop",
            buildJsonObject {
                put(TYPE, "content_block_stop")
                put("index", index.value)
            },
        )
    }

    override suspend fun closeAll() {
        for (idx in open.toList()) closeBlock(WireBlockIndex(idx))
    }

    override suspend fun addTextBlock(text: String) {
        if (text.isEmpty()) return
        val idx = openBlock(
            buildJsonObject {
                put(TYPE, "text")
                put("text", "")
            },
        )
        delta(
            idx,
            buildJsonObject {
                put(TYPE, "text_delta")
                put("text", text)
            },
        )
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

    /** The ONLY clean ending — derives stop_reason internally (L3). */
    public suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        if (!ended.compareAndSet(false, true)) return
        ensureStart()
        frame(
            "message_delta",
            buildJsonObject {
                put(TYPE, "message_delta")
                putJsonObject("delta") {
                    put("stop_reason", deriveStopReason(hasToolUse, incomplete))
                    put("stop_sequence", null as String?)
                }
                put("usage", usagePayload(usage))
            },
        )
        frame("message_stop", buildJsonObject { put(TYPE, "message_stop") })
    }

    /** The ONLY failure ending — an SSE error event lets Claude Code retry honestly. */
    public suspend fun emitError(type: ErrorType, message: String) {
        if (!ended.compareAndSet(false, true)) return
        frame(
            "error",
            buildJsonObject {
                put(TYPE, "error")
                putJsonObject("error") {
                    put(TYPE, type.wireName)
                    put("message", message)
                }
            },
        )
    }

    /** Client vanished mid-stream — nothing to emit, just seal the emitter. */
    public fun abandon() {
        ended.set(true)
    }

    public companion object {
        private const val TYPE = "type"

        public fun create(
            write: suspend (String) -> Unit,
            model: String,
            usagePayload: UsagePayloadBuilder,
            messageId: String = "msg_${System.currentTimeMillis()}",
        ): SseEmitter = SseEmitter(write, model, usagePayload, messageId)

        /** Non-stream terminal message (translateResponse envelope) — built HERE because the
         *  stop_reason derivation and its literals are walled to this file (L3). */
        @Suppress("LongParameterList") // wire envelope mirror — all fields are the contract
        public fun terminalMessageJson(
            id: String,
            model: String,
            content: List<JsonObject>,
            hasToolUse: Boolean,
            incomplete: Boolean,
            usagePayload: JsonObject,
        ): JsonObject = buildJsonObject {
            put("id", id)
            put(TYPE, "message")
            put("role", "assistant")
            put("content", buildJsonArray { content.forEach { add(it) } })
            put("model", model)
            put("stop_reason", deriveStopReason(hasToolUse, incomplete))
            put("stop_sequence", null as String?)
            put("usage", usagePayload)
        }

        private fun deriveStopReason(hasToolUse: Boolean, incomplete: Boolean): String = when {
            hasToolUse -> "tool_use"
            incomplete -> "max_tokens"
            else -> "end_turn"
        }
    }
}
