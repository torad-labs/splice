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

    /** Wait for [move] to come back in [attempt]; when it does not, the session goes on where it was. */
    suspend fun await(driving: SessionDriver, attempt: Attempt, move: Move): JsonReply {
        val missed = missed(driving, attempt.pane, move.session, move.head, move.pid)
        if (missed != null) return back(driving, attempt, move, missed)
        ledger?.arrived(move.session)
        return JsonReply(
            HttpStatusCode.OK,
            buildJsonObject {
                put("session_id", move.session)
                put("head", move.head)
            }.toString(),
        )
    }

    /** Null once [session] runs on [head], or under a new client in [pane]: a launch that could not record its
     *  command leaves the registry no head to name, and it is still the move. Else why not: the terminal closed, or
     *  time ran out. */
    private suspend fun missed(
        driving: SessionDriver,
        pane: SessionPane,
        session: String,
        head: String,
        left: Long?,
    ): String? {
        var waited = 0L
        while (waited < ARRIVAL_SECONDS) {
            if (handover.cameOn(session, head, ARRIVAL_SLICE_SECONDS) || upIn(driving, pane, session, left)) return null
            if (!driving.terminal.isOpen(pane)) return MISSED_NO_START
            waited += ARRIVAL_SLICE_SECONDS
        }
        return MISSED_NO_ANSWER
    }

    /** Whether [session] runs in [pane] under a client other than the one the move ended ([left]). */
    private fun upIn(driving: SessionDriver, pane: SessionPane, session: String, left: Long?): Boolean =
        registry?.read().orEmpty().any { record ->
            val pid = record.process.pid
            record.sessionId == session && record.availability == SessionAvailability.LIVE &&
                pid != null && pid != left && driving.terminal.hosts(pane, pid)
        }

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
                put("head", move.from)
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
            ?.takeIf { missed(driving, it, move.session, move.from, move.pid) == null }
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

    /** The bottom of the attempt's screen: the question a first-run prompt holds on is the last thing on it. */
    private suspend fun tail(driving: SessionDriver, pane: SessionPane): String =
        Cancellables.runCatchingCleanup {
            if (driving.terminal.isOpen(pane)) driving.terminal.screen(pane) else ""
        }.getOrDefault("").lines().filter(String::isNotBlank).takeLast(SCREEN_TAIL_LINES).joinToString("\n")

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

    /** [session] came back on the command it left for: the move read at [leaving] is kept. */
    public fun arrived(session: String)

    /** [session] did not come back: the move read at [leaving] is dropped, and the model it left is answered, so it
     *  goes on where it was on that model (null when its row named none). */
    public fun failed(session: String): String?
}
