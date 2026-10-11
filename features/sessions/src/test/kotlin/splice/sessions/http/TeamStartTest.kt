// NEW: Oct 10, 2026 — starting a team member, stopping its turn, and answering what it is waiting on. A start is
// judged by what it leaves behind: the slot bound to a session Claude Code was started with, the terminal given the
// member's command in the team's folder, and, when the member never comes up, the slot exactly as it was with its
// terminal closed and the screen in the reply. A key is only ever pressed in a pane splice opened itself.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.session.SessionPanes
import splice.core.session.SessionTerminal
import splice.http.JsonReply
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import java.io.IOException
import java.nio.file.Path

internal data class Opened(val session: String, val command: List<String>, val directory: String)

/** A terminal that records what it is asked, and shows [screenText] when read. */
internal class FakeTerminal(var openFails: Boolean = false, var screenText: String = "") : SessionTerminal {
    val opened = mutableListOf<Opened>()
    val pressed = mutableListOf<Pair<SessionPane, SessionKey>>()
    val sent = mutableListOf<Pair<SessionPane, String>>()
    var sendFails = false
    val closed = mutableListOf<SessionPane>()
    var live = true

    /** The pane each open was asked to sit beside, in order: where a moved session's new terminal goes. */
    val besides = mutableListOf<SessionPane?>()

    override fun open(sessionId: String, command: List<String>, directory: String, beside: SessionPane?): SessionPane {
        if (openFails) throw IOException("no server is running")
        opened += Opened(sessionId, command, directory)
        besides += beside
        return SessionPane("%$sessionId")
    }

    override fun send(pane: SessionPane, text: String) {
        if (sendFails) throw IOException("can't find pane")
        sent += pane to text
    }

    override fun press(pane: SessionPane, key: SessionKey) {
        pressed += pane to key
    }

    override fun screen(pane: SessionPane): String = screenText

    override fun close(pane: SessionPane) {
        closed += pane
    }

    override fun howToOpen(pane: SessionPane): String = "attach ${pane.id}"

    override fun isOpen(pane: SessionPane): Boolean = live

    /** The pids each pane has in front, as the person's terminal would show them. */
    val inFront = mutableMapOf<SessionPane, Long>()

    override fun recorded(pane: String, server: String): SessionPane = SessionPane("$pane@$server")

    override fun hosts(pane: SessionPane, pid: Long): Boolean = inFront[pane] == pid
}

internal class FakePanes : SessionPanes {
    val known = mutableMapOf<String, SessionPane>()

    override fun paneFor(sessionId: String): SessionPane? = known[sessionId]

    override fun remember(sessionId: String, pane: SessionPane) {
        known[sessionId] = pane
    }

    override fun forget(sessionId: String) {
        known.remove(sessionId)
    }
}

