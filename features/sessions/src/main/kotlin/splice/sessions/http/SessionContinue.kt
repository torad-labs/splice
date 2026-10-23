// NEW: Oct 10, 2026 (step 3, Continue on) — moving one session onto another command, by its own id.
//
//   POST /api/sessions/{id}/continue   {"head": "<key>", "model": "<id>"}   go on under that command's model
//
// WHAT A MOVE IS. The session goes on under another head: Claude Code is ended where it runs and started again on the
// chosen command with `-r <id>`, and that launch's cross-head resume makes a copy of the session beside it under a new
// id, with its rows on the head's model (ResumeAcrossHeads, TranscriptModelRewrite), recording the joint the page
// draws (ModelMoves). The original is left as it was (the President's ruling on V4-169, Oct 13). A move between heads
// is that explicit `-r` copy and nothing else (head isolation); the answer names the copy's id, which the page opens.
// A move to another model on the same command, whose rows all stay, goes on as the same session. Nothing is sent for
// the person: his next message runs on the new model (the mock, hitstop).
//
// WHAT IS KEPT. A move that came back is handed to [MoveLedger] with what it left, the command and the model it ran on
// before he pressed, and that moment, so the transcript's mark reads "Continued on [to] · from [from]" (fin, hitstop).
//
// WHERE IT GOES ON. In the terminal it ran in. The client is ended with its own `/exit`, never by killing the
// terminal: a session he started from his own shell leaves that shell in front, and the resume is typed there as he
// would type it. A terminal splice opened closes with its client, so the resume opens a new one. A session whose
// client already exited opens a new one too.
//
// WHAT IS REFUSED. A session waiting on an answer: `/exit` would land in the question. One already on that command
// with no other model picked; another model on the same command is a move like any other (p176: his Opus week is
// spent and Fable on the same plan has room).
// One splice cannot reach (the refusals SessionDrive gives). One whose prompt holds words he has not sent: `/exit`
// would be sent with them. A working turn is stopped first, as Stop would, and once it has stopped the prompt is
// emptied, because Claude Code puts the stopped message back in it and that message is already in the transcript.
// One already moving, pressed again from another tab or the manager's head: both would plan against the one client
// and end it, and the later resume would be typed into the client the earlier one started. The press is answered 409
// before anything is read or typed, and the move in flight goes on alone (PR #247's second review).
// The route waits for the client to exit and for the session to register on the new command, and answers:
//   200  it runs on the new command: its id, the command, and what a person types to sit in front of it.
//   400  no command named.   404  no such session.   409  refused, and why.
//   502  the terminal did not take it, or the new command did not start.   503  no terminal.
//   504  it did not exit, or did not come back in time.
// A move that did not come back never leaves the agent ended: it goes on where it was, on the command and model it
// left, and the answer says so (MoveArrival).
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
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
private const val MOVE_IN_FLIGHT = "it is already moving; wait for that move to answer"

