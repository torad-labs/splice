// NEW: Oct 15, 2026 (the splice manager) — POST /api/manager/start once its folder is ready: never a second manager.
//
// A manager already running is answered as it is, and one the pointer does not name yet is adopted: he started it in
// the folder himself. A start that stopped on a question waits on that same pane again. Else a transcript is resumed
// with `-r <id>` alone on the chosen head (whether `-r` combines with `--session-id` is unknown, so the resumed session
// is found by its folder, ManagerLaunch), or a new session starts under an id splice mints, with `--session-id <id>
// --name splice-manager`, as Teams starts a member. His yes, the head and the id are kept before the terminal opens, so
// a second start finds them; a start that did not come up gives the id back and keeps his yes and his head.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import splice.http.JsonReply
import java.nio.file.Path
import java.util.UUID

private const val MANAGER_NAME = "splice-manager"
internal const val PICK_A_HEAD = "pick one of the heads the manager offers"

/** The head a start runs on with its command, or the refusal that says why there is none. */
private sealed class Pick {
    class Ready(val head: String, val argv: List<String>) : Pick()

    class Refused(val reply: JsonReply) : Pick()
}

internal class ManagerStart(
    private val ports: ManagerDrive,
    private val pointers: ManagerPointerStore,
    private val lookup: ManagerLookup,
    private val replies: ManagerReplies,
) {
    private val launch = ManagerLaunch(ports, lookup)

    /** Starts, resumes or adopts the manager in [folder], on [asked] or the head the pointer keeps. */
    suspend fun start(folder: Path, asked: String?): JsonReply {
        val pointer = pointers.read()
        return when (val found = lookup.find(folder, pointer)) {
            is ManagerFound.Live ->
                if (found.record == null) rewait(folder, pointer, found.session) else adopt(folder, pointer, found)
            is ManagerFound.Resumable -> launch(folder, pointer, asked, found.session)
            ManagerFound.Off -> launch(folder, pointer, asked, null)
        }
    }

    /** The running manager, recorded as the pointer's with his yes when it is not yet. */
    private fun adopt(folder: Path, pointer: ManagerPointer?, found: ManagerFound.Live): JsonReply {
        val adopted = pointers.agreed(pointer, found.session, found.record?.head ?: pointer?.head)
        if (adopted != pointer) pointers.write(adopted)
        return replies.state(folder)
    }

    /** A start that stopped on a question waits on that same pane again, its clock starting over. */
    private suspend fun rewait(folder: Path, pointer: ManagerPointer?, session: String): JsonReply {
        val driving = ports.drive.driver()
        val pane = driving?.panes?.paneFor(session)
        if (driving == null || pane == null) return replies.state(folder)
        val agreed = pointers.agreed(pointer, session, pointer?.head)
        return settle(folder, agreed, session, launch.await(driving, session, pane, folder))
    }

    private suspend fun launch(folder: Path, pointer: ManagerPointer?, asked: String?, resume: String?): JsonReply =
        when (val pick = pick(asked ?: pointer?.head)) {
            is Pick.Refused -> pick.reply
            is Pick.Ready -> open(folder, pointer, pick, resume)
        }

    private fun pick(head: String?): Pick {
        val key = replies.known(head) ?: return Pick.Refused(replies.refuse(PICK_A_HEAD, Refusal.HEAD_UNKNOWN))
        return when (val command = ports.commands.forHead(key)) {
            is StartCommand.Refused -> Pick.Refused(replies.refuse(command.reason, Refusal.NOT_INSTALLED))
            is StartCommand.Ready -> Pick.Ready(key, command.argv)
        }
    }

    private suspend fun open(folder: Path, pointer: ManagerPointer?, pick: Pick.Ready, resume: String?): JsonReply {
        val driving = ports.drive.driver()
            ?: return Refusals.reply(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE, Refusal.NO_TERMINAL)
        val session = resume ?: UUID.randomUUID().toString()
        val flags = resume?.let { listOf("-r", it) } ?: listOf("--session-id", session, "--name", MANAGER_NAME)
        val agreed = pointers.agreed(pointer, session, pick.head)
        pointers.write(agreed)
        return settle(folder, agreed, pointer?.sessionId, launch.open(driving, session, pick.argv + flags, folder))
    }

    /** The pointer after a start: the id it came up under; or, when it did not come up, the id it had [before]. */
    private fun settle(folder: Path, agreed: ManagerPointer, before: String?, up: ManagerUp): JsonReply = when (up) {
        is ManagerUp.Up -> {
            pointers.write(agreed.copy(sessionId = up.session))
            replies.state(folder)
        }
        // the page reads the question off /api/sessions/{id}/screen and answers it through .../answer, on his click
        is ManagerUp.Asked -> replies.state(folder, HttpStatusCode.Accepted)
        is ManagerUp.Missed -> {
            pointers.write(agreed.copy(sessionId = before))
            up.reply
        }
    }
}
