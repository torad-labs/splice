// PORT-OF: server/src/anthropic/sse.mjs @ pre-public-port-baseline — invariants (L3, structural): this file is
// the SOLE Anthropic wire emitter; a clean stop is reachable ONLY via emitTerminal (which owns
// stop_reason derivation: tool_use > max_tokens(incomplete) > end_turn — no caller ever holds
// the literal); failures ONLY via emitError (an SSE error event, so Claude Code retries
// honestly); client-gone seals via abandon() with nothing on the wire. The non-stream terminal
// message builder lives HERE too (its stop_reason literal), nested as [TerminalEnvelope] — the
// wall pins the `end_turn` / `message_*` literals to this filename, so the derivation is a member
// of the sole terminal, not an independent concern sitting beside it. Ended-idempotence guards
// double terminals.
//
// Content-block description (open/delta/close), message_start/ping, frame byte-assembly and the
// RFC 8259 escaper live in this package's sibling files (WireBlockWriter, MessageStart,
// SseFrameWriter, JsonStringEscaper) — none of them can hold a clean-stop literal by construction
// (kt-l3-sole-wire-terminals / kt-l3-end-turn-literal are filename-anchored to THIS file), so the
// L3 split is structural: the object that can describe content literally cannot end a turn.
package splice.gateway.wire

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.core.wire.ErrorEnvelope
import splice.spi.WireSink
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TYPE = "type"
private const val MESSAGE = "message"

