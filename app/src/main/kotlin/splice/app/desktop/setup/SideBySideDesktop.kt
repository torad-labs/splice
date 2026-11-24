// NEW: side-by-side-launchers. Prepares and launches a second, isolated Claude Desktop instance
// ("Claude (Splice)") pointed at Splice's door without touching the user's default Claude Desktop
// configuration or personal claude.ai login.
package splice.app.desktop.setup

import splice.app.LifecycleWiring
import splice.app.cli.AdminSupport
import splice.app.cli.desktop.DaemonUp
import splice.app.desktop.DOOR_PATH
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.record.DesktopRecords
import splice.client.desktop.DesktopAppConfig
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import java.nio.file.Files
import java.nio.file.Path

/** Spawns the resolved Claude Desktop executable with the isolated side-by-side environment. */
internal fun interface DesktopProcessStarter {
    fun start(command: List<String>, env: Map<String, String>): Int
}

internal class SideBySideDesktop(
    private val env: EnvReader = EnvReader(System::getenv),
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val daemon: DaemonUp = DaemonUp(LifecycleWiring::ensureDaemon),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val starter: DesktopProcessStarter = ProcessBuilderDesktopStarter,
) {
    private val paths = StatePaths(envReader = env)

    /** The isolated config root (`~/.config/claude-splice` on Linux, `~/Library/Application Support/claude-splice` on
     *  macOS) that `DesktopAppConfig.beside()` discovers automatically. */
    fun profileDir(): Path {
        val base = if (isMac()) {
            UserHome.dir(env).resolve("Library/Application Support")
        } else {
            env(SIDE_BY_SIDE_XDG_CONFIG_HOME)?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: UserHome.dir(env).resolve(".config")
        }
        return base.resolve(SIDE_BY_SIDE_DIR_NAME)
    }

    /** Prepares the side-by-side Desktop library pointing at [door] without modifying default Claude Desktop. */
    fun prepareProfile(door: String): Path {
        val profile = profileDir()
        Files.createDirectories(profile)
        val log = LogSink { err.line(it.trimEnd()) }
        val _ = DesktopKey(paths.desktopKeyFile, log).get()
        val records = DesktopRecords(paths.desktopFile, log)
        if (!records.get().on) records.update { it.copy(on = true) }
        val helper = DesktopPrograms(paths.stateDir).lay()
        val config = DesktopAppConfig(env = env, osName = osName, baseDir = profile)
        val overrides = DesktopPolicyOverrides(paths.desktopPolicyOverridesFile)
        val library = DesktopAppLibrary(
            config,
            profileSync = DesktopProfileSync(env, osName),
            overrides = overrides,
            log = log,
        )
        val applied = DesktopLibraryFiles().read(config.meta)?.let(config::applied)
        val alreadyOnDoor = applied?.let(library::doorOf)?.trimEnd('/') == door.trimEnd('/')
        if (alreadyOnDoor) {
            library.publish(door)
        } else {
            library.putIn(SIDE_BY_SIDE_CONFIG_ID, door, helper, held = null)
        }
        return profile
    }

    /** Ensures the daemon is running, prepares the isolated profile, and launches Claude Desktop side-by-side. */
    fun launch(extraArgs: List<String>): Int {
        val port = AdminSupport.controlPort(env)
        if (!daemon.ensure(port)) {
            err.line("splice: the daemon is not running and could not be started on port $port")
            return 1
        }
        val executable = findClaudeDesktop()
        if (executable == null) {
            err.line("splice: Claude Desktop was not found on this machine")
            return 1
        }
        val door = "http://127.0.0.1:$port$DOOR_PATH"
        val profile = prepareProfile(door)
        publishBestEffort(port)
        val command = buildCommand(executable, profile, extraArgs)
        val extraEnv = buildEnv(profile)
        out.line("splice: launching Claude (Splice) on $door (profile $profile)")
        return starter.start(command, extraEnv)
    }

    private fun publishBestEffort(port: Int) {
        Cancellables.runCatchingCancellable {
            val key = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key ?: return@runCatchingCancellable
            ControlPlaneClient.post("http://127.0.0.1:$port/api/desktop/publish", key, "{}", PUBLISH_TIMEOUT_MS)
        }
    }

    private fun buildCommand(executable: Path, profile: Path, extraArgs: List<String>): List<String> = when {
        isMac() -> listOf(executable.toString(), "--user-data-dir=${profile.resolve("Claude-3p")}") + extraArgs
        else -> listOf(executable.toString(), "--class=$SIDE_BY_SIDE_WM_CLASS") + extraArgs
    }

    private fun buildEnv(profile: Path): Map<String, String> = buildMap {
        if (!isMac()) {
            put(SIDE_BY_SIDE_XDG_CONFIG_HOME, profile.toString())
            put("CHROME_DESKTOP", SIDE_BY_SIDE_DESKTOP_FILE)
        }
        put(CLAUDE_SPLICE_DESKTOP_ENV, "1")
    }

    internal fun findClaudeDesktop(): Path? {
        val candidates = if (isMac()) macCandidates() else linuxCandidates()
        return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private fun macCandidates(): List<Path> = listOf(
        Path.of("/Applications/Claude.app/Contents/MacOS/Claude"),
        UserHome.dir(env).resolve("Applications/Claude.app/Contents/MacOS/Claude"),
    )

    private fun linuxCandidates(): List<Path> {
        val fromPath = env("PATH").orEmpty()
            .split(':')
            .filter { it.isNotBlank() }
            .map { Path.of(it, "claude-desktop") }
        return fromPath + listOf(
            Path.of("/usr/bin/claude-desktop"),
            Path.of("/usr/local/bin/claude-desktop"),
            Path.of("/opt/Claude/claude-desktop"),
        )
    }

    private fun isMac(): Boolean = osName.startsWith("Mac", ignoreCase = true)
}

private object ProcessBuilderDesktopStarter : DesktopProcessStarter {
    override fun start(command: List<String>, env: Map<String, String>): Int {
        val builder = ProcessBuilder(command).inheritIO()
        builder.environment().putAll(env)
        builder.start()
        return 0
    }
}

internal const val SIDE_BY_SIDE_DIR_NAME: String = "claude-splice"
internal const val SIDE_BY_SIDE_WM_CLASS: String = "ai.torad.claude-splice"
internal const val SIDE_BY_SIDE_DESKTOP_FILE: String = "ai.torad.claude-splice.desktop"
internal const val SIDE_BY_SIDE_APP_NAME: String = "Claude (Splice)"
internal const val CLAUDE_SPLICE_DESKTOP_ENV: String = "CLAUDE_SPLICE_DESKTOP"

private const val SIDE_BY_SIDE_CONFIG_ID = "splice-side-by-side"
private const val SIDE_BY_SIDE_XDG_CONFIG_HOME = "XDG_CONFIG_HOME"

// why: best-effort first-launch publish must not stall launching Claude Desktop if the daemon is busy or older
private const val PUBLISH_TIMEOUT_MS = 3_000
