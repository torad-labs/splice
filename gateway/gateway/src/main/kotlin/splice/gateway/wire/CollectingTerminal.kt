// NEW: the non-stream sink (2026-07-20). Claude Code streams for interactive turns but sends
// stream:false on some internal calls (the Node predecessor served these by collecting the
// terminal Responses object; the Kotlin port rejected them with a 400 — the "serves streaming
// clients only" errors). This TurnTerminal accumulates the SAME content ops the SseEmitter would
// have framed and exposes them as ONE Anthropic Messages JSON body (translateResponse parity),
// so the whole fold/translator/honesty pipeline drives it unchanged — no parallel non-stream path.
// Content accumulation lives in CollectingBlocks.kt (concentration HIGH, 2026-08-19).
package splice.gateway.wire

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.core.wire.ErrorEnvelope
import splice.core.wire.HttpStatus
import java.util.concurrent.atomic.AtomicBoolean

/** The status a stream turn commits before its first frame, and a clean collect answers with;
 *  shared with TurnTrace's turn record so 200 is spelled once in this package (V4-174). */
internal const val OK_STATUS = 200

internal class CollectingTerminal(
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
    private var status = HttpStatus.BAD_GATEWAY
    private var degraded: String? = null

    // DR-87: the emit-time Success->error rewrites below were invisible to the caller — StreamFinish
    // returned "ok" and health/log/perf all read green while the client got this 502.
    override val degradedReason: String? get() = degraded

    /** The single JSON body to write back (a terminal message or an error envelope). Never null
     *  after a driven turn — a turn always ends in emitTerminal or emitError; the fallback covers
     *  only a torn drive that somehow emitted neither. */
    public fun responseBody(): JsonObject = body ?: errorEnvelope(
        ErrorType.API_ERROR.wireName,
        "splice: gateway produced no response — retry",
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
        if (content.toolInputCapacityExceeded) {
            degraded = "buffered_capacity"
            body = errorEnvelope(
                ErrorType.API_ERROR.wireName,
                "splice: response exceeded max buffered size — aborting",
                usagePayload(usage),
            )
            status = statusFor(ErrorType.API_ERROR)
            return
        }
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
            degraded = "malformed_tool_input"
            body = errorEnvelope(
                ErrorType.API_ERROR.wireName,
                "splice: malformed tool_use input from upstream — retry",
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

    /** V4-81: [permanent] is accepted and DELIBERATELY UNUSED here — the pre-content wire-type
     *  rule is a streaming rule and does not apply to a buffered response. On `stream:false` the
     *  client has read nothing yet by construction, but the response it is about to read is a
     *  single HTTP status, not an in-band SSE event: there is no retryable event to relabel, and
     *  the status IS the information (429 rate_limit_error, 502 api_error). Applying the rule here
     *  would ship a genuine 429 as a 529 with rate-limit headers attached and turn every buffered
     *  api_error into a lie about an overload that did not happen. The signature carries the
     *  parameter only because the interface does. */
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {
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
    // V4-102: the shape lives in core now (splice.core.wire.ErrorEnvelope), because :upstream
    // cannot import :gateway and its own fail-fast body is the same envelope. Kept as a named
    // delegate so the three call sites above read unchanged.
    private fun errorEnvelope(type: String, message: String, usage: JsonObject? = null): JsonObject =
        ErrorEnvelope.of(type, message, usage)

    // ErrorType -> HTTP status. api_error maps to 502 to match the Node non-stream path's
    // upstream-error/empty-model response; the rest mirror the Anthropic status conventions.
    private fun statusFor(type: ErrorType): Int = when (type) {
        ErrorType.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
        ErrorType.AUTHENTICATION -> HttpStatus.UNAUTHORIZED
        ErrorType.PERMISSION -> HttpStatus.FORBIDDEN
        ErrorType.NOT_FOUND -> HttpStatus.NOT_FOUND
        ErrorType.RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS
        ErrorType.OVERLOADED -> HttpStatus.OVERLOADED
        ErrorType.API_ERROR -> HttpStatus.BAD_GATEWAY
    }
}