internal class SseEmitter(
    private val frames: SseFrameWriter,
    private val start: MessageStart,
    private val blocks: WireBlockWriter,
    /** The keepalive pinger's own writers — see [ProgressWire] for why they are not the turn's. */
    private val progress: ProgressWire,
    private val usagePayload: UsagePayloadBuilder,
    /** V4-81: has ANY content reached this client on THIS turn? Read only by [emitError], where it
     *  decides whether a failure type may still be relabelled as retryable — see
     *  [PreContentWireType]. It is a function, not a boolean, because the answer changes during the
     *  turn and the emitter is built before the first byte; the streaming caller points it at the
     *  channel's own CONTENT_FRAMES_OUT counter, so the emitter's answer and the wire's accounting
     *  cannot disagree. */
    private val contentReached: ContentReached,
) : TurnTerminal, WireSink by blocks {

    // Sole-terminal state machine: OPEN → ENDING (claim) → ENDED (frames succeeded, or abandon).
    // A cancellation landing between the claim and the last frame releases the claim back to OPEN
    // so the cancellation seal's emitError can still seal honestly (a stranded ENDING was the
    // truncated-200 hole, review 2026-07-22); an IOException — client gone — stays ENDED so a
    // follow-up emitError cleanly no-ops instead of re-attempting a doomed write. One
    // AtomicReference so an illegal ended-without-ending combination is unrepresentable.
    private enum class SealState { OPEN, ENDING, ENDED }
    private val seal = AtomicReference(SealState.OPEN)
    private val cleanEnd = AtomicBoolean(false)

    // The shared stop_reason derivation (L3) — one definition, held rather than copied.
    private val envelope = TerminalEnvelope()

    override val hasEnded: Boolean get() = seal.get() == SealState.ENDED

    // A tool_use block already on the wire must still derive stop_reason=tool_use when a
    // deterministic failure ends the turn in words — the client runs what it was shown.
    @Volatile private var toolSeen = false

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        blocks.openTool(id, name).also { toolSeen = true }

    /** A deterministic failure's ending: the explanation as one text block, then the clean
     *  terminal with the stop_reason the streamed content earned. Nothing here is an error frame. */
    override suspend fun emitExplained(message: String, usage: Usage) {
        if (seal.get() != SealState.OPEN) return
        blocks.addTextBlock(message)
        emitTerminal(hasToolUse = toolSeen, incomplete = false, usage = usage)
    }

    override val endedCleanly: Boolean get() = cleanEnd.get()

    override suspend fun ensureStarted(): Unit = start.ensureStart()

    // The status line's block, opened at the FIRST progress line and closed by the terminal. Written
    // by the pinger's coroutine, read by the terminal's — volatile, like the latch it follows.
    @Volatile private var progressIndex: WireBlockIndex? = null

    // The pinger's seam is single-access-at-a-time. [progress]'s writers hold mutable state — one
    // reused frame buffer, one `open` block set — and TWO coroutines reach them: the keepalive
    // pinger for [heartbeat]/[progress], the turn's own for [closeProgress] at the ending. This is
    // what makes that legal. The turn's own writers are untouched and stay lock-free single-writer.
    private val progressMutex = Mutex()

    /** ClientChannel's heartbeat: a ping after message_start while the turn is still open. A turn
     *  that has claimed or reached its ending writes nothing more (ended-idempotence, as for every
     *  other frame). Fixed bytes through the pinger's own writer — never the turn's shared buffer.
     *
     *  The seal is read TWICE for the same reason [progress] reads it twice, and the entry read
     *  alone is what made this verb the one that broke its own promise above (found in peer review,
     *  2026-09-06): the gap between clearing that read and holding the lock is enough for the whole
     *  ending to run, after which an unguarded write puts a ping past message_stop. The pinger
     *  outlives the terminal by design — TurnOneDrive cancels it in its finally, after the round
     *  returns — so this verb cannot lean on the pinger being gone. */
    override suspend fun heartbeat() {
        if (seal.get() != SealState.OPEN) return
        if (!start.hasOpened) return
        progressMutex.withLock {
            if (seal.get() == SealState.OPEN) progress.frames.writeVerbatim(PING_FRAME)
        }
    }

    /** splice's own status line on a quiet wire: appended to ONE thinking block for the turn, opened
     *  lazily at the first line so a turn that never goes quiet carries no empty block (the "walls
     *  of Thinking" shape). It is NOT model output and is never counted as any — the pinger's write
     *  port is what excludes it (ClientChannel.timedProgressWrite), so the content_frames_out the
     *  watchdog tier and G5's reissue probe read stay the model's alone.
     *
     *  The ending closes it, and the seal is what orders the two: [emitTerminal]/[emitError] claim
     *  it BEFORE calling [closeProgress], so a line that had already passed the gate and is waiting
     *  on [progressMutex] re-reads the seal once it holds the lock and writes nothing — the reason
     *  the check is repeated rather than merely guarding the entry. Without that second read a
     *  status line could open a fresh block after the ending had closed the last one.
     *
     *  [line] is invoked HERE — inside the lock, past every guard — and never before. Composing a
     *  line consumes the caller's ticker state, so building one for a write that is then dropped
     *  loses it: the pre-opener ticks ate the "holding this turn open" intro and the client's first
     *  visible line was the terse follow-up form (found by splice-astra in the combined run,
     *  2026-09-06). The same laziness makes the line's clauses true when WRITTEN rather than when
     *  called, which is what TurnProgressLine's own contract already claimed. */
    override suspend fun progress(line: ProgressLine) {
        if (seal.get() != SealState.OPEN) return
        if (!start.hasOpened) return
        progressMutex.withLock {
            if (seal.get() == SealState.OPEN) {
                val idx = progressIndex ?: progress.blocks.openThinking().also { progressIndex = it }
                progress.blocks.thinkingDelta(idx, line())
            }
        }
    }

    /** Close the status-line block before a terminal, so nothing of ours sits open across the end.
     *  A no-op for the overwhelming majority of turns, which never went quiet enough to open one. */
    private suspend fun closeProgress() {
        progressMutex.withLock { progressIndex?.let { progress.blocks.closeBlock(it) } }
    }

    /** The ONLY clean ending — derives stop_reason internally (L3). */
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        if (!seal.compareAndSet(SealState.OPEN, SealState.ENDING)) return
        var cancelled = false
        try {
            start.ensureStart()
            closeProgress()
            frames.frame(
                "message_delta",
                buildJsonObject {
                    put(TYPE, "message_delta")
                    putJsonObject("delta") {
                        put("stop_reason", envelope.deriveStopReason(hasToolUse, incomplete))
                        put("stop_sequence", null as String?)
                    }
                    put("usage", usagePayload(usage))
                },
            )
            frames.frame("message_stop", buildJsonObject { put(TYPE, "message_stop") })
            cleanEnd.set(true)
        } catch (e: CancellationException) {
            // Cancelled mid-frame — release so the cancellation seal's emitError
            // (TurnDriver.driveSealingCancellation) can still seal honestly; a stranded ENDING
            // was the truncated-200 hole (review 2026-07-22).
            cancelled = true
            seal.set(SealState.OPEN)
            throw e
        } finally {
            // Frames delivered → sealed ENDED; frames FAILED (client gone, IOException) → still
            // ENDED so a follow-up emitError cleanly no-ops. Only a cancellation releases instead.
            if (!cancelled) seal.set(SealState.ENDED)
        }
    }

    /** The ONLY failure ending — an SSE error event lets Claude Code retry honestly.
     *
     *  V4-81: THE PRE-CONTENT WIRE-TYPE RULE IS APPLIED HERE, and this is the whole of it — one
     *  call, at the one place that owns both the frame and the question it turns on. Moving it
     *  here is what makes it unskippable: there is no other way to write an error frame, so a new
     *  ending cannot forget it, and the collect path never arrives because it has its own terminal
     *  ([CollectingTerminal]) whose envelope keeps the failure's REAL status — a buffered 429 stays
     *  429 and a buffered api_error stays 502, which is the shape its callers already assert. */
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {
        val wireType = PreContentWireType.of(type, contentReached(), permanent)
        if (!seal.compareAndSet(SealState.OPEN, SealState.ENDING)) return
        var cancelled = false
        try {
            closeProgress()
            // V4-102: the envelope is built once in core so this frame, the non-stream JSON body,
            // the admission 4xx/5xx bodies and provider-spi's own fail-fast body cannot drift apart.
            frames.frame(
                "error",
                ErrorEnvelope.of(wireType.wireName, message),
            )
        } catch (e: CancellationException) {
            // Cancelled before the frame went out — release so a later seal can still retry.
            cancelled = true
            seal.set(SealState.OPEN)
            throw e
        } finally {
            // Frame delivered → sealed; frame FAILED (client gone, IOException) → still sealed so
            // retries don't double-end. Only a cancellation releases the claim instead.
            if (!cancelled) seal.set(SealState.ENDED)
        }
    }

    /** Client vanished mid-stream — nothing to emit, just seal the emitter. */
    override fun abandon() {
        seal.set(SealState.ENDED)
    }

    /** The stop_reason derivation and the non-stream terminal envelope that carries it. Declared
     *  in THIS file because the L3 walls pin the `end_turn` / `message_*` literals here; it is a
     *  nested class (not a static namespace, not top-level) so both [SseEmitter] and
     *  [CollectingTerminal] each hold one, and it constructs freely (non-inner) even though
     *  SseEmitter's own constructor is internal. Held-not-copied, per its callers. */
    class TerminalEnvelope {
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

        // `internal`, not `private`: a Kotlin private member is CLASS-private, and SseEmitter
        // (the enclosing class) must reach it — a second copy is what L3 forbids.
        internal fun deriveStopReason(hasToolUse: Boolean, incomplete: Boolean): String = when {
            hasToolUse -> "tool_use"
            incomplete -> "max_tokens"
            else -> "end_turn"
        }
    }
}

