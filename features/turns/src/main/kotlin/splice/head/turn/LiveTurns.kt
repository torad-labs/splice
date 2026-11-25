// NEW: V4-319 — a head's live turns, and the operator's stop.
//
// WHAT IS LISTED: every STREAMING turn from the moment admission names it (HeadAdmission, where the
// gate's own live row is described) until its gate slot is released, which is the one event every exit
// already passes through (InflightGate.Slot.onRelease). A non-stream turn is not listed: it answers
// once, whole, so there is no open stream a stop could end with a frame, and the one non-stream request
// a stop provokes is refused before it becomes a turn at all (below). The slot is the turn's identity
// here because it is the one object already carried from admission into the drive (TurnDrive.slot).
//
// WHAT A STOP DOES: it cancels the turn's attached client job and any retained raw source reader
// with an [OperatorStop], so the cancellation seal writes the stop's frame, an
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
import splice.core.head.HeldSaying
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.util.ElapsedClock
import splice.core.util.JsonWire
import splice.core.util.MonoClock
import splice.head.hold.HoldRegistry
import splice.head.turn.listing.LiveTurn
import splice.head.turn.listing.ResumeReading
import splice.head.turn.listing.TurnSilence
import splice.head.wire.TurnIdMint
import splice.upstream.retry.InflightGate
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What the stopped turn's client is told, in its frame and in the refusal of its re-send. */
internal const val OPERATOR_STOPPED = "the operator stopped this turn"

/** How long after a stop the session's re-send of the stopped request is refused. The re-send came
 *  4.4 to 19.6 ms after the error frame over six probe runs; 10 s is 500 times the slowest, for a
 *  loaded machine. The mark is held in memory only, so a daemon restart inside the window forgets it
 *  and that one re-send goes upstream as an ordinary turn. */
internal const val STOP_RESEND_WINDOW_MS = 10_000L

/** The cancellation a stop sends into the turn's job: the seal reads it to write the stop's frame. */
internal class OperatorStop : CancellationException(OPERATOR_STOPPED)

/** One head's live turns and its unused stop marks. [ids] mints a whole random UUID per turn, so no
 *  other turn of this daemon, before or after a restart, carries it: a stale console that asks to stop
 *  an old id finds nothing rather than a newer turn. */
