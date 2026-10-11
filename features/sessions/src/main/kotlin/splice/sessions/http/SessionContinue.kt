// NEW: Oct 10, 2026 (step 3, Continue on) — moving one session onto another command, by its own id.
//
//   POST /api/sessions/{id}/continue   {"head": "<key>", "model": "<id>"}   go on under that command's model
//
// WHAT A MOVE IS. The same session id and transcript go on under another head: Claude Code is ended where it runs and
// started again on the chosen command with `-r <id>`, and that launch's cross-head resume copies the transcript into
// the head's tree and moves its rows onto the head's model (ResumeAcrossHeads, TranscriptModelRewrite), recording the
// joint the page draws (ModelMoves). A move between heads is that explicit `-r` copy and nothing else (head
// isolation). Nothing is sent for the person: his next message runs on the new model (the mock, hitstop).
//
// WHERE IT GOES ON. In the terminal it ran in. The client is ended with its own `/exit`, never by killing the
// terminal: a session he started from his own shell leaves that shell in front, and the resume is typed there as he
// would type it. A terminal splice opened closes with its client, so the resume opens a new one. A session whose
// client already exited opens a new one too.
//
// WHAT IS REFUSED. A session waiting on an answer: `/exit` would land in the question. One already on that command.
// One splice cannot reach (the refusals SessionDrive gives). One whose prompt holds words he has not sent: `/exit`
// would be sent with them. A working turn is stopped first, as Stop would, and once it has stopped the prompt is
// emptied, because Claude Code puts the stopped message back in it and that message is already in the transcript.
// The route waits for the client to exit and for the session to register on the new command, and answers:
//   200  it runs on the new command: its id, the command, and what a person types to sit in front of it.
//   400  no command named.   404  no such session.   409  refused, and why.
//   502  the terminal did not take it.   503  no terminal.   504  it did not exit, or did not come back in time.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import java.nio.file.Files
import java.nio.file.Path

// why: Claude Code's /exit flushes and quits within a second or two; past this the client is holding on something
// the person has to see, and nothing is started on top of it.
private const val EXIT_SECONDS = 15L

// why: a stopped turn settles within a second; past this it is still running and nothing is typed over it.
private const val STOP_SECONDS = 10L
private const val EXIT_COMMAND = "/exit"
private const val MOVE_CLOSED = "its terminal is closed, so there is no client to move"
private const val MOVE_NOT_OURS =
    "splice did not start this session, so it cannot move it from here; exit it and resume it on the other command"

/** Whether a moved session's client has gone and come back, read off the registry: the move's two waits. */
public interface SessionHandover {
    /** Whether process [pid] no longer runs [session] within [seconds]. */
    public suspend fun left(session: String, pid: Long, seconds: Long): Boolean

    /** Whether [session] has no turn running within [seconds]. */
    public suspend fun stopped(session: String, seconds: Long): Boolean

    /** Whether [session] runs on [head] within [seconds]. */
    public suspend fun cameOn(session: String, head: String, seconds: Long): Boolean
}

