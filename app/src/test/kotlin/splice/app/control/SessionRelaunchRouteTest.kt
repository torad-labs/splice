// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — POST /api/sessions/{id}/relaunch through a real
// ControlServer, the way `splice sessions relaunch` drives it after a key rotation: over a registry of running Claude
// Code sessions, their processes as /proc shows them, and a terminal that records what it is asked. The relaunch of
// Oct 26 resumed every session on its head's own command in a new tmux session: two sessions started as `claude` came
// back "Not logged in" in the head's config folder, reviews came back without their agent, and nobody could find the
// seats it moved.
//
// What a relaunch must give back: the session started again on the command it was started with, in its own config
// folder (so signed in as it was), with its agent and every other option it was given and never its first prompt, on
// the model its row reports, in the same pane, and only between its turns.
//
// What it must never do: type /exit into a session holding a feedback draft that has not been sent. Claude Code
// 2.1.310 answers that /exit with "You have 1 unsent feedback draft · Enter to review & send · Esc to discard and
// exit", and sending or discarding the draft is the person's call. On Oct 27 at 08:30 CT a relaunch did, and left a
// session in ~/.claude-claudex sitting at that question.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.sessions.SessionsRelaunchCommand
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.InstallPaths
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.config.UserHome
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.process.LaunchOwners
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.session.SessionPanes
import splice.core.session.SessionTerminal
import splice.core.terminal.TerminalOutput
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.recipe.LaunchService
import splice.launch.wrap.WrappedClaudeLaunch
import splice.sessions.http.SessionDriver
import splice.sessions.registry.DarwinPs
import splice.sessions.registry.PidAlive
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val HEAD = "claude-splice"
private const val REVIEW = "5e1a0001-0000-4000-8000-000000000001"
private const val SEAT = "5e1a0002-0000-4000-8000-000000000002"
private const val LEADER = "5e1a0003-0000-4000-8000-000000000003"

/** A seat beside [SEAT] in one config folder, as the seats of the claudex head share ~/.claude-claudex. */
private const val NEIGHBOR = "5e1a0004-0000-4000-8000-000000000004"

/** Seats in that folder holding a feedback draft they have not sent, each held otherwise too: in its turn, running
 *  background shells, waiting on an answer, and on no splice command. */
private const val IN_TURN = "5e1a0005-0000-4000-8000-000000000005"
private const val SHELLS = "5e1a0006-0000-4000-8000-000000000006"
private const val ASKING = "5e1a0007-0000-4000-8000-000000000007"
private const val OUTSIDE = "5e1a0008-0000-4000-8000-000000000008"

/** A registration's status at rest at its prompt, as Claude Code writes it. */
private const val AT_REST = """"status":"idle""""

/** What a refusal for a feedback draft that has not been sent says, in the daemon's reply and the CLI's line. */
private const val HOLDS_FEEDBACK = "holds a feedback draft that has not been sent"

/** The model each session's transcript last ran, which its row reports. */
private const val ROW_MODEL = "claude-opus-5-5"

// why: above the kernel's pid_max (4194304), so no process on the machine running the test answers to these pids
private const val FIRST_PID = 5_000_001L

class SessionRelaunchRouteTest {

    @TempDir
    lateinit var home: Path

    private val bin by lazy { Files.createDirectories(home.resolve(".local/bin")) }
    private val share by lazy { Files.createDirectories(home.resolve(".local/share/splice")) }
    private val paths by lazy { StatePaths(baseOverride = home.resolve("state")) }
    private val sessionsDir by lazy { Files.createDirectories(home.resolve(".claude/sessions")) }
    private val headDir by lazy { home.resolve(".claude-claude-splice") }
    private val realClaude by lazy { home.resolve("claude-real").also { Files.writeString(it, "#!/bin/sh\n") } }

    /** The pids that run, as the registry and the terminal see them. */
    private val alive = ConcurrentHashMap.newKeySet<Long>()

    /** The pids whose sessions talk to their provider directly, on no splice command; every other runs on [HEAD]. */
    private val direct = ConcurrentHashMap.newKeySet<Long>()
    private var nextPid = FIRST_PID + 1_000
    private val terminal = RecordingTerminal()
    private val panes = KnownPanes()

