// NEW: the non-stream sink (2026-07-20). Claude Code streams for interactive turns but sends
// stream:false on some internal calls (the Node predecessor served these by collecting the
// terminal Responses object; the Kotlin port rejected them with a 400 — the "serves streaming
// clients only" errors). This TurnTerminal accumulates the SAME content ops the SseEmitter would
// have framed and exposes them as ONE Anthropic Messages JSON body (translateResponse parity),
// so the whole fold/translator/honesty pipeline drives it unchanged — no parallel non-stream path.
// Content accumulation lives in CollectingBlocks.kt (concentration HIGH, 2026-08-19).
package splice.gateway.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.Usage
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
private const val FIELD_ERROR = "error"

public class CollectingTerminal(
    private val model: String,
    private val usagePayload: UsagePayloadBuilder,
    private val messageId: String = MessageIds().generateMessageId(),
) : TurnTerminal {

    private val content = CollectingBlocks()
    private val ended = AtomicBoolean(false)

    // The L3 terminal envelope (stop_reason derivation lives in SseEmitter.kt), held not copied.
    private val envelope = SseEmitter.TerminalEnvelope()

    override val hasEnded: Boolean get() = ended.get()

    private var body: JsonObject? = null
    private var status = DEFAULT_ERROR_STATUS

    /** The single JSON body to write back (a terminal message or an error envelope). Never null
     *  after a driven turn — a turn always ends in emitTerminal or emitError; the fallback covers
     *  only a torn drive that somehow emitted neither. */
    public fun responseBody(): JsonObject = body ?: errorEnvelope(
        ErrorType.API_ERROR.wireName,
        "claudex: gateway produced no response — retry",
    )

    public fun httpStatus(): Int = status

    // ── content accumulation (WireSink) ──────────────────────────────────────
    override suspend fun openText(): WireBlockIndex = content.openText()

    override suspend fun openThinking(): WireBlockIndex = content.openThinking()

    override suspend fun openTool(id: String, name: String): WireBlockIndex = content.openTool(id, name)

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        content.textDelta(index, text)
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        content.thinkingDelta(index, thinking)
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        content.signatureDelta(index, signature)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        content.inputJsonDelta(index, partialJson)
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        // no-op: blocks finalize at build time (contentBlocks), never on close
    }

    override suspend fun closeAll() {
        // no-op: nothing streams here; the whole body is assembled at the terminal
    }

    override suspend fun addTextBlock(text: String) {
        content.addTextBlock(text)
    }

    override suspend fun addRedactedThinking(data: String) {
        content.addRedactedThinking(data)
    }

    // ── terminal (TurnTerminal) ──────────────────────────────────────────────
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        if (!ended.compareAndSet(false, true)) return
        val blocks = content.contentBlocks()
        if (content.malformedToolInput) {
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
                content = blocks,
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
