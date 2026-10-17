// NEW: Oct 11, 2026 (Continue on, Marlin's live-settings-6 verdict) — the end of a move: the session either comes back on
// the command he picked, or goes on where it was. A move ends the old client before the new one has to prove itself,
// so a move that did not come back has already taken the agent away; this is where it is given back. "They asked to
// keep going and lost the agent" is the one outcome a move must never have.
//
// ARRIVING. The session registers on the new command within ARRIVAL_SECONDS, or its terminal closes first: a command
// that exits at once (it could not start) is known the moment its terminal goes, and nobody waits out the deadline.
//
// GOING BACK. What the attempt left is closed: a terminal splice opened for it is closed, and an attempt typed into his
// own shell is interrupted there, so it can never come up later beside the session it gave back. Then the session is
// resumed on the command it left, by its own id and on the model it ran on, where the attempt ran: in his shell, typed
// as he would type it, or in a new terminal among the ones it ran in. The answer says the move did not happen, why
// ("no_start": it could not start; "no_answer": it did not come back in time), whether it went on where it was, the
// last lines of the attempt's terminal, and the line he would type to resume it himself.
//
// THE LEDGER (Oct 11, the transcript's move mark): where Continue on keeps a move that came back: the command and model
// it went on, the command and model it left, and the moment he pressed, so the mark reads "Continued on [to] · from
// [from]" (fin, hitstop f38853e). The daemon's ledger keeps them beside the move its launch's rewrite recorded.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import java.nio.file.Path

// why: the registry is read once a second while a move waits; arrival answers within a second of the client's
// registration, and a closed terminal is noticed as soon.
private const val ARRIVAL_SLICE_SECONDS = 1L
private const val MISSED_NO_START = "no_start"
private const val MISSED_NO_ANSWER = "no_answer"

/** A move SessionContinue decided: the session, the command it goes to and the one it left, the argv that resumes it
 *  there, the folder it runs in, and the client to end first (its pid, null when it already exited). */
internal data class Move(
    val session: String,
    val head: String,
    val from: String,
    val argv: List<String>,
    val folder: String,
    val pid: Long?,
    val working: Boolean,
)

/** How an attempt ended: the session that came up (the moved one, or the launch's copy of it), or why none did. */
private sealed class Landing {
    data class Up(val session: String) : Landing()

    data class Missed(val reason: String) : Landing()
}

/** The terminal an attempt runs in, and whether splice opened it for the attempt (else it is his own shell). */
internal data class Attempt(val pane: SessionPane, val opened: Boolean)

