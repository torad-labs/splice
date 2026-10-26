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
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
import splice.core.config.UserHome
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.process.LaunchOwners
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.session.SessionPanes
import splice.core.session.SessionTerminal
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
import java.util.concurrent.ConcurrentHashMap

private const val HEAD = "claude-splice"
private const val REVIEW = "5e1a0001-0000-4000-8000-000000000001"
private const val SEAT = "5e1a0002-0000-4000-8000-000000000002"
private const val LEADER = "5e1a0003-0000-4000-8000-000000000003"

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
    private var nextPid = FIRST_PID + 1_000
    private val terminal = RecordingTerminal()
    private val panes = KnownPanes()

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

    /** A seat started through his wrapped `claude` in a config folder of its own, with its own model named. */
    private fun startSeat(folder: Path): SessionPane = start(
        SEAT,
        FIRST_PID + 1,
        listOf(realClaude.toString(), "--settings", """{"env":{"SPLICE":"1"}}""") +
            listOf("--mcp-config", paths.stateDir.resolve("wrapped-mcp-claude-splice.json").toString()) +
            listOf("--allow-dangerously-skip-permissions", "--model", "claude-fable-5-1"),
        environ = listOf("SPLICE=1", "CLAUDE_CONFIG_DIR=$folder"),
        transcripts = folder,
    )

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
        serve { relaunch ->
            val review = startReview()
            val seat = startSeat(seatFolder)

            val reviewed = relaunch(REVIEW)
            assertEquals(HttpStatusCode.OK, reviewed.first, reviewed.second)
            val seated = relaunch(SEAT)
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
        serve { relaunch ->
            val lead = startLead()

            val reply = relaunch(LEADER)

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
        serve { relaunch ->
            val review = startReview()
            val file = sessionsDir.resolve("$FIRST_PID.json")
            Files.writeString(file, Files.readString(file).replace("\"idle\"", "\"busy\""))

            val reply = relaunch(REVIEW)

            assertEquals(HttpStatusCode.Conflict, reply.first, reply.second)
            assertTrue(terminal.sent.isEmpty() && terminal.held.isEmpty(), "nothing typed, no pane kept")
            assertTrue(terminal.restarted.isEmpty() && terminal.opened.isEmpty(), "nothing started")
            assertEquals(review, panes.paneFor(REVIEW), "it stays where it runs")
        }
    }

    // ── the rig ──────────────────────────────────────────────────────────────────────────────────────────────

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

    /** Claude Code's registration of [session] as [pid], at rest at its prompt. */
    private fun register(session: String, pid: Long) {
        alive += pid
        Files.writeString(
            sessionsDir.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"$session","cwd":"${cwd(session)}","status":"idle",""" +
                """"updatedAt":${System.currentTimeMillis()}}""",
        )
    }

    /** A daemon serving [HEAD], whose `claude` is wrapped, with this rig's registry, processes and terminal. */
    private fun serve(test: suspend (relaunch: suspend (String) -> Pair<HttpStatusCode, String>) -> Unit) =
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
            val reads = ProcessEnvironment(
                home.resolve("proc"),
                LaunchOwners(home.resolve("owners")),
                DarwinPs(here = false),
            )
            control.ports.processes = ProcessReads { reads }
            runBlocking { control.start() }
            val client = HttpClient(CIO) { expectSuccess = false }
            try {
                runBlocking {
                    test { session ->
                        val at = "http://127.0.0.1:${control.listeningPort}/api/sessions/$session/relaunch"
                        val reply = client.post(at) { header("Authorization", "Bearer $key") }
                        reply.status to reply.bodyAsText()
                    }
                }
            } finally {
                client.close()
                control.stop()
            }
        }

    private fun registry(): SessionRegistry = SessionRegistry(
        sessionsDir = sessionsDir,
        routeOf = RouteOfPid { SessionRoute.Head(HEAD) },
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
