// NEW: desktop-full (df-managed-launcher), split out of DesktopLaunch by concern (ConcentrationLawTest). The
// environment a Code tab session's program starts with under `splice desktop launch`: the variables SideBySideDesktop
// sets on an isolated Desktop taken out, so they neither redirect splice.toml resolution nor leak into the session;
// and, for Claude Code on this splice's door, the desktop key put where Claude Code reads it: AWS_BEARER_TOKEN_BEDROCK
// for a Mantle base, and for a gateway base the variable Desktop names in CLAUDE_CODE_HOST_AUTH_ENV_VAR
// (ANTHROPIC_AUTH_TOKEN, the bearer, by default). Any other launch runs as it came, with no key of splice's. It also
// says where Claude Code keeps the records of running sessions, which a live switch reads the session id from.
package splice.app.cli.desktop

import splice.app.cli.AdminSupport
import splice.app.desktop.DESKTOP_PATH
import splice.app.desktop.DOOR_PATH
import splice.app.desktop.pipe.LegEnvironment
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.setup.CLAUDE_SPLICE_DESKTOP_ENV
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
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/** The session environment for a launch under [env] whose state folder is [state] (null: the usual one), with
 *  [inheritedEnv] laid over what programs inherit; what goes wrong is said to [err]. */
internal class DesktopLaunchEnv(
    private val env: EnvReader,
    private val err: TerminalOutput,
    private val inheritedEnv: Map<String, String>,
    state: String?,
) {
    private val sideBySide = env(CLAUDE_SPLICE_DESKTOP_ENV) == "1"

    /** The environment splice reads for this launch: the side-by-side variables absent, the state folder named. */
    private val here: EnvReader = run {
        val session = if (sideBySide) EnvReader { if (it in SIDE_BY_SIDE_ENV) null else env(it) } else env
        state?.let { dir -> EnvReader { if (it == STATE_DIR_ENV) dir else session(it) } } ?: session
    }

    /** Sets up each program's environment: Claude Code keyed on the door, the translator given no key. */
    val programs: LegEnvironment = LegEnvironment { vars, claudeCode ->
        if (inheritedEnv.isNotEmpty()) vars.putAll(inheritedEnv)
        if (sideBySide) SIDE_BY_SIDE_ENV.forEach(vars::remove)
        if (claudeCode) keyed()?.let { name -> vars[name] = key() }
    }

    /** Where Claude Code keeps the records of running sessions: under CLAUDE_CONFIG_DIR when Desktop sets it. */
    fun sessions(): Path {
        val config = here(CLAUDE_CONFIG_DIR)?.takeIf(String::isNotBlank)?.let(PathProbe::spelled)
            ?: UserHome.claudeDir(here)
        return config.resolve(SESSIONS_DIR)
    }

    /** The variable the desktop key goes in for this launch, or null when it is not on this splice's door. */
    private fun keyed(): String? {
        val port = AdminSupport.controlPort(topology(), here)
        return when {
            onDoor(env(MANTLE_BASE), port) -> MANTLE_BEARER
            onDoor(env(GATEWAY_BASE), port) -> env(HOST_AUTH_VAR)?.takeIf { it in GATEWAY_KEYS } ?: GATEWAY_BEARER
            else -> null
        }
    }

    /** splice.toml as it is, read without writing a starter one where there is none (Desktop runs this before every
     *  Code tab session); null where it is absent or unreadable, and the state and environment layers name the port. */
    private fun topology(): Topology? {
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

    private fun key(): String =
        DesktopKey(StatePaths(envReader = here).desktopKeyFile) { line -> err.line(line.trimEnd()) }.get()
}

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

private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")

// Variables SideBySideDesktop sets on the isolated Desktop process (plus Desktop's beta flag) that must neither
// redirect splice.toml resolution nor leak into Code tab sessions.
private val SIDE_BY_SIDE_ENV = setOf(
    "XDG_CONFIG_HOME",
    "CHROME_DESKTOP",
    CLAUDE_SPLICE_DESKTOP_ENV,
    "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS",
)