/** V4-78, re-sited by V4-81: THE PRE-CONTENT WIRE-TYPE RULE, as a value rather than a branch
 *  buried in a handler. It lived in the head's failure surfaces until V4-81 moved it to the one
 *  place an error frame can be written ([SseEmitter.emitError]); it sits beside that class so the
 *  rule and its only caller are one file, and so no caller has to remember to consult it.
 *
 *  Claude Code 2.1.257 retries an IN-BAND error event only when its body carries overloaded_error
 *  (a real 429/529 is retried by STATUS, and neither is ours to send once the 200 is committed).
 *  So before any content has reached the client, a failure whose type the client would treat as
 *  terminal — RATE_LIMIT, and API_ERROR — is wired as OVERLOADED instead. Nothing the client has
 *  read is at stake at that point, and the turn is indistinguishable from a transient overload.
 *
 *  THE OPERATOR LAW THIS IMPLEMENTS — "always a retry armed" — IS ABOUT FAILURES A RETRY CAN HEAL.
 *  A [permanent] failure is not one of those: the identical bytes produce the identical verdict, so
 *  a retry is not a heal but a bill. RetryPolicy arms a cooldown only for RATE_LIMITED, so with
 *  CLAUDE_CODE_RETRY_WATCHDOG=1 a relabelled permanent failure costs up to 300 client re-sends at
 *  six upstream attempts each. A permanent failure therefore KEEPS ITS REAL TYPE and the client
 *  ends the session on the honest verdict instead of grinding.
 *
 *  THE BOUNDS, all four deliberate: after content the type is left ALONE (the client finalizes
 *  whatever it holds, and relabelling would misdescribe what it is reading); [permanent] failures
 *  are never remapped (see above); INVALID_REQUEST and AUTHENTICATION are never remapped (splice is
 *  telling the client something only the operator can change); and only the WIRE TYPE moves — the
 *  message still comes from FailurePresenter and telemetry still records the REAL type. */
/** Has any content frame reached the client this turn — the one fact the pre-content rule turns on.
 *  Named for its role at the seam (wall kt-no-lambda-seam); the emitter asks it per error frame. */
internal fun interface ContentReached {
    public operator fun invoke(): Boolean
}

internal object PreContentWireType {
    fun of(type: ErrorType, contentReachedClient: Boolean, permanent: Boolean = false): ErrorType {
        // 1. The client is already finalizing what it holds — a relabel would misdescribe it. Written
        //    as the leading guard so every branch below reads as "nothing has been read yet".
        if (contentReachedClient) return type
        return when {
            // 2. A RATE LIMIT IS ALWAYS RETRYABLE IN BAND, however the classifier scored it. This arm
            //    is not an exception to the permanence rule; it is the observation that a quota window
            //    is a condition that CHANGES WITH TIME, which is the one thing a permanent failure
            //    cannot be. Load-bearing twice over: V4-71's whole fix is that the first persistent
            //    429 reaches the client as something it re-sends, and the cooldown that re-send meets
            //    is what makes the retry cheap. MEASURED 2026-09-17: ClassifiedFailure for a 429 (and
            //    the 403 spend-limit of V4-73) carries transient = FALSE, so a permanence test
            //    written as "!transient" would have silently reverted V4-71 on every rate-limited
            //    turn — which is exactly what the first draft of this row's plumbing did.
            type == ErrorType.RATE_LIMIT -> ErrorType.OVERLOADED
            // 3. An api_error is retryable in band UNLESS a retry reproduces it exactly — see above.
            type == ErrorType.API_ERROR && !permanent -> ErrorType.OVERLOADED
            // 4. Everything else keeps its type: INVALID_REQUEST and AUTHENTICATION because a retry
            //    of identical bytes cannot change what only the operator can, and OVERLOADED /
            //    PERMISSION because the client's reading of them is already the one we want.
            else -> type
        }
    }
}
