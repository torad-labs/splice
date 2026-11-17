// NEW: the harness at one head (docs/specs/harness.md, decisions 2, 3, 5 and 6). For each request it decides, once
// for its thread, whether this is the thread's first turn, calls the capabilities whose rules name the request in the
// order splice.toml declares them while the turn is held, and answers with the texts the request carries: the ones
// its thread's capabilities placed, for a first user message that is still the one they were placed in.
package splice.head.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import splice.core.capability.Capability
import splice.core.capability.CapabilityFailure
import splice.core.capability.CapabilityTurn
import splice.core.failure.Decline
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Holds a turn while its capabilities work: the response opens and is pinged, the turn's notice is off and its
 *  place in the head's queue is given back; [release] takes one again before the turn goes on, and hears what the
 *  hold was, however it ended: the work done, a refusal, or the client leaving while it waited. */
internal interface TurnHolding {
    suspend fun hold()

    suspend fun release(held: Held)
}

/** A hold as the turn's record keeps it: the capabilities it was held on, in order, and for how long. */
internal data class Held(val on: List<String>, val ms: Long)

/** What the harness makes of one request. */
internal sealed class HarnessEnd {
    /** The request goes on carrying [texts], in order, at the end of its first user message. */
    data class Go(val texts: List<String>) : HarnessEnd()

    /** The turn is refused, and nothing goes upstream. */
    data class Refused(val why: Decline) : HarnessEnd()
}

