// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). `splice desktop acp`: the program Claude
// Desktop's launcher runs in place of `claude` for a `claude-antigravity--` model. It reads Desktop's stream-json on
// its standard input and writes Desktop's stream-json on its standard output, and runs the session on Google's own
// `agy acp`, started unmodified in the project's folder (`--add-dir` passed on, `--dangerously-skip-permissions` when
// Desktop starts in `bypassPermissions`). It keeps the transcript and the session record a `claude` keeps, under
// CLAUDE_CONFIG_DIR when Desktop sets it and `~/.claude` otherwise.
package splice.provider.antigravity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.antigravity.hooks.AcpSessionHooks
import splice.provider.antigravity.skills.ClaudeSkillCatalog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One Desktop session started with Desktop's `claude` [args] in [cwd], under [env], its agy read on [io]; what
 *  goes wrong outside the conversation goes to [log]. [agy] is the command that starts agy, [clock] stamps the
 *  transcript. */
public class DesktopAcp(
    private val args: List<String>,
    private val cwd: Path,
    private val env: EnvReader,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val agy: List<String> = agyCommand,
) {
    /** Runs the session, Desktop's lines read from [input] and the session's written to [output], until Desktop
     *  closes [input] or switches to a non-Antigravity model; the exit code. */
    public suspend fun run(
        input: InputStream,
        output: OutputStream,
        hooks: AcpSessionHooks = AcpSessionHooks(),
    ): Int = coroutineScope {
        val lines = Channel<String>(Channel.UNLIMITED)
        val reader = startInputReader(input, lines)
        val claude = ClaudeArgsReader().of(args)
        val started = AgyLaunch(agy, cwd, io).start(claude, this)
        try {
            serve(ServeTarget(claude, started, hooks), lines, output, this)
        } finally {
            reader.shutdownNow()
            started.close(log)
        }
        0
    }

    /** Reads blocking [input] lines on a daemon executor so an early exit (live switch back to Claude while Desktop's
     *  stdin pipe stays open) returns immediately without blocking coroutineScope completion. */
    private fun startInputReader(input: InputStream, lines: Channel<String>): ExecutorService {
        val pool = Executors.newSingleThreadExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "splice-desktop-acp-stdin"
                isDaemon = true
            }
        }
        pool.execute {
            try {
                input.bufferedReader(StandardCharsets.UTF_8).useLines { read ->
                    read.forEach { lines.trySend(it) }
                }
            } catch (_: IOException) {
                // Stream closed or interrupted on exit.
            } finally {
                lines.close()
            }
        }
        return pool
    }

    private data class ServeTarget(val claude: ClaudeArgs, val started: StartedAgy, val hooks: AcpSessionHooks)

    private suspend fun serve(
        target: ServeTarget,
        lines: ReceiveChannel<String>,
        output: OutputStream,
        scope: CoroutineScope,
    ) {
        val claude = target.claude
        val started = target.started
        val (hello, helloFailure) = started.connection?.let { AgyOpener(it, cwd.toString()).sayHello() }
            ?: (null to started.reason)
        val out = synchronizedOut(output)
        val mcpState = McpState()
        val deferredLines = ArrayDeque<String>()
        val connected = object : BridgeConnected {
            override fun connected(serverName: String) = mcpState.markConnected(serverName)
        }
        McpBridge(out, connected).use { bridge ->
            val envTarget = CoordinatorEnv(claude, STRICT_MCP_CONFIG in args, cwd, env, log)
            val chan = CoordinatorIo(started, hello, helloFailure, lines, deferredLines, scope)
            val coordinator = AgyMcpCoordinator(envTarget, chan, mcpState, bridge)
            val factory = PartsFactory { id -> parts(id, claude, claude.model.orEmpty(), out) }
            val session = DesktopSession(claude, factory, coordinator, mcpState, bridge, clock, target.hooks)
            try {
                loop(session, lines, deferredLines, started)
            } finally {
                session.closeRecord()
            }
        }
    }

    private fun synchronizedOut(output: OutputStream): DesktopOut = DesktopOut { line: JsonObject ->
        synchronized(output) {
            JsonWire.write(line, output)
            output.write(STREAM_LINE_END)
            output.flush()
        }
    }

    private fun parts(id: String, claude: ClaudeArgs, model: String, out: DesktopOut): DesktopParts {
        val config = env(DESKTOP_CONFIG_VARIABLE)?.takeIf(String::isNotBlank)?.let(Path::of) ?: UserHome.claudeDir(env)
        val version = claude.version
        val entrypoint = env(ENTRYPOINT_VARIABLE) ?: ENTRYPOINT
        val folder = cwd.toString()
        val facts = SessionFacts(ProcessHandle.current().pid(), id, folder, version, entrypoint, env(HOST_SESSION))
        val localSkills = ClaudeSkillCatalog(cwd, config, log).load()
        return DesktopParts(
            DesktopFrames(id),
            out,
            ClaudeTranscript(config, TranscriptOwner(id, folder, version, entrypoint), clock),
            ClaudeSessionRecord(config, facts, clock, log),
            SessionLook(folder, model, claude.permissionMode, emptyList(), version, skills = localSkills.skillNames),
            localSkills,
        )
    }

    /** Hands Desktop's lines and agy's messages to [session], in the order each arrives, until Desktop is gone or the
     *  session hands back to Claude Code. */
    private suspend fun loop(
        session: DesktopSession,
        lines: ReceiveChannel<String>,
        deferredLines: ArrayDeque<String>,
        started: StartedAgy,
    ) {
        while (!session.shouldExit) {
            if (!stepLoop(session, lines, deferredLines, started)) return
        }
    }

    private suspend fun stepLoop(
        session: DesktopSession,
        lines: ReceiveChannel<String>,
        deferredLines: ArrayDeque<String>,
        started: StartedAgy,
    ): Boolean {
        if (!session.switchingOut && deferredLines.isNotEmpty()) {
            session.desktop(deferredLines.removeFirst())
            return true
        }
        val fromAgy = session.fromAgy
        val heard = select {
            if (fromAgy != null) fromAgy.onReceiveCatching { Heard.Agy(it.getOrNull()) }
            if (!session.switchingOut) lines.onReceiveCatching { Heard.Desktop(it.getOrNull()) }
        }
        return when (heard) {
            is Heard.Desktop -> {
                val line = heard.line ?: return false
                session.desktop(line)
                true
            }
            is Heard.Agy -> {
                heard.message?.let(session::agy)
                    ?: session.agyEnded("agy ended" + started.connection?.lastWords().orEmpty())
                true
            }
        }
    }
}

/** What the session heard next: a line of Desktop's (null once Desktop is gone) or a message of agy's (null once agy
 *  ended). */
private sealed class Heard {
    class Desktop(val line: String?) : Heard()

    class Agy(val message: JsonObject?) : Heard()
}

private const val DESKTOP_CONFIG_VARIABLE = "CLAUDE_CONFIG_DIR"
private const val ENTRYPOINT_VARIABLE = "CLAUDE_CODE_ENTRYPOINT"
private const val HOST_SESSION = "CLAUDE_CODE_HOST_SESSION_ID"
private const val ENTRYPOINT = "sdk-cli"
private const val STREAM_LINE_END = '\n'.code
