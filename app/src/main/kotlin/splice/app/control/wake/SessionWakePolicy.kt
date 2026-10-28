package splice.app.control.wake

import splice.app.control.SessionHeadPooled
import splice.app.control.SessionTurnEnded
import splice.core.failure.SessionNote
import splice.core.perf.OutcomeTag
import splice.core.util.WallClock
import splice.sessions.http.SessionEnding
import splice.sessions.registry.SessionRecord

private val IN_ITS_RETRY = setOf("working", "busy")
private val WAITING_ENDINGS = setOf(
    OutcomeTag.PLAN_LIMIT.wire,
    OutcomeTag.ALL_ACCOUNTS_EXHAUSTED.wire,
    OutcomeTag.AUTH_MISSING.wire,
)

/** Rechecks who owns a stopped conversation and whether its stated reset is due. */
internal class SessionWakePolicy(
    private val turnEnded: SessionTurnEnded,
    val clock: WallClock = WallClock(System::currentTimeMillis),
    private val pooled: SessionHeadPooled = SessionHeadPooled { true },
) {
    fun ready(head: String, record: SessionRecord, ending: SessionEnding, note: SessionNote, actedAt: Long): Boolean {
        if (ending.head != head || record.head != head) return false
        if (ending.atMs <= actedAt || !waiting(ending)) return false
        val reset = ending.resumeAtMs ?: ending.resetMs
        return note != SessionNote.RESET || reset?.let { it <= clock() } == true
    }

    fun held(record: SessionRecord, ending: SessionEnding, note: SessionNote, head: String): String? = when {
        retryOwns(record, note, head) -> "its own retry"
        !turnEnded.untouched(record, ending.atMs) -> "the person who took it up"
        else -> null
    }

    private fun waiting(ending: SessionEnding): Boolean =
        ending.resetMs != null || ending.outcome in WAITING_ENDINGS

    private fun retryOwns(record: SessionRecord, note: SessionNote, head: String): Boolean =
        record.status.state in IN_ITS_RETRY && retryAtHead(note, head)

    private fun retryAtHead(note: SessionNote, head: String): Boolean = note == SessionNote.RESET || pooled.pooled(head)
}