    /** The processes as /proc shows them, read by the daemon and by `splice sessions relaunch` alike. */
    private val processes by lazy {
        ProcessEnvironment(home.resolve("proc"), LaunchOwners(home.resolve("owners")), DarwinPs(here = false))
    }

    // ── the sessions, as they were started ─────────────────────────────────────────────────────────────────

    /** A review started through his wrapped `claude` in his own ~/.claude: its agent and its name, and the prompt it
     *  was started with, after the words the launch put first. */
    private fun startReview(): SessionPane = start(
        REVIEW,
        FIRST_PID,
        listOf(
            realClaude.toString(), "--settings", """{"env":{"SPLICE":"1"}}""",
            "--mcp-config", paths.stateDir.resolve("mcp-hub/launch-$FIRST_PID.json").toString(),
            "--plugin-dir-no-mcp", "/home/p/.claude/plugins/eli",
            "--agent", "eli", "-n", "splice-review-3", "/review-pr https://github.com/torad/splice/pull/251",
        ),
        environ = listOf("SPLICE=1"),
        transcripts = home.resolve(".claude"),
    )

    /** A seat started through his wrapped `claude` in a config folder of its own, with its own model named: [SEAT] as
     *  [FIRST_PID] + 1 unless another [session] and [pid] are named, holding the turn [key] its launch planted when one
     *  is given. */
    private fun startSeat(
        folder: Path,
        session: String = SEAT,
        pid: Long = FIRST_PID + 1,
        key: String? = null,
    ): SessionPane = start(
        session,
        pid,
        listOf(realClaude.toString(), "--settings", """{"env":{"SPLICE":"1"}}""") +
            listOf("--mcp-config", paths.stateDir.resolve("wrapped-mcp-claude-splice.json").toString()) +
            listOf("--allow-dangerously-skip-permissions", "--model", "claude-fable-5-1"),
        environ = listOf("SPLICE=1", "CLAUDE_CONFIG_DIR=$folder") +
            listOfNotNull(key?.let { "ANTHROPIC_AUTH_TOKEN=$it" }),
        transcripts = folder,
    )

    /** A feedback draft as Claude Code 2.1.310 writes one, <[folder]>/feedback/drafts/<draft_id>.json: from [session],
     *  and "queued" in [status] until it is sent. A [cut] one stops after its status, as a file left unfinished. */
    private fun feedbackDraft(folder: Path, session: String, status: String, cut: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val text = """{"type":"bug","title":"a tool call failed","details":"the edit did not apply",""" +
            """"source_session_id":"$session","status":"$status","created_at":"2026-10-27T13:30:00.000Z",""" +
            """"draft_id":"$id","trigger":"model_judgment","cli_version":"2.1.310"}"""
        val written = if (cut) text.substringBefore(",\"draft_id\"") else text
        Files.writeString(Files.createDirectories(folder.resolve("feedback/drafts")).resolve("$id.json"), written)
    }

    /** What [body], a refusal the daemon answered, says in [field]. */
    private fun said(body: String, field: String): String? =
        Json.parseToJsonElement(body).jsonObject[field]?.jsonPrimitive?.content

    /** The lead, started by the head's own command in the head's own folder, resumed by its name. */
    private fun startLead(): SessionPane = start(
        LEADER,
        FIRST_PID + 2,
        listOf(
            realClaude.toString(),
            "--mcp-config", paths.stateDir.resolve("mcp-hub/launch-${FIRST_PID + 2}.json").toString(),
            "--plugin-dir-no-mcp", "/home/p/.claude/plugins/a", "--plugin-dir-no-mcp", "/home/p/.claude/plugins/b",
            "--name", "splice-lead", "-r", "splice-lead",
        ),
        environ = listOf("SPLICE=1", "CLAUDE_CONFIG_DIR=$headDir"),
        transcripts = headDir,
    )

