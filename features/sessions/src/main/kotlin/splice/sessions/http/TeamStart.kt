// NEW: Oct 10, 2026 (BUILD.md first row, Teams: start its members) — starting and stopping a team member's session.
//
//   POST /api/teams/{id}/slots/{slot}/start   open the slot's command in the team's folder; answer when it is up
//   POST /api/teams/{id}/slots/{slot}/stop    stop the turn the member is running (Escape), nothing else
//   POST /api/teams/{id}/slots/{slot}/answer  press the numbered choice the member is waiting on, nothing else
//
// ANSWERING. The question itself is a READ the console already has: /api/sessions carries each session's last
// transcript line, and an AskUserQuestion call comes with the question and its option labels (AskedQuestions,
// V4-444). So answering is only the key press the person would make, 1 to 9 as the SCREEN lists them — splice
// keeps no list of what this client version offers (SessionTerminal's note on [screen]).
//
// START. Claude Code takes the session id from the caller (`--session-id`), so splice MINTS it and binds the slot to
// it before the terminal opens: the member's first request already finds its role, the team's goal and the slot's
// instructions (SlotInstructions), and a send to the member by its slot name reaches it (`--name <slot>`). A slot with
// an account has that account pinned to the new session before it opens, so the first turn is already on it. The
// route then waits for Claude Code to register that id ([SessionArrival], with a deadline) and answers one of:
//   200  the member is up: its session id, and what a person types to sit in front of it.
//   404  no such team or slot.   409  nothing started, and the reason (archived, already running, not launchable).
//   502  the terminal would not open.   503  there is no terminal to start it in.
//   504  the terminal opened and Claude Code did not register in time.
// A failure to come up leaves the slot as it was: the binding is put back and the terminal closed, so a slot is never
// left on Starting. The 504 carries the screen's last lines, because the reason is usually on it (a folder to trust,
// a sign-in to finish), and splice does not answer those for the person.
//
// THE TERMINAL IS THE CONTRACT'S (SessionTerminal): no terminal product is named here. A session splice did not open
// has no pane and is refused on stop, never typed into (SessionPanes).
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.session.SessionPane
import splice.core.session.SessionPanes
import splice.core.session.SessionTerminal
import splice.core.util.Cancellables
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** A terminal and the record of which of its panes carries which session: two halves of one capability. */
public data class SessionDriver(val terminal: SessionTerminal, val panes: SessionPanes)

/** The daemon's session driver, read per request: null until one is wired. */
public fun interface TerminalSource {
    public operator fun invoke(): SessionDriver?
}

/** What a head's launch command is, or why it cannot be launched. */
public sealed class StartCommand {
    /** The argv that starts the head's Claude Code, before the flags a member adds. */
    public data class Ready(val argv: List<String>) : StartCommand()

    /** The sentence the person reads, including the fix when there is one. */
    public data class Refused(val reason: String) : StartCommand()
}

/** The launch command of a head, by its key. */
public fun interface StartCommands {
    public fun forHead(head: String): StartCommand
}

/** Pins one session to one account of its head's pool; false when the head has no such account. */
public fun interface AccountPins {
    public fun pin(head: String, label: String, session: String): Boolean
}

/** Whether a session Claude Code was started with registers itself within [seconds]. */
public fun interface SessionArrival {
    public suspend fun arrived(session: String, seconds: Long): Boolean
}

// why: a cold Claude Code on a slow disk, or one that stops at a first-run prompt, shows itself within this long;
// past it the screen is the answer, and a person is never left looking at Starting.
private const val ARRIVAL_SECONDS = 45L

// why: enough of the bottom of the screen to read the question a prompt asks, and not a page of scrollback.
private const val SCREEN_TAIL_LINES = 12
private const val NO_TERMINAL = "splice has no terminal to start sessions in yet"

/** The member a request names, or the reply that says there is none. */
private sealed class Located {
    class Member(val team: Team, val slot: TeamSlot) : Located()

    class Missing(val reply: JsonReply) : Located()
}

