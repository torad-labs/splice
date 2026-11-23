// NEW: desktop-full (docs/specs/desktop-full.md, df-managed-launcher). `splice desktop launch <command> [args...]`:
// what a managed `processWrapper` runs the Code tab's Claude Code through. When Claude Code's managed settings name a
// process wrapper, Claude Desktop starts the Code tab through it with every credential stripped from the launch
// (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, CLAUDE_CODE_OAUTH_TOKEN, AWS_BEARER_TOKEN_BEDROCK) and
// CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1, so a session on splice's door has no key to send. This runs the command with
// its own arguments and exits with its exit code; when the launch's base address is this splice's door, it first puts
// the desktop key where Claude Code reads it: AWS_BEARER_TOKEN_BEDROCK for a Mantle base, and for a gateway base the
// variable Desktop names in CLAUDE_CODE_HOST_AUTH_ENV_VAR (ANTHROPIC_AUTH_TOKEN, the bearer, by default). Any other
// launch runs as it came, with no key of splice's. The command may be another wrapper followed by Claude Code's own
// command line. Like the credential helper, it reads the key from splice's state folder and needs no running daemon;
// `<state dir>/desktop-launch` (DesktopPrograms) names that folder with `--state-dir`.
//
// desktop-antigravity (docs/specs/desktop-antigravity.md, decisions 3 and 5): a session Desktop starts on a
// `claude-antigravity--` model runs on `splice desktop acp` in place of Claude Code, and a stream-json session runs in
// the pipe (DesktopPipe), so a live switch between Antigravity and any other model changes the program under it. A
// launch that carries the no-new-privileges bit on Linux runs itself again outside it first (PrivilegeEscape).
package splice.app.cli.desktop

import kotlinx.coroutines.CoroutineDispatcher
import splice.app.cli.AdminSupport
import splice.app.desktop.DESKTOP_PATH
import splice.app.desktop.DOOR_PATH
import splice.app.desktop.pipe.ClaudeLineReader
import splice.app.desktop.pipe.DesktopPipe
import splice.app.desktop.pipe.LegEnvironment
import splice.app.desktop.pipe.PipeLegs
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.setup.CLAUDE_SPLICE_DESKTOP_ENV
import splice.app.desktop.setup.SpliceSelf
import splice.core.config.STATE_DIR_ENV
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.PathProbe
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/** The launch under [env], what goes wrong said to [err]. [inheritedEnv] is laid over the environment programs start
 *  with, [escape] decides whether it runs again outside the no-new-privileges bit, [acp] is the command that runs
 *  `splice desktop acp`, [input] is Desktop's standard input and [io] waits on programs. */
