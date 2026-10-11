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
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.core.util.ElapsedClock
import splice.core.util.JsonWire
import splice.core.util.MonoClock
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

/** How a turn's provider silence reads: how long quiet, and whether there is anything to be quiet
 *  after. ONE VALUE BECAUSE NEITHER IS READABLE ALONE.
 *
 *  The watchdog measures silence against a different limit depending on [seenOutput]
 *  ([splice.core.turn.WatchdogBudget]): before the first byte the limit is firstByteTimeout, and a
 *  prefill is *legitimately silent for minutes*; after it, the limit is the re-anchor tier when the
 *  round can be resumed and streamIdle otherwise. [idleMs] does not differ between the two, because a
 *  turn that has heard nothing reports its whole age as idle — so a reader given the number alone
 *  draws a two-minute prefill, which is a model thinking, exactly like a two-minute stall, which is a
 *  model that stopped. Those are opposite answers: one says wait, the other says go look.
 *
 *  [seenOutput] IS NOT THE GATE'S PHASE, though that is the obvious place to reach for. The gate turns
 *  [splice.core.head.GatePhase.STREAMING] on `touch()`, which upstream HEADERS call — a turn whose
 *  provider sent a 200 and then nothing reads as streaming there while the client has seen no content
 *  at all. That is right for the gate, whose job is liveness of the connection, and wrong here: it
 *  would push a turn into the stall tiers while the watchdog still holds it against firstByteTimeout.
 *  So this follows `received()`, the body-byte signal, exactly as [idleMs] does, and the two cannot
 *  disagree with each other. */
public data class TurnSilence(
    /** Time since the provider's last byte, or since this turn was listed before its first byte. */
    val idleMs: Long,
    /** Whether the provider has answered at all yet. */
    val seenOutput: Boolean = false,
    /** How many times splice has already ended a silence on this turn by cancelling the round and
     *  re-POSTing it from its own salvage, carrying on invisibly to the client. Zero for a turn that
     *  has run straight through. Here because a resume is what a silence ends in, so it is read with
     *  [idleMs] and never apart from it.
     *
     *  READ OFF [splice.core.perf.PerfKeys.REANCHORS], THE COUNTER THE RE-ANCHOR ITSELF WRITES
     *  (DriveSignals.onReanchor). THE GATE CANNOT ANSWER THIS, though it looks like it can, and the
     *  first build of this field was wrong for exactly that reason. `InflightGate.Slot.resumedSource`
     *  marks a borrowed handle over a source's counted permit, and `AdmittedTurn.settle` keeps such a
     *  handle only when `roundInterceptor.resumesSource()` is true — which in production only the
     *  codex code-mode bridge ever answers, when a LATER CLIENT REQUEST joins an upstream stream
     *  splice still holds. That is the opposite direction of travel from a re-anchor: the client sent
     *  it and knows about it. And the re-anchor never passes through admission at all, because it
     *  lives inside the round loop (Watchdog, RetryMatrix, the dialects' ReanchorPolicy), so no gate
     *  handle is ever borrowed for one. Counted off the gate, this read was 0 forever on every head
     *  but one, and counted something else on that one.
     *
     *  IT IS A COUNT OF WHAT HAPPENED, NOT A PREDICTION OF WHAT WILL. A reader deciding whether THIS
     *  silence is about to end in another re-send needs more than this and more than the head's armed
     *  tier: the round must also still be eligible, and one of those conditions — whether the round has
     *  already emitted a tool call — lives inside the dialect's own round state, which this seam cannot
     *  see. So this says how many resumes a turn has had, which is true, and says nothing about the
     *  next one, which would not be. */
    val resumes: Int = 0,
    /** How many times the upstream REFUSED this turn and splice re-POSTed it: a 429 with budget left,
     *  an overload, a 5xx, a transport error before any output (UpstreamAttempt.markRetry, read off
     *  [splice.core.perf.PerfKeys.RETRIES]).
     *
     *  A DIFFERENT NUMBER FROM [resumes] AND NOT INTERCHANGEABLE WITH IT, which is why both are here
     *  rather than one sum. A retry means the provider would not take the request; a resume means it
     *  took it, began answering, and went quiet. Different cause, different remedy: a retried turn is
     *  waiting on capacity that is not ours, and a resumed turn is being carried by splice. A reader
     *  shown one under the other's name would act on the wrong one. */
    val retries: Int = 0,
    /** Whether a failure of this round right now would still be carried on by splice: the head has a re-anchor tier,
     *  the dialect has a rule, the continuation budget is not spent, and the round has not emitted a tool call. The
     *  dialect decides (ReanchorPolicy.wouldContinue); false is the Stalled card's "Won't resume". */
    val willResume: Boolean = false,
)

/** Whether splice would carry on a turn's round if it failed now, read when the console lists the turn. The
 *  reading belongs to the dialect's re-anchor rule (see the admission's WillResume); the listing only asks. */
internal fun interface ResumeReading {
    fun now(perf: TurnPerf): Boolean
}

/** One live turn as the console lists it. [session] is the client's full session id when it sent one;
 *  [stopped] is true between the stop and the slot's release, which is the seal's few milliseconds. */
public data class LiveTurn(
    val id: String,
    val session: String?,
    val model: String,
    val compact: Boolean,
    val ageMs: Long,
    val stopped: Boolean,
    /** Silent for its whole age, having heard nothing, until the provider's first byte says otherwise. */
    val silence: TurnSilence = TurnSilence(ageMs),
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

        /** Set once, on the provider's first byte. Not derived from [lastByte] against [since]: a byte
         *  that lands in the same millisecond as admission would read as no byte at all, and on a fast
         *  provider that is the common case rather than the rare one. */
        private val answered = AtomicBoolean(false)
        override val turnId: String get() = id

        override fun received() {
            lastByte.set(clock())
            answered.set(true)
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
            meta.compact,
            now - since,
            stopped.get(),
            TurnSilence(
                idleMs = (now - lastByte.get()).coerceAtLeast(0L),
                seenOutput = answered.get(),
                resumes = perf.count(PerfKeys.REANCHORS).toInt(),
                retries = perf.count(PerfKeys.RETRIES).toInt(),
                willResume = resume?.now(perf) == true,
            ),
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
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { JsonWire.write(messages, it) }
        return HexFormat.of().formatHex(digest.digest())
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

    /** The session of every turn live on any head now, for the console to hear those sessions from. */
    public fun sessions(): Set<String> =
        heads.values.flatMapTo(HashSet()) { turns -> turns.list().mapNotNull(LiveTurn::session) }
}
