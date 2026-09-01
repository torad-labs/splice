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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.spi.WireSink
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

private const val TYPE = "type"
private const val MESSAGE = "message"

public class SseEmitter internal constructor(
    private val frames: SseFrameWriter,
    private val start: MessageStart,
    private val blocks: WireBlockWriter,
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

    // The shared stop_reason derivation (L3) — one definition, held rather than copied.
    private val envelope = TerminalEnvelope()

    override val hasEnded: Boolean get() = seal.get() == SealState.ENDED

    override suspend fun ensureStarted(): Unit = start.ensureStart()

    /** The ONLY clean ending — derives stop_reason internally (L3). */
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) {
        if (!seal.compareAndSet(SealState.OPEN, SealState.ENDING)) return
        var cancelled = false
        try {
            start.ensureStart()
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
