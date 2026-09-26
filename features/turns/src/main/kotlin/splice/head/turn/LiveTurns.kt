// NEW: V4-319 — a head's live turns, and the operator's stop.
//
// WHAT IS LISTED: every STREAMING turn from the moment admission names it (HeadAdmission, where the
// gate's own live row is described) until its gate slot is released, which is the one event every exit
// already passes through (InflightGate.Slot.onRelease). A non-stream turn is not listed: it answers
// once, whole, so there is no open stream a stop could end with a frame, and the one non-stream request
// a stop provokes is refused before it becomes a turn at all (below). The slot is the turn's identity
// here because it is the one object already carried from admission into the drive (TurnDrive.slot).
//
// WHAT A STOP DOES: it cancels exactly the turn's own job (TurnOneDrive's child job, the one the
// watchdog cancels) with an [OperatorStop], so the cancellation seal writes the stop's frame, an
// invalid_request_error saying the operator stopped the turn and never "retry" (CancellationSeal),
// and the slot is released on the path every cancelled turn takes.
//
// WHY A STOP ALSO REFUSES ONE REQUEST. Claude Code re-sends a stream that ended in an error once, as
// stream=false with the same messages and the same session (measured on 2.1.283 with fake credentials,
// console's probe 2026-09-26: 2 requests for every mid-stream error type tried; x-stainless-retry-count
// is 0 on both, so only the body tells the re-send apart). Unanswered, that re-send is the stopped turn
// run again upstream, and it completed as a success in the probe. So the stop leaves a mark, the
// session and the hash of its messages, and TurnPreparation answers the matching re-send locally with
// a 400, which the probe showed ends the client's turn with no further request. The mark is used once;
// a different hash, a streaming request, or anything after [STOP_RESEND_WINDOW_MS] goes through.
package splice.head.turn

import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnMeta
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.head.wire.TurnIdMint
import splice.upstream.retry.InflightGate
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** What the stopped turn's client is told, in its frame and in the refusal of its re-send. */
internal const val OPERATOR_STOPPED = "the operator stopped this turn"

/** How long after a stop the session's re-send of the stopped request is refused. The re-send came
 *  4.4 to 19.6 ms after the error frame over six probe runs; 10 s is 500 times the slowest, for a
 *  loaded machine. The mark is held in memory only, so a daemon restart inside the window forgets it
 *  and that one re-send goes upstream as an ordinary turn. */
internal const val STOP_RESEND_WINDOW_MS = 10_000L

/** The cancellation a stop sends into the turn's job: the seal reads it to write the stop's frame. */
internal class OperatorStop : CancellationException(OPERATOR_STOPPED)

/** One live turn as the console lists it. [session] is the client's full session id when it sent one;
 *  [stopped] is true between the stop and the slot's release, which is the seal's few milliseconds. */
public data class LiveTurn(
    val id: String,
    val session: String?,
    val model: String,
    val compact: Boolean,
    val ageMs: Long,
    val stopped: Boolean,
)

/** One head's live turns and its unused stop marks. [ids] mints a whole random UUID per turn, so no
 *  other turn of this daemon, before or after a restart, carries it: a stale console that asks to stop
 *  an old id finds nothing rather than a newer turn. */
