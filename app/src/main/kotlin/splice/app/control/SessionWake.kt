package splice.app.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import splice.app.control.mount.BoundResource
import splice.app.control.wake.SessionWakePolicy
import splice.app.control.wake.SessionWakeTimers
import splice.core.failure.HeadVoice
import splice.core.failure.SessionNote
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.sessions.http.SessionEnding
import splice.sessions.http.SessionEndingOf
import splice.sessions.note.NoteOutcome
import splice.sessions.note.SessionNoteSender
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.upstream.LifecycleScope
import java.util.concurrent.ConcurrentHashMap

/** Whether a person or the model took this conversation up after the recorded ending. */
internal fun interface SessionTurnEnded {
    fun untouched(record: SessionRecord, endedAtMs: Long): Boolean
}

/** A pooled client's short retry already notices a newly joined login. */
internal fun interface SessionHeadPooled {
    fun pooled(head: String): Boolean
}

/** The app joins a head's ending stream with its head name without making sessions depend on turns. */
internal fun interface SessionWakeEvent {
    fun ended(session: String, ending: SessionEnding)
}

internal fun interface SessionWakeObservers {
    fun observe(listener: SessionWakeEvent): List<AutoCloseable>
}

/** Wakes a live, untouched session once its recorded reset passes or a usable login joins. */
internal class SessionWake(
    private val sessions: SessionSource,
    private val endingOf: SessionEndingOf,
    private val sender: SessionNoteSender,
    scope: CoroutineScope,
    private val log: LogSink,
    private val policy: SessionWakePolicy,
    private val observers: SessionWakeObservers = SessionWakeObservers { emptyList() },
) : BoundResource {
    private val work = LifecycleScope(scope.coroutineContext)
    private val timers = SessionWakeTimers(work, policy.clock, splice.app.control.wake.SessionResetWake(::reset))
    private val acted = ConcurrentHashMap<String, Long>()
    private val released = ConcurrentHashMap<String, Pair<String, Long>>()
    private var subscriptions: List<AutoCloseable> = emptyList()

    override fun start() {
        subscriptions = observers.observe(SessionWakeEvent(timers::record))
        work.launch {
            sessions.read().forEach { record ->
                record.sessionId?.let { id -> endingOf.endingOf(id)?.let { timers.record(id, it) } }
            }
        }
    }

    override fun stop() {
        subscriptions.forEach(AutoCloseable::close)
        subscriptions = emptyList()
        work.cancel()
    }

    /** Console actions revoke an old wake even when transcript reading is disabled. */
    fun acted(session: String) {
        acted[session] = policy.clock()
    }

    /** A single-login client's long sleep cannot notice a login until this note wakes it. */
    fun joined(head: String) {
        work.launch {
            live().filter { it.head == head }.forEach { record ->
                safely(record, head) { release(head, record, SessionNote.NEW_LOGIN) }
            }
        }
    }

    /** The timer re-reads both the newest ending and the live registry before sending. */
    private suspend fun reset(session: String, expected: SessionEnding) {
        val head = expected.head ?: return
        live().filter { it.sessionId == session }.forEach { record ->
            val current = record.sessionId?.let(endingOf::endingOf)
            if (current?.atMs == expected.atMs && current.head == head) {
                safely(record, head) { release(head, record, SessionNote.RESET) }
            }
        }
    }

    private fun live(): List<SessionRecord> =
        sessions.read().filter { it.availability != SessionAvailability.GONE && it.sessionId != null }

    private suspend inline fun safely(record: SessionRecord, head: String, action: () -> Unit) {
        Cancellables.runCatchingCancellable { action() }.onFailure {
            log(
                "[session-wake] ${LogSafe.str(record.sessionId.orEmpty())} not released on " +
                    "${LogSafe.str(head)}: ${LogSafe.str(SafeFailureText.render(it))}",
            )
        }
    }

    private suspend fun release(head: String, record: SessionRecord, note: SessionNote) {
        val id = requireNotNull(record.sessionId)
        val ending = endingOf.endingOf(id) ?: return
        if (!policy.ready(head, record, ending, note, acted[id] ?: 0L)) return
        val stamp = head to ending.atMs
        if (released.put(id, stamp) == stamp) return
        val holder = Cancellables.runCatchingCancellable { policy.held(record, ending, note, head) }
            .onFailure { released.remove(id, stamp) }.getOrThrow()
        if (holder == null) {
            send(head, record, note, stamp)
        } else {
            released.remove(id, stamp)
            log("[session-wake] left ${LogSafe.str(id)} to ${LogSafe.str(holder)} on ${LogSafe.str(head)}")
        }
    }

    private suspend fun send(head: String, record: SessionRecord, note: SessionNote, stamp: Pair<String, Long>) {
        val id = requireNotNull(record.sessionId)
        val text = HeadVoice(head, loginCommand = null).anyTurn.note(note).text
        val outcome = Cancellables.runCatchingCancellable { sender.send(record, text) }
            .getOrElse { NoteOutcome.Failed(SafeFailureText.render(it)) }
        when (outcome) {
            NoteOutcome.Submitted -> log("[session-wake] released ${LogSafe.str(id)} on ${LogSafe.str(head)}")
            is NoteOutcome.Refused -> {
                released.remove(id, stamp)
                unreached(id, outcome.reason)
            }
            is NoteOutcome.Failed -> unreached(id, outcome.reason)
        }
    }

    private fun unreached(id: String, reason: String) {
        log("[session-wake] could not reach ${LogSafe.str(id)}: ${LogSafe.str(reason)}")
    }
}