class TeamStartTest {

    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }
    private val terminal = FakeTerminal()
    private val panes = FakePanes()
    private var arrives = true
    private val pinned = mutableListOf<Triple<String, String, String>>()
    private var accountExists = true
    private var command: StartCommand = StartCommand.Ready(listOf("claudex"))

    private fun start(on: TeamRig = rig, wired: Boolean = true) = TeamStart(
        teams = TeamSource { on.store },
        drive = SessionDrive(TerminalSource { SessionDriver(terminal, panes).takeIf { wired } }),
        commands = StartCommands { command },
        pins = AccountPins { head, label, session ->
            pinned += Triple(head, label, session)
            accountExists
        },
        arrival = SessionArrival { _, _ -> arrives },
        registry = rig.registry,
        home = tmp.resolve("home"),
    )

    private fun team(archived: Boolean = false): Team {
        val made = rig.store.upsert(
            Team(
                name = "atlas",
                goal = "ship",
                repo = rig.repo.toString(),
                slots = listOf(
                    TeamSlot(id = "lead", role = "orchestrator", head = "claude", lead = true),
                    TeamSlot(id = "b1", role = "builder", head = "codex", model = "gpt-6-sol", account = "Primary"),
                ),
            ),
        )
        return if (archived) rig.store.archive(made.id) else made
    }

    private fun slot(id: String) = rig.store.teams().single().slots.single { it.id == id }

    private fun error(reply: JsonReply) = rig.json(reply.body).getValue("error").jsonPrimitive.content

    @Test
    fun `a start opens the member's command in the team's folder under a session splice named, bound to the slot`() =
        runBlocking {
            val id = team().id
            val reply = start().start(id, "b1")
            assertEquals(HttpStatusCode.OK, reply.status, reply.body)
            val session = rig.json(reply.body).getValue("session_id").jsonPrimitive.content
            val opened = terminal.opened.single()
            assertEquals(session, opened.session)
            assertEquals(
                listOf("claudex", "--session-id", session, "--name", "b1", "--model", "gpt-6-sol"),
                opened.command,
            )
            assertEquals(rig.repo.toString(), opened.directory)
            assertEquals(session, slot("b1").session, "bound before the terminal opened")
            assertEquals(listOf(Triple("codex", "Primary", session)), pinned)
            assertEquals(SessionPane("%$session"), panes.paneFor(session))
            assertEquals("attach %$session", rig.json(reply.body).getValue("how_to_open").jsonPrimitive.content)
        }

    @Test
    fun `a member that never registers leaves the slot as it was, its terminal closed and its screen in the reply`() =
        runBlocking {
            val id = team().id
            terminal.screenText = "\n\nDo you trust the files in this folder?\n  1. Yes\n  2. No\n"
            arrives = false
            val reply = start().start(id, "b1")
            assertEquals(HttpStatusCode.GatewayTimeout, reply.status)
            assertTrue(error(reply).startsWith("b1 did not start"), error(reply))
            assertEquals(
                "Do you trust the files in this folder?\n  1. Yes\n  2. No",
                rig.json(reply.body).getValue("screen").jsonPrimitive.content,
            )
            assertNull(slot("b1").session, "the slot is open again, not stuck on a session that never came")
            assertEquals(1, terminal.closed.size)
            assertNull(panes.paneFor(terminal.opened.single().session))
        }

    @Test
    fun `a terminal that will not open leaves the slot as it was`() = runBlocking {
        val id = team().id
        terminal.openFails = true
        val reply = start().start(id, "b1")
        assertEquals(HttpStatusCode.BadGateway, reply.status)
        assertTrue(error(reply).contains("no server is running"), error(reply))
        assertNull(slot("b1").session)
    }

    @Test
    fun `no terminal, a refused command or an unknown account starts nothing and binds nothing`() =
        runBlocking {
            val id = team().id
            assertEquals(HttpStatusCode.ServiceUnavailable, start(wired = false).start(id, "b1").status)
            command = StartCommand.Refused("The claudex command is not linked; run splice install codex.")
            val refused = start().start(id, "b1")
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals("The claudex command is not linked; run splice install codex.", error(refused))
            command = StartCommand.Ready(listOf("claudex"))
            accountExists = false
            val noAccount = start().start(id, "b1")
            assertEquals(HttpStatusCode.Conflict, noAccount.status)
            assertEquals("codex has no account named Primary", error(noAccount))
            assertNull(slot("b1").session)
            assertTrue(terminal.opened.isEmpty())
        }

    @Test
    fun `an archived team, a member already running, and a name that is not there are each refused by name`() =
        runBlocking {
            val archived = team(archived = true)
            assertEquals(HttpStatusCode.Conflict, start().start(archived.id, "b1").status)
            val unknownTeam = start().start("team-nope", "b1")
            assertEquals(HttpStatusCode.NotFound, unknownTeam.status)
            val unknownSlot = start().start(archived.id, "nobody")
            assertEquals("no such slot in ${archived.id}: nobody", error(unknownSlot))
            assertTrue(terminal.opened.isEmpty())
        }

    @Test
    fun `a member whose session is live is not started a second time`() = runBlocking {
        val id = team().id
        rig.store.bind(id, mapOf("lead" to LEAD))
        val reply = start().start(id, "lead")
        assertEquals(HttpStatusCode.Conflict, reply.status)
        assertEquals("lead already has a running session", error(reply))
        assertEquals(LEAD, slot("lead").session)
        assertTrue(terminal.opened.isEmpty())
    }

    @Test
    fun `stop presses the stop key in the terminal splice opened, and refuses a session it did not open`() =
        runBlocking {
            val id = team().id
            val session = rig.json(start().start(id, "b1").body).getValue("session_id").jsonPrimitive.content
            val stopped = start().stop(id, "b1")
            assertEquals(HttpStatusCode.OK, stopped.status)
            assertEquals(listOf(SessionPane("%$session") to SessionKey.STOP), terminal.pressed)

            rig.store.bind(id, mapOf("lead" to LEAD))
            val foreign = start().stop(id, "lead")
            assertEquals(HttpStatusCode.Conflict, foreign.status)
            assertTrue(error(foreign).startsWith("splice did not start this session"), error(foreign))
            assertEquals(1, terminal.pressed.size, "nothing was typed into a pane splice does not know")

            terminal.live = false
            assertEquals(HttpStatusCode.Conflict, start().stop(id, "b1").status)
            assertEquals(HttpStatusCode.Conflict, start().stop(id, "lead").status)
            assertNotNull(slot("lead").session)
        }

    @Test
    fun `an answer presses the choice the person picked, in that member's own pane`() =
        runBlocking {
            val id = team().id
            val session = rig.json(start().start(id, "b1").body).getValue("session_id").jsonPrimitive.content
            val answered = start().answer(id, "b1", 3)
            assertEquals(HttpStatusCode.OK, answered.status, answered.body)
            assertEquals(session, rig.json(answered.body).getValue("session_id").jsonPrimitive.content)
            assertEquals(listOf(SessionPane("%$session") to SessionKey.CHOICE_3), terminal.pressed)
        }

    @Test
    fun `an answer that is not one of the numbered choices presses nothing and says what a choice is`() =
        runBlocking {
            val id = team().id
            start().start(id, "b1")
            for (asked in listOf(0, -1, 10)) {
                val reply = start().answer(id, "b1", asked)
                assertEquals(HttpStatusCode.BadRequest, reply.status, "$asked")
                assertEquals("a choice is one of the numbered options, 1 to 9", error(reply))
            }
            assertTrue(terminal.pressed.isEmpty())
        }

    @Test
    fun `an answer is refused for a session splice did not open, and for a terminal that is gone`() =
        runBlocking {
            val id = team().id
            start().start(id, "b1")
            rig.store.bind(id, mapOf("lead" to LEAD))
            val foreign = start().answer(id, "lead", 1)
            assertEquals(HttpStatusCode.Conflict, foreign.status)
            assertTrue(error(foreign).startsWith("splice did not start this session"), error(foreign))

            terminal.live = false
            val closed = start().answer(id, "b1", 1)
            assertEquals(HttpStatusCode.Conflict, closed.status)
            assertEquals("its terminal is closed, so nothing is being asked", error(closed))
            assertTrue(terminal.pressed.isEmpty(), "nothing was typed into a pane splice cannot drive")
        }
}
