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
import splice.http.JsonReply
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

    /** The model a session's row reports it runs on: what a relaunch resumes it on. */
    private var runsOn: String? = "claude-fable-5-1"

    /** What the registry reads once a stop the move pressed lands: idle at its prompt, unless a test says otherwise. */
    private var stoppedTo: () -> Unit = { leadIdle() }

    private val continueOn by lazy {
        SessionContinue(
            drive = SessionDrive(TerminalSource { SessionDriver(terminal, panes) }),
            commands = StartCommands { head -> StartCommand.Ready(listOf("claude-$head")) },
            handover = object : SessionHandover {
                override suspend fun left(session: String, pid: Long, seconds: Long): Boolean = exits.also {
                    if (it && closesWithClient) terminal.live = false
                    if (it) afterExit()
                }

                // A stop that lands reads in the registry, the one RegistryHandover.stopped watches.
                override suspend fun stopped(session: String, seconds: Long): Boolean =
                    stops.also { if (it) stoppedTo() }

                override suspend fun cameOn(session: String, head: String, seconds: Long): Boolean {
                    cameOn += session to head
                    return startsOn(head)
                }
            },
            registry = rig.registry,
            home = tmp.resolve("home"),
            ledger = object : MoveLedger {
                private val open = mutableSetOf<String>()

                override fun opens(session: String): Boolean = open.add(session)

                override fun leaving(record: SessionRecord, to: String, model: String?) {
                    leaving = ModelMove(null, model.orEmpty(), to, 1L, record.head, "claude-fable-5-1")
                }

                override fun arrived(session: String, arrivedAs: String) {
                    kept += arrivedAs to checkNotNull(leaving)
                }

                override fun failed(session: String): String? = leaving?.fromModel.also { leaving = null }

                override fun dropped(session: String) {
                    leaving = null
                    open.remove(session)
                }
            },
            relaunches = object : SessionRelaunches {
                override fun model(record: SessionRecord): String? = runsOn

                override fun command(record: SessionRecord, model: String?): StartCommand = startedWith(model)
            },
        )
    }

    /** The command the lead was started with, as the daemon reads it back from its process: his own `claude` in his own
     *  config folder, its agent kept, resumed by its id on [model]. A relaunch starts it on exactly this. */
    private fun started(model: String?): List<String> =
        listOf("env", "-u", "CLAUDE_CONFIG_DIR", "/home/p/.local/bin/claude", "--agent", "eli", "-r", LEAD) +
            model?.let { listOf("--model", it) }.orEmpty()

    /** What the daemon answers for the command the lead was started with: [started], unless a test says otherwise. */
    private var startedWith: (String?) -> StartCommand = { model -> StartCommand.Ready(started(model)) }

    private val leadPane = SessionPane("%lead")

    private fun move(session: String, body: String) = runBlocking { continueOn.moveJson(session, body) }

    private fun relaunch(session: String) = runBlocking { continueOn.relaunch(session) }

    /** The lead's client reports a turn in flight, as Claude Code writes it to its registry file. */
    private fun leadWorking() {
        val file = rig.tmp.resolve("sessions/1.json")
        Files.writeString(file, Files.readString(file).replace("{", """{"status":"busy","""))
    }

    /** The lead's client idle at its prompt with background shells running, which Claude Code 2.1.310 writes as
     *  "shell" and counts as working in its own session list. */
    private fun leadRunningShells() {
        val file = rig.tmp.resolve("sessions/1.json")
        Files.writeString(file, Files.readString(file).replace("{", """{"status":"shell","""))
    }

    private fun leadIdle() {
        val file = rig.tmp.resolve("sessions/1.json")
        Files.writeString(file, Files.readString(file).replace(""""status":"busy",""", ""))
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
    fun `a turn in flight is stopped and the words it puts back are his, answered back and never cleared`() {
        panes.remember(LEAD, leadPane)
        leadWorking()
        terminal.screenText = "────────────── lead ─\n❯ the message it put back\n──────────────\n"

        val reply = move(LEAD, """{"head":"codex"}""")

        assertEquals(HttpStatusCode.Conflict, reply.status)
        val body = rig.json(reply.body)
        assertEquals("draft", body.getValue("reason").jsonPrimitive.content)
        assertEquals("the message it put back", body.getValue("draft").jsonPrimitive.content, "his words, back to him")
        assertEquals(listOf(SessionKey.STOP), terminal.pressed.map { it.second }, "stopped, and nothing emptied")
        assertTrue(terminal.sent.isEmpty(), "nothing is typed over words he has not sent")
    }

    @Test
    fun `on his say-so the prompt is emptied and the move goes on, so only exit is sent`() {
        panes.remember(LEAD, leadPane)
        leadWorking()
        terminal.screenText = "────────────── lead ─\n❯ the message it put back\n──────────────\n"

        val reply = move(LEAD, """{"head":"codex","clear":true}""")

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
    fun `a second press while it is moving is refused, and only the first move reaches its terminal`() {
        panes.remember(LEAD, leadPane)
        var second: JsonReply? = null
        afterExit = {
            // another tab presses while the first move waits on the old client's exit
            afterExit = {}
            second = runBlocking { continueOn.move(LEAD, "codex", null) }
        }

        val first = move(LEAD, """{"head":"codex","model":"gpt-6-sol"}""")

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.Conflict, second?.status)
        val refused = rig.json(checkNotNull(second).body).getValue("error").jsonPrimitive.content
        assertEquals("it is already moving; wait for that move to answer", refused)
        assertEquals(listOf(leadPane to "/exit"), terminal.sent, "one exit, and no resume typed into the new client")
        assertEquals(listOf("claude-codex", "-r", LEAD, "--model", "gpt-6-sol"), terminal.opened.single().command)
        assertEquals("gpt-6-sol", kept.single().second.model, "the mark is the first move's, never the second's")
    }

    @Test
    fun `a session already on that command, or one splice cannot reach, is refused and nothing is typed`() {
        assertEquals(HttpStatusCode.Conflict, move(LEAD, """{"head":"claude"}""").status)
        val notOurs = rig.json(move(LEAD, """{"head":"codex"}""").body)
        assertEquals("not_ours", notOurs.getValue("reason").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, move(LEAD, "{}").status)
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.none { it.second == SessionKey.STOP })
    }

    // Key rotation (Oct 26, 2026): a session launched before the key helper holds a turn key a rotation strands, so
    // it is relaunched first. A relaunch brings it back as it was: on the command it was started with, so it stays in
    // its own config folder and signed in, with its agent and its model, never with its first prompt, and in the same
    // pane its starter and the person find it by. The relaunch of Oct 26 resumed every session on its head's own
    // command in a new terminal: two came back "Not logged in", and every one moved to a tmux session nobody knew.
    @Test
    fun `a relaunch starts the session again in its own pane, on the command it was started with and its model`() {
        panes.remember(LEAD, leadPane)

        val reply = relaunch(LEAD)

        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        assertEquals(listOf(leadPane), terminal.held, "its pane is kept before its client leaves")
        assertEquals(listOf(leadPane to "/exit"), terminal.sent, "its client leaves on its own exit")
        assertEquals(
            listOf(Opened(leadPane.id, started("claude-fable-5-1"), rig.repo.toString())),
            terminal.restarted,
            "it starts again in the pane it ran in, in its folder, on the command it was started with",
        )
        assertTrue(terminal.opened.isEmpty(), "no new terminal is opened for it")
        assertEquals(listOf(leadPane), terminal.released, "and the pane is given back its own end")
        assertEquals(SessionPane("%lead+again"), panes.known[LEAD], "the pane is known as the one it runs in now")
        assertEquals(listOf(LEAD to "claude"), cameOn)
    }

    @Test
    fun `a relaunch of a session whose model is not known starts it on the command alone`() {
        panes.remember(LEAD, leadPane)
        runsOn = null

        assertEquals(HttpStatusCode.OK, relaunch(LEAD).status)
        assertEquals(listOf(started(null)), terminal.restarted.map { it.command })
    }

    @Test
    fun `a relaunch of a session started from his own shell is typed into that shell, as it was started`() {
        panes.remember(LEAD, leadPane)
        closesWithClient = false

        val reply = relaunch(LEAD)

        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        val line = "env -u CLAUDE_CONFIG_DIR /home/p/.local/bin/claude --agent eli -r $LEAD --model claude-fable-5-1"
        assertEquals(listOf(leadPane to "/exit", leadPane to line), terminal.sent)
        assertTrue(terminal.opened.isEmpty() && terminal.restarted.isEmpty(), "he keeps the shell he is in")
        assertEquals(listOf(leadPane), terminal.released)
    }

    @Test
    fun `a relaunch whose command cannot be read back leaves the session where it is, and types nothing`() {
        panes.remember(LEAD, leadPane)
        startedWith = { StartCommand.Refused("splice could not read the command line it was started with") }

        val reply = relaunch(LEAD)

        assertEquals(HttpStatusCode.Conflict, reply.status)
        val error = rig.json(reply.body).getValue("error").jsonPrimitive.content
        assertEquals("splice could not read the command line it was started with", error)
        assertTrue(terminal.sent.isEmpty() && terminal.held.isEmpty(), "nothing is typed and no pane is kept")
        assertTrue(terminal.opened.isEmpty() && terminal.restarted.isEmpty(), "nothing is started")
    }

    @Test
    fun `a relaunch whose pane cannot be kept leaves the session where it is, and types nothing`() {
        panes.remember(LEAD, leadPane)
        terminal.holds = false

        val reply = relaunch(LEAD)

        assertEquals(HttpStatusCode.BadGateway, reply.status)
        assertTrue(terminal.sent.isEmpty(), "no exit: the session would not come back where it was")
        assertTrue(terminal.opened.isEmpty() && terminal.restarted.isEmpty(), "nothing is started")
    }

    @Test
    fun `a relaunch that does not come up goes back in its own pane on the command it was started with`() {
        panes.remember(LEAD, leadPane)
        startsOn = { false }
        terminal.onRestart = {} // the command exits at once, and its pane is kept dead

        val reply = relaunch(LEAD)

        assertEquals(HttpStatusCode.BadGateway, reply.status)
        val body = rig.json(reply.body)
        assertEquals("no_start", body.getValue("reason").jsonPrimitive.content)
        assertEquals(
            listOf(started("claude-fable-5-1"), started("claude-fable-5-1")),
            terminal.restarted.map { it.command },
            "the attempt, then the way back, both on the command it was started with and never on its head's",
        )
        assertTrue(terminal.opened.isEmpty(), "it never leaves its pane")
        val line = "env -u CLAUDE_CONFIG_DIR /home/p/.local/bin/claude --agent eli -r $LEAD --model claude-fable-5-1"
        assertEquals("cd ${rig.repo} && $line", body.getValue("resume").jsonPrimitive.content)
        assertEquals(listOf(leadPane), terminal.released, "its pane is given back its own end")
    }

    // A relaunch is splice's, never his: it waits for his turn to end rather than stopping it, and it never types
    // over words he has not sent.
    @Test
    fun `a relaunch leaves a working session, or one holding his words, where it is`() {
        panes.remember(LEAD, leadPane)
        leadWorking()
        val working = relaunch(LEAD)
        assertEquals(HttpStatusCode.Conflict, working.status)
        assertEquals("working", rig.json(working.body).getValue("reason").jsonPrimitive.content)
        assertTrue(terminal.pressed.isEmpty(), "its turn is not stopped")

        leadIdle()
        terminal.screenText = "────────────── lead ─\n❯ half a thought\n──────────────\n"
        val held = relaunch(LEAD)
        assertEquals("draft", rig.json(held.body).getValue("reason").jsonPrimitive.content)
        assertTrue(terminal.sent.isEmpty(), "nothing is typed over his words")
        assertEquals(HttpStatusCode.NotFound, relaunch("no-such-session").status, "only a running session relaunches")
    }

    // A session idle at its prompt with background shells running answers /exit with "Background work is running" and
    // "Exit and stop tasks" focused (Oct 26, 2026: a relaunch left that question on the rig session's screen). A relaunch
    // stops nothing of his, so it leaves that session where it is and types nothing into it until that work ends.
    @Test
    fun `a relaunch leaves a session running background work where it is, and types nothing into it`() {
        panes.remember(LEAD, leadPane)
        leadRunningShells()

        val reply = relaunch(LEAD)

        assertEquals(HttpStatusCode.Conflict, reply.status)
        val body = rig.json(reply.body)
        assertEquals("working", body.getValue("reason").jsonPrimitive.content)
        assertTrue("background" in body.getValue("error").jsonPrimitive.content, "it says what it waits for: $body")
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty(), "nothing is typed or pressed")
        assertTrue(terminal.opened.isEmpty(), "nothing is started")
    }

    // Continue on ends the client with /exit as a relaunch does, and /exit with background shells running stops on
    // "Background work is running" with "Exit and stop tasks" focused, where his next Enter ends that work (PR #247's
    // fifth review). The move leaves such a session where it is and types nothing into it.
    @Test
    fun `Continue on leaves a session running background work where it is, and types nothing into it`() {
        panes.remember(LEAD, leadPane)
        leadRunningShells()

        val reply = move(LEAD, """{"head":"codex","model":"gpt-6-sol"}""")

        assertEquals(HttpStatusCode.Conflict, reply.status)
        val body = rig.json(reply.body)
        assertEquals("working", body.getValue("reason").jsonPrimitive.content)
        assertTrue("background" in body.getValue("error").jsonPrimitive.content, "it says what it waits for: $body")
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty(), "nothing is typed or pressed")
        assertTrue(terminal.opened.isEmpty() && kept.isEmpty(), "nothing is started and no mark is left")
    }

    // A turn can leave background shells running when it stops: the move stops the turn it was pressed on, and the
    // client then sits at its prompt with that work still running, so /exit would land in the same question.
    @Test
    fun `a stopped turn that leaves background work running gets no exit, and the session stays where it is`() {
        panes.remember(LEAD, leadPane)
        leadWorking()
        stoppedTo = {
            leadIdle()
            leadRunningShells()
        }

        val reply = move(LEAD, """{"head":"codex"}""")

        assertEquals(HttpStatusCode.Conflict, reply.status)
        val body = rig.json(reply.body)
        assertEquals("working", body.getValue("reason").jsonPrimitive.content)
        assertTrue("background" in body.getValue("error").jsonPrimitive.content, "it says what it waits for: $body")
        assertEquals(listOf(SessionKey.STOP), terminal.pressed.map { it.second }, "only the turn he pressed on stops")
        assertTrue(terminal.sent.isEmpty(), "no /exit lands in the background-work question")
        assertTrue(terminal.opened.isEmpty() && kept.isEmpty(), "nothing is started and no mark is left")
    }
}