public class LiveTurns(
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val ids: TurnIdMint = TurnIdMint { UUID.randomUUID().toString() },
) {
    /** The head's held turns, by the login each waits on: what wakes them, probes them and lets the console try a held
     *  login now (failure-handling slice 5). One per head, beside the turns it holds. */
    public val holds: HoldRegistry = HoldRegistry()

    /** One listed turn, and the one place its job and its stop meet: whichever of [driving] and a stop
     *  comes second cancels the job, so a stop never misses a drive that was starting. */
    private class Live(
        val id: String,
        private val meta: TurnMeta,
        @Volatile var messages: String?,
        private val clock: ElapsedClock,
        /** This turn's own telemetry, the one admission minted and the drive records into, so the
         *  counters below are this turn's and no other's. Held for the row's life, which ends on the
         *  slot's release, so it is dropped with the row. */
        private val perf: TurnPerf,
        private val resume: ResumeReading?,
    ) : InflightGate.Slot.UpstreamBytes {
        /** Read from [clock] here rather than taken as a parameter beside it: the two have to come
         *  from the same clock, because [TurnSilence.idleMs] is a difference against this origin, and
         *  a caller holding both could hand over an origin the idle is not measured against. */
        val since: Long = clock()
        val session: String? get() = meta.scope.sessionId
        private val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
        private val stopped = AtomicBoolean(false)
        private val lastByte = AtomicLong(since)

        /** What the turn is doing: running, or held until its cause lifts (slice 5). */
        @Volatile var state: TurnState = TurnState.Running

        /** What the turn says while it is held, whatever holds it (docs/specs/harness.md, decision 7); null while it
         *  runs, except a message said to last until the turn's answer begins (decision 9). */
        @Volatile var saying: HeldSaying? = null
            private set

        /** Whether [saying] lasts past the hold, until the provider's first byte of the answer. */
        @Volatile private var untilAnswered = false

        @Synchronized
        fun say(said: HeldSaying, untilAnswered: Boolean) {
            saying = said
            this.untilAnswered = untilAnswered
        }

        @Synchronized
        fun quiet() {
            if (!untilAnswered) saying = null
        }

        /** Set once, on the provider's first byte. Not derived from [lastByte] against [since]: a byte
         *  that lands in the same millisecond as admission would read as no byte at all, and on a fast
         *  provider that is the common case rather than the rare one. */
        private val answered = AtomicBoolean(false)
        override val turnId: String get() = id

        override fun received() {
            lastByte.set(clock())
            answered.set(true)
            if (untilAnswered) answering()
        }

        /** The answer has begun: a message said until then is said no more. */
        @Synchronized
        private fun answering() {
            if (!untilAnswered) return
            untilAnswered = false
            saying = null
        }

        fun driving(job: Job) {
            jobs += job
            job.invokeOnCompletion { jobs.remove(job) }
            if (stopped.get()) job.cancel(OperatorStop())
        }

        fun isStopped(): Boolean = stopped.get()

        /** True for the first stop only: a second stop of the same turn changes nothing. */
        fun claimStop(): Boolean = stopped.compareAndSet(false, true)

        /** Cancels every active reader and client drive; one that starts later is cancelled by [driving]. */
        fun cancel() {
            jobs.forEach { it.cancel(OperatorStop()) }
        }

        fun view(now: Long): LiveTurn = LiveTurn(
            id,
            session,
            meta.route.upstreamModel,
            stopped.get(),
            TurnSilence(
                idleMs = (now - lastByte.get()).coerceAtLeast(0L),
                seenOutput = answered.get(),
                resumes = perf.count(PerfKeys.REANCHORS).toInt(),
                retries = perf.count(PerfKeys.RETRIES).toInt(),
                willResume = resume?.now(perf) == true,
            ),
            state,
        )
    }

    /** A stop's mark: which session's re-send of which messages is refused, until when. */
    private data class Mark(val session: String, val messages: String)

    private val live = ConcurrentHashMap<String, Live>()
    private val bySlot = ConcurrentHashMap<InflightGate.Slot, Live>()
    private val marks = ConcurrentHashMap<Mark, Long>()

    /** A streaming turn admitted on [slot], listed until the slot is released. [messagesHash] is
     *  [MessagesHash.of] the client's request, null when it sent no session (no re-send can be told).
     *  [perf] is the turn's own telemetry, which the row reads its resume and retry counts off: both
     *  are written where they happen, deeper than this seam can see (see [TurnSilence.resumes]). */
    internal fun admitted(
        slot: InflightGate.Slot,
        meta: TurnMeta,
        messagesHash: String?,
        perf: TurnPerf,
        resume: ResumeReading? = null,
    ) {
        val counted = slot.countedSlot
        val turn = bySlot.computeIfAbsent(counted) {
            val created = Live(
                ids.next(),
                meta,
                messagesHash,
                clock,
                perf,
                resume,
            )
            live[created.id] = created
            counted.onReceived(created)
            counted.onRelease {
                live.remove(created.id)
                bySlot.remove(counted, created)
            }
            created
        }
        turn.messages = messagesHash
        if (turn.isStopped()) mark(turn)
    }

    /** The drive's own job for the turn on [slot], so a stop cancels exactly that turn. A stop that
     *  arrived before the drive started cancels it here. */
    internal fun driving(slot: InflightGate.Slot, job: Job) {
        bySlot[slot.countedSlot]?.driving(job)
    }

    /** The turn on [slot] is now in [state]: held until its cause lifts, or running again (slice 5). */
    internal fun state(slot: InflightGate.Slot, state: TurnState) {
        bySlot[slot.countedSlot]?.state = state
    }

    /** The turn on [slot] now says [message] in [color], said at [atEpochMs] on the wall clock: its age starts again.
     *  Whoever holds the turn says it, and the turn says nothing once it is released or [quiet], except a message said
     *  [untilAnswered], which stays until the provider's first byte of the turn's answer. */
    internal fun says(
        slot: InflightGate.Slot,
        message: String,
        color: String,
        atEpochMs: Long,
        untilAnswered: Boolean = false,
    ) {
        val turn = bySlot[slot.countedSlot] ?: return
        turn.say(HeldSaying(turn.session, message, color, atEpochMs), untilAnswered)
    }

    /** The turn on [slot] is no longer held, and says nothing, unless what it says lasts until its answer begins. */
    internal fun quiet(slot: InflightGate.Slot) {
        bySlot[slot.countedSlot]?.quiet()
    }

    /** The turns live now, oldest first. They are copied out of the map in one pass: a list made from the map's own
     *  size and then its first element fails with NoSuchElementException when the one turn it counted ends between
     *  the two, and a turn ends at the very moment its client has the answer and reads the listing. */
    private fun liveOldestFirst(): List<Live> = live.values.toTypedArray().sortedBy { it.since }

    /** What each held turn says now, in the order the turns were admitted in. */
    public fun sayings(): List<HeldSaying> = liveOldestFirst().mapNotNull { it.saying }

    /** Oldest first, the order the turns were admitted in. */
    public fun list(): List<LiveTurn> {
        val now = clock()
        return liveOldestFirst().map { it.view(now) }
    }

    /** Stops the live turn [id] and answers true, or false when no turn with that id is live
     *  (it ended, or never ran here). A second stop of the same turn answers again and does nothing.
     *  The mark is left BEFORE the cancel, so the re-send the cancel provokes always finds it. */
    public fun stop(id: String): Boolean {
        val turn = live[id] ?: return false
        if (turn.claimStop()) {
            mark(turn)
            turn.cancel()
        }
        return true
    }

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
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { JsonWire.write(messages, it) }
        return HexFormat.of().formatHex(digest.digest())
    }
}
