// PORT-OF: server/src/anthropic/sse.mjs @ pre-public-port-baseline — invariants (L3, structural): this file is
// the SOLE Anthropic wire emitter; a clean stop is reachable ONLY via emitTerminal (which owns
// stop_reason derivation: tool_use > max_tokens(incomplete) > end_turn — no caller ever holds
// the literal); failures ONLY via emitError (an SSE error event, so Claude Code retries
// honestly); client-gone seals via abandon() with nothing on the wire. message_start is lazy
// (first block/terminal), followed by ping. Frames are `event: X\ndata: {json}\n\n` exactly —
// the golden differential diffs bytes. The non-stream terminal message builder lives HERE for
// the same reason (its stop_reason literal). Ended-idempotence guards double terminals.
//
// Hot path: the three per-token delta shapes (text / thinking / input_json) are hand-built into
// a reused StringBuilder so a long stream does not allocate a JsonObject map + toString per token.
// Structural frames (start/stop/terminal/error) still use buildJsonObject — they fire O(blocks).
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
import java.util.concurrent.atomic.AtomicBoolean

/** Builds the (non-standard) usage payload Claude Code reads from gateways — injected so the
 *  emitter stays hud-agnostic; the real builder lands with the usage port (P3-USE). */
public typealias UsagePayloadBuilder = (Usage?) -> JsonObject

/** The fields of the non-stream terminal message envelope, grouped so its builder
 *  ([SseEmitter.terminalMessageJson]) keeps a single cohesive argument (L3 wire mirror). */
public data class TerminalMessage(
    val id: String,
    val model: String,
    val content: List<JsonObject>,
    val hasToolUse: Boolean,
    val incomplete: Boolean,
    val usagePayload: JsonObject,
)

