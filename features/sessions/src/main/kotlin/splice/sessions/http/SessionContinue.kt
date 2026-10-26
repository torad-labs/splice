// NEW: Oct 10, 2026 (step 3, Continue on) — moving one session onto another command, by its own id.
//
//   POST /api/sessions/{id}/continue   {"head": "<key>", "model": "<id>"}   go on under that command's model
//   POST /api/sessions/{id}/relaunch                                       go on as it was, launched anew
//
// A RELAUNCH (key rotation, Oct 26, 2026) brings a running session back AS IT WAS: on the command it was started with,
// read back from its own process, so it stays in its own config folder and signed in, with its agent and every other
// option it was given and never its first prompt, on the model its row reports, and in the pane it ran in. A session
// launched before the key helper holds a turn key a rotated key strands, and the launch its own command makes reads the
// new one through the helper. It is splice's, never his, so it never stops a turn.
//
// The relaunch of Oct 26 was a move onto each session's HEAD command in a NEW terminal: two sessions started as
// `claude` came back "Not logged in" in the head's config folder, reviews came back without their agent, and nobody
// could find the seats it moved. What a session was started with is splice's to read ([SessionRelaunches]), and the
// pane it ran in is kept so it comes back there (SessionTerminal.hold, restart, release).
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
// would type it. A terminal splice opened closes with its client, so a move's resume opens a new one. A session whose
// client already exited opens a new one too. A RELAUNCH opens none: its pane is held before its client leaves, so the
// pane outlives the client, and the session is started again in that same pane, in the same terminal session and
// window its starter and the person find it by. The pane is given back its own end however the relaunch ends, and a
// pane the terminal cannot keep refuses the relaunch before anything is typed.
//
// WHAT IS REFUSED. A session waiting on an answer: `/exit` would land in the question. One running background shells,
// at its prompt or once the move stopped its turn: `/exit` would land in "Background work is running", whose focused
// answer stops that work (PR #247's fifth review). One already on that command
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
private const val RELAUNCH_WORKING = "it is working; relaunch it once its turn ends"
private const val RELAUNCH_BACKGROUND = "it has background work running; relaunch it once that work ends"
private const val MOVE_BACKGROUND =
    "it has background work running, which its /exit would stop; move it once that work ends"

// why: Claude Code 2.1.310's word for a client idle at its prompt with background shells running, which its own session
// list counts as working. Its /exit answers "Background work is running" with "Exit and stop tasks" focused.
private const val RUNNING_SHELLS = "shell"
private const val RELAUNCH_NO_HEAD = "it runs on no splice command, so there is none to relaunch it on"
private const val RELAUNCH_NONE = "this Continue on relaunches nothing; relaunch it from Sessions"
private const val RELAUNCH_UNHELD =
    "its pane could not be kept open, so it would not come back where it runs; nothing was ended"

/** The handover a relaunch makes while the pane it comes back in is kept: the client ended with its own exit, and the
 *  session started again in that pane. */
private fun interface HandOverHeld {
    suspend operator fun invoke(): JsonReply
}

/** A Continue on that only moves sessions, such as the manager's: it relaunches none. */
private object NoRelaunches : SessionRelaunches {
    override fun model(record: SessionRecord): String? = null

    override fun command(record: SessionRecord, model: String?): StartCommand = StartCommand.Refused(RELAUNCH_NONE)
}

