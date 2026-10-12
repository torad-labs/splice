// NEW: Oct 12, 2026 (Marcos via splice-lead): a login added after a plan limit wakes the sessions the limit stopped.
// Only a session whose newest request ended At limit on the command the login joined, whose limit has not expired, and
// that nobody stopped, answered or wrote to since, is sent the note, once per ending. Each release and each session
// that could not be reached is logged with why. Only a new login does this: a window that resets is not a login. A
// session still in Claude Code's own retry is left to it, since that retry rechecks the pool within 30 s, and one whose
// newest message is not the limit's own error was taken up by its person (Escape, a stop, a new message).
package splice.app.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.core.perf.OutcomeTag
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.http.SessionEndingOf
import splice.sessions.note.NoteOutcome
import splice.sessions.note.SessionNoteSender
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import java.util.concurrent.ConcurrentHashMap

private const val NEW_ACCOUNT_NOTE = "A new account is available. Continue where you left off."

// why: a turn still in Claude Code's own wait reads busy: its retry rechecks the pool within 30 s (ClientRecheck), so a
// note to it would only land after that turn, as a second ask.
private val IN_ITS_RETRY = setOf("working", "busy")

// why: only these two endings are a limit that a fresh login lifts; a missing login or a provider outage is not.
private val LIMIT_ENDINGS = setOf(OutcomeTag.PLAN_LIMIT.wire, OutcomeTag.ALL_ACCOUNTS_EXHAUSTED.wire)

/** Whether a session's turn ended at its limit and nothing came after: its newest message is the client's own limit
 *  error. A person who pressed Escape on the wait, in the terminal or from a card, or wrote to it since, leaves
 *  something else last, and a restart of the daemon does not forget it. */
internal fun interface LimitTurnEnded {
    fun endedAtLimit(record: SessionRecord): Boolean
}

internal class LimitResume(
    private val sessions: SessionSource,
    private val endingOf: SessionEndingOf,
    private val sender: SessionNoteSender,
    private val scope: CoroutineScope,
    private val log: LogSink,
    private val turnEnded: LimitTurnEnded,
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
) {
    /** When a person last stopped, answered or wrote to each session through the console. */
    private val acted = ConcurrentHashMap<String, Long>()

    /** The ending each session was already released for, so one ending is released once. */
    private val released = ConcurrentHashMap<String, Long>()

    /** A person did something to [session] just now: whatever limit it sat at is no longer waiting on a login. */
    fun acted(session: String) {
        acted[session] = clock()
    }

    /** A login was added to [head]: release every session stopped At limit on it. */
    fun joined(head: String) {
        scope.launch {
            val live = sessions.read().filter { it.availability != SessionAvailability.GONE && it.sessionId != null }
            live.forEach { record -> release(head, record) }
        }
    }

    private suspend fun release(head: String, record: SessionRecord) {
        val id = requireNotNull(record.sessionId)
        val ending = stoppedAt(head, record, id) ?: return
        if (released.put(id, ending) == ending) return
        held(record)?.let {
            log("[limit-resume] left ${LogSafe.str(id)} to ${LogSafe.str(it)} on ${LogSafe.str(head)}")
            return
        }
        val outcome = Cancellables.runCatchingCancellable { sender.send(record, NEW_ACCOUNT_NOTE) }
            .getOrElse { NoteOutcome.Failed(SafeFailureText.render(it)) }
        when (outcome) {
            is NoteOutcome.Submitted -> log("[limit-resume] released ${LogSafe.str(id)} on ${LogSafe.str(head)}")
            is NoteOutcome.Refused -> unreached(id, outcome.reason)
            is NoteOutcome.Failed -> unreached(id, outcome.reason)
        }
    }

    /** When the limit holding [id] on [head] ended its newest request, or null when nothing waits on a login: another
     *  ending, another command, or a person who acted on the session since. */
    private fun stoppedAt(head: String, record: SessionRecord, id: String): Long? {
        val ending = endingOf.endingOf(id) ?: return null
        val here = ending.outcome in LIMIT_ENDINGS && ending.head == head && record.head == head
        return ending.atMs.takeIf { here && it > (acted[id] ?: 0L) }
    }

    /** Who [record] is left to instead of the note, or null when its turn ended at the limit and waits on a login. */
    private fun held(record: SessionRecord): String? = when {
        record.status.state in IN_ITS_RETRY -> "its own retry"
        !turnEnded.endedAtLimit(record) -> "the person who took it up"
        else -> null
    }

    private fun unreached(id: String, reason: String) {
        log("[limit-resume] could not reach ${LogSafe.str(id)} after a new login: ${LogSafe.str(reason)}")
    }
}