public class SessionContinue(
    private val drive: SessionDrive,
    private val commands: StartCommands,
    private val handover: SessionHandover,
    private val registry: SessionSource?,
    private val home: Path,
) {
    /** The client's own words for a turn in flight. */
    private val busy = setOf("working", "busy")

    /** One argv word a POSIX shell reads back as itself without quoting. */
    private val plainWord = Regex("[A-Za-z0-9._/:=@%+,-]+")

    /** POST .../continue's body: the command (`head`) and, optionally, the model on it. */
    public suspend fun moveJson(session: String, body: String): JsonReply {
        val obj = JsonScalars.objectOrNull(Json, body)
        val head = obj?.let { JsonScalars.str(it, "head") }?.takeIf(String::isNotBlank)
        if (obj == null || head == null) {
            return Refusals.reply(HttpStatusCode.BadRequest, "name the command to continue on")
        }
        return move(session, head, JsonScalars.str(obj, "model")?.takeIf(String::isNotBlank))
    }

    public suspend fun move(session: String, head: String, model: String?): JsonReply =
        when (val plan = plan(session, head, model)) {
            is Plan.Refused -> plan.reply
            is Plan.Go -> go(plan.move)
        }

    /** The session, the command it moves to, the argv that resumes it there, the folder it runs in, and the client
     *  to end first: its pid (null when it already exited) and whether a turn is in flight. */
    private data class Move(
        val session: String,
        val head: String,
        val argv: List<String>,
        val folder: String,
        val pid: Long?,
        val working: Boolean,
    )

    /** A move ready to make, or the reply that refuses it. */
    private sealed class Plan {
        class Refused(val reply: JsonReply) : Plan()

        class Go(val move: Move) : Plan()
    }

    private fun plan(session: String, head: String, model: String?): Plan {
        val record = registry?.read().orEmpty().firstOrNull { it.sessionId == session }
            ?: return Plan.Refused(Refusals.reply(HttpStatusCode.NotFound, "no such session: $session"))
        refusalToMove(record, head)?.let { return Plan.Refused(it) }
        return when (val command = commands.forHead(head)) {
            is StartCommand.Refused -> Plan.Refused(Refusals.reply(HttpStatusCode.Conflict, command.reason))
            is StartCommand.Ready -> {
                val resume = listOf("-r", session) + model?.let { listOf("--model", it) }.orEmpty()
                val running = record.availability != SessionAvailability.GONE
                val folder = record.process.cwd?.takeIf { Files.isDirectory(Path.of(it)) } ?: home.toString()
                Plan.Go(
                    Move(
                        session = session,
                        head = head,
                        argv = command.argv + resume,
                        folder = folder,
                        pid = record.process.pid?.takeIf { running },
                        working = running && record.status.state in busy,
                    ),
                )
            }
        }
    }

    /** Why this session cannot move to [head] now, or null when it can. */
    private fun refusalToMove(record: SessionRecord, head: String): JsonReply? = when {
        record.head == head -> Refusals.reply(HttpStatusCode.Conflict, "it already runs on $head")
        record.availability != SessionAvailability.GONE && record.status.waitingFor != null ->
            Refusals.reply(HttpStatusCode.Conflict, "it is waiting on an answer; answer it before moving it")
        else -> null
    }

    /** A client still running is ended in its pane first; one already gone goes on in a new terminal. */
    private suspend fun go(move: Move): JsonReply {
        val pid = move.pid ?: return openNew(move)
        val driving = drive.driver() ?: return unwired()
        val at = drive.locate(driving, move.session, MOVE_NOT_OURS, MOVE_CLOSED)
        val pane = at.pane
        return if (pane == null) requireNotNull(at.refusal) else handOver(driving, pane, pid, move)
    }

    /** End the client in [pane] with its own exit, and resume once it has gone. */
    private suspend fun handOver(driving: SessionDriver, pane: SessionPane, pid: Long, move: Move): JsonReply {
        if (!move.working) drive.drafted(move.session)?.let { return it }
        val failure = if (move.working) stopTurn(driving, pane, move) else null
        if (failure != null) return failure
        val sent = Cancellables.runCatchingCleanup { driving.terminal.send(pane, EXIT_COMMAND) }.exceptionOrNull()
        return when {
            sent != null -> refusedByTerminal(sent)
            !handover.left(move.session, pid, EXIT_SECONDS) -> Refusals.reply(
                HttpStatusCode.GatewayTimeout,
                "its client did not exit within ${EXIT_SECONDS}s, so nothing was started on ${move.head}",
            )
            else -> resume(driving, pane, move)
        }
    }

    /** Stop the turn in flight and empty the prompt the stopped message came back to; null once both are done. */
    private suspend fun stopTurn(driving: SessionDriver, pane: SessionPane, move: Move): JsonReply? {
        Cancellables.runCatchingCleanup { driving.terminal.press(pane, SessionKey.STOP) }
            .exceptionOrNull()?.let { return refusedByTerminal(it) }
        if (!handover.stopped(move.session, STOP_SECONDS)) {
            return Refusals.reply(
                HttpStatusCode.GatewayTimeout,
                "its turn did not stop within ${STOP_SECONDS}s, so nothing was started on ${move.head}",
            )
        }
        return Cancellables.runCatchingCleanup { driving.terminal.press(pane, SessionKey.CLEAR) }
            .exceptionOrNull()?.let(::refusedByTerminal)
    }

    /** The resume where the person is: his own shell when one is left in front, else a new terminal. */
    private suspend fun resume(driving: SessionDriver, pane: SessionPane, move: Move): JsonReply {
        driving.panes.forget(move.session)
        if (!driving.terminal.isOpen(pane)) return openNew(move, beside = pane)
        // his own shell is in front again: the resume is typed there, the way he would type it
        val line = move.argv.joinToString(" ", transform = ::shellWord)
        val failure = Cancellables.runCatchingCleanup { driving.terminal.send(pane, line) }.exceptionOrNull()
        return if (failure != null) refusedByTerminal(failure) else cameBack(driving, pane, move)
    }

    private suspend fun openNew(move: Move, beside: SessionPane? = null): JsonReply {
        val driving = drive.driver() ?: return unwired()
        val pane = Cancellables.runCatchingCleanup {
            driving.terminal.open(move.session, move.argv, move.folder, beside)
        }.getOrElse { return refusedByTerminal(it) }
        driving.panes.remember(move.session, pane)
        return cameBack(driving, pane, move)
    }

    private suspend fun cameBack(driving: SessionDriver, pane: SessionPane, move: Move): JsonReply {
        if (!handover.cameOn(move.session, move.head, ARRIVAL_SECONDS)) {
            // the bottom of the screen: the question a first-run prompt holds on is the last thing on it
            val screen = Cancellables.runCatchingCleanup { driving.terminal.screen(pane) }.fold(
                { it.lines().filter(String::isNotBlank).takeLast(SCREEN_TAIL_LINES).joinToString("\n") },
                { "(the screen could not be read: ${it.message})" },
            )
            return JsonReply(
                HttpStatusCode.GatewayTimeout,
                buildJsonObject {
                    put("error", "it had not come back on ${move.head} after ${ARRIVAL_SECONDS}s")
                    put("screen", screen)
                }.toString(),
            )
        }
        return JsonReply(
            HttpStatusCode.OK,
            buildJsonObject {
                put("session_id", move.session)
                put("head", move.head)
                put("how_to_open", driving.terminal.howToOpen(pane))
            }.toString(),
        )
    }

    private fun unwired(): JsonReply =
        Refusals.reply(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE, Refusal.NO_TERMINAL)

    private fun refusedByTerminal(failure: Throwable): JsonReply = Refusals.reply(
        HttpStatusCode.BadGateway,
        "the terminal did not take it: " + SafeFailureText.render(failure),
        Refusal.REFUSED,
    )

    /** One argv word as a POSIX shell reads it back: as it is when plainly safe, single-quoted otherwise. */
    private fun shellWord(word: String): String = if (plainWord.matches(word)) {
        word
    } else {
        "'" + word.replace("'", "'\\''") + "'"
    }
}