    @Test
    fun `a session started through his claude comes back in its pane, in its own config folder, as it was started`() {
        val seatFolder = home.resolve(".claude-work")
        serve { daemon ->
            val review = startReview()
            val seat = startSeat(seatFolder)

            val reviewed = daemon.relaunch(REVIEW)
            assertEquals(HttpStatusCode.OK, reviewed.first, reviewed.second)
            val seated = daemon.relaunch(SEAT)
            assertEquals(HttpStatusCode.OK, seated.first, seated.second)

            val claude = bin.resolve("claude").toString()
            assertEquals(
                listOf(
                    Restart(
                        review,
                        listOf("env", "-u", "CLAUDE_CONFIG_DIR", claude, "--agent", "eli", "-n", "splice-review-3") +
                            listOf("-r", REVIEW, "--model", ROW_MODEL),
                        cwd(REVIEW).toString(),
                    ),
                    Restart(
                        seat,
                        listOf("env", "CLAUDE_CONFIG_DIR=$seatFolder", claude) +
                            listOf("--allow-dangerously-skip-permissions", "--model", "claude-fable-5-1", "-r", SEAT),
                        cwd(SEAT).toString(),
                    ),
                ),
                terminal.restarted,
                "each starts again in the pane it ran in, in its own folder, on the command it was started with",
            )
            assertEquals(listOf(review to "/exit", seat to "/exit"), terminal.sent, "each client left on its own exit")
            assertTrue(terminal.opened.isEmpty(), "no new terminal is opened: ${terminal.opened}")
            assertEquals(listOf(review, seat), terminal.held, "each pane is kept before its client leaves")
            assertEquals(listOf(review, seat), terminal.released, "and given back its own end once it is back")
        }
    }

    @Test
    fun `a session started by its head's command comes back on that command, in the head's folder`() {
        serve { daemon ->
            val lead = startLead()

            val reply = daemon.relaunch(LEADER)

            assertEquals(HttpStatusCode.OK, reply.first, reply.second)
            assertEquals(
                listOf(
                    Restart(
                        lead,
                        listOf(bin.resolve(HEAD).toString(), "--name", "splice-lead") +
                            listOf("-r", LEADER, "--model", ROW_MODEL),
                        cwd(LEADER).toString(),
                    ),
                ),
                terminal.restarted,
            )
            assertTrue(terminal.opened.isEmpty(), "no new terminal is opened: ${terminal.opened}")
            assertEquals(listOf(lead), terminal.released)
        }
    }

    @Test
    fun `a session in its turn is left where it is, and nothing is typed into it`() {
        serve { daemon ->
            val review = startReview()
            val file = sessionsDir.resolve("$FIRST_PID.json")
            Files.writeString(file, Files.readString(file).replace("\"idle\"", "\"busy\""))

            val reply = daemon.relaunch(REVIEW)

            assertEquals(HttpStatusCode.Conflict, reply.first, reply.second)
            assertTrue(terminal.sent.isEmpty() && terminal.held.isEmpty(), "nothing typed, no pane kept")
            assertTrue(terminal.restarted.isEmpty() && terminal.opened.isEmpty(), "nothing started")
            assertEquals(review, panes.paneFor(REVIEW), "it stays where it runs")
        }
    }

    // The seats of one head share its config folder, and Claude Code keeps every seat's feedback drafts in it: a draft
    // names the session it came from, and its status reads "queued" until it is sent. On Oct 27 ~/.claude-claudex held
    // ten queued drafts of several seats, and one file Claude Code had left cut off mid-way.
    @Test
    fun `a session holding a feedback draft that has not been sent is left where it is, and its neighbor relaunches`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val holder = startSeat(folder)
            val neighbor = startSeat(folder, NEIGHBOR, FIRST_PID + 3)
            feedbackDraft(folder, SEAT, "queued")
            // what the neighbor's folder holds besides the holder's draft: its own, already sent, and one cut off
            feedbackDraft(folder, NEIGHBOR, "sent")
            feedbackDraft(folder, NEIGHBOR, "queued", cut = true)

            val held = daemon.relaunch(SEAT)
            val relaunched = daemon.relaunch(NEIGHBOR)

