// NEW: Oct 10, 2026 — Continue on: a session is ended where it runs and resumed by its own id on the command he picked.
// A move is judged by what the person's terminal is given: /exit for the client, then the resume, in his own shell
// when one is left in front and in a new terminal when the old one closed with its client. Nothing is started on top
// of a client that would not exit, and a session waiting on an answer is never typed into. A running turn is stopped and
// its prompt emptied before /exit, and words he has not sent are never sent with it.
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
import splice.sessions.registry.SessionRecord
import splice.sessions.transcript.ModelMove
import java.nio.file.Files
import java.nio.file.Path

class SessionContinueTest {
    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }
    private val terminal = FakeTerminal()
    private val panes = FakePanes()
    private var exits = true
    private var stops = true

    /** A terminal splice opened runs the client alone and closes with it; his own shell stays in front. */
    private var closesWithClient = true
    private val cameOn = mutableListOf<Pair<String, String>>()

    /** The commands a session registers on when it is started there: every command, unless a test says otherwise. */
    private var startsOn: (String) -> Boolean = { true }

    /** What happens once the old client has exited, before the resume: a new client registering, in some tests. */
    private var afterExit: () -> Unit = {}
    private val kept = mutableListOf<Pair<String, ModelMove>>()
    private var leaving: ModelMove? = null

    private val continueOn by lazy {
        SessionContinue(
            drive = SessionDrive(TerminalSource { SessionDriver(terminal, panes) }),
            commands = StartCommands { head -> StartCommand.Ready(listOf("claude-$head")) },
            handover = object : SessionHandover {
                override suspend fun left(session: String, pid: Long, seconds: Long): Boolean = exits.also {
                    if (it && closesWithClient) terminal.live = false
                    if (it) afterExit()
                }

                override suspend fun stopped(session: String, seconds: Long): Boolean = stops

                override suspend fun cameOn(session: String, head: String, seconds: Long): Boolean {
                    cameOn += session to head
                    return startsOn(head)
                }
            },
            registry = rig.registry,
            home = tmp.resolve("home"),
            ledger = object : MoveLedger {
                override fun leaving(record: SessionRecord, to: String, model: String?) {
                    leaving = ModelMove(null, model.orEmpty(), to, 1L, record.head, "claude-fable-5-1")
                }

                override fun arrived(session: String) {
                    kept += session to checkNotNull(leaving)
                }

                override fun failed(session: String): String? = leaving?.fromModel.also { leaving = null }
            },
        )
    }

    private val leadPane = SessionPane("%lead")

    private fun move(session: String, body: String) = runBlocking { continueOn.moveJson(session, body) }

    /** The lead's client reports a turn in flight, as Claude Code writes it to its registry file. */
    private fun leadWorking() {
        val file = rig.tmp.resolve("sessions/1.json")
        Files.writeString(file, Files.readString(file).replace("{", """{"status":"busy","""))
    }

    @Test
    fun `a session splice opened exits there and comes back on the chosen command in a new terminal`() {
        panes.remember(LEAD, leadPane)

        val reply = move(LEAD, """{"head":"codex","model":"gpt-6-sol"}""")

        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(listOf(leadPane to "/exit"), terminal.sent)
        val reopened = terminal.opened.single()
        assertEquals(listOf("claude-codex", "-r", LEAD, "--model", "gpt-6-sol"), reopened.command)
        assertEquals(rig.repo.toString(), reopened.directory, "it goes on in the folder it ran in")
        assertEquals(listOf(leadPane), terminal.besides, "and among the terminals it ran in, not the person's default")
        assertEquals(listOf(LEAD to "codex"), cameOn)
        assertEquals("codex", rig.json(reply.body).getValue("head").jsonPrimitive.content)
        // the mark keeps where it went on and what it left, at the moment he pressed
        val (session, move) = kept.single()
        assertEquals(LEAD, session)
        assertEquals(
            listOf("codex", "gpt-6-sol", "claude", "claude-fable-5-1"),
            listOf(move.command, move.model, move.fromCommand, move.fromModel),
        )
    }

    @Test
    fun `another model on the command it already runs on goes on there, the same exit and resume`() {
        panes.remember(LEAD, leadPane)

        val reply = move(LEAD, """{"head":"claude","model":"claude-opus-5"}""")

        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(listOf(leadPane to "/exit"), terminal.sent)
        assertEquals(listOf("claude-claude", "-r", LEAD, "--model", "claude-opus-5"), terminal.opened.single().command)
        val (_, kept) = kept.single()
        assertEquals(listOf("claude", "claude"), listOf(kept.command, kept.fromCommand))
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
        assertTrue(kept.isEmpty(), "a move that did not happen leaves no mark")
    }

    @Test
    fun `a command that does not start gives the session back where it was, on the command and model it left`() {
        panes.remember(LEAD, leadPane)
        startsOn = { it != "codex" } // the new command's terminal closes at once: it never registers

        val reply = move(LEAD, """{"head":"codex","model":"gpt-6-sol"}""")

        assertEquals(HttpStatusCode.BadGateway, reply.status)
        val body = rig.json(reply.body)
        assertEquals("no_start", body.getValue("reason").jsonPrimitive.content)
        assertEquals("false", body.getValue("moved").jsonPrimitive.content)
        assertEquals("true", body.getValue("back").jsonPrimitive.content, "the agent goes on: it is never left ended")
        assertEquals(
            listOf(
                listOf("claude-codex", "-r", LEAD, "--model", "gpt-6-sol"),
                listOf("claude-claude", "-r", LEAD, "--model", "claude-fable-5-1"),
            ),
            terminal.opened.map { it.command },
            "the attempt, then the way back on the command and model it ran on",
        )
        assertEquals(listOf(SessionPane("%$LEAD")), terminal.closed, "what the attempt left is closed first")
        assertEquals(listOf(LEAD to "codex", LEAD to "claude"), cameOn)
        val resume = "cd ${rig.repo} && claude-claude -r $LEAD --model claude-fable-5-1"
        assertEquals(resume, body.getValue("resume").jsonPrimitive.content)
        assertTrue(kept.isEmpty(), "a move that did not happen leaves no mark")
    }

    @Test
    fun `a client that comes up in the attempt's terminal is the move, though its launch named no command`() {
        panes.remember(LEAD, leadPane)
        startsOn = { it != "codex" } // the registry never names codex: the launch could not record its command
        closesWithClient = false
        afterExit = {
            // the old client's row goes with it, and the resumed one registers under a new pid, in front of his shell
            Files.delete(rig.tmp.resolve("sessions/1.json"))
            val row = """{"pid":7,"sessionId":"$LEAD","cwd":"${rig.repo}","updatedAt":${AT + 7}}"""
            Files.writeString(rig.tmp.resolve("sessions/7.json"), row)
            terminal.inFront[leadPane] = 7L
        }

        val reply = move(LEAD, """{"head":"codex"}""")

        assertEquals(HttpStatusCode.OK, reply.status, "nothing is torn down: it came up where it was started")
        assertTrue(terminal.pressed.none { it.second == SessionKey.INTERRUPT })
        assertEquals(listOf(leadPane to "/exit", leadPane to "claude-codex -r $LEAD"), terminal.sent)
        assertEquals(LEAD, kept.single().first)
    }

    @Test
    fun `a move typed into his shell that never answers is interrupted there and the session resumed in that shell`() {
        panes.remember(LEAD, leadPane)
        closesWithClient = false
        startsOn = { it != "codex" }

        val reply = move(LEAD, """{"head":"codex"}""")

        assertEquals(HttpStatusCode.GatewayTimeout, reply.status)
        assertEquals("no_answer", rig.json(reply.body).getValue("reason").jsonPrimitive.content)
        assertEquals(listOf(leadPane to SessionKey.INTERRUPT), terminal.pressed)
        assertEquals(
            listOf(
                leadPane to "/exit",
                leadPane to "claude-codex -r $LEAD",
                leadPane to "claude-claude -r $LEAD --model claude-fable-5-1",
            ),
            terminal.sent,
        )
        assertTrue(terminal.opened.isEmpty(), "he keeps the one terminal he is in")
    }

    @Test
    fun `when even the way back does not come up, the answer carries the line he would type to resume it`() {
        panes.remember(LEAD, leadPane)
        startsOn = { false }

        val body = rig.json(move(LEAD, """{"head":"codex"}""").body)

        assertEquals("false", body.getValue("back").jsonPrimitive.content)
        val resume = "cd ${rig.repo} && claude-claude -r $LEAD --model claude-fable-5-1"
        assertEquals(resume, body.getValue("resume").jsonPrimitive.content)
        assertTrue(body.getValue("error").jsonPrimitive.content.contains("could not be resumed on claude"))
    }

    @Test
    fun `a turn in flight is stopped and the message it puts back is cleared, so only exit is sent`() {
        panes.remember(LEAD, leadPane)
        leadWorking()

        val reply = move(LEAD, """{"head":"codex"}""")

        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(listOf(SessionKey.STOP, SessionKey.CLEAR), terminal.pressed.map { it.second })
        assertEquals(leadPane to "/exit", terminal.sent.first())
    }

    @Test
    fun `a turn that will not stop, or words he has not sent, leave the session where it is`() {
        panes.remember(LEAD, leadPane)
        terminal.screenText = "────────────── lead ─\n❯ half a thought\n──────────────\n"
        val held = rig.json(move(LEAD, """{"head":"codex"}""").body)
        assertEquals("draft", held.getValue("reason").jsonPrimitive.content)

        leadWorking()
        stops = false
        assertEquals(HttpStatusCode.GatewayTimeout, move(LEAD, """{"head":"codex"}""").status)
        assertTrue(terminal.sent.isEmpty(), "nothing typed: not with his words, not over a running turn")
        assertTrue(terminal.pressed.none { it.second == SessionKey.CLEAR })
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