public class TeamStart(
    private val teams: TeamSource,
    private val driver: TerminalSource,
    private val commands: StartCommands,
    private val pins: AccountPins,
    private val arrival: SessionArrival,
    private val registry: SessionSource?,
    private val home: Path,
) {
    public suspend fun start(teamId: String, slotId: String): JsonReply {
        val store = teams() ?: return refuse(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        return when (val member = locate(store, teamId, slotId)) {
            is Located.Missing -> member.reply
            is Located.Member -> refusalToStart(member.team, member.slot) ?: launchable(store, member)
        }
    }

    private suspend fun launchable(store: TeamStore, member: Located.Member): JsonReply {
        val driving = driver() ?: return refuse(HttpStatusCode.ServiceUnavailable, NO_TERMINAL)
        return when (val command = commands.forHead(member.slot.head)) {
            is StartCommand.Refused -> refuse(HttpStatusCode.Conflict, command.reason)
            is StartCommand.Ready -> launch(store, driving, member, command.argv)
        }
    }

    /** The member's session drives the same pane, with the same refusals, as it does on Sessions (SessionDrive). */
    private val drive = SessionDrive(driver)

    public fun stop(teamId: String, slotId: String): JsonReply = onSession(teamId, slotId) { drive.stop(it) }

    /** Answer the question the member is waiting on by pressing its numbered choice, as the person would. */
    public fun answer(teamId: String, slotId: String, choice: Int): JsonReply =
        onSession(teamId, slotId) { drive.answer(it, choice) }

    /** [act] on the member's session, or the reply that says there is no such member or it holds no session. */
    private inline fun onSession(teamId: String, slotId: String, act: (String) -> JsonReply): JsonReply {
        val store = teams() ?: return refuse(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        return when (val member = locate(store, teamId, slotId)) {
            is Located.Missing -> member.reply
            is Located.Member ->
                member.slot.session?.let(act) ?: refuse(HttpStatusCode.Conflict, "${member.slot.id} has no session")
        }
    }

    private fun locate(store: TeamStore, teamId: String, slotId: String): Located {
        val team = store.team(teamId)
            ?: return Located.Missing(refuse(HttpStatusCode.NotFound, "$NO_SUCH_TEAM$teamId"))
        val slot = team.slots.firstOrNull { it.id == slotId }
            ?: return Located.Missing(refuse(HttpStatusCode.NotFound, "no such slot in $teamId: $slotId"))
        return Located.Member(team, slot)
    }

    /** Why this slot cannot be started now, or null when it can. */
    private fun refusalToStart(team: Team, slot: TeamSlot): JsonReply? = when {
        team.archived -> refuse(HttpStatusCode.Conflict, "${team.name} is archived; restore it before starting")
        slot.session != null && live(slot.session) ->
            refuse(HttpStatusCode.Conflict, "${slot.id} already has a running session")
        else -> null
    }

    private fun live(session: String): Boolean =
        registry?.read().orEmpty().any { it.sessionId == session && it.availability == SessionAvailability.LIVE }

    private suspend fun launch(
        store: TeamStore,
        driving: SessionDriver,
        member: Located.Member,
        argv: List<String>,
    ): JsonReply {
        val (team, slot) = member.team to member.slot
        val session = UUID.randomUUID().toString()
        val folder = team.repo.takeIf { it.isNotBlank() && Files.isDirectory(Path.of(it)) } ?: home.toString()
        if (slot.account != null && !pins.pin(slot.head, slot.account, session)) {
            return refuse(HttpStatusCode.Conflict, "${slot.head} has no account named ${slot.account}")
        }
        store.bind(team.id, mapOf(slot.id to session))
        // The flags that make the member a member: the id splice minted, the slot's own name, and its model.
        val flags = listOf("--session-id", session, "--name", slot.id) +
            slot.model?.let { listOf("--model", it) }.orEmpty()
        // Any failure to open must hand the slot back, so the cleanup variant: it widens the captured set past
        // I/O to the IllegalStateException a half-built terminal throws, and still lets cancellation through.
        return Cancellables.runCatchingCleanup {
            driving.terminal.open(session, argv + flags, folder)
        }.fold(
            onSuccess = { pane -> opened(store, driving, member, session, pane) },
            onFailure = {
                giveBack(store, member)
                refuse(HttpStatusCode.BadGateway, "the terminal would not open: ${it.message}")
            },
        )
    }

    private suspend fun opened(
        store: TeamStore,
        driving: SessionDriver,
        member: Located.Member,
        session: String,
        pane: SessionPane,
    ): JsonReply {
        driving.panes.remember(session, pane)
        if (!arrival.arrived(session, ARRIVAL_SECONDS)) return didNotComeUp(store, driving, member, session, pane)
        return JsonReply(
            HttpStatusCode.OK,
            buildJsonObject {
                put("session_id", session)
                put("how_to_open", driving.terminal.howToOpen(pane))
            }.toString(),
        )
    }

    private fun didNotComeUp(
        store: TeamStore,
        driving: SessionDriver,
        member: Located.Member,
        session: String,
        pane: SessionPane,
    ): JsonReply {
        // The bottom of the screen, blank lines dropped: the question a prompt is holding on is the last thing on it.
        val screen = Cancellables.runCatchingCleanup { driving.terminal.screen(pane) }
            .fold(
                { it.lines().filter(String::isNotBlank).takeLast(SCREEN_TAIL_LINES).joinToString("\n") },
                { "(the screen could not be read: ${it.message})" },
            )
        val closing = Cancellables.runCatchingCleanup { driving.terminal.close(pane) }
            .fold({ "" }, { "; its terminal would not close: ${it.message}" })
        driving.panes.forget(session)
        giveBack(store, member)
        val said = "${member.slot.id} did not start: Claude Code had not registered after ${ARRIVAL_SECONDS}s"
        return JsonReply(
            HttpStatusCode.GatewayTimeout,
            buildJsonObject {
                put("error", said + closing)
                put("screen", screen)
            }.toString(),
        )
    }

    /** The slot as it was before the start: bound to what it held, or open. */
    private fun giveBack(store: TeamStore, member: Located.Member) {
        store.bind(member.team.id, mapOf(member.slot.id to member.slot.session))
    }

    private fun refuse(status: HttpStatusCode, sentence: String) =
        JsonReply(status, buildJsonObject { put("error", sentence) }.toString())
}
