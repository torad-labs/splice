// NEW: Oct 10, 2026 — Continue on: a session is ended where it runs and resumed by its own id on the command he picked.
// A move is judged by what the person's terminal is given: /exit for the client, then the resume, in his own shell
// when one is left in front and in a new terminal when the old one closed with its client. Nothing is started on top
// of a client that would not exit, and a session waiting on an answer is never typed into.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import java.nio.file.Path

class SessionContinueTest {
    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }
    private val terminal = FakeTerminal()
    private val panes = FakePanes()
    private var exits = true

    /** A terminal splice opened runs the client alone and closes with it; his own shell stays in front. */
    private var closesWithClient = true
    private val cameOn = mutableListOf<Pair<String, String>>()

    private val continueOn by lazy {
        SessionContinue(
            drive = SessionDrive(TerminalSource { SessionDriver(terminal, panes) }),
            commands = StartCommands { head -> StartCommand.Ready(listOf("claude-$head")) },
            handover = object : SessionHandover {
                override suspend fun left(session: String, pid: Long, seconds: Long): Boolean = exits.also {
                    if (it && closesWithClient) terminal.live = false
                }

                override suspend fun cameOn(session: String, head: String, seconds: Long): Boolean {
                    cameOn += session to head
                    return true
                }
            },
            registry = rig.registry,
            home = tmp.resolve("home"),
        )
    }

    private val leadPane = SessionPane("%lead")

    private fun move(session: String, body: String) = runBlocking { continueOn.moveJson(session, body) }

    @Test
    fun `a session splice opened exits there and comes back on the chosen command in a new terminal`() {
        panes.remember(LEAD, leadPane)

        val reply = move(LEAD, """{"head":"codex","model":"gpt-6-sol"}""")

        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(listOf(leadPane to "/exit"), terminal.sent)
        val reopened = terminal.opened.single()
        assertEquals(listOf("claude-codex", "-r", LEAD, "--model", "gpt-6-sol"), reopened.command)
        assertEquals(rig.repo.toString(), reopened.directory, "it goes on in the folder it ran in")
        assertEquals(listOf(LEAD to "codex"), cameOn)
        assertEquals("codex", rig.json(reply.body).getValue("head").jsonPrimitive.content)
    }

    @Test
    fun `a session started from his own shell is resumed in that shell, typed as he would type it`() {
        panes.remember(LEAD, leadPane)
        closesWithClient = false

        val reply = runBlocking { continueOn.move(LEAD, "codex", "it's/odd") }

        assertEquals(HttpStatusCode.OK, reply.status)
        assertTrue(terminal.opened.isEmpty(), "no new terminal: he keeps the one he is in")
        assertEquals(
            listOf(leadPane to "/exit", leadPane to "claude-codex -r $LEAD --model 'it'\\''s/odd'"),
            terminal.sent,
        )
    }

    @Test
    fun `nothing is started on top of a client that would not exit`() {
        panes.remember(LEAD, leadPane)
        exits = false

        val reply = move(LEAD, """{"head":"codex"}""")

        assertEquals(HttpStatusCode.GatewayTimeout, reply.status)
        assertTrue(terminal.opened.isEmpty())
        assertEquals(listOf(leadPane to "/exit"), terminal.sent)
    }

    @Test
    fun `a session already on that command, or one splice cannot reach, is refused and nothing is typed`() {
        assertEquals(HttpStatusCode.Conflict, move(LEAD, """{"head":"claude"}""").status)
        val notOurs = rig.json(move(LEAD, """{"head":"codex"}""").body)
        assertEquals("not_ours", notOurs.getValue("reason").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, move(LEAD, "{}").status)
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.none { it.second == SessionKey.STOP })
    }
}
