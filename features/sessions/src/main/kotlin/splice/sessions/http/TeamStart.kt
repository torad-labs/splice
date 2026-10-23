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
//   200  the member is up, with an empty body: the page reads its session off the team.
//   404  no such team or slot.   409  nothing started, and the reason (archived, already running, not launchable).
//   502  the terminal would not open.   503  there is no terminal to start it in.
//   202  the terminal opened and Claude Code is asking something before it registers (its folder trust question, a
//        first-run prompt), also with an empty body. The slot stays bound and the pane stays open; the page
//        reads the question off .../screen and answers it on his click only, through .../answer. splice never answers
//        it for him (Marlin, Oct 12). A start asked again for that slot then waits on the SAME pane, its 45 s clock
//        starting over, and a pane his answer closed (No, exit) gives the slot back.
//   504  the terminal opened and Claude Code did not register in time, with nothing asked on its screen.
// A failure to come up leaves the slot as it was: the binding is put back and the terminal closed, so a slot is never
// left on Starting. The 504 carries the screen's last lines, because the reason is usually on it (a folder to trust,
// a sign-in to finish), and splice does not answer those for the person.
//
// ONE START AT A TIME PER SLOT. Starts run concurrently (the route's blocking-I/O dispatcher), so a start holds its
// slot from its first read to its answer, and a second start asked meanwhile is told the slot is already starting
// rather than opening a second client the slot cannot hold. The hold is in memory only, so a daemon that stops never
// leaves a slot on Starting. The bind and the give-back are each made only while the slot still holds what this start
// left in it, so a binding made meanwhile by hand is never overwritten.
//
// THE TERMINAL IS THE CONTRACT'S (SessionTerminal): no terminal product is named here. A session splice did not open
// has no pane and is refused on stop, never typed into (SessionPanes).
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.session.SessionPane
import splice.core.util.Cancellables
import splice.http.JsonReply
import splice.sessions.prompt.ScreenChoices
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// why: a cold Claude Code on a slow disk, or one that stops at a first-run prompt, shows itself within this long;
// past it the screen is the answer, and a person is never left looking at Starting.
internal const val ARRIVAL_SECONDS = 45L

// why: enough of the bottom of the screen to read the question a prompt asks, and not a page of scrollback.
internal const val SCREEN_TAIL_LINES = 12
private const val NO_TERMINAL = "splice has no terminal to start sessions in yet"

/** The member a request names, or the reply that says there is none. */
private sealed class Located {
    class Member(val team: Team, val slot: TeamSlot) : Located()

    class Missing(val reply: JsonReply) : Located()
}

