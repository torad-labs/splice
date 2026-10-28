// NEW: the non-stream sink (2026-07-20). Claude Code streams for interactive turns but sends
// stream:false on some internal calls (the Node predecessor served these by collecting the
// terminal Responses object; the Kotlin port rejected them with a 400 — the "serves streaming
// clients only" errors). This TurnTerminal accumulates the SAME content ops the SseEmitter would
// have framed and exposes them as ONE Anthropic Messages JSON body (translateResponse parity),
// so the whole fold/translator/honesty pipeline drives it unchanged — no parallel non-stream path.
// Content accumulation lives in CollectingBlocks.kt (concentration HIGH, 2026-08-19).
package splice.head.wire

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.SpliceLine
import splice.core.failure.TurnVoice
import splice.core.failure.WireAnswer
import splice.core.index.WireBlockIndex
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.core.wire.ErrorEnvelope
import splice.core.wire.HttpStatus
import splice.upstream.sse.ContentBlockSink
import splice.upstream.sse.SourceFrameAction
import java.util.concurrent.atomic.AtomicBoolean

/** The status a stream turn commits before its first frame, and a clean collect answers with;
 *  shared with TurnTrace's turn record so 200 is spelled once in this package (V4-174). */
internal const val OK_STATUS = 200

/** A drive that ended in neither verb: no ending named a cause, so this is the one splice cannot attribute
 *  (failure-handling slice 4, which answers it through ClientWire like every other ending). */
private val ESCAPED_CAUSE = FailureCause.Splice.Internal(escaped = true)

