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
    /** How many times splice has already ended a silence on this turn by re-sending it upstream and
     *  carrying on, invisibly to the client. Zero for a turn that has run straight through. Here
     *  because a resume is what a silence ends in, so it is read with [idleMs] and never apart from it.
     *
     *  IT IS A COUNT OF WHAT HAPPENED, NOT A PREDICTION OF WHAT WILL. A reader deciding whether THIS
     *  silence is about to end in another re-send needs more than this and more than the head's armed
     *  tier: the round must also still be eligible, and one of those conditions — whether the round has
     *  already emitted a tool call — lives inside the dialect's own round state, which this seam cannot
     *  see. So this says how many resumes a turn has had, which is true, and says nothing about the
     *  next one, which would not be. */
    val resumes: Int = 0,
)

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
        val session: String?,
        @Volatile var messages: String?,
        private val model: String,
        private val compact: Boolean,
        val since: Long,
        private val clock: ElapsedClock,
    ) : InflightGate.Slot.UpstreamBytes {
        private val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
        private val stopped = AtomicBoolean(false)
        private val lastByte = AtomicLong(since)

        /** Set once, on the provider's first byte. Not derived from [lastByte] against [since]: a byte
         *  that lands in the same millisecond as admission would read as no byte at all, and on a fast
         *  provider that is the common case rather than the rare one. */
        private val answered = AtomicBoolean(false)

        /** How many times splice has re-sent this turn upstream and carried on. */
        private val resumes = AtomicLong(0)
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

        fun resumed() {
            resumes.incrementAndGet()
        }

        /** True for the first stop only: a second stop of the same turn changes nothing. */
        fun claimStop(): Boolean = stopped.compareAndSet(false, true)

        /** Cancels every active reader and client drive; one that starts later is cancelled by [driving]. */
        fun cancel() {
            jobs.forEach { it.cancel(OperatorStop()) }
        }

        fun view(now: Long): LiveTurn = LiveTurn(
            id,
            session,
            model,
            compact,
            now - since,
            stopped.get(),
            TurnSilence((now - lastByte.get()).coerceAtLeast(0L), answered.get(), resumes.get().toInt()),
        )
    }

    /** A stop's mark: which session's re-send of which messages is refused, until when. */
    private data class Mark(val session: String, val messages: String)

    private val live = ConcurrentHashMap<String, Live>()
    private val bySlot = ConcurrentHashMap<InflightGate.Slot, Live>()
    private val marks = ConcurrentHashMap<Mark, Long>()

    /** A streaming turn admitted on [slot], listed until the slot is released. [messagesHash] is
     *  [MessagesHash.of] the client's request, null when it sent no session (no re-send can be told). */
    internal fun admitted(slot: InflightGate.Slot, meta: TurnMeta, messagesHash: String?) {
        val counted = slot.countedSlot
        val turn = bySlot.computeIfAbsent(counted) {
            val created = Live(
                ids.next(),
                meta.scope.sessionId,
                messagesHash,
                meta.route.upstreamModel,
                meta.compact,
                clock(),
                clock,
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
        // A BORROWED HANDLE ON A ROW THAT ALREADY EXISTS IS A RESUME. The gate hands a continuation its
        // own Slot over the source's one counted permit (InflightGate.Slot.resumedSource), and that
        // continuation is admitted again through here, finding the row rather than creating it. So the
        // count of resumes is observable at this one seam, without reaching into any dialect's round.
        if (slot.resumedSource) turn.resumed()
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