public class TeamStart(
    private val teams: TeamSource,
    /** The one drive every session's pane is reached through (SessionsWiring.drive), launch records included, so a
     *  member Teams did not start, launched from his own tmux, answers and stops the way it does on Sessions. */
    private val drive: SessionDrive,
    private val commands: StartCommands,
    private val pins: AccountPins,
    private val arrival: SessionArrival,
    /** The listing that keeps the sessions Claude Code forgot (RecordedSessions), not the raw registry: Claude Code
     *  deletes its registration on a clean exit, and only a recorded session tells a member that came up and ended
     *  from one that never registered. */
    private val registry: SessionSource?,
    private val home: Path,
) {
    /** How a screen is read for a question asked before the session registers: the drive's own reading. */
    private val choices = ScreenChoices()

    /** The slots a start is under way for, by team id and slot id (the header's ONE START AT A TIME PER SLOT). */
    private val starting: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    public suspend fun start(teamId: String, slotId: String): JsonReply {
        val store = teams() ?: return Refusals.reply(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        val held = teamId to slotId
        if (!starting.add(held)) return Refusals.reply(HttpStatusCode.Conflict, "$slotId is already starting")
        return try {
            when (val member = locate(store, teamId, slotId)) {
                is Located.Missing -> member.reply
                is Located.Member -> pending(
                    store,
                    member,
                ) ?: refusalToStart(member.team, member.slot) ?: launchable(store, member)
            }
        } finally {
            starting.remove(held)
        }
    }

    private suspend fun launchable(store: TeamStore, member: Located.Member): JsonReply {
        val driving = drive.driver() ?: return Refusals.reply(HttpStatusCode.ServiceUnavailable, NO_TERMINAL)
        return when (val command = commands.forHead(member.slot.head)) {
            is StartCommand.Refused -> Refusals.reply(HttpStatusCode.Conflict, command.reason)
            is StartCommand.Ready -> launch(store, driving, member, command.argv)
        }
    }

    public fun stop(teamId: String, slotId: String): JsonReply = onSession(teamId, slotId) { drive.stop(it) }

    /** Answer the question the member is waiting on as the person would; [label], the words the page drew, must
     *  still be that choice's on the screen (SessionDrive.answer). */
    public fun answer(teamId: String, slotId: String, choice: Int, label: String? = null): JsonReply =
        onSession(teamId, slotId) { drive.answer(it, choice, label) }

    /** [act] on the member's session, or the reply that says there is no such member or it holds no session. */
    private inline fun onSession(teamId: String, slotId: String, act: (String) -> JsonReply): JsonReply {
        val store = teams() ?: return Refusals.reply(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        return when (val member = locate(store, teamId, slotId)) {
            is Located.Missing -> member.reply
            is Located.Member ->
                member.slot.session?.let(act)
                    ?: Refusals.reply(HttpStatusCode.Conflict, "${member.slot.id} has no session", Refusal.ENDED)
        }
    }

    private fun locate(store: TeamStore, teamId: String, slotId: String): Located {
        // a teams.json that does not parse is no "no such team": the sentence says which file to fix
        val unreadable = store.unreadable()
        val team = store.team(teamId)
        val slot = team?.slots?.firstOrNull { it.id == slotId }
        return when {
            unreadable != null -> Located.Missing(Refusals.reply(HttpStatusCode.InternalServerError, unreadable))
            team == null -> Located.Missing(Refusals.reply(HttpStatusCode.NotFound, "$NO_SUCH_TEAM$teamId"))
            slot == null -> Located.Missing(Refusals.reply(HttpStatusCode.NotFound, "no such slot in $teamId: $slotId"))
            else -> Located.Member(team, slot)
        }
    }

    /** Why this slot cannot be started now, or null when it can. */
    private fun refusalToStart(team: Team, slot: TeamSlot): JsonReply? = when {
        team.archived -> Refusals.reply(HttpStatusCode.Conflict, "${team.name} is archived; restore it before starting")
        slot.session != null && live(slot.session) ->
            Refusals.reply(HttpStatusCode.Conflict, "${slot.id} already has a running session")
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
            return Refusals.reply(HttpStatusCode.Conflict, "${slot.head} has no account named ${slot.account}")
        }
        val bound = store.bind(team.id, mapOf(slot.id to session), held = mapOf(slot.id to slot.session))
        if (bound.slots.none { it.id == slot.id && it.session == session }) {
            return Refusals.reply(HttpStatusCode.Conflict, "${slot.id} was bound to another session while it started")
        }
        // The flags that make the member a member: the id splice minted, the slot's own name, and its model.
        val flags = listOf("--session-id", session, "--name", slot.id) +
            slot.model?.let { listOf("--model", it) }.orEmpty()
        // Any failure to open must hand the slot back, so the cleanup variant: it widens the captured set past
        // I/O to the IllegalStateException a half-built terminal throws, and still lets cancellation through.
        return Cancellables.runCatchingCleanup {
            driving.terminal.open(session, argv + flags, folder)
        }.fold(
            onSuccess = { pane ->
                driving.panes.remember(session, pane)
                awaitUp(store, driving, member, session, pane)
            },
            onFailure = {
                giveBack(store, member, session)
                Refusals.reply(HttpStatusCode.BadGateway, "the terminal would not open: ${it.message}")
            },
        )
    }

    /** A slot whose start stopped on a question: its session never registered (a member that came up and later ended
     *  starts afresh, and the recorded listing still names it as ended) and the pane splice opened for it is still
     *  remembered. Asked again, the start waits on that pane; a pane his answer closed gives the slot back. Null for
     *  every other slot, which then starts or is refused as before. */
    private suspend fun pending(store: TeamStore, member: Located.Member): JsonReply? {
        val driving = drive.driver() ?: return null
        val (session, pane) = member.slot.session?.takeIf { s -> registry?.read().orEmpty().none { it.sessionId == s } }
            ?.let { s -> driving.panes.paneFor(s)?.let { s to it } } ?: return null
        return if (driving.terminal.isOpen(pane)) {
            awaitUp(store, driving, member, session, pane)
        } else {
            driving.panes.forget(session)
            store.bind(member.team.id, mapOf(member.slot.id to null), held = mapOf(member.slot.id to session))
            Refusals.reply(
                HttpStatusCode.Conflict,
                "${member.slot.id} did not start: its terminal closed",
                Refusal.CLOSED,
            )
        }
    }

    /** Waits up to [ARRIVAL_SECONDS] for the session to register, a second at a time, reading the screen between: a
     *  question on it before the session is up is his to answer, so the start says so and leaves the pane open. */
    private suspend fun awaitUp(
        store: TeamStore,
        driving: SessionDriver,
        member: Located.Member,
        session: String,
        pane: SessionPane,
    ): JsonReply {
        repeat(ARRIVAL_SECONDS.toInt()) {
            if (arrival.arrived(session, 1)) return JsonReply(HttpStatusCode.OK, "{}")
            val asked = Cancellables.runCatchingCleanup { choices.on(driving.terminal.screen(pane)).offering }
            if (asked.getOrDefault(false)) {
                return JsonReply(HttpStatusCode.Accepted, "{}")
            }
        }
        return didNotComeUp(store, driving, member, session, pane)
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
        giveBack(store, member, session)
        val said = "${member.slot.id} did not start: Claude Code had not registered after ${ARRIVAL_SECONDS}s"
        return JsonReply(
            HttpStatusCode.GatewayTimeout,
            buildJsonObject {
                put("error", said + closing)
                put("screen", screen)
            }.toString(),
        )
    }

    /** The slot as it was before the start: bound to what it held, or open. Only while it still holds [session], the
     *  one this start bound: a binding made since is kept. */
    private fun giveBack(store: TeamStore, member: Located.Member, session: String) {
        val slot = member.slot.id
        store.bind(member.team.id, mapOf(slot to member.slot.session), held = mapOf(slot to session))
    }
}