public class SessionContinue(
    private val drive: SessionDrive,
    private val commands: StartCommands,
    private val handover: SessionHandover,
    private val registry: SessionSource?,
    private val home: Path,
    /** Where a move that came back is kept, with what it left, read before it leaves, and which session has a move in
     *  flight: one ledger under every Continue on, so a session moves once at a time from any of them. */
    private val ledger: MoveLedger,
    /** The model and the command each running session was started with: what a relaunch starts it on again. */
    private val relaunches: SessionRelaunches = NoRelaunches,
) {
    /** The client's own words for a turn in flight. */
    private val busy = setOf("working", "busy")

    /** Where a move ends: back on the command he picked, or on the one it left. */
    private val arrival = MoveArrival(commands, handover, ledger, registry)

    /** Where a relaunch happens: in the pane the session already runs in. */
    private val inPlace = InPlace()

    private val plans = Plans()

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

    /** POST .../relaunch: the running session brought back as it was. It is ended where it runs and started again in
     *  that same pane, on the command it was started with, read back from its own process, so it stays in its own
     *  config folder and signed in, with its agent and every other option it was given and never its first prompt, and
     *  on the model its row reports. Key rotation (Oct 26, 2026): a session launched before the key helper holds a
     *  turn key a rotation strands, and the launch its own command makes reads the new one through the helper. A
     *  relaunch is splice's, never his, so it stops nothing: a working session is refused until its turn ends, one
     *  running background shells until they end, and one waiting on an answer or holding words he has not sent is
     *  refused as a move is. */
    public suspend fun relaunch(session: String): JsonReply = moving(session, clear = false, Wanted.Relaunch)

    public suspend fun move(session: String, head: String, model: String?, clear: Boolean = false): JsonReply =
        moving(session, clear, Wanted.Onto(head, model))

    /** The move [wanted] asks for, planned once this press holds [session]'s move alone. */
    private suspend fun moving(session: String, clear: Boolean, wanted: Wanted): JsonReply {
        // what the move decides on, counted before anything reads the session: an act landing after it stops the move
        val ask = Ask(clear, drive.turns.acts(session))
        // the whole move is this press's alone, from its plan to its last word: a second plans nothing, types nothing
        if (!ledger.opens(session)) return Refusals.reply(HttpStatusCode.Conflict, MOVE_IN_FLIGHT)
        return try {
            val planned = when (wanted) {
                is Wanted.Onto -> plans.onto(session, wanted.head, wanted.model)
                Wanted.Relaunch -> plans.relaunch(session)
            }
            when (planned) {
                is Plan.Refused -> planned.reply
                is Plan.Go -> go(planned.move, ask)
            }
        } finally {
            // a reply that did not come from the arrival left the grant and the pending mark open; the move closes too
            ledger.dropped(session)
        }
    }

    /** What a press asks for: the session onto another command or model, or launched again as it runs. */
    private sealed class Wanted {
        class Onto(val head: String, val model: String?) : Wanted()

        data object Relaunch : Wanted()
    }

    /** What the press asked beyond the move: whether to empty the prompt, and the session's act count as it came. */
    private data class Ask(val clear: Boolean, val seen: Long)

    /** A move ready to make, or the reply that refuses it. */
    private sealed class Plan {
        class Refused(val reply: JsonReply) : Plan()

        class Go(val move: Move) : Plan()
    }

    /** What a press decides before anything is read from its pane or typed: the move it makes, or why not now. */
    private inner class Plans {
        /** Continue on: [session], running or exited, onto [head] and [model]. */
        fun onto(session: String, head: String, model: String?): Plan {
            val record = live(session, orExited = true)
                ?: return Plan.Refused(Refusals.reply(HttpStatusCode.NotFound, "no such session: $session"))
            if (record.head == head && model == null) {
                return refused("it already runs on $head; pick another model on it")
            }
            return planOnto(session, record, head, model)
        }

        /** A relaunch: [session]'s running client, started again as it was started, only between its turns. */
        fun relaunch(session: String): Plan {
            val record = live(session)
            val head = record?.head
            return when {
                record == null -> Plan.Refused(Refusals.reply(HttpStatusCode.NotFound, "no running session: $session"))
                head == null -> refused(RELAUNCH_NO_HEAD, Refusal.HEAD_UNKNOWN)
                record.status.state in busy -> refused(RELAUNCH_WORKING, Refusal.WORKING)
                // its /exit would ask him whether to stop that work, and a relaunch stops nothing of his
                record.status.state == RUNNING_SHELLS -> refused(RELAUNCH_BACKGROUND, Refusal.WORKING)
                else -> asItWas(session, record, head)
            }
        }

        /** [record] started again on the command it was started with and the model its row reports, in the pane it
         *  runs in, or why not now: its client cannot be ended now ([notNow]), or splice cannot read that command
         *  back, and then nothing is ended, since there would be nothing to start. */
        private fun asItWas(session: String, record: SessionRecord, head: String): Plan {
            notNow(record)?.let { return it }
            val model = relaunches.model(record)
            return when (val command = relaunches.command(record, model)) {
                is StartCommand.Refused -> refused(command.reason)
                is StartCommand.Ready -> {
                    ledger.leaving(record, head, model)
                    Plan.Go(
                        Move(
                            session = session,
                            head = head,
                            from = head,
                            argv = command.argv,
                            folder = folder(record),
                            // a relaunch waits for his turn to end, so the client it ends runs none
                            leaving = Leaving(record.process, working = false),
                            relaunch = true,
                        ),
                    )
                }
            }
        }

        /** [record] moved onto [head] and [model], or why not now: its client cannot be ended now ([notNow]), or the
         *  command cannot start. */
        private fun planOnto(session: String, record: SessionRecord, head: String, model: String?): Plan {
            notNow(record)?.let { return it }
            val running = record.availability != SessionAvailability.GONE
            return when (val command = commands.forHead(head)) {
                is StartCommand.Refused -> refused(command.reason)
                is StartCommand.Ready -> {
                    val resume = listOf("-r", session) + model?.let { listOf("--model", it) }.orEmpty()
                    ledger.leaving(record, head, model)
                    Plan.Go(
                        Move(
                            session = session,
                            head = head,
                            from = record.head ?: head,
                            argv = command.argv + resume,
                            folder = folder(record),
                            leaving = record.process.takeIf { running }
                                ?.let { Leaving(it, record.status.state in busy) },
                        ),
                    )
                }
            }
        }

        /** The folder [record] runs in, or the daemon's own home when that folder is gone. */
        private fun folder(record: SessionRecord): String =
            record.process.cwd?.takeIf { Files.isDirectory(Path.of(it)) } ?: home.toString()

        /** Why a running [record]'s client cannot be ended now, or null: its `/exit` would land in a question, about
         *  an answer it waits on or about the background shells it runs, whose focused answer stops them. */
        private fun notNow(record: SessionRecord): Plan? = when {
            record.availability == SessionAvailability.GONE -> null
            record.status.waitingFor != null -> refused("it is waiting on an answer; answer it before moving it")
            record.status.state == RUNNING_SHELLS -> refused(MOVE_BACKGROUND, Refusal.WORKING)
            else -> null
        }

        private fun refused(sentence: String, reason: Refusal? = null): Plan =
            Plan.Refused(Refusals.reply(HttpStatusCode.Conflict, sentence, reason))
    }

    /** A client still running is ended in its pane first; one already gone goes on in a new terminal. A relaunch holds
     *  the pane around the whole handover, so the pane outlives the client and the session comes back in it. */
    private suspend fun go(move: Move, ask: Ask): JsonReply {
        val pid = move.pid ?: return openNew(move)
        val driving = drive.driver() ?: return unwired()
        val at = drive.locate(driving, move.session, MOVE_NOT_OURS, MOVE_CLOSED)
        val pane = at.pane
        return when {
            pane == null -> requireNotNull(at.refusal)
            move.relaunch -> inPlace.held(driving, pane) { handOver(driving, pane, pid, move, ask) }
            else -> handOver(driving, pane, pid, move, ask)
        }
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
            override val process = move.process

            override fun check(): JsonReply? = changed(move, ask) ?: when (live(move.session)?.status?.state) {
                // working now, on a turn the move did not stop: one the person started in the terminal itself, which
                // splice's count does not see. A turn the move stopped already reads stopped here (RegistryHandover).
                in busy -> Refusals.reply(
                    HttpStatusCode.Conflict,
                    "it started working while the move waited its turn, so nothing was started on ${move.head}",
                    Refusal.WORKING,
                )
                // background shells a stopped turn left running, or ones started since: /exit would stop them
                RUNNING_SHELLS -> Refusals.reply(
                    HttpStatusCode.Conflict,
                    "it has background work its /exit would stop, so nothing was started on ${move.head}",
                    Refusal.WORKING,
                )
                else -> if (ask.clear) null else drive.drafted(move.session)
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
     *  press came, or a running client other than [Move.process], the one the move read, such as the same session
     *  resumed in that pane meanwhile, even under the same pid. The Stop, the `/exit`, the pane's mark and the wait
     *  all name that process, so another is refused rather than typed to or waited on (PR #247's sixth and seventh
     *  reviews). */
    private fun changed(move: Move, ask: Ask): JsonReply? = when {
        drive.turns.acts(move.session) != ask.seen -> Refusals.reply(
            HttpStatusCode.Conflict,
            "it was given something while the move waited its turn, so nothing was started on ${move.head}",
            Refusal.WORKING,
        )
        move.process?.sameAs(live(move.session)?.process) != true ->
            Refusals.reply(
                HttpStatusCode.Conflict,
                "its client was started again while the move waited its turn, so nothing was started on ${move.head}",
                Refusal.PANE_TAKEN,
            )
        else -> null
    }

    /** [session]'s running registration, the one its pane is located by, never an exited one listed beside it
     *  (PR #247's seventh review). With [orExited], the exited one when none runs, which a move plans from and takes
     *  into a new terminal. */
    private fun live(session: String, orExited: Boolean = false): SessionRecord? {
        val records = registry?.read().orEmpty().filter { it.sessionId == session }
        return records.firstOrNull { it.availability != SessionAvailability.GONE }
            ?: records.firstOrNull()?.takeIf { orExited }
    }

    /** [move]'s session located anew, as its pane's turn checks it before a key goes. */
    private fun again(driving: SessionDriver, move: Move): PaneTurns.Relocate =
        PaneTurns.Relocate { drive.locate(driving, move.session, MOVE_NOT_OURS, MOVE_CLOSED) }

    /** The resume where the person is: the pane the session ran in for a relaunch, his own shell when one is left in
     *  front, else a new terminal. */
    private suspend fun resume(driving: SessionDriver, pane: SessionPane, move: Move): JsonReply {
        driving.panes.forget(move.session)
        if (move.relaunch) inPlace.restartIn(driving, pane, move)?.let { return it }
        if (!driving.terminal.isOpen(pane)) return openNew(move, beside = pane)
        // his own shell is in front again: the resume is typed there, the way he would type it
        val line = arrival.line(move.argv)
        val failure = Cancellables.runCatchingCleanup { driving.terminal.send(pane, line) }.exceptionOrNull()
        return if (failure != null) {
            refusedByTerminal(failure)
        } else {
            arrival.await(driving, Attempt(pane, Ran.TYPED), move)
        }
    }

    private suspend fun openNew(move: Move, beside: SessionPane? = null): JsonReply {
        val driving = drive.driver() ?: return unwired()
        val pane = Cancellables.runCatchingCleanup {
            driving.terminal.open(move.session, move.argv, move.folder, beside)
        }.getOrElse { return refusedByTerminal(it) }
        driving.panes.remember(move.session, pane)
        return arrival.await(driving, Attempt(pane, Ran.OPENED), move)
    }

    /** A relaunch in the pane the session runs in (Oct 26, 2026): the pane kept so it outlives the client that leaves,
     *  and the session started again there, in the same terminal session, window and pane its starter and the person
     *  find it by. The relaunch of Oct 26 opened a new terminal for every seat it brought back, and nobody could find
     *  them again. */
    private inner class InPlace {
        /** [then] with [pane] kept, so the client's own exit does not take the pane with it, and the pane given back
         *  its own end however [then] ends. A pane the terminal cannot keep refuses the relaunch before its client is
         *  told to leave: the session would come back in a terminal nobody is looking at, which is the whole of what
         *  the relaunch is for. */
        suspend fun held(driving: SessionDriver, pane: SessionPane, then: HandOverHeld): JsonReply {
            val kept = Cancellables.runCatchingCleanup { driving.terminal.hold(pane) }
                .getOrElse { return refusedByTerminal(it) }
            if (!kept) return Refusals.reply(HttpStatusCode.BadGateway, RELAUNCH_UNHELD, Refusal.REFUSED)
            try {
                return then()
            } finally {
                Cancellables.discard(
                    Cancellables.runCatchingCleanup { driving.terminal.release(pane) },
                    "the relaunch has already answered, and a pane that could not be given back is still his own: " +
                        "it keeps the end it was held with until it is closed",
                )
            }
        }

        /** The session started again in [pane], where it ran, and the wait for it to come back there. Null while
         *  something still runs in the pane, his own shell among them, so the resume is typed there instead; a new
         *  terminal beside it when the pane itself is gone, since a session with no terminal has no agent. */
        suspend fun restartIn(driving: SessionDriver, pane: SessionPane, move: Move): JsonReply? {
            val restarted = Cancellables.runCatchingCleanup {
                driving.terminal.restart(pane, move.argv, move.folder)
            }.getOrElse { return openNew(move, beside = pane) } ?: return null
            driving.panes.remember(move.session, restarted)
            return arrival.await(driving, Attempt(restarted, Ran.RESTARTED), move)
        }
    }

    private fun unwired(): JsonReply =
        Refusals.reply(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE, Refusal.NO_TERMINAL)

    private fun refusedByTerminal(failure: Throwable): JsonReply = Refusals.reply(
        HttpStatusCode.BadGateway,
        "the terminal did not take it: " + SafeFailureText.render(failure),
        Refusal.REFUSED,
    )
}

/** The model a session's row reported, read at the moment Continue on or a relaunch was asked for, before it ends
 *  the session's client. */
public fun interface MovingSessionModel {
    public operator fun invoke(record: SessionRecord): String?
}

/** How a running session is started again as it was (a relaunch, Oct 26, 2026): the model its row reports, and the
 *  command it was started with, read back from its own process. */
public interface SessionRelaunches {
    /** The model [record]'s row reports it runs on, read before its client ends: what it goes on with. */
    public fun model(record: SessionRecord): String?

    /** The argv that starts [record]'s session again as it was started, resumed by its id on [model] when known, or
     *  why splice cannot start it that way. */
    public fun command(record: SessionRecord, model: String?): StartCommand
}