public class SessionContinue(
    private val drive: SessionDrive,
    private val commands: StartCommands,
    private val handover: SessionHandover,
    private val registry: SessionSource?,
    private val home: Path,
    /** Where a move that came back is kept, with what it left, read before it leaves, and which session has a move in
     *  flight: one ledger under every Continue on, so a session moves once at a time from any of them. */
    private val ledger: MoveLedger,
) {
    /** The client's own words for a turn in flight. */
    private val busy = setOf("working", "busy")

    /** Where a move ends: back on the command he picked, or on the one it left. */
    private val arrival = MoveArrival(commands, handover, ledger, registry)

    /** POST .../continue's body: the command (`head`) and, optionally, the model on it. */
    public suspend fun moveJson(session: String, body: String): JsonReply {
        val obj = JsonScalars.objectOrNull(Json, body)
        val head = obj?.let { JsonScalars.str(it, "head") }?.takeIf(String::isNotBlank)
        if (obj == null || head == null) {
            return Refusals.reply(HttpStatusCode.BadRequest, "name the command to continue on")
        }
        val clear = JsonScalars.str(obj, "clear") == "true"
        return move(session, head, JsonScalars.str(obj, "model")?.takeIf(String::isNotBlank), clear)
    }

    public suspend fun move(session: String, head: String, model: String?, clear: Boolean = false): JsonReply {
        // what the move decides on, counted before anything reads the session: an act landing after it stops the move
        val ask = Ask(clear, drive.turns.acts(session))
        // the whole move is this press's alone, from its plan to its last word: a second plans nothing, types nothing
        if (!ledger.opens(session)) return Refusals.reply(HttpStatusCode.Conflict, MOVE_IN_FLIGHT)
        return try {
            when (val plan = plan(session, head, model)) {
                is Plan.Refused -> plan.reply
                is Plan.Go -> go(plan.move, ask)
            }
        } finally {
            // a reply that did not come from the arrival left the grant and the pending mark open; the move closes too
            ledger.dropped(session)
        }
    }

    /** What the press asked beyond the move: whether to empty the prompt, and the session's act count as it came. */
    private data class Ask(val clear: Boolean, val seen: Long)

    /** A move ready to make, or the reply that refuses it. */
    private sealed class Plan {
        class Refused(val reply: JsonReply) : Plan()

        class Go(val move: Move) : Plan()
    }

    private fun plan(session: String, head: String, model: String?): Plan {
        val record = registry?.read().orEmpty().firstOrNull { it.sessionId == session }
            ?: return Plan.Refused(Refusals.reply(HttpStatusCode.NotFound, "no such session: $session"))
        refusalToMove(record, head, model)?.let { return Plan.Refused(it) }
        return when (val command = commands.forHead(head)) {
            is StartCommand.Refused -> Plan.Refused(Refusals.reply(HttpStatusCode.Conflict, command.reason))
            is StartCommand.Ready -> {
                val resume = listOf("-r", session) + model?.let { listOf("--model", it) }.orEmpty()
                val running = record.availability != SessionAvailability.GONE
                val folder = record.process.cwd?.takeIf { Files.isDirectory(Path.of(it)) } ?: home.toString()
                ledger.leaving(record, head, model)
                Plan.Go(
                    Move(
                        session = session,
                        head = head,
                        from = record.head ?: head,
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
    private fun refusalToMove(record: SessionRecord, head: String, model: String?): JsonReply? = when {
        record.head == head && model == null ->
            Refusals.reply(HttpStatusCode.Conflict, "it already runs on $head; pick another model on it")
        record.availability != SessionAvailability.GONE && record.status.waitingFor != null ->
            Refusals.reply(HttpStatusCode.Conflict, "it is waiting on an answer; answer it before moving it")
        else -> null
    }

    /** A client still running is ended in its pane first; one already gone goes on in a new terminal. */
    private suspend fun go(move: Move, ask: Ask): JsonReply {
        val pid = move.pid ?: return openNew(move)
        val driving = drive.driver() ?: return unwired()
        val at = drive.locate(driving, move.session, MOVE_NOT_OURS, MOVE_CLOSED)
        val pane = at.pane
        return if (pane == null) requireNotNull(at.refusal) else handOver(driving, pane, pid, move, ask)
    }

    /** End the client in [pane] with its own exit, and resume once it has gone. A turn in flight is stopped first,
     *  and the stopped message Claude Code puts back, with anything he typed while it ran, is his: it is answered back
     *  to him, and emptied only on his say-so, never to get the move through (Marlin, move check 4). The prompt is read
     *  and `/exit` typed in the pane's turn, refused when anything was given to the session since the press came or
     *  it runs a turn the move did not stop, and nothing else is typed there until the client has left: a message
     *  that landed after `/exit` would reach the shell it leaves in front. A client that does not leave in time keeps
     *  the pane marked (PR #247's reviews). */
    private suspend fun handOver(
        driving: SessionDriver,
        pane: SessionPane,
        pid: Long,
        move: Move,
        ask: Ask,
    ): JsonReply {
        if (move.working) stopTurn(driving, pane, move, ask)?.let { return it }
        val exit = object : PaneTurns.Exit {
            override val pid: Long = pid

            override fun check(): JsonReply? = changed(move, ask) ?: when {
                // working now, on a turn the move did not stop: one the person started in the terminal itself, which
                // splice's count does not see. A turn the move stopped already reads stopped here (RegistryHandover).
                registry?.read().orEmpty().any { it.sessionId == move.session && it.status.state in busy } ->
                    Refusals.reply(
                        HttpStatusCode.Conflict,
                        "it started working while the move waited its turn, so nothing was started on ${move.head}",
                        Refusal.WORKING,
                    )
                ask.clear -> null
                else -> drive.drafted(move.session)
            }

            override fun keys(): JsonReply? = Cancellables.runCatchingCleanup {
                if (ask.clear) driving.terminal.press(pane, SessionKey.CLEAR)
                driving.terminal.send(pane, EXIT_COMMAND)
            }.exceptionOrNull()?.let(::refusedByTerminal)

            override suspend fun left(): Boolean = handover.left(move.session, pid, EXIT_SECONDS)

            override fun stayed(): JsonReply = Refusals.reply(
                HttpStatusCode.GatewayTimeout,
                "its client did not exit within ${EXIT_SECONDS}s, so nothing was started on ${move.head}",
            )
        }
        return drive.turns.leave(pane, move.session, again(driving, move), exit) ?: resume(driving, pane, move)
    }

    /** Stop the turn in flight, its key typed in the pane's turn once nothing [changed]; null once it has stopped. A
     *  turn given to the session after the press came is not the one the move decided to stop (sixth review). */
    private suspend fun stopTurn(driving: SessionDriver, pane: SessionPane, move: Move, ask: Ask): JsonReply? {
        drive.turns.ending(pane, move.session, again(driving, move)) {
            changed(move, ask) ?: Cancellables.runCatchingCleanup { driving.terminal.press(pane, SessionKey.STOP) }
                .exceptionOrNull()?.let(::refusedByTerminal)
        }?.let { return it }
        if (!handover.stopped(move.session, STOP_SECONDS)) {
            return Refusals.reply(
                HttpStatusCode.GatewayTimeout,
                "its turn did not stop within ${STOP_SECONDS}s, so nothing was started on ${move.head}",
            )
        }
        return null
    }

    /** Why the move stops in its pane's turn before a key goes, or null: anything given to the session since the
     *  press came, or a client process other than [Move.pid], the one the move read, such as the same session
     *  resumed in that pane meanwhile. The Stop, the `/exit`, the pane's mark and the wait all name that process,
     *  so another is refused rather than typed to or waited on (PR #247's sixth review). */
    private fun changed(move: Move, ask: Ask): JsonReply? = when {
        drive.turns.acts(move.session) != ask.seen -> Refusals.reply(
            HttpStatusCode.Conflict,
            "it was given something while the move waited its turn, so nothing was started on ${move.head}",
            Refusal.WORKING,
        )
        registry?.read().orEmpty().firstOrNull { it.sessionId == move.session }?.process?.pid != move.pid ->
            Refusals.reply(
                HttpStatusCode.Conflict,
                "its client was started again while the move waited its turn, so nothing was started on ${move.head}",
                Refusal.PANE_TAKEN,
            )
        else -> null
    }

    /** [move]'s session located anew, as its pane's turn checks it before a key goes. */
    private fun again(driving: SessionDriver, move: Move): PaneTurns.Relocate =
        PaneTurns.Relocate { drive.locate(driving, move.session, MOVE_NOT_OURS, MOVE_CLOSED) }

    /** The resume where the person is: his own shell when one is left in front, else a new terminal. */
    private suspend fun resume(driving: SessionDriver, pane: SessionPane, move: Move): JsonReply {
        driving.panes.forget(move.session)
        if (!driving.terminal.isOpen(pane)) return openNew(move, beside = pane)
        // his own shell is in front again: the resume is typed there, the way he would type it
        val line = arrival.line(move.argv)
        val failure = Cancellables.runCatchingCleanup { driving.terminal.send(pane, line) }.exceptionOrNull()
        return if (failure != null) refusedByTerminal(failure) else arrival.await(driving, Attempt(pane, false), move)
    }

    private suspend fun openNew(move: Move, beside: SessionPane? = null): JsonReply {
        val driving = drive.driver() ?: return unwired()
        val pane = Cancellables.runCatchingCleanup {
            driving.terminal.open(move.session, move.argv, move.folder, beside)
        }.getOrElse { return refusedByTerminal(it) }
        driving.panes.remember(move.session, pane)
        return arrival.await(driving, Attempt(pane, true), move)
    }

    private fun unwired(): JsonReply =
        Refusals.reply(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE, Refusal.NO_TERMINAL)

    private fun refusedByTerminal(failure: Throwable): JsonReply = Refusals.reply(
        HttpStatusCode.BadGateway,
        "the terminal did not take it: " + SafeFailureText.render(failure),
        Refusal.REFUSED,
    )
}
