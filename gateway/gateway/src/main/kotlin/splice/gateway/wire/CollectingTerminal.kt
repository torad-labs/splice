// NEW: the non-stream sink (2026-07-20). Claude Code streams for interactive turns but sends
// stream:false on some internal calls (the Node predecessor served these by collecting the
// terminal Responses object; the Kotlin port rejected them with a 400 — the "serves streaming
// clients only" errors). This TurnTerminal accumulates the SAME content ops the SseEmitter would
// have framed and exposes them as ONE Anthropic Messages JSON body (translateResponse parity),
// so the whole fold/translator/honesty pipeline drives it unchanged — no parallel non-stream path.
package splice.gateway.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.core.util.runCatchingCancellable
import java.util.concurrent.atomic.AtomicBoolean

private const val OK_STATUS = 200
private const val DEFAULT_ERROR_STATUS = 502
private const val STATUS_INVALID = 400
private const val STATUS_AUTH = 401
private const val STATUS_PERMISSION = 403
private const val STATUS_NOT_FOUND = 404
private const val STATUS_RATE_LIMIT = 429
private const val STATUS_OVERLOADED = 529

private const val FIELD_TYPE = "type"
private const val FIELD_TEXT = "text"
private const val FIELD_THINKING = "thinking"
private const val FIELD_ERROR = "error"

// FILE SCOPE ON PURPOSE: one shared immutable empty JsonObject. As a member it would allocate per
// CollectingTerminal, i.e. per non-stream turn, for a value that can never differ.
private val EMPTY_INPUT = JsonObject(emptyMap())

