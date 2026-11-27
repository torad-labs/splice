package splice.sessions.http

import io.ktor.http.HttpStatusCode
import splice.core.session.SessionPane
import splice.core.util.Cancellables
import splice.http.JsonReply
import splice.sessions.registry.SessionRecord

private const val RELAUNCH_NONE = "this Continue on relaunches nothing; relaunch it from Sessions"
private const val RELAUNCH_UNHELD =
    "its pane could not be kept open, so it would not come back where it runs; nothing was ended"

/** How a running session is started again as it was: the model its row reports and the command it was started with. */
public interface SessionRelaunches {
    /** The model [record]'s row reports it runs on, read before its client ends: what it goes on with. */
    public fun model(record: SessionRecord): String?

    /** The argv that starts [record]'s session again as it was started, resumed by its id on [model] when known, or
     *  why splice cannot start it that way. */
    public fun command(record: SessionRecord, model: String?): StartCommand
}

/** A Continue on that only moves sessions, such as the manager's: it relaunches none. */
internal object NoRelaunches : SessionRelaunches {
    override fun model(record: SessionRecord): String? = null

    override fun command(record: SessionRecord, model: String?): StartCommand = StartCommand.Refused(RELAUNCH_NONE)
}

/** Keeps a relaunch in the session's existing pane while its client is replaced. */
internal class SessionInPlaceRelaunch(
    private val arrival: MoveArrival,
    private val refusedByTerminal: TerminalRefusal,
    private val openNew: OpenFallback,
) {
    fun interface TerminalRefusal {
        fun reply(failure: Throwable): JsonReply
    }

    fun interface OpenFallback {
        suspend fun open(move: Move, beside: SessionPane?): JsonReply
    }

    fun interface HeldWork {
        suspend fun run(): JsonReply
    }

    /** Run [then] with [pane] kept, so a relaunch can return where it already ran. */
    suspend fun held(driving: SessionDriver, pane: SessionPane, then: HeldWork): JsonReply {
        val kept = Cancellables.runCatchingCleanup { driving.terminal.hold(pane) }
            .getOrElse { return refusedByTerminal.reply(it) }
        if (!kept) return Refusals.reply(HttpStatusCode.BadGateway, RELAUNCH_UNHELD, Refusal.REFUSED)
        try {
            return then.run()
        } finally {
            Cancellables.discard(
                Cancellables.runCatchingCleanup { driving.terminal.release(pane) },
                "the relaunch has already answered, and a pane that could not be given back is still his own: " +
                    "it keeps the end it was held with until it is closed",
            )
        }
    }

    /** Restart [move] in [pane], or let its existing shell or a new terminal carry the resume. */
    suspend fun restartIn(driving: SessionDriver, pane: SessionPane, move: Move): JsonReply? {
        val restarted = Cancellables.runCatchingCleanup {
            driving.terminal.restart(pane, move.argv, move.folder)
        }.getOrElse { return openNew.open(move, pane) } ?: return null
        driving.panes.remember(move.session, restarted)
        return arrival.await(driving, Attempt(restarted, Ran.RESTARTED), move)
    }
}
