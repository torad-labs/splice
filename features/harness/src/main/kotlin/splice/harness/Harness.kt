// NEW: the harness at one head (docs/specs/harness.md, decisions 2, 3, 5 and 6). For each request it decides, once
// for its thread, whether this is the thread's first turn, calls the capabilities whose rules name the request in the
// order splice.toml declares them while the turn is held, and answers with the texts the request carries: the ones
// its thread's capabilities placed, for a first user message that is still the one they were placed in.
package splice.harness

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
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.port.HarnessEnd
import splice.head.port.HarnessRequest
import splice.head.port.HarnessTurn
import splice.head.port.Held
import splice.head.port.TurnHarness
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The capabilities splice.toml declares, at head [head], over the threads' records. */
internal class Harness(
    private val head: String,
    private val capabilities: List<Capability>,
    private val records: ThreadRecords,
    private val calls: CapabilityCall,
    private val log: LogSink,
    private val clock: ElapsedClock,
    /** The model's calls of the capabilities (decision 12). */
    private val agent: AgentCalls,
) : TurnHarness {
    private val rules = CapabilityRules()

    /** The wait each thread's held turn is in, which a second request of the thread or the head's stop ends with the
     *  reason it ended. */
    private val waits = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Set while the head stops: no turn is held from then on. */
    private val stopping = AtomicBoolean(false)

    /** The head stops: every held turn stops waiting and goes on as today, and no turn is held from now on. */
    override fun stop() {
        stopping.set(true)
        waits.values.forEach { it.complete(HEAD_STOPPING) }
        agent.stop()
    }

    /** The head runs again after a stop: turns are held again. */
    override fun started() {
        stopping.set(false)
        agent.started()
    }

    /** [request] is null for a request the harness reads nothing of. The turn is held only where [turn] can be held
     *  (a request that does not stream cannot). */
    override suspend fun around(request: HarnessRequest?, turn: HarnessTurn): HarnessEnd {
        val ruled = ruled(request, turn)
        if (request == null || ruled !is HarnessEnd.Go) return ruled
        return when (val agent = agent.around(request, turn)) {
            is AgentEnd.Refused -> HarnessEnd.Refused(agent.why)
            is AgentEnd.Go -> ruled.copy(sending = agent.sending, rounds = agent.rounds)
        }
    }

    /** What the capabilities called by rule make of [request]. */
    private suspend fun ruled(request: HarnessRequest?, turn: HarnessTurn): HarnessEnd {
        val thread = request?.thread ?: return HarnessEnd.Go(emptyList())
        // A second request of the thread ends the wait of the first: neither places anything, both go on as today.
        waits.remove(thread)?.complete(SUPERSEDED)
        val hash = records.hashOf(request.firstUser)
        val first = firstTurn(thread, request)
        val on = CallFor(head, turn, request)
        // The capabilities whose rules name the request, in the order splice.toml declares them.
        val named = if (turn.holdable && !stopping.get()) {
            capabilities.filter { rules.names(it, head, request, first) }
        } else {
            emptyList()
        }
        val skipped = notCalled(named, on)
        val due = named.filter { it.name !in skipped }
        if (first) records.update(thread) { taken(it, due, hash) }
        val end = if (due.isEmpty()) HarnessEnd.Go(records.of(thread).placedFor(hash)) else held(thread, due, on, hash)
        val notes = skipped.map { (name, why) -> "$name: $why" }
        return if (end is HarnessEnd.Go && notes.isNotEmpty()) end.copy(skipped = notes) else end
    }

    /** The capabilities among [named] that are not called for [on] though their rules name it, each with the reason,
     *  which the log keeps with the whole session id, so the turn's own record can be found from it (decision 9). */
    private suspend fun notCalled(named: List<Capability>, on: CallFor): Map<String, String> =
        named.mapNotNull { capability -> calls.notCalled(capability, on)?.let { capability.name to it } }.toMap().also {
            it.forEach { (name, why) ->
                log("[$head] capability $name was not called (session ${on.request.sessionId}): $why\n")
            }
        }

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
        val firsts = capabilities.filter {
            it.call.byRule && it.call.rule.turn == CapabilityTurn.FIRST && it.onFor(head)
        }
        val states = firsts.map { CapabilityState(it.name, if (it in due) WAITING else NEVER_CALLED) }
        return states.fold(record.copy(first = FirstTurn.TAKEN, firstHash = hash)) { acc, state -> acc.with(state) }
    }

    private suspend fun held(
        thread: String,
        due: List<Capability>,
        on: CallFor,
        hash: String?,
    ): HarnessEnd {
        val startedMs = clock()
        val ended = CompletableDeferred<String>()
        val failures = mutableListOf<String>()
        on.turn.hold()
        waits[thread] = ended
        if (stopping.get()) ended.complete(HEAD_STOPPING)
        val outcome = try {
            runAll(thread, due, on, ended, failures)
        } finally {
            waits.remove(thread, ended)
            on.turn.release(Held(due.map { it.name }, clock() - startedMs, failures.toList()))
        }
        if (outcome == null) return HarnessEnd.Go(records.of(thread).placedFor(hash))
        // A refused turn was not the thread's first turn: the next request the rule names is called.
        if (due.any { it.call.rule.turn == CapabilityTurn.FIRST }) records.update(thread) { record -> record.undone() }
        return HarnessEnd.Refused(outcome)
    }

    /** Calls each capability in turn, until one refuses the turn; null when the turn goes on. A wait ended by a
     *  second request of the thread, by the head stopping or by the client leaving leaves the rest uncalled and places
     *  nothing. Each capability whose work failed or was stopped goes into [failures] with its reason. */
    private suspend fun runAll(
        thread: String,
        due: List<Capability>,
        on: CallFor,
        ended: CompletableDeferred<String>,
        failures: MutableList<String>,
    ): Decline? {
        for (capability in due) {
            // The held turn says the capability's own message as its work starts, then each progress line it sends.
            on.turn.says(capability.notice.message, capability.notice.color)
            val end = try {
                raced(capability, on, ended)
            } catch (cancelled: CancellationException) {
                // The turn itself was cancelled: a held turn is kept through a head's stop, so its client left.
                failures += stopped(thread, due, capability, on, if (stopping.get()) HEAD_STOPPING else CLIENT_LEFT)
                throw cancelled
            }
            if (end == null) {
                failures += stopped(thread, due, capability, on, if (ended.isCompleted) ended.await() else CUT_SHORT)
                return null
            }
            if (end is CallEnd.Failed) failures += "${capability.name}: ${end.why}"
            val refusal = settle(thread, capability, end, on)
            if (refusal != null) return refusal
        }
        return null
    }

    /** The wait on [capability] ended for [why] before its work did: every capability of [due] is marked as failed,
     *  and the log names the whole session, so the turn's own record can be found from it. Answers the failure. */
    private fun stopped(
        thread: String,
        due: List<Capability>,
        capability: Capability,
        on: CallFor,
        why: String,
    ): String {
        due.forEach { mark(thread, it, CapabilityState(it.name, FAILED)) }
        log("[$head] capability ${capability.name} stopped waiting (session ${on.request.sessionId}): $why\n")
        return "${capability.name}: $why"
    }

    /** The capability's end, or null when the wait was ended first. */
    private suspend fun raced(capability: Capability, on: CallFor, ended: CompletableDeferred<String>): CallEnd? =
        coroutineScope {
            val work = async {
                calls.call(capability, on) { message -> on.turn.says(message, capability.notice.color) }
            }
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
        val session = on.request.sessionId
        return when (end) {
            is CallEnd.Placed -> null.also { mark(thread, capability, CapabilityState(name, PLACED, end.text)) }
            CallEnd.Passed -> null.also { mark(thread, capability, CapabilityState(name, NEVER_CALLED)) }
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
        if (capability.call.rule.turn == CapabilityTurn.FIRST) records.update(thread) { it.with(state) }
    }
}

/** why: what the log says ended a wait. */
private const val SUPERSEDED = "a later request of the thread came"
private const val HEAD_STOPPING = "the head is stopping"
private const val CUT_SHORT = "its work was cut short"
private const val CLIENT_LEFT = "the client left"