/** The capabilities splice.toml declares, at head [head], over the threads' records. */
internal class Harness(
    private val head: String,
    private val capabilities: List<Capability>,
    private val records: ThreadRecords,
    private val calls: CapabilityCall,
    private val log: LogSink,
    private val clock: splice.core.util.ElapsedClock,
) {
    private val rules = CapabilityRules()

    /** The wait each thread's held turn is in, which a second request of the thread or the head's stop ends with the
     *  reason it ended. */
    private val waits = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Set while the head stops: no turn is held from then on. */
    private val stopping = AtomicBoolean(false)

    /** The head stops: every held turn stops waiting and goes on as today, and no turn is held from now on. */
    fun stop() {
        stopping.set(true)
        waits.values.forEach { it.complete(HEAD_STOPPED) }
    }

    /** The head runs again after a stop: turns are held again. */
    fun started() {
        stopping.set(false)
    }

    /** [request] is null for a request the harness reads nothing of. [holdable] is false where the turn cannot be
     *  held (a request that does not stream). */
    suspend fun around(request: HarnessRequest?, turnId: String, holdable: Boolean, holding: TurnHolding): HarnessEnd {
        val thread = request?.thread ?: return HarnessEnd.Go(emptyList())
        // A second request of the thread ends the wait of the first: neither places anything, both go on as today.
        waits.remove(thread)?.complete(SUPERSEDED)
        val hash = records.hashOf(request.firstUser)
        val first = firstTurn(thread, request)
        val due = if (holdable && !stopping.get()) due(request, first) else emptyList()
        if (first) records.update(thread) { taken(it, due, hash) }
        if (due.isEmpty()) return HarnessEnd.Go(records.of(thread).placedFor(hash))
        return held(thread, due, CallFor(head, turnId, request), holding, hash)
    }

    /** The capabilities whose rules name [request], in the order splice.toml declares them. */
    private fun due(request: HarnessRequest, first: Boolean): List<Capability> =
        capabilities.filter { rules.names(it, head, request, first) }

    /** Whether [request] is its thread's first turn, decided once: at the thread's first request that streams and
     *  carries tools. One that already holds an answer marks the thread as first seen mid-conversation, which never
     *  has one. */
    private fun firstTurn(thread: String, request: HarnessRequest): Boolean {
        if (!request.turn || records.of(thread).first != FirstTurn.UNDECIDED) return false
        if (request.answered) records.update(thread) { it.copy(first = FirstTurn.MIDWAY) }
        return !request.answered
    }

    /** The thread's first turn has come: the hash of its first message, and each capability's state, written down
     *  before any is called. */
    private fun taken(record: ThreadRecord, due: List<Capability>, hash: String?): ThreadRecord {
        val firsts = capabilities.filter { it.rule.turn == CapabilityTurn.FIRST && it.onFor(head) }
        val states = firsts.map { CapabilityState(it.name, if (it in due) WAITING else NEVER) }
        return states.fold(record.copy(first = FirstTurn.TAKEN, firstHash = hash)) { acc, state -> acc.with(state) }
    }

    private suspend fun held(
        thread: String,
        due: List<Capability>,
        on: CallFor,
        holding: TurnHolding,
        hash: String?,
    ): HarnessEnd {
        val startedMs = clock()
        val ended = CompletableDeferred<String>()
        holding.hold()
        waits[thread] = ended
        if (stopping.get()) ended.complete(HEAD_STOPPED)
        val outcome = try {
            runAll(thread, due, on, ended)
        } finally {
            waits.remove(thread, ended)
            holding.release(Held(due.map { it.name }, clock() - startedMs))
        }
        if (outcome == null) return HarnessEnd.Go(records.of(thread).placedFor(hash))
        // A refused turn was not the thread's first turn: the next request the rule names is called.
        records.update(thread) { record -> undone(record, due) }
        return HarnessEnd.Refused(outcome)
    }

    /** Calls each capability in turn, until one refuses the turn; null when the turn goes on. A wait ended by a
     *  second request of the thread, or by the head stopping, leaves the rest uncalled and places nothing. */
    private suspend fun runAll(
        thread: String,
        due: List<Capability>,
        on: CallFor,
        ended: CompletableDeferred<String>,
    ): Decline? {
        for (capability in due) {
            val end = raced(capability, on, ended)
            if (end == null) {
                due.forEach { mark(thread, it, CapabilityState(it.name, FAILED)) }
                val why = if (ended.isCompleted) ended.await() else CUT
                log("[$head] capability ${capability.name} stopped waiting: $why\n")
                return null
            }
            val refusal = settle(thread, capability, end, on)
            if (refusal != null) return refusal
        }
        return null
    }

    /** The capability's end, or null when the wait was ended first. */
    private suspend fun raced(capability: Capability, on: CallFor, ended: CompletableDeferred<String>): CallEnd? =
        coroutineScope {
            val work = async { calls.call(capability, on) }
            val stop = async { ended.await() }
            try {
                kotlinx.coroutines.selects.select {
                    work.onAwait { it }
                    stop.onAwait { null }
                }
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive) null else throw cancelled
            } finally {
                work.cancel()
                stop.cancel()
            }
        }

    /** Records [end] for [capability] and answers the refusal it makes of the turn, if any. */
    private fun settle(thread: String, capability: Capability, end: CallEnd, on: CallFor): Decline? {
        val name = capability.name
        val session = on.request.sessionId?.take(SESSION_CHARS)
        return when (end) {
            is CallEnd.Placed -> null.also { mark(thread, capability, CapabilityState(name, PLACED, end.text)) }
            CallEnd.Passed -> null.also { mark(thread, capability, CapabilityState(name, NEVER)) }
            is CallEnd.Refused -> Decline.CapabilityRefused(name, end.reason).also {
                log("[$head] capability $name refused a turn (session $session): ${end.reason}\n")
            }
            is CallEnd.Failed -> failed(thread, capability, end, session)
        }
    }

    /** A capability's work failed: closed, the turn is refused; open, it goes on without it. */
    private fun failed(thread: String, capability: Capability, end: CallEnd.Failed, session: String?): Decline? {
        val name = capability.name
        if (capability.limits.onFailure == CapabilityFailure.CLOSED) {
            log("[$head] capability $name failed: ${end.why}; the turn is refused (session $session)\n")
            return Decline.CapabilityFailed(name, end.why)
        }
        log("[$head] capability $name failed: ${end.why}; the turn goes on without it (session $session)\n")
        mark(thread, capability, CapabilityState(name, FAILED))
        return null
    }

    /** A capability called on every turn keeps no state in the thread; one called on its first turn does. */
    private fun mark(thread: String, capability: Capability, state: CapabilityState) {
        if (capability.rule.turn == CapabilityTurn.FIRST) records.update(thread) { it.with(state) }
    }

    /** The thread after a refused turn: its first turn not yet come, and the capabilities called for it as if they
     *  never were. */
    private fun undone(record: ThreadRecord, due: List<Capability>): ThreadRecord {
        if (due.none { it.rule.turn == CapabilityTurn.FIRST }) return record
        return record.copy(first = FirstTurn.UNDECIDED, firstHash = null, capabilities = emptyList())
    }
}

/** why: a session is named in the log by its first eight characters, as every head line names it. */
private const val SESSION_CHARS = 8

/** why: what the log says ended a wait. */
private const val SUPERSEDED = "a later request of the thread came"
private const val HEAD_STOPPED = "the head is stopping"
private const val CUT = "its work was cut short"