internal class MoveArrival(
    private val commands: StartCommands,
    private val handover: SessionHandover,
    private val ledger: MoveLedger?,
    /** Who runs which session now: a client that came up in the attempt's own terminal is the move arriving. */
    private val registry: SessionSource?,
) {
    /** One argv word a POSIX shell reads back as itself without quoting. */
    private val plainWord = Regex("[A-Za-z0-9._/:=@%+,-]+")

    /** The argv as a line he would type: each word as a POSIX shell reads it back. */
    fun line(argv: List<String>): String = argv.joinToString(" ", transform = ::shellWord)

    /** Wait for [move] to come back in [attempt]; when it does not, the session goes on where it was. A move between
     *  heads goes on as the launch's copy of the session, under its own id (ResumeAcrossHeads, the President's ruling
     *  on V4-169), and the answer names that id so the page follows it. */
    suspend fun await(driving: SessionDriver, attempt: Attempt, move: Move): JsonReply {
        val arrived = when (val landing = land(driving, attempt.pane, move.session, move.head, move.pid)) {
            is Landing.Missed -> return back(driving, attempt, move, landing.reason)
            is Landing.Up -> landing.session
        }
        if (arrived != move.session) driving.panes.remember(arrived, attempt.pane)
        ledger?.arrived(move.session, arrived)
        val body = if (arrived == move.session) "{}" else buildJsonObject { put("session_id", arrived) }.toString()
        return JsonReply(HttpStatusCode.OK, body)
    }

    /** Up once [session] runs on [head], or a new client runs in [pane]: a launch that could not record its command
     *  leaves the registry no head to name, and one that resumed a copy names the copy; either is the move. Else why
     *  not: the terminal closed, or time ran out. */
    private suspend fun land(
        driving: SessionDriver,
        pane: SessionPane,
        session: String,
        head: String,
        left: Long?,
    ): Landing {
        var waited = 0L
        var landing: Landing? = null
        while (landing == null && waited < ARRIVAL_SECONDS) {
            landing = if (handover.cameOn(session, head, ARRIVAL_SLICE_SECONDS)) {
                Landing.Up(session)
            } else {
                upIn(driving, pane, left)?.let { Landing.Up(it) }
                    ?: Landing.Missed(MISSED_NO_START).takeUnless { driving.terminal.isOpen(pane) }
            }
            waited += ARRIVAL_SLICE_SECONDS
        }
        return landing ?: Landing.Missed(MISSED_NO_ANSWER)
    }

    /** The session a client other than the one the move ended ([left]) runs live in [pane], or null. */
    private fun upIn(driving: SessionDriver, pane: SessionPane, left: Long?): String? =
        registry?.read().orEmpty().firstOrNull { record ->
            val pid = record.process.pid
            record.sessionId != null && record.availability == SessionAvailability.LIVE &&
                pid != null && pid != left && driving.terminal.hosts(pane, pid)
        }?.sessionId

    /** Close the attempt and resume the session on the command it left, when it was running before the move. */
    private suspend fun back(driving: SessionDriver, attempt: Attempt, move: Move, missed: String): JsonReply {
        val screen = tail(driving, attempt.pane)
        val resume = listOf("-r", move.session) + ledger?.failed(move.session)?.let { listOf("--model", it) }.orEmpty()
        val backOn = wayBack(driving, attempt, move, resume)
        return JsonReply(
            if (missed == MISSED_NO_START) HttpStatusCode.BadGateway else HttpStatusCode.GatewayTimeout,
            buildJsonObject {
                put("error", sentence(move, missed, backOn != null))
                put("reason", missed)
                if (missed == MISSED_NO_ANSWER) put("waited_s", ARRIVAL_SECONDS)
                put("moved", false)
                put("back", backOn != null)
                put("resume", "cd " + shellWord(move.folder) + " && " + line(listOf(commandName(move)) + resume))
                if (screen.isNotEmpty()) put("screen", screen)
            }.toString(),
        )
    }

    /** The pane the session went on in, on the command it left; null when it was not running or did not come up. */
    private suspend fun wayBack(
        driving: SessionDriver,
        attempt: Attempt,
        move: Move,
        resume: List<String>,
    ): SessionPane? {
        val ready = commands.forHead(move.from) as? StartCommand.Ready
        if (ready == null || move.pid == null) return null
        return reopen(driving, attempt, move, ready.argv + resume)
            ?.takeIf { land(driving, it, move.session, move.from, move.pid) is Landing.Up }
    }

    /** The command he types to resume it: the name the command is linked as, else its head's key. */
    private fun commandName(move: Move): String =
        (commands.forHead(move.from) as? StartCommand.Ready)?.argv?.firstOrNull()
            ?.let { Path.of(it).fileName.toString() }
            ?: move.from

    /** End what the attempt left and run [argv] where it ran: the pane it runs in, or null if the terminal refused. */
    private suspend fun reopen(driving: SessionDriver, attempt: Attempt, move: Move, argv: List<String>): SessionPane? =
        Cancellables.runCatchingCleanup {
            if (attempt.opened) {
                driving.terminal.close(attempt.pane)
                driving.panes.forget(move.session)
                driving.terminal.open(move.session, argv, move.folder, attempt.pane)
                    .also { driving.panes.remember(move.session, it) }
            } else {
                driving.terminal.press(attempt.pane, SessionKey.INTERRUPT)
                driving.terminal.send(attempt.pane, line(argv))
                attempt.pane
            }
        }.getOrNull()

    /** The bottom of the attempt's screen: the question a first-run prompt holds on is the last thing on it. A screen
     *  that could not be read says so in its place, as a team start's does (TeamStart), never as no screen at all. */
    private suspend fun tail(driving: SessionDriver, pane: SessionPane): String =
        Cancellables.runCatchingCleanup {
            if (driving.terminal.isOpen(pane)) driving.terminal.screen(pane) else ""
        }.fold(
            { it.lines().filter(String::isNotBlank).takeLast(SCREEN_TAIL_LINES).joinToString("\n") },
            { "(the screen could not be read: ${SafeFailureText.render(it)})" },
        )

    private fun sentence(move: Move, missed: String, wentBack: Boolean): String {
        val why = if (missed == MISSED_NO_START) {
            "${move.head} did not start"
        } else {
            "it had not come back on ${move.head} after ${ARRIVAL_SECONDS}s"
        }
        val where = when {
            move.pid == null -> ""
            wentBack -> ", so it went on where it was, on ${move.from}"
            else -> ", and it could not be resumed on ${move.from}"
        }
        return why + where
    }

    /** One argv word as a POSIX shell reads it back: as it is when plainly safe, single-quoted otherwise. */
    private fun shellWord(word: String): String = if (plainWord.matches(word)) {
        word
    } else {
        "'" + word.replace("'", "'\\''") + "'"
    }
}

public interface MoveLedger {
    /** [record] is about to move onto command [to] and [model]: what it leaves is read now, before it leaves. */
    public fun leaving(record: SessionRecord, to: String, model: String?)

    /** [session] came back on the command it left for, as [arrivedAs] (itself, or the launch's copy of it when the
     *  move crossed heads): the move read at [leaving] is kept on the session that came back. */
    public fun arrived(session: String, arrivedAs: String = session)

    /** [session] did not come back: the move read at [leaving] is dropped, and the model it left is answered, so it
     *  goes on where it was on that model (null when its row named none). */
    public fun failed(session: String): String?
}