public class CollectingTerminal(
    private val model: String,
    private val usagePayload: UsagePayloadBuilder,
    private val messageId: String = MessageIds().generateMessageId(),
) : TurnTerminal {

    private sealed class Blk {
        class Text(val sb: StringBuilder = StringBuilder()) : Blk()
        class Thinking(val sb: StringBuilder = StringBuilder(), val sig: StringBuilder = StringBuilder()) : Blk()
        class Tool(val id: String, val name: String, val args: StringBuilder = StringBuilder()) : Blk()
        class Redacted(val data: String) : Blk()
    }

    // Blocks in OPEN order — the Anthropic content array order. Index handles are list positions.
    private val blocks = mutableListOf<Blk>()
    private val ended = AtomicBoolean(false)

    // The L3 terminal envelope (stop_reason derivation lives in SseEmitter.kt), held not copied.
    private val envelope = TerminalEnvelope()

    override val hasEnded: Boolean get() = ended.get()

    private var body: JsonObject? = null
    private var status = DEFAULT_ERROR_STATUS

    // HEAD-003: latched when a tool_use's accumulated input never parsed as JSON, OR (REG-001)
    // when a tool_use has no usable name and is dropped from content — either way the client must
    // never receive a turn whose stop_reason claims tool_use while content disagrees (dropped
    // silently) or carries the wrong (silently emptied) arguments.
    private var malformedToolInput = false

    // HEAD-004: id fallback counter for a tool_use whose upstream id was blank.
    private var toolSynthCounter = 0

    /** The single JSON body to write back (a terminal message or an error envelope). Never null
     *  after a driven turn — a turn always ends in emitTerminal or emitError; the fallback covers
     *  only a torn drive that somehow emitted neither. */
    public fun responseBody(): JsonObject = body ?: errorEnvelope(
        ErrorType.API_ERROR.wireName,
        "claudex: gateway produced no response — retry",
    )

    public fun httpStatus(): Int = status

    // ── content accumulation (WireSink) ──────────────────────────────────────
    override suspend fun openText(): WireBlockIndex = add(Blk.Text())

    override suspend fun openThinking(): WireBlockIndex = add(Blk.Thinking())

    override suspend fun openTool(id: String, name: String): WireBlockIndex = add(Blk.Tool(id, name))

    private fun add(blk: Blk): WireBlockIndex {
        blocks.add(blk)
        return WireBlockIndex(blocks.lastIndex)
    }

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        (blocks.getOrNull(index.value) as? Blk.Text)?.sb?.append(text)
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        (blocks.getOrNull(index.value) as? Blk.Thinking)?.sb?.append(thinking)
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        (blocks.getOrNull(index.value) as? Blk.Thinking)?.sig?.append(signature)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        (blocks.getOrNull(index.value) as? Blk.Tool)?.args?.append(partialJson)
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        // no-op: blocks finalize at build time (contentBlocks), never on close
    }

    override suspend fun closeAll() {
        // no-op: nothing streams here; the whole body is assembled at the terminal
    }

    override suspend fun addTextBlock(text: String) {
        if (text.isNotEmpty()) blocks.add(Blk.Text(StringBuilder(text)))
    }

    override suspend fun addRedactedThinking(data: String) {
        if (data.isNotEmpty()) blocks.add(Blk.Redacted(data))
    }

    // ── terminal (TurnTerminal) ──────────────────────────────────────────────
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        if (!ended.compareAndSet(false, true)) return
        val content = contentBlocks()
        if (malformedToolInput) {
            // HEAD-003: a tool_use whose input never parsed as JSON must not reach the client as
            // {} — a tool executing with the wrong (silently emptied) arguments is a wrong action
            // taken on the user's machine. Fail the turn honestly instead. `ended` is already
            // latched by this call's own CAS above, so this sets body/status directly rather than
            // through emitError (its CAS would no-op against an already-ended terminal).
            // RG2-001: the turn's usage still rides the envelope — the client was billed for this
            // turn even though it failed honestly, and the internal usage store already recorded
            // it; the wire response must not be the one place that accounting goes missing.
            body = errorEnvelope(
                ErrorType.API_ERROR.wireName,
                "claudex: malformed tool_use input from upstream — retry",
                usagePayload(usage),
            )
            status = statusFor(ErrorType.API_ERROR)
            return
        }
        body = envelope.terminalMessageJson(
            TerminalMessage(
                id = messageId,
                model = model,
                content = content,
                hasToolUse = hasToolUse,
                incomplete = incomplete,
                usagePayload = usagePayload(usage),
            ),
        )
        status = OK_STATUS
    }

    override suspend fun emitError(type: ErrorType, message: String) {
        if (!ended.compareAndSet(false, true)) return
        body = errorEnvelope(type.wireName, message)
        status = statusFor(type)
    }

    override fun abandon() {
        ended.set(true) // no body: responseBody() falls back to the honest api_error envelope
    }

    /** Finalize the accumulated blocks into Anthropic content items. Empty text/thinking blocks are
     *  dropped (the wire rejects an empty text block; matches the stream path's honesty gate). */
    private fun contentBlocks(): List<JsonObject> = blocks.mapNotNull { blk ->
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
        val parsed = runCatchingCancellable { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (parsed == null) malformedToolInput = true // HEAD-003: non-blank input that never parsed
        return parsed ?: EMPTY_INPUT
    }

    // RG2-001: [usage] is null for every OTHER caller of this envelope (the responseBody()
    // fallback has none to give) — only the malformed-tool-use path in emitTerminal has a real
    // turn usage in scope, so it is the only caller that passes one.
    private fun errorEnvelope(type: String, message: String, usage: JsonObject? = null): JsonObject =
        buildJsonObject {
            put(FIELD_TYPE, FIELD_ERROR)
            put(
                FIELD_ERROR,
                buildJsonObject {
                    put(FIELD_TYPE, type)
                    put("message", message)
                },
            )
            usage?.let { put("usage", it) }
        }

    // ErrorType -> HTTP status. api_error maps to 502 to match the Node non-stream path's
    // upstream-error/empty-model response; the rest mirror the Anthropic status conventions.
    private fun statusFor(type: ErrorType): Int = when (type) {
        ErrorType.INVALID_REQUEST -> STATUS_INVALID
        ErrorType.AUTHENTICATION -> STATUS_AUTH
        ErrorType.PERMISSION -> STATUS_PERMISSION
        ErrorType.NOT_FOUND -> STATUS_NOT_FOUND
        ErrorType.RATE_LIMIT -> STATUS_RATE_LIMIT
        ErrorType.OVERLOADED -> STATUS_OVERLOADED
        ErrorType.API_ERROR -> DEFAULT_ERROR_STATUS
    }
}