internal class CollectingTerminal(
    private val model: String,
    private val usagePayload: UsagePayloadBuilder,
    private val messageId: String = MessageIds().generateMessageId(),
    private val content: CollectingBlocks = CollectingBlocks(),
    /** Failure-handling slice 2: the voice this collect's error ending speaks in. Every error body it builds carries
     *  the line this voice composes from the same cause, position and answer as the body's status and type. */
    private val voice: TurnVoice,
) : TurnTerminal, ContentBlockSink by CollectedContent(content) {
    private val ended = AtomicBoolean(false)

    // The L3 verdict DetachedCompaction keeps a collected compaction by: set only where emitTerminal
    // builds its terminal message, never on the degraded/error rewrites above it or an abandon.
    private val cleanEnding = AtomicBoolean(false)

    // The L3 terminal envelope (stop_reason derivation lives in SseEmitter.kt), held not copied.
    private val envelope = SseEmitter.TerminalEnvelope()

    override val hasEnded: Boolean get() = ended.get()
    override val responseMessageId: String get() = messageId

    private var body: JsonObject? = null

    /** Failure-handling slice 4: a drive that ended in neither verb is answered by the one table too, so this sink
     *  holds no status of its own. Its cause is a failure splice cannot attribute, which the client may send again. */
    private val escapedAnswer: WireAnswer = voice.answer(ESCAPED_CAUSE, Position(Moment.NON_STREAM, false))
    private var status = escapedAnswer.status ?: HttpStatus.BAD_GATEWAY
    private var degraded: String? = null
    private var written: SpliceLine? = null

    /** The answer [fail] gave, which a handed-over limit's headers are read from (HandOverHeaders, slice 5). */
    var answered: WireAnswer? = null
        private set

    // DR-87: the emit-time Success->error rewrites below were invisible to the caller — StreamFinish
    // returned "ok" and health/log/perf all read green while the client got this 502.
    override val degradedReason: String? get() = degraded

    /** The single JSON body to write back (a terminal message or an error envelope). Never null
     *  after a driven turn — a turn always ends in emitTerminal or emitError; the fallback covers
     *  only a torn drive that somehow emitted neither, a failure splice cannot attribute, whose
     *  status the default above already holds. */
    public fun responseBody(): JsonObject = body ?: escaped()

    /** The body of a drive that ended in neither verb: a failure splice cannot attribute, said in its own line. It
     *  is not this terminal's written line, because no ending wrote it. */
    private fun escaped(): JsonObject =
        errorEnvelope(escapedAnswer.type.wireName, lineFor(ESCAPED_CAUSE).text)

    public fun httpStatus(): Int = status

    override suspend fun withSourceFrame(event: JsonObject, action: SourceFrameAction) {
        envelope.acceptSource(event)
        content.source.native.deliver(event, this, action)
    }

    override suspend fun relayEvent(event: JsonObject, index: WireBlockIndex?) {
        val type = JsonScalars.strOrEmpty(event["type"])
        if (type.isEmpty() || type in nativeProtocolEvents) return
        if (event.containsKey("index")) {
            if (index == null) return
            if (!content.source.hasBlock(index)) return
        }
        envelope.acceptFuture(event)
    }

    // ── terminal (TurnTerminal) ──────────────────────────────────────────────
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        if (!ended.compareAndSet(false, true)) return
        if (content.toolInputCapacityExceeded) {
            degraded = "buffered_capacity"
            // splice's own size guard tripped on the provider's tool call: a runaway tear.
            fail(FailureCause.Provider.ToolTear(runaway = true), usagePayload(usage))
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
            fail(FailureCause.Provider.ToolTear(runaway = false), usagePayload(usage))
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
        cleanEnding.set(true)
    }

    /** True once [emitTerminal] delivered the clean ending — the first detached collect consumer is
     *  DetachedCompaction.launchCollected, whose keep verdict reads it exactly as it reads a stream's
     *  (TurnTerminal.endedCleanly). Default false stood while collect never detached. */
    override val endedCleanly: Boolean get() = cleanEnding.get()

    /** V4-81: the pre-content wire-type rule is a streaming rule and does not apply to a buffered
     *  response, so ClientWire answers this sink at [Moment.NON_STREAM]. On `stream:false` the
     *  client has read nothing yet by construction, but the response it is about to read is a
     *  single HTTP status, not an in-band SSE event: there is no retryable event to relabel, and
     *  the status IS the information (429 rate_limit_error, 502 api_error). Applying the rule here
     *  would ship a genuine 429 as a 529 with rate-limit headers attached and turn every buffered
     *  api_error into a lie about an overload that did not happen. */
    override suspend fun emitError(cause: FailureCause) {
        if (!ended.compareAndSet(false, true)) return
        fail(cause, usage = null, streamBegan = false)
    }

    override val lineWritten: SpliceLine? get() = written

    /** The line [emitError] writes for [cause]: a buffered answer, before the client has read anything. */
    override fun lineFor(cause: FailureCause): SpliceLine = voice.ending(cause, Position(Moment.NON_STREAM, false))

    override fun abandon() {
        ended.set(true) // no body: responseBody() falls back to the honest api_error envelope
    }

    // RG2-001: [usage] is null for every OTHER caller of this envelope (the responseBody()
    // fallback has none to give) — only the malformed-tool-use path in emitTerminal has a real
    // turn usage in scope, so it is the only caller that passes one.
    // V4-102: the shape lives in core now (splice.core.wire.ErrorEnvelope), because :upstream
    // cannot import :daemon-head and its own fail-fast body is the same envelope. Kept as a named
    // delegate so the three call sites above read unchanged.
    private fun errorEnvelope(type: String, message: String, usage: JsonObject? = null): JsonObject =
        ErrorEnvelope.of(type, message, usage)

    /** Ends the collect as [cause]'s failure: its real status and type (ClientWire at NON_STREAM), and the line its
     *  voice composes from them. [streamBegan] is what this sink can see: true once it collected content, false
     *  where it cannot tell. */
    private fun fail(cause: FailureCause, usage: JsonObject?, streamBegan: Boolean = true) {
        val at = Position(Moment.NON_STREAM, streamBegan)
        val answer = voice.answer(cause, at)
        answered = answer
        val line = voice.ending(cause, at, answer)
        written = line
        body = errorEnvelope(answer.type.wireName, line.text, usage)
        status = answer.status ?: HttpStatus.BAD_GATEWAY
    }
}

/** The content verbs of the non-stream sink: each lands in [content], and closing a block only records its stop,
 *  because the whole body is assembled at the terminal. Split out of [CollectingTerminal]. */
private class CollectedContent(private val content: CollectingBlocks) : ContentBlockSink {
    override suspend fun openText(): WireBlockIndex = content.openText()

    override suspend fun openThinking(): WireBlockIndex = content.openThinking()

    override suspend fun openTool(id: String, name: String): WireBlockIndex = content.openTool(id, name)

    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex = content.openRawBlock(contentBlock)

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

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        content.source.rawDelta(index, delta)
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        content.source.stop(index) // finalization stays at build time, source stop extensions survive
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
}
