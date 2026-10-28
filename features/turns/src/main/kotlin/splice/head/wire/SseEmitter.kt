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
package splice.head.wire

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.failure.FailureCause
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Recovery
import splice.core.failure.SpliceLine
import splice.core.failure.TurnVoice
import splice.core.failure.WaitLine
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.core.wire.ANTHROPIC_ASSISTANT_ROLE
import splice.core.wire.ErrorEnvelope
import splice.upstream.sse.SourceFrameAction
import splice.upstream.sse.WireSink
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TYPE = "type"
private const val MESSAGE = "message"
internal const val MESSAGE_DELTA: String = "message_delta"
internal const val MESSAGE_STOP: String = "message_stop"
internal const val END_TURN: String = "end_turn"

// Only the sole terminal owns the protocol event names; generic relays cannot write these frames.
internal val nativeProtocolEvents: Set<String> = setOf(
    "message_start",
    MESSAGE_DELTA,
    MESSAGE_STOP,
    "error",
    "content_block_start",
    "content_block_delta",
    "content_block_stop",
)

internal class SseEmitter(
    private val frames: SseFrameWriter,
    private val start: MessageStart,
    private val blocks: WireBlockWriter,
    /** The keepalive pinger's own writers — see [ProgressWire] for why they are not the turn's. */
    private val progress: ProgressWire,
    private val usagePayload: UsagePayloadBuilder,
    /** V4-81: has ANY content reached this client on THIS turn? Read only by [emitError], where it
     *  decides whether a failure type may still be relabelled as retryable — see
     *  ClientWire's pre-content rule. It is a function, not a boolean, because the answer changes during the
     *  turn and the emitter is built before the first byte; the streaming caller points it at the
     *  channel's own CONTENT_FRAMES_OUT counter, so the emitter's answer and the wire's accounting
     *  cannot disagree. */
    private val contentReached: ContentReached,
    /** Failure-handling slice 2: the voice this turn's error ending speaks in. [emitError] asks it for the line
     *  with the cause, the position and the answer the frame carries, so the words and the wire cannot disagree. */
    private val voice: TurnVoice,
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

    // Only the seal's claimant writes it (OPEN → ENDING admits one caller at a time), so a volatile field keeps the
    // first line handed to the wire without an identity compare a value class cannot offer.
    @Volatile private var written: SpliceLine? = null

    // The shared stop_reason derivation (L3) — one definition, held rather than copied.
    private val envelope = TerminalEnvelope()

    override val hasEnded: Boolean get() = seal.get() == SealState.ENDED
    override val responseMessageId: String get() = start.messageId

    override val endedCleanly: Boolean get() = cleanEnd.get()

    override val lineWritten: SpliceLine? get() = written

    override fun lineFor(cause: FailureCause): SpliceLine = voice.ending(cause, position())

    /** Where an error frame written now stands: the stream is open, so the answer is in band, and whether content
     *  has reached this client decides the rest (V4-81). */
    private fun position(): Position {
        val reached = contentReached()
        return Position(if (reached) Moment.MID_CONTENT else Moment.COMMITTED, streamBegan = reached)
    }

    override suspend fun ensureStarted(): Unit = start.openEarly()

    override suspend fun withSourceFrame(event: JsonObject, action: SourceFrameAction) {
        envelope.acceptSource(event)
        blocks.withSourceFrame(event) { action.deliver(this) }
    }

    // The pinger's seam is single-access-at-a-time: its writers and its open notice block sit behind
    // ProgressWire.lock, which TWO coroutines reach — the keepalive pinger for [heartbeat]/[progress],
    // the turn's own at its block boundaries and its ending. The turn's own writers stay lock-free.

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
        progress.lock.withLock {
            if (seal.get() == SealState.OPEN) progress.pingLocked()
        }
    }

    /** splice's own status line on a quiet wire: appended to ONE thinking block per quiet stretch,
     *  opened lazily at the stretch's first line so a turn that never goes quiet carries no empty
     *  block (the "walls of Thinking" shape), and ended by the turn's writer before any block of the
     *  model's opens or closes (V4-451). It is NOT model output and is never counted as any — the
     *  pinger's write port is what excludes it (ClientChannel.timedProgressWrite), so the
     *  content_frames_out the watchdog tier and G5's reissue probe read stay the model's alone.
     *
     *  The ending closes it, and the seal is what orders the two: [emitTerminal]/[emitError] claim
     *  it BEFORE calling [closeProgress], so a line that had already passed the gate and is waiting
     *  on ProgressWire.lock re-reads the seal once it holds the lock and writes nothing — the reason
     *  the check is repeated rather than merely guarding the entry. Without that second read a
     *  status line could open a fresh block after the ending had closed the last one.
     *
     *  [line] is invoked HERE — inside the lock, past every guard — and never before. Composing a
     *  line consumes the caller's ticker state, so building one for a write that is then dropped
     *  loses it: the pre-opener ticks ate the "holding this turn open" intro and the client's first
     *  visible line was the terse follow-up form (found by splice-astra in the combined run,
     *  2026-09-06). The same laziness makes the line's clauses true when WRITTEN rather than when
     *  called, which is what TurnProgressLine's own contract already claimed. */

    /** True while the turn holds: its hold line is said, and the quiet wire carries pings alone. */
    @Volatile private var held = false

    override fun holding(on: Boolean) {
        held = on
    }

    override suspend fun progress(line: ProgressLine) {
        if (seal.get() != SealState.OPEN) return
        if (held) return
        if (!start.hasOpened) return
        progress.lock.withLock {
            if (seal.get() == SealState.OPEN) progress.writeNoticeLocked(line)
        }
    }

    /** The line that opens a recovery episode (failure-handling slice 2, final-design.md section 8, rules 3 to 5):
     *  what the round met and what the turn does next, in this turn's voice, appended to the notice block the way a
     *  status line is, under the same seal and lock. ProgressWire shows one per episode, so a failure that follows
     *  another before model content reaches the client again says nothing it has not said. The turn is opened first,
     *  as a retry's line always opened it. Slice 5: the line commits the response as it is written (PendingSse), so
     *  the client reads it while the wait it names runs, and a hold's line is a finished notice at once. */
    override suspend fun recovering(wait: WaitLine) {
        if (seal.get() != SealState.OPEN) return
        ensureStarted()
        progress.lock.withLock {
            if (seal.get() == SealState.OPEN) {
                progress.writeEpisodeLocked(voice.waiting(wait.cause, wait.next))
                if (wait.next is Recovery.Hold) progress.closeEpisodeLocked()
            }
        }
    }

    /** Close the status-line block before a terminal, so nothing of ours sits open across the end.
     *  A no-op for the overwhelming majority of turns, which never went quiet enough to open one. */
    private suspend fun closeProgress() {
        progress.endNotice()
    }

    /** The ONLY clean ending — derives stop_reason internally (L3). */
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        if (!seal.compareAndSet(SealState.OPEN, SealState.ENDING)) return
        var cancelled = false
        try {
            start.ensureStart()
            closeProgress()
            frames.frame(
                MESSAGE_DELTA,
                envelope.deltaFrame(hasToolUse, incomplete, usagePayload(usage)),
            )
            frames.frame(MESSAGE_STOP, envelope.stopFrame())
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
     *  V4-81: THE PRE-CONTENT WIRE-TYPE RULE IS ASKED FOR HERE, and this is the whole of it — one
     *  call to ClientWire, at the one place that owns both the frame and the question it turns on
     *  (has content reached the client). Asking here is what makes it unskippable: there is no
     *  other way to write an error frame, so a new ending cannot forget it, and the collect path
     *  never arrives because it has its own terminal ([CollectingTerminal]) whose envelope keeps
     *  the failure's REAL status — a buffered 429 stays 429 and a buffered api_error stays 502,
     *  which is the shape its callers already assert. The stream is open by the time this runs,
     *  so the answer is an in-band event; whether the provider's stream began is what this sink
     *  can see, content having reached the client.
     *
     *  The words are the line this turn's voice composes from the same cause, position and answer
     *  (failure-handling slice 2), and the first line handed to the wire is [lineWritten]: a frame
     *  the production channel took is delivered even when a cancellation lands on its flush, and
     *  the channel drops any later error frame (PendingSse), so the first is the one the client read. */
    override suspend fun emitError(cause: FailureCause) {
        val at = position()
        val answer = voice.answer(cause, at)
        val line = voice.ending(cause, at, answer)
        if (!seal.compareAndSet(SealState.OPEN, SealState.ENDING)) return
        var cancelled = false
        var handed = false
        try {
            closeProgress()
            handed = true
            // V4-102: the envelope is built once in core so this frame, the non-stream JSON body,
            // the admission 4xx/5xx bodies and :upstream's own fail-fast body cannot drift apart.
            // Failure-handling slice 2: the frame carries what the failure is while the status is still splice's to
            // choose, which only the channel knows, so an overflow answers with its own status whatever its line says.
            val unsent = voice.answer(cause, Position(Moment.BEFORE_COMMIT, streamBegan = true))
            frames.ending(
                ErrorEnvelope.of(answer.type.wireName, line.text),
                unsent,
                ErrorEnvelope.of(unsent.type.wireName, line.text),
            )
            written = written ?: line
        } catch (e: CancellationException) {
            // Cancelled before the frame went out — release so a later seal can still retry. A frame
            // already handed to the wire keeps its line as the one written (see above).
            cancelled = true
            if (handed) written = written ?: line
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
        private val fields = NativeFields()
        private var nativeStart: JsonObject? = null
        private var nativeDelta: JsonObject? = null
        private var nativeStop: JsonObject? = null
        private var futureFields = JsonObject(emptyMap())

        internal fun acceptSource(event: JsonObject) {
            when (JsonScalars.strOrEmpty(event[TYPE])) {
                "message_start" -> nativeStart = event
                MESSAGE_DELTA -> nativeDelta = fields.merge(nativeDelta, event)
                MESSAGE_STOP -> nativeStop = event
            }
        }

        internal fun acceptFuture(event: JsonObject) {
            futureFields = fields.merge(futureFields, fields.extensions(event, TYPE, "index"))
        }

        internal fun deltaFrame(hasToolUse: Boolean, incomplete: Boolean, usage: JsonObject): JsonObject =
            fields.merge(
                nativeDelta,
                buildJsonObject {
                    put(TYPE, MESSAGE_DELTA)
                    putJsonObject("delta") {
                        put("stop_reason", deriveStopReason(hasToolUse, incomplete))
                        put("stop_sequence", null as String?)
                    }
                    put("usage", usage)
                },
            )

        internal fun stopFrame(): JsonObject = fields.merge(nativeStop, buildJsonObject { put(TYPE, MESSAGE_STOP) })

        private fun collectedFields(): JsonObject {
            var result = fields.merge(nativeStart?.get(MESSAGE) as? JsonObject, futureFields)
            nativeStart?.let { result = fields.merge(result, fields.extensions(it, TYPE, MESSAGE)) }
            nativeDelta?.let {
                result = fields.merge(result, it["delta"] as? JsonObject ?: JsonObject(emptyMap()))
                result = fields.merge(result, fields.extensions(it, TYPE, "delta"))
            }
            nativeStop?.let { result = fields.merge(result, fields.extensions(it, TYPE)) }
            return result
        }

        /** Non-stream terminal message (translateResponse envelope) — built HERE because the
         *  stop_reason derivation and its literals are walled to this file (L3). The envelope
         *  fields are grouped into [TerminalMessage] so the builder stays a single L3 argument. */
        public fun terminalMessageJson(msg: TerminalMessage): JsonObject = fields.merge(
            collectedFields(),
            buildJsonObject {
                put("id", msg.id)
                put(TYPE, MESSAGE)
                put("role", ANTHROPIC_ASSISTANT_ROLE)
                put("content", buildJsonArray { msg.content.forEach { add(it) } })
                put("model", msg.model)
                put("stop_reason", deriveStopReason(msg.hasToolUse, msg.incomplete))
                put("stop_sequence", null as String?)
                put("usage", msg.usagePayload)
            },
        )

        // `internal`, not `private`: a Kotlin private member is CLASS-private, and SseEmitter
        // (the enclosing class) must reach it — a second copy is what L3 forbids.
        internal fun deriveStopReason(hasToolUse: Boolean, incomplete: Boolean): String = when {
            hasToolUse -> "tool_use"
            incomplete -> "max_tokens"
            else -> END_TURN
        }
    }
}

/** Has any content frame reached the client this turn — the one fact the pre-content rule turns on.
 *  Named for its role at the seam (wall kt-no-lambda-seam); the emitter asks it per error frame. */
internal fun interface ContentReached {
    public operator fun invoke(): Boolean
}