public class LiveTurns(
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val ids: TurnIdMint = TurnIdMint { UUID.randomUUID().toString() },
) {
    /** One listed turn, and the one place its job and its stop meet: whichever of [driving] and a stop
     *  comes second cancels the job, so a stop never misses a drive that was starting. */
    private class Live(
        val id: String,
        val session: String?,
        val messages: String?,
        private val model: String,
        private val compact: Boolean,
        val since: Long,
    ) {
        @Volatile private var job: Job? = null
        private val stopped = AtomicBoolean(false)

        fun driving(job: Job) {
            this.job = job
            if (stopped.get()) job.cancel(OperatorStop())
        }

        /** True for the first stop only: a second stop of the same turn changes nothing. */
        fun claimStop(): Boolean = stopped.compareAndSet(false, true)

        /** Cancels the drive's job when the drive has one yet; one that starts later is cancelled by
         *  [driving]. */
        fun cancel() {
            job?.cancel(OperatorStop())
        }

        fun view(now: Long): LiveTurn = LiveTurn(id, session, model, compact, now - since, stopped.get())
    }

    /** A stop's mark: which session's re-send of which messages is refused, until when. */
    private data class Mark(val session: String, val messages: String)

    private val live = ConcurrentHashMap<String, Live>()
    private val bySlot = ConcurrentHashMap<InflightGate.Slot, Live>()
    private val marks = ConcurrentHashMap<Mark, Long>()

    /** A streaming turn admitted on [slot], listed until the slot is released. [messagesHash] is
     *  [MessagesHash.of] the client's request, null when it sent no session (no re-send can be told). */
    internal fun admitted(slot: InflightGate.Slot, meta: TurnMeta, messagesHash: String?) {
        val turn = Live(ids.next(), meta.sessionId, messagesHash, meta.upstreamModel, meta.compact, clock())
        live[turn.id] = turn
        bySlot[slot] = turn
        slot.onRelease {
            live.remove(turn.id)
            bySlot.remove(slot)
        }
    }

    /** The drive's own job for the turn on [slot], so a stop cancels exactly that turn. A stop that
     *  arrived before the drive started cancels it here. */
    internal fun driving(slot: InflightGate.Slot, job: Job) {
        bySlot[slot]?.driving(job)
    }

    /** Oldest first, the order the turns were admitted in. */
    public fun list(): List<LiveTurn> {
        val now = clock()
        return live.values.sortedBy { it.since }.map { it.view(now) }
    }

    /** Stops the live turn [id] and answers its session, or null when no turn with that id is live
     *  (it ended, or never ran here). A second stop of the same turn answers again and does nothing.
     *  The mark is left BEFORE the cancel, so the re-send the cancel provokes always finds it. */
    public fun stop(id: String): Stopped? {
        val turn = live[id] ?: return null
        if (turn.claimStop()) {
            mark(turn)
            turn.cancel()
        }
        return Stopped(turn.session)
    }

    /** What a stop answers: the session whose turn it was, null when the client sent none. */
    public data class Stopped(val session: String?)

    private fun mark(turn: Live) {
        val session = turn.session ?: return
        val messages = turn.messages ?: return
        val now = clock()
        marks.entries.removeIf { now - it.value > STOP_RESEND_WINDOW_MS }
        marks[Mark(session, messages)] = now
    }

    /** True exactly once: for [sessionId]'s non-streaming request whose messages hash matches a turn
     *  of that session stopped inside the window. The hash is only computed when the session holds a
     *  mark, so no other request pays for it. */
    internal fun refusesResend(sessionId: String?, request: JsonObject): Boolean {
        if (sessionId == null || marks.keys.none { it.session == sessionId }) return false
        val at = MessagesHash.of(request)?.let { marks.remove(Mark(sessionId, it)) }
        return at != null && clock() - at <= STOP_RESEND_WINDOW_MS
    }
}

/** The client's messages as the stop's mark and the re-send's check both read them: SHA-256 of the
 *  request's `messages` exactly as it arrived. The re-send is the same request with stream=false, so
 *  its messages serialize the same; everything else in the body is left out of the comparison. */
internal object MessagesHash {
    fun of(request: JsonObject): String? {
        val messages = request["messages"] ?: return null
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(messages.toString().toByteArray()))
    }
}

/** Every head's live turns, by head key: the daemon's ONE registry, filled as each head is built
 *  (HeadServerFactory) and read by the console's turn routes, the way [splice.head.wire.WireTaps] is. */
public class LiveTurnsByHead {
    private val heads = ConcurrentHashMap<String, LiveTurns>()

    public fun put(head: String, turns: LiveTurns) {
        heads[head] = turns
    }

    public fun of(head: String): LiveTurns? = heads[head]
}
