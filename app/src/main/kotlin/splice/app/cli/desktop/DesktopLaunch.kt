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
package splice.app.cli.desktop

import splice.app.cli.AdminSupport
import splice.app.desktop.DESKTOP_PATH
import splice.app.desktop.DOOR_PATH
import splice.app.desktop.DesktopKey
import splice.core.config.STATE_DIR_ENV
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.util.concurrent.Executors

internal class DesktopLaunch(private val env: EnvReader, private val err: TerminalOutput) {
    /** [args] run as a command, given the desktop key when it launches on this splice's door; its exit code. */
    fun run(args: List<String>): Int {
        val state = args.takeIf { it.firstOrNull() == STATE_DIR_FLAG }?.getOrNull(1)
        val command = if (state != null) args.drop(2) else args
        if (command.isEmpty()) {
            err.line("usage: splice desktop launch <command> [args...]")
            return LAUNCH_USAGE_EXIT
        }
        val here = state?.let { dir -> EnvReader { if (it == STATE_DIR_ENV) dir else env(it) } } ?: env
        val process = ProcessBuilder(command).inheritIO()
        keyed(here)?.let { name -> process.environment()[name] = key(here) }
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

private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")

// Shell convention for invalid command arguments.
private const val LAUNCH_USAGE_EXIT = 2

// A shell's exit for a command it could not run.
private const val NOT_STARTED_EXIT = 127
