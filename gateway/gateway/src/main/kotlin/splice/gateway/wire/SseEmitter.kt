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
import splice.spi.WireSink
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TYPE = "type"
private const val MESSAGE = "message"

public class SseEmitter internal constructor(
    private val frames: SseFrameWriter,
    private val start: MessageStart,
    private val blocks: WireBlockWriter,
    /** The keepalive pinger's own writers — see [ProgressWire] for why they are not the turn's. */
    private val progress: ProgressWire,
    private val usagePayload: UsagePayloadBuilder,
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
     *  other frame). Fixed bytes through the pinger's own writer — never the turn's shared buffer. */
    override suspend fun heartbeat() {
        if (seal.get() != SealState.OPEN) return
        if (!start.hasOpened) return
        progressMutex.withLock { progress.frames.writeVerbatim(PING_FRAME) }
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
     *  status line could open a fresh block after the ending had closed the last one. */
    override suspend fun progress(text: String) {
        if (seal.get() != SealState.OPEN) return
        if (!start.hasOpened) return
        progressMutex.withLock {
            if (seal.get() == SealState.OPEN) {
                val idx = progressIndex ?: progress.blocks.openThinking().also { progressIndex = it }
                progress.blocks.thinkingDelta(idx, text)
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

    /** The ONLY failure ending — an SSE error event lets Claude Code retry honestly. */
    override suspend fun emitError(type: ErrorType, message: String) {
        if (!seal.compareAndSet(SealState.OPEN, SealState.ENDING)) return
        var cancelled = false
        try {
            closeProgress()
            frames.frame(
                "error",
                buildJsonObject {
                    put(TYPE, "error")
                    putJsonObject("error") {
                        put(TYPE, type.wireName)
                        put(MESSAGE, message)
                    }
                },
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
    public class TerminalEnvelope {
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