            assertEquals(HttpStatusCode.Conflict, held.first, held.second)
            assertTrue(HOLDS_FEEDBACK in said(held.second, "error").orEmpty(), "it says why: ${held.second}")
            assertEquals("feedback", said(held.second, "reason"), held.second)
            assertEquals(HttpStatusCode.OK, relaunched.first, relaunched.second)
            assertEquals(listOf(neighbor to "/exit"), terminal.sent, "nothing is typed into the holder's pane")
            assertEquals(listOf(neighbor), terminal.held, "and its pane is not kept for a restart")
            assertEquals(listOf(neighbor), terminal.restarted.map { it.pane }, "only the neighbor starts again")
            assertEquals(holder, panes.paneFor(SEAT), "the holder stays where it runs")
        }
    }

    // Continue on ends the client with the same /exit, so it leaves such a session where it is too.
    @Test
    fun `Continue on leaves a session holding a feedback draft that has not been sent where it is`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val holder = startSeat(folder)
            feedbackDraft(folder, SEAT, "queued")

            val reply = daemon.move(SEAT, """{"head":"$HEAD","model":"claude-opus-5"}""")

            assertEquals(HttpStatusCode.Conflict, reply.first, reply.second)
            assertTrue(HOLDS_FEEDBACK in said(reply.second, "error").orEmpty(), "it says why: ${reply.second}")
            assertEquals("feedback", said(reply.second, "reason"), reply.second)
            assertTrue(terminal.sent.isEmpty(), "nothing is typed into its pane: ${terminal.sent}")
            assertTrue(terminal.opened.isEmpty() && terminal.restarted.isEmpty(), "nothing is started")
            assertEquals(holder, panes.paneFor(SEAT), "it stays where it runs")
        }
    }

    // `splice sessions relaunch` runs after a rotation until it passes. On Oct 27 three sessions due for a relaunch
    // held queued drafts: each is named with why it was left, and every other session is still relaunched.
    @Test
    fun `splice sessions relaunch names a session holding a feedback draft and goes on with the others`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val key = TurnKey(MgmtKey(paths)).get()
            val neighbor = startSeat(folder, NEIGHBOR, FIRST_PID + 3, key)
            val holder = startSeat(folder, SEAT, FIRST_PID + 1, key)
            // the holder's client wrote its registration last, so the run comes to it first
            register(SEAT, FIRST_PID + 1, at = System.currentTimeMillis() + 60_000)
            feedbackDraft(folder, SEAT, "queued")

            val (exit, said) = daemon.relaunchAll()

            val left = said.indexOfFirst { SEAT in it && "left" in it }
            val relaunched = said.indexOfFirst { NEIGHBOR in it && "relaunched" in it }
            assertTrue(left >= 0 && HOLDS_FEEDBACK in said[left], "the holder is named with why: $said")
            assertTrue(relaunched > left, "and the run goes on to the neighbor, which is relaunched: $said")
            assertEquals(listOf(neighbor to "/exit"), terminal.sent, "nothing is typed into the holder's pane")
            assertEquals(holder, panes.paneFor(SEAT), "the holder stays where it runs")
            assertEquals(1, exit, "a session left where it is fails the run, so it is run again: $said")
        }
    }

    // A session holding a draft can be held otherwise too: in its turn, running background shells, waiting on an
    // answer, or on no splice command at all. Whatever else holds it, sending or discarding the draft is the person's,
    // so a relaunch and a Continue on each refuse it for that draft, and type nothing into it.
    @Test
    fun `whatever else holds a session, a relaunch or a move names the feedback draft it has not sent`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val holders = startHeldOtherwise(folder, key = null) + (OUTSIDE to startOutside())

            for (session in holders.keys) {
                val relaunched = daemon.relaunch(session)
                val moved = daemon.move(session, """{"head":"$HEAD","model":"claude-opus-5"}""")
                for ((act, reply) in listOf("relaunch" to relaunched, "move" to moved)) {
                    val (status, body) = reply
                    assertEquals(HttpStatusCode.Conflict, status, "$act of $session: $body")
                    assertEquals("feedback", said(body, "reason"), "$act of $session: $body")
                    assertTrue(HOLDS_FEEDBACK in said(body, "error").orEmpty(), "$act of $session says why: $body")
                }
            }
            assertTrue(terminal.sent.isEmpty(), "nothing is typed into a holder's pane: ${terminal.sent}")
            assertTrue(terminal.held.isEmpty(), "no holder's pane is kept for a restart: ${terminal.held}")
            assertTrue(terminal.opened.isEmpty() && terminal.restarted.isEmpty(), "nothing is started")
            holders.forEach { (session, pane) -> assertEquals(pane, panes.paneFor(session), "$session stays") }
        }
    }

    // On Oct 27 three sessions due for a relaunch held queued drafts, and a session holding one may be in its turn,
    // running background shells or waiting on an answer too. The run names the draft of each, and still relaunches
    // every other session.
    @Test
    fun `splice sessions relaunch names each holder's draft whatever else holds it, and goes on with the others`() {
        val folder = home.resolve(".claude-claudex")
        serve { daemon ->
            val key = TurnKey(MgmtKey(paths)).get()
            val neighbor = startSeat(folder, NEIGHBOR, FIRST_PID + 3, key)
            val holders = startHeldOtherwise(folder, key)

            val (exit, lines) = daemon.relaunchAll()

            val relaunched = lines.indexOfFirst { NEIGHBOR in it && "relaunched" in it }
            for (session in holders.keys) {
                val left = lines.indexOfFirst { session in it && "left" in it }
                assertTrue(left >= 0 && HOLDS_FEEDBACK in lines[left], "$session is named with its draft: $lines")
                assertTrue(relaunched > left, "and the run goes on to the neighbor, which is relaunched: $lines")
            }
            assertEquals(listOf(neighbor to "/exit"), terminal.sent, "nothing is typed into a holder's pane")
            holders.forEach { (session, pane) -> assertEquals(pane, panes.paneFor(session), "$session stays") }
            assertEquals(1, exit, "a session left where it is fails the run, so it is run again: $lines")
        }
    }

    // ── the rig ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Seats in [folder], each holding a feedback draft it has not sent and held otherwise too: [IN_TURN] in its turn,
     *  [SHELLS] running background shells and [ASKING] waiting on an answer, each holding the turn [key] when one is
     *  given. They wrote their registrations after every seat started before them, so a run comes to them first. Each
     *  with the pane it runs in. */
    private fun startHeldOtherwise(folder: Path, key: String?): Map<String, SessionPane> {
        val states = listOf(
            IN_TURN to """"status":"busy"""",
            SHELLS to """"status":"shell"""",
            ASKING to """"status":"waiting","waitingFor":"permission prompt"""",
        )
        return states.withIndex().associate { (n, held) ->
            val (session, state) = held
            val pid = FIRST_PID + 5 + n
            val pane = startSeat(folder, session, pid, key)
            register(session, pid, at = System.currentTimeMillis() + 60_000, state = state)
            feedbackDraft(folder, session, "queued")
            session to pane
        }
    }

    /** [OUTSIDE]: plain `claude` started from his own shell in his own ~/.claude, on no splice command, at rest at its
     *  prompt and holding a feedback draft it has not sent. */
    private fun startOutside(): SessionPane {
        val pid = FIRST_PID + 8
        direct += pid
        val own = home.resolve(".claude")
        val pane = start(OUTSIDE, pid, listOf(realClaude.toString()), environ = listOf("HOME=$home"), transcripts = own)
        feedbackDraft(own, OUTSIDE, "queued")
        return pane
    }

    /** The folder [session] runs in. */
    private fun cwd(session: String): Path = Files.createDirectories(home.resolve("work").resolve(session.take(8)))

    /** [session] running as [pid] in a pane of its own: its registration, the words and environment its process
     *  shows, and the transcript under [transcripts] whose last turn ran on [ROW_MODEL]. */
    private fun start(
        session: String,
        pid: Long,
        argv: List<String>,
        environ: List<String>,
        transcripts: Path,
    ): SessionPane {
        val proc = Files.createDirectories(home.resolve("proc").resolve(pid.toString()))
        Files.write(proc.resolve("cmdline"), argv.joinToString("\u0000", postfix = "\u0000").toByteArray())
        Files.write(proc.resolve("environ"), environ.joinToString("\u0000", postfix = "\u0000").toByteArray())
        val slug = cwd(session).toString().replace(Regex("[^A-Za-z0-9]"), "-")
        val project = Files.createDirectories(transcripts.resolve("projects").resolve(slug))
        val transcript = project.resolve("$session.jsonl")
        Files.writeString(
            transcript,
            """{"type":"assistant","timestamp":"2026-10-26T12:00:00Z","message":{"id":"msg_1",""" +
                """"model":"$ROW_MODEL","role":"assistant","content":[{"type":"text","text":"ok"}]}}""" + "\n",
        )
        register(session, pid)
        val pane = SessionPane("%${session.take(8)}")
        terminal.running[pane.id] = session to pid
        panes.remember(session, pane)
        return pane
    }

    /** Claude Code's registration of [session] as [pid], written [at] that moment, its status fields reading [state]:
     *  at rest at its prompt unless another is named. */
    private fun register(session: String, pid: Long, at: Long = System.currentTimeMillis(), state: String = AT_REST) {
        alive += pid
        Files.writeString(
            sessionsDir.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"$session","cwd":"${cwd(session)}",$state,"updatedAt":$at}""",
        )
    }

    /** A daemon serving [HEAD], whose `claude` is wrapped, with this rig's registry, processes and terminal. */
    private fun serve(test: suspend (Daemon) -> Unit) =
        UserHome.within(home) {
            val launch = launchService()
            assertNotNull(
                WrappedClaudeLaunch(launch.wrap).of(HEAD),
                "his claude is wrapped, as `splice wrap` leaves it",
            )
            Files.createSymbolicLink(bin.resolve(HEAD), share.resolve("splice-launch"))
            val mgmt = MgmtKey(paths)
            val key = mgmt.get()
            val control = controlServerFor(
                port = 0,
                heads = mapOf(HEAD to managedHead()),
                config = ConfigService(paths),
                auth = ControlAuth(mgmtKey = mgmt, log = { }),
                runtime = ControlRuntime(launchService = launch, sessions = registry()),
            )
            control.ports.sessionDriver = SessionDriver(terminal, panes)
            control.ports.processes = ProcessReads { processes }
            runBlocking { control.start() }
            val client = HttpClient(CIO) { expectSuccess = false }
            try {
                runBlocking { test(Daemon(client, control.listeningPort, key)) }
            } finally {
                client.close()
                control.stop()
            }
        }

    /** The daemon as its callers reach it: its relaunch and Continue on routes, opened with its management [key], and
     *  `splice sessions relaunch` run against it as the person runs it after a rotation. */
    private inner class Daemon(private val client: HttpClient, private val port: Int, private val key: String) {
        suspend fun relaunch(session: String): Pair<HttpStatusCode, String> = post("$session/relaunch", "")

        /** Continue on, with [body] as the page sends it: the command and the model to go on under. */
        suspend fun move(session: String, body: String): Pair<HttpStatusCode, String> = post("$session/continue", body)

        private suspend fun post(path: String, body: String): Pair<HttpStatusCode, String> {
            val reply = client.post("http://127.0.0.1:$port/api/sessions/$path") {
                header("Authorization", "Bearer $key")
                setBody(body)
            }
            return reply.status to reply.bodyAsText()
        }

        /** `splice sessions relaunch` over this rig's registry and processes, run from no session of its own: its exit
         *  code, and each line it printed. */
        fun relaunchAll(): Pair<Int, List<String>> {
            val said = mutableListOf<String>()
            val env = mapOf(
                "SPLICE_STATE_DIR" to home.resolve("state").toString(),
                "SPLICE_CONTROL_PORT" to port.toString(),
                "SPLICE_CONFIG" to home.resolve("splice.toml").toString(),
                "HOME" to home.toString(),
            )
            val exit = SessionsRelaunchCommand(
                env = { name -> env[name] },
                out = TerminalOutput { said += it },
                sessions = registry(),
                environment = processes,
                ancestors = SessionsRelaunchCommand.Ancestors { emptySet() },
            ).run()
            return exit to said
        }
    }

    private fun registry(): SessionRegistry = SessionRegistry(
        sessionsDir = sessionsDir,
        routeOf = RouteOfPid { pid -> if (pid in direct) SessionRoute.Direct else SessionRoute.Head(HEAD) },
        pidAlive = PidAlive { it in alive },
    )

    /** The launch the daemon serves, its wrap under [home]: `claude` linked to the launch shim, the real binary
     *  kept. */
    private fun launchService(): LaunchService {
        Files.writeString(share.resolve("splice-launch"), "#!/bin/sh\n")
        Files.createSymbolicLink(bin.resolve("claude"), realClaude)
        val wrap = WrappedHead(
            home = home,
            installPaths = InstallPaths(binOverride = bin, shareOverride = share),
            stateStore = WrapStateStore(file = home.resolve("state/claude-head-wrap.json")),
        )
        wrap.wrap()
        return LaunchService(ClaudeConfigMaterializer(home), wrap = wrap)
    }

    private fun managedHead(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = HEAD
            override val label: String = HEAD
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "client", emptyMap())
        },
        sources = HeadSources(
            usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int): String = ""
                override fun path(): String = ""
            },
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
        launchSpec = LaunchSpec(
            trees = HeadTrees(headDir),
            models = LaunchModels(
                pinnedModel = ROW_MODEL,
                availableModelIds = listOf(ROW_MODEL),
                modelLabels = mapOf(ROW_MODEL to "Claude Opus 5.5"),
                contextWindow = 200_000,
                modelOptionsCache = buildJsonObject { },
            ),
            signIn = LaunchSignIn(loginCommand = "claude-splice login", signInLabel = "Claude", headKey = HEAD),
            gateway = LaunchGateway(
                statuslineCommand = "\"/bin/curl\" -s :3096/statusline/claude-splice",
                port = 3104,
                bearer = GatewayBearer.Planted("test-token"),
                apiTimeoutMs = 960_000,
                forwardClientAuth = true,
            ),
            policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        ),
    )

    /** One start in place: the pane, the command and the folder. */
    private data class Restart(val pane: SessionPane, val command: List<String>, val directory: String)

    private class KnownPanes : SessionPanes {
        private val known = ConcurrentHashMap<String, SessionPane>()

        override fun paneFor(sessionId: String): SessionPane? = known[sessionId]

        override fun remember(sessionId: String, pane: SessionPane) {
            known[sessionId] = pane
        }

        override fun forget(sessionId: String) {
            known.remove(sessionId)
        }
    }

    /** A terminal that records what it is asked, and behaves as tmux and Claude Code would: a client leaves its pane
     *  on its own `/exit`, dropping its registration, and a command started in a pane, or in a new terminal, comes up
     *  there and registers its session under a process of its own. */
    private inner class RecordingTerminal : SessionTerminal {
        /** The client each pane runs: its session and pid, until it leaves. */
        val running = ConcurrentHashMap<String, Pair<String, Long>>()
        val sent = mutableListOf<Pair<SessionPane, String>>()
        val opened = mutableListOf<List<String>>()
        val held = mutableListOf<SessionPane>()
        val released = mutableListOf<SessionPane>()
        val restarted = mutableListOf<Restart>()

        override fun open(
            sessionId: String,
            command: List<String>,
            directory: String,
            beside: SessionPane?,
        ): SessionPane {
            opened += command
            val pane = SessionPane("%new-${sessionId.take(8)}")
            comeUp(pane, sessionId)
            return pane
        }

        override fun send(pane: SessionPane, text: String) {
            sent += pane to text
            if (text == "/exit") {
                running.remove(pane.id)?.let { (_, pid) ->
                    alive -= pid
                    Files.deleteIfExists(sessionsDir.resolve("$pid.json"))
                }
            }
        }

        override fun press(pane: SessionPane, key: SessionKey) = Unit

        override fun screen(pane: SessionPane): String = ""

        override fun close(pane: SessionPane) {
            running.remove(pane.id)
        }

        override fun isOpen(pane: SessionPane): Boolean = running.containsKey(pane.id)

        override fun owned(pane: SessionPane): Boolean = true

        override fun recorded(pane: String, server: String): SessionPane? = null

        override fun hosts(pane: SessionPane, pid: Long): Boolean = running[pane.id]?.second == pid

        override fun hold(pane: SessionPane): Boolean = true.also { held += pane }

        override fun restart(pane: SessionPane, command: List<String>, directory: String): SessionPane? {
            if (running.containsKey(pane.id)) return null
            restarted += Restart(pane, command, directory)
            comeUp(pane, command[command.indexOf("-r") + 1])
            return pane
        }

        override fun release(pane: SessionPane) {
            released += pane
        }

        private fun comeUp(pane: SessionPane, session: String) {
            val pid = nextPid++
            register(session, pid)
            running[pane.id] = session to pid
        }
    }
}
