// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). `splice desktop acp`: the program Claude
// Desktop's launcher runs in place of `claude` for a `claude-antigravity--` model. It reads Desktop's stream-json on
// its standard input and writes Desktop's stream-json on its standard output, and runs the session on Google's own
// `agy acp`, started unmodified in the project's folder (`--add-dir` passed on, `--dangerously-skip-permissions` when
// Desktop starts in `bypassPermissions`). It keeps the transcript and the session record a `claude` keeps, under
// CLAUDE_CONFIG_DIR when Desktop sets it and `~/.claude` otherwise.
//
// The session's id is the one Desktop gave (`--session-id`), or the one it takes up (`--resume`), or agy's own for a
// new session. A `--resume` agy does not know (a session that began on Claude, decision 5) opens a new agy session
// under Desktop's id instead of failing. A session agy can't open (signed out, not installed) still starts: Desktop's
// `initialize` is answered, and each turn says what is wrong. It ends when Desktop closes its input.
package splice.provider.antigravity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

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
     *  closes [input]; the exit code. */
    public suspend fun run(input: InputStream, output: OutputStream): Int = coroutineScope {
        val lines = Channel<String>(Channel.UNLIMITED)
        launch(io) {
            input.bufferedReader(StandardCharsets.UTF_8).useLines { read -> read.forEach { lines.trySend(it) } }
            lines.close()
        }
        val claude = ClaudeArgsReader().of(args)
        val started = AgyLaunch(agy, cwd, io).start(claude, this)
        try {
            serve(claude, started, lines, output)
        } finally {
            started.close(log)
        }
        0
    }

    private suspend fun serve(
        claude: ClaudeArgs,
        started: StartedAgy,
        lines: ReceiveChannel<String>,
        output: OutputStream,
    ) {
        val opened = started.connection?.let { AgyOpener(it, cwd.toString()).open(claude.resume) }
            ?: AgyOpen.Unusable(started.reason)
        val agySession = (opened as? AgyOpen.Ready)?.session
        val id = claude.sessionId ?: claude.resume ?: agySession?.id ?: UUID.randomUUID().toString()
        val model = claude.model.orEmpty()
        agySession?.let { prepare(it, claude) }
        val parts = parts(id, claude, model, output)
        parts.record.write(busy = false)
        try {
            loop(DesktopSession(parts, agySession, (opened as? AgyOpen.Unusable)?.reason, clock), lines, started)
        } finally {
            parts.record.remove()
        }
    }

    /** Puts agy's session on the model, effort and mode Desktop started with; what agy refused is logged. */
    private suspend fun prepare(session: AgySession, claude: ClaudeArgs) {
        val refusals = listOfNotNull(
            claude.model?.let { session.model(it) },
            claude.effort?.let { session.effort(it) },
            session.mode(claude.permissionMode),
        )
        refusals.forEach { log("[antigravity] $it\n") }
    }

    private fun parts(id: String, claude: ClaudeArgs, model: String, output: OutputStream): DesktopParts {
        val config = env(DESKTOP_CONFIG_VARIABLE)?.takeIf(String::isNotBlank)?.let(Path::of) ?: UserHome.claudeDir(env)
        val version = claude.version
        val entrypoint = env(ENTRYPOINT_VARIABLE) ?: ENTRYPOINT
        val folder = cwd.toString()
        val facts = SessionFacts(ProcessHandle.current().pid(), id, folder, version, entrypoint, env(HOST_SESSION))
        val out = DesktopOut { line: JsonObject ->
            JsonWire.write(line, output)
            output.write(STREAM_LINE_END)
            output.flush()
        }
        return DesktopParts(
            DesktopFrames(id),
            out,
            ClaudeTranscript(config, TranscriptOwner(id, folder, version, entrypoint), clock),
            ClaudeSessionRecord(config, facts, clock, log),
            SessionLook(folder, model, claude.permissionMode, emptyList(), version),
        )
    }

    /** Hands Desktop's lines and agy's messages to [session], in the order each arrives, until Desktop is gone. */
    private suspend fun loop(session: DesktopSession, lines: ReceiveChannel<String>, started: StartedAgy) {
        while (true) {
            val fromAgy = session.fromAgy
            // agy first: what it already said (its commands, a finished update) is in place before Desktop's next line
            // is answered; agy's output is finite, so Desktop's lines wait at most for what agy has already written.
            val heard = select {
                if (fromAgy != null) fromAgy.onReceiveCatching { Heard.Agy(it.getOrNull()) }
                lines.onReceiveCatching { Heard.Desktop(it.getOrNull()) }
            }
            when (heard) {
                is Heard.Desktop -> session.desktop(heard.line ?: return)
                is Heard.Agy -> heard.message?.let(session::agy)
                    ?: session.agyEnded("agy ended" + started.connection?.lastWords().orEmpty())
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

/** Where Claude Code reads its config folder from, as Desktop may set it. */
private const val DESKTOP_CONFIG_VARIABLE = "CLAUDE_CONFIG_DIR"

/** What started Claude Code, as Desktop says it; the session record and transcript carry it. */
private const val ENTRYPOINT_VARIABLE = "CLAUDE_CODE_ENTRYPOINT"

/** Desktop's id for the session it hosts, which the session record carries. */
private const val HOST_SESSION = "CLAUDE_CODE_HOST_SESSION_ID"

/** Claude Code's entrypoint when nothing says what started it. */
private const val ENTRYPOINT = "sdk-cli"

private const val STREAM_LINE_END = '\n'.code
