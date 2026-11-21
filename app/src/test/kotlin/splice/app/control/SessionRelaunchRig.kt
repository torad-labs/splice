// The rig the relaunch route tests share (SessionRelaunchRouteTest, DoorSessionRelaunchRouteTest): a real
// ControlServer over a registry of running Claude Code sessions, their processes as /proc shows them, and a terminal
// that records what it is asked and behaves as tmux and Claude Code would. Split out of SessionRelaunchRouteTest when
// door sessions (door-sessions, Nov 20, 2026) took their own tests, so neither class carries the other's.
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
import splice.core.config.UserHome
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.process.LaunchOwners
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.session.SessionPanes
import splice.core.session.SessionTerminal
import splice.core.terminal.TerminalOutput
import splice.core.topology.LaunchDoor
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

private const val HEAD = "claude"

/** A registration's status at rest at its prompt, as Claude Code writes it. */
private const val AT_REST = """"status":"idle""""

/** The model each session's transcript last ran, which its row reports. */
private const val ROW_MODEL = "claude-opus-5-5"

// why: above the kernel's pid_max (4194304), so no process on the machine running the test answers to these pids
private const val FIRST_PID = 5_000_001L

/** The daemon serving the head `claude`, the sessions it relaunches and the terminal they run in. */
internal abstract class SessionRelaunchRig {

    @TempDir
    lateinit var home: Path

    protected val bin by lazy { Files.createDirectories(home.resolve(".local/bin")) }
    protected val share by lazy { Files.createDirectories(home.resolve(".local/share/splice")) }
    protected val paths by lazy { StatePaths(baseOverride = home.resolve("state")) }
    protected val sessionsDir by lazy { Files.createDirectories(home.resolve(".claude/sessions")) }
    private val headDir by lazy { home.resolve(".claude-claude") }
    protected val realClaude by lazy { home.resolve("claude-real").also { Files.writeString(it, "#!/bin/sh\n") } }

    /** The pids that run, as the registry and the terminal see them. */
    private val alive = ConcurrentHashMap.newKeySet<Long>()

    /** The pids whose sessions talk to their provider directly, on no splice command; every other runs on [HEAD]. */
    protected val direct = ConcurrentHashMap.newKeySet<Long>()

    /** The door each session started on one runs on, by session: each client of it, a relaunch's too, is on it. */
    protected val doorOf = ConcurrentHashMap<String, LaunchDoor>()

    /** The session each pid registered. */
    private val sessionOf = ConcurrentHashMap<Long, String>()
    private var nextPid = FIRST_PID + 1_000
    protected val terminal = RecordingTerminal()
    protected val panes = KnownPanes()

    /** The processes as /proc shows them, read by the daemon and by `splice sessions relaunch` alike. */
    protected val processes by lazy {
        ProcessEnvironment(home.resolve("proc"), LaunchOwners(home.resolve("owners")), DarwinPs(here = false))
    }

    /** What [body], a refusal the daemon answered, says in [field]. */
    protected fun said(body: String, field: String): String? =
        Json.parseToJsonElement(body).jsonObject[field]?.jsonPrimitive?.content

    /** The folder [session] runs in. */
    protected fun cwd(session: String): Path = Files.createDirectories(home.resolve("work").resolve(session.take(8)))

    /** [session] running as [pid] in a pane of its own: its registration, the words and environment its process
     *  shows, and the transcript under [transcripts] whose last turn ran on [ROW_MODEL]. */
    protected fun start(
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
    protected fun register(session: String, pid: Long, at: Long = System.currentTimeMillis(), state: String = AT_REST) {
        alive += pid
        sessionOf[pid] = session
        Files.writeString(
            sessionsDir.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"$session","cwd":"${cwd(session)}",$state,"updatedAt":$at}""",
        )
    }

    /** A daemon serving [HEAD], whose `claude` is [wrapped], with this rig's registry, processes and terminal. */
    protected fun serve(wrapped: Boolean = true, test: suspend (Daemon) -> Unit) =
        UserHome.within(home) {
            val launch = launchService(wrapped)
            assertEquals(
                wrapped,
                WrappedClaudeLaunch(launch.wrap).of(HEAD) != null,
                "his claude is wrapped as `splice wrap` leaves it, or left alone",
            )
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
    protected inner class Daemon(private val client: HttpClient, private val port: Int, private val key: String) {
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
        routeOf = RouteOfPid { pid ->
            val door = sessionOf[pid]?.let { doorOf[it] }
            when {
                door != null -> SessionRoute.Door(door)
                pid in direct -> SessionRoute.Direct
                else -> SessionRoute.Head(HEAD)
            }
        },
        pidAlive = PidAlive { it in alive },
    )

    /** The launch the daemon serves, its wrap under [home]: `claude` linked to the launch shim, the real binary
     *  kept. */
    private fun launchService(wrapped: Boolean): LaunchService {
        Files.writeString(share.resolve("splice-launch"), "#!/bin/sh\n")
        Files.createSymbolicLink(bin.resolve("claude"), realClaude)
        val wrap = WrappedHead(
            home = home,
            installPaths = InstallPaths(binOverride = bin, shareOverride = share),
            stateStore = WrapStateStore(file = home.resolve("state/claude-head-wrap.json")),
        )
        if (wrapped) wrap.wrap()
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
            signIn = LaunchSignIn(loginCommand = "claude login", signInLabel = "Claude", headKey = HEAD),
            gateway = LaunchGateway(
                statuslineCommand = "\"/bin/curl\" -s :3096/statusline/claude",
                port = 3104,
                bearer = GatewayBearer.Planted("test-token"),
                apiTimeoutMs = 960_000,
                forwardClientAuth = true,
            ),
            policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        ),
    )

    /** One start in place: the pane, the command and the folder. */
    protected data class Restart(val pane: SessionPane, val command: List<String>, val directory: String)

    protected class KnownPanes : SessionPanes {
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
    protected inner class RecordingTerminal : SessionTerminal {
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
