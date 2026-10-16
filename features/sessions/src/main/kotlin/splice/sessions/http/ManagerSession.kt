// NEW: Oct 15, 2026 (the splice manager) — the one resident Claude Code session the console's composer is the door to.
// Marcos (Oct 15): "You should always check if a session exists already, if yes it should resume it. The first time
// the console runs, the composer should ask permission to start the claude code session with it and the user should
// pick which head they wanna use. They should also be able to later change the head without changing the transcript".
//
//   GET  /api/manager          the state (ManagerReplies), read only: nothing is created or written
//   POST /api/manager/start    {"head"?}  make the folder ready, then find, adopt, resume or start it (ManagerStart)
//                              200 the state once it registered; 202 the state while it asks something first;
//                              409 head_unknown | not_installed | launch_failed | folder_missing; 503 no terminal
//   POST /api/manager/head     {"head"}   move it while it runs, else keep the head for the next start (ManagerMove)
//                              200 the state; 409 head_unknown | same_head | waiting | not_installed, and every
//                              refusal Continue on gives, passed on as it is
//
// Where the manager is, is read afresh on every call (ManagerLookup): the pointer splice keeps (ManagerPointer), the
// session registry, the panes splice opened, and the transcripts in the folder.
package splice.sessions.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import splice.core.session.SessionLines
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.http.JsonReply

public class ManagerSession(
    private val home: ManagerFolder,
    private val pointers: ManagerPointerStore,
    heads: ManagerHeads,
    ports: ManagerDrive,
    /** What each session's status line last said; the state carries the manager's. */
    lines: SessionLines = SessionLines(),
) {
    private val lookup = ManagerLookup(ports)
    private val replies = ManagerReplies(lookup, pointers, heads, lines)
    private val starts = ManagerStart(ports, pointers, lookup, replies)
    private val moves = ManagerMove(ports, pointers, replies)

    /** One start or move at a time: a second press while the first waits for its session finds that session when it
     *  gets its turn, so two clicks never open two managers, and a move never runs under a start. */
    private val one = Mutex()

    public fun state(): JsonReply = replies.state(home.folder())

    public suspend fun startJson(body: String): JsonReply = one.withLock {
        Cancellables.runCatchingCancellable { home.ensure() }.fold(
            onSuccess = { folder -> starts.start(folder, headOf(body)) },
            onFailure = {
                replies.refuse(
                    "the manager's folder could not be made ready: " + SafeFailureText.render(it),
                    Refusal.FOLDER_MISSING,
                )
            },
        )
    }

    public suspend fun headJson(body: String): JsonReply = one.withLock { changeHead(body) }

    private suspend fun changeHead(body: String): JsonReply {
        val head = replies.known(headOf(body)) ?: return replies.refuse(PICK_A_HEAD, Refusal.HEAD_UNKNOWN)
        val folder = home.folder()
        val pointer = pointers.read()
        return when (val found = lookup.find(folder, pointer)) {
            is ManagerFound.Live -> moves.move(folder, pointer, found, head)
            is ManagerFound.Resumable, ManagerFound.Off -> moves.keep(folder, pointer, head)
        }
    }

    /** The head a body names, or null for a body that names none. */
    private fun headOf(body: String): String? =
        JsonScalars.objectOrNull(Json, body)?.let { JsonScalars.str(it, "head") }?.takeIf(String::isNotBlank)
}
