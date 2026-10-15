// NEW: Oct 15, 2026 (the splice manager) — POST /api/manager/head: the manager goes on under another head, the same
// conversation. Marcos: "They should also be able to later change the head without changing the transcript".
//
// While it runs, Continue on moves it (SessionContinue): its client is ended with `/exit` and resumed on the new
// head's command with `-r <id>`, and an answer naming a session_id (the cross-head copy) makes that id the pointer's.
// The answer is the state once Continue on has seen it register there. The same head, or a manager waiting on an
// answer (`/exit` would land in the question), is refused here with its reason; every other refusal Continue on gives
// is passed on as it is. While nothing runs, the head is only kept, so the next start resumes on it.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import splice.core.util.JsonScalars
import splice.http.JsonReply
import splice.sessions.registry.SessionRecord
import java.nio.file.Path

internal class ManagerMove(
    private val ports: ManagerDrive,
    private val pointers: ManagerPointerStore,
    private val replies: ManagerReplies,
) {
    /** Moves the running manager, [found], onto [head]. */
    suspend fun move(folder: Path, pointer: ManagerPointer?, found: ManagerFound.Live, head: String): JsonReply {
        refusalToMove(found.record, head)?.let { return it }
        val moved = ports.continueOn.move(found.session, head, null)
        if (moved.status != HttpStatusCode.OK) return moved
        val copy = JsonScalars.objectOrNull(Json, moved.body)?.let { JsonScalars.str(it, "session_id") }
        pointers.write(pointers.agreed(pointer, copy ?: found.session, head))
        return replies.state(folder)
    }

    /** Keeps [head] for the next start, the session the pointer names, and his yes or its absence, as they were. */
    fun keep(folder: Path, pointer: ManagerPointer?, head: String): JsonReply {
        pointers.write(pointer?.copy(head = head) ?: ManagerPointer(null, head, consented = false, since = null))
        return replies.state(folder)
    }

    /** Why the running manager cannot move to [head] now, or null when it can. A pane still asking before Claude Code
     *  registers has no registration to move, and waits on him like a question does. */
    private fun refusalToMove(record: SessionRecord?, head: String): JsonReply? = when {
        record == null || record.status.waitingFor != null ->
            replies.refuse("the manager is waiting on an answer; answer it before moving it", Refusal.WAITING)
        record.head == head -> replies.refuse("the manager already runs on $head", Refusal.SAME_HEAD)
        else -> (ports.commands.forHead(head) as? StartCommand.Refused)
            ?.let { replies.refuse(it.reason, Refusal.NOT_INSTALLED) }
    }
}