public class SseEmitter internal constructor(
    private val write: suspend (String) -> Unit,
    private val model: String,
    private val usagePayload: UsagePayloadBuilder,
    private val messageId: String,
) : TurnTerminal {

    private var started = false
    private val ended = AtomicBoolean(false)
    private var nextBlockIndex = 0
    private val open = LinkedHashSet<Int>()

    // Reused for every frame assembly — never escapes the emitter; not concurrent.
    private val frameBuf = StringBuilder(FRAME_BUF_CAPACITY)

    public val hasStarted: Boolean get() = started
    public val hasEnded: Boolean get() = ended.get()

    private suspend fun frame(event: String, data: JsonObject) {
        frameBuf.setLength(0)
        frameBuf.append("event: ").append(event).append("\ndata: ").append(data).append("\n\n")
        write(frameBuf.toString())
    }

    /** Hand-built SSE frame for a fixed-shape hot frame (content_block_stop) — no JsonObject map. */
    private suspend fun writeRawFrame(event: String, dataJson: String) {
        frameBuf.setLength(0)
        frameBuf.append("event: ").append(event).append("\ndata: ").append(dataJson).append("\n\n")
        write(frameBuf.toString())
    }

    public suspend fun ensureStart() {
        if (started) return
        started = true
        frame(
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

    /**
     * Hot-path delta: hand-build
     * `{"type":"content_block_delta","index":N,"delta":{"type":"<deltaType>","<field>":"<escaped>"}}`
     * without a JsonObject map. JSON string escaping is applied to [value] only. Guard symmetric
     * with closeBlock: never write a delta to a block that isn't open (a delta after
     * content_block_stop would corrupt the wire) — L3 block-pairing stays a property of THIS
     * emitter, not of caller discipline.
     */
    private suspend fun hotDelta(index: WireBlockIndex, deltaType: String, field: String, value: String) {
        if (index.value !in open) return
        // Assemble the WHOLE frame directly into the reused frameBuf — one toString() per token, no
        // throwaway builder/String (the old path built the payload in a fresh buildString THEN copied
        // it into frameBuf and toString()'d again — two Strings/token on the hottest path, defeating
        // the "one reused StringBuilder" the file header promises).
        frameBuf.setLength(0)
        frameBuf.append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":")
            .append(index.value)
            .append(",\"delta\":{\"type\":\"")
            .append(deltaType)
            .append("\",\"")
            .append(field)
            .append("\":\"")
            .appendJsonEscaped(value)
            .append("\"}}\n\n")
        write(frameBuf.toString())
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
        writeRawFrame(
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

    /** The ONLY clean ending — derives stop_reason internally (L3). */
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
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
    override suspend fun emitError(type: ErrorType, message: String) {
        if (!ended.compareAndSet(false, true)) return
        frame(
            "error",
            buildJsonObject {
                put(TYPE, "error")
                putJsonObject("error") {
                    put(TYPE, type.wireName)
                    put(MESSAGE, message)
                }
            },
        )
    }

    /** Client vanished mid-stream — nothing to emit, just seal the emitter. */
    override fun abandon() {
        ended.set(true)
    }

    public companion object {
        private const val TYPE = "type"
        private const val MESSAGE = "message"

        // Reused frame buffer: sized to hold a typical delta frame without a regrow; it keeps
        // whatever capacity the largest frame needed, so steady-state hot-delta writes never realloc.
        private const val FRAME_BUF_CAPACITY = 256

        public fun create(
            write: suspend (String) -> Unit,
            model: String,
            usagePayload: UsagePayloadBuilder,
            messageId: String = "msg_${System.currentTimeMillis()}",
        ): SseEmitter = SseEmitter(write, model, usagePayload, messageId)

        /** Non-stream terminal message (translateResponse envelope) — built HERE because the
         *  stop_reason derivation and its literals are walled to this file (L3). The envelope
         *  fields are grouped into [TerminalMessage] so the builder stays a single L3 argument. */
        public fun terminalMessageJson(msg: TerminalMessage): JsonObject = buildJsonObject {
            put("id", msg.id)
            put(TYPE, MESSAGE)
            put("role", "assistant")
            put("content", buildJsonArray { msg.content.forEach { add(it) } })
            put("model", msg.model)
            put("stop_reason", deriveStopReason(msg.hasToolUse, msg.incomplete))
            put("stop_sequence", null as String?)
            put("usage", msg.usagePayload)
        }

        private fun deriveStopReason(hasToolUse: Boolean, incomplete: Boolean): String = when {
            hasToolUse -> "tool_use"
            incomplete -> "max_tokens"
            else -> "end_turn"
        }
    }
}

// JSON string-escape shapes (RFC 8259): controls below 0x20 escape as \u four-hex-digits.
private const val CONTROL_CHAR_BOUND = 0x20
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4

/** RFC 8259 string escape into [this] builder — only the value payload of a hot delta.
 *  MUST stay byte-identical to kotlinx-serialization's escaper (L3: the golden differential
 *  diffs bytes): kotlinx emits the SHORT forms for backspace (0x08) and form feed (0x0C);
 *  the \uXXXX forms would be valid JSON but a byte divergence (audit 2026-07-18; the whole
 *  control range is pinned by SseEscapingParityTest). */
private fun StringBuilder.appendJsonEscaped(s: String): StringBuilder {
    for (i in s.indices) {
        val c = s[i]
        val shortEsc = shortEscape(c)
        when {
            shortEsc != null -> append(shortEsc)
            c.code < CONTROL_CHAR_BOUND -> appendUnicodeEscape(c)
            else -> append(c)
        }
    }
    return this
}

// Control-char code points kotlinx short-escapes (named so the escaper carries no magic numbers).
private const val CH_BACKSPACE = 0x08
private const val CH_TAB = 0x09
private const val CH_NEWLINE = 0x0A
private const val CH_FORMFEED = 0x0C
private const val CH_RETURN = 0x0D

/** The six two-char JSON escapes kotlinx emits; null = not a short-escaped char. */
private fun shortEscape(c: Char): String? = when (c.code) {
    '\\'.code -> "\\\\"
    '"'.code -> "\\\""
    CH_NEWLINE -> "\\n"
    CH_RETURN -> "\\r"
    CH_TAB -> "\\t"
    CH_BACKSPACE -> "\\b"
    CH_FORMFEED -> "\\f"
    else -> null
}

private fun StringBuilder.appendUnicodeEscape(c: Char) {
    append("\\u")
    val hex = c.code.toString(HEX_RADIX)
    repeat(UNICODE_ESCAPE_DIGITS - hex.length) { append('0') }
    append(hex)
}