internal class DesktopLaunch(
    private val env: EnvReader,
    private val err: TerminalOutput,
    private val inheritedEnv: Map<String, String> = emptyMap(),
    private val escape: PrivilegeEscape = PrivilegeEscape(env),
    private val acp: List<String> = SpliceSelf().command() + listOf("desktop", "acp"),
    private val input: InputStream = System.`in`,
    private val io: CoroutineDispatcher = ProcessDispatchers().io(),
) {
    /** [args] run as a command, given the desktop key when it launches on this splice's door; its exit code. */
    suspend fun run(args: List<String>): Int {
        val state = args.takeIf { it.firstOrNull() == STATE_DIR_FLAG }?.getOrNull(1)
        val command = if (state != null) args.drop(2) else args
        if (command.isEmpty()) {
            err.line("usage: splice desktop launch <command> [args...]")
            return LAUNCH_USAGE_EXIT
        }
        return escaped(args) ?: here(command, state)
    }

    /** [command] run in this process's place; its exit code. A stream-json session runs in the pipe, so a live switch
     *  can change its program, and one on an Antigravity model runs on `splice desktop acp` from the start. */
    private suspend fun here(command: List<String>, state: String?): Int {
        val sideBySide = env(CLAUDE_SPLICE_DESKTOP_ENV) == "1"
        val here = sessionEnv(sideBySide, state)
        val environment = LegEnvironment { vars, claudeCode -> configureEnv(vars, here, sideBySide, claudeCode) }
        val line = ClaudeLineReader().of(command)
        if (!line.streamJson && !line.antigravity) return direct(command, environment)
        return DesktopPipe(PipeLegs(acp, environment, sessions(here)), io, err).run(line, input)
    }

    /** [command] run with Desktop's own streams, as it came; its exit code. */
    private fun direct(command: List<String>, environment: LegEnvironment): Int {
        val process = ProcessBuilder(command).inheritIO()
        environment.apply(process.environment(), claudeCode = true)
        return try {
            val child = process.start()
            // Desktop stopping the wrapper stops the session it started, rather than leaving it running alone.
            val stop = Executors.defaultThreadFactory().newThread { child.destroy() }
            Runtime.getRuntime().addShutdownHook(stop)
            child.waitFor().also { Runtime.getRuntime().removeShutdownHook(stop) }
        } catch (e: IOException) {
            err.line("splice desktop launch: ${command.first()} could not be started: ${SafeFailureText.render(e)}")
            NOT_STARTED_EXIT
        }
    }

    /** The session's exit code once this launch ran again outside the no-new-privileges bit; null when it runs here. */
    private fun escaped(args: List<String>): Int? {
        val unit = "$UNIT_PREFIX${ProcessHandle.current().pid()}"
        val names = ProcessBuilder().environment().keys + inheritedEnv.keys
        val command = escape.command(SpliceSelf().command() + listOf("desktop", "launch") + args, names, unit)
            ?: return null
        val systemctl = escape.program(SYSTEMCTL)?.toString() ?: SYSTEMCTL
        return EscapedLaunch(command, unit, systemctl, err).run()
    }

    private fun sessionEnv(sideBySide: Boolean, state: String?): EnvReader {
        val session = if (sideBySide) EnvReader { if (it in SIDE_BY_SIDE_ENV) null else env(it) } else env
        return state?.let { dir -> EnvReader { if (it == STATE_DIR_ENV) dir else session(it) } } ?: session
    }

    private fun configureEnv(vars: MutableMap<String, String>, here: EnvReader, sideBySide: Boolean, keyed: Boolean) {
        if (inheritedEnv.isNotEmpty()) vars.putAll(inheritedEnv)
        if (sideBySide) SIDE_BY_SIDE_ENV.forEach(vars::remove)
        if (keyed) keyed(here)?.let { name -> vars[name] = key(here) }
    }

    /** Where Claude Code keeps the records of running sessions: under CLAUDE_CONFIG_DIR when Desktop sets it. */
    private fun sessions(here: EnvReader): Path {
        val config = here(CLAUDE_CONFIG_DIR)?.takeIf(String::isNotBlank)?.let(PathProbe::spelled)
            ?: UserHome.claudeDir(here)
        return config.resolve(SESSIONS_DIR)
    }

    /** The variable the desktop key goes in for this launch, or null when it is not on this splice's door. */
    private fun keyed(here: EnvReader): String? {
        val port = AdminSupport.controlPort(topology(here), here)
        return when {
            onDoor(env(MANTLE_BASE), port) -> MANTLE_BEARER
            onDoor(env(GATEWAY_BASE), port) -> env(HOST_AUTH_VAR)?.takeIf { it in GATEWAY_KEYS } ?: GATEWAY_BEARER
            else -> null
        }
    }

    /** splice.toml as it is, read without writing a starter one where there is none (Desktop runs this before every
     *  Code tab session); null where it is absent or unreadable, and the state and environment layers name the port. */
    private fun topology(here: EnvReader): Topology? {
        val path = TopologyLoader.configPath(here)
        if (!Files.isRegularFile(path)) return null
        return Cancellables.runCatchingCancellable { TopologyLoader.parse(Files.readString(path)) }
            .onFailure { err.line("splice desktop launch: $path could not be read (${SafeFailureText.render(it)})") }
            .getOrNull()
    }

    /** Whether [url] is this machine's door on control port [port]; an address that does not parse is not. */
    private fun onDoor(url: String?, port: Int): Boolean {
        val uri = try {
            URI(url?.takeIf(String::isNotBlank) ?: return false)
        } catch (_: URISyntaxException) {
            return false
        }
        return uri.host in LOOPBACK && uri.port == port && uri.path.orEmpty().trimEnd('/') in DOOR_PATHS
    }

    private fun key(here: EnvReader): String =
        DesktopKey(StatePaths(envReader = here).desktopKeyFile) { line -> err.line(line.trimEnd()) }.get()
}

// How `<state dir>/desktop-launch` names splice's state folder, ahead of the command.
internal const val STATE_DIR_FLAG = "--state-dir"

// The door's paths on the control port: today's, and the one a Desktop set up before it had two callers.
private val DOOR_PATHS = setOf(DOOR_PATH, DESKTOP_PATH)

// Where Claude Code finds the base address, and where it reads the key, under Mantle and under a gateway.
private const val MANTLE_BASE = "ANTHROPIC_BEDROCK_MANTLE_BASE_URL"
private const val MANTLE_BEARER = "AWS_BEARER_TOKEN_BEDROCK"
private const val GATEWAY_BASE = "ANTHROPIC_BASE_URL"
private const val GATEWAY_BEARER = "ANTHROPIC_AUTH_TOKEN"
private val GATEWAY_KEYS = setOf(GATEWAY_BEARER, "ANTHROPIC_API_KEY")

// The variable Desktop names a host-managed credential in.
private const val HOST_AUTH_VAR = "CLAUDE_CODE_HOST_AUTH_ENV_VAR"

// Where Desktop points Claude Code's folder, and the folder in it of running sessions' records.
private const val CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR"
private const val SESSIONS_DIR = "sessions"

// The user unit a launch that escapes the no-new-privileges bit runs as, with this process's pid after it.
private const val UNIT_PREFIX = "splice-desktop-launch-"

private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")

// Shell convention for invalid command arguments.
private const val LAUNCH_USAGE_EXIT = 2

// A shell's exit for a command it could not run.
private const val NOT_STARTED_EXIT = 127

// Variables SideBySideDesktop sets on the isolated Desktop process (plus Desktop's beta flag) that must neither
// redirect splice.toml resolution nor leak into Code tab sessions.
private val SIDE_BY_SIDE_ENV = setOf(
    "XDG_CONFIG_HOME",
    "CHROME_DESKTOP",
    CLAUDE_SPLICE_DESKTOP_ENV,
    "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS",
)
