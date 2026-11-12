// NEW: peer messaging, package B (docs/specs/peer-messaging.md 4.1) — how the daemon starts a peer, moved out of
// PeerSupervisor.kt: where peers live, which build serves them, and the detached command line. Since Oct 28, 2026
// (Marcos: "yeah replcace the java process per peer") one peer-host process serves every peer: a start asks it for
// the peer, and starts the host only when none of this build runs, after asking one of an older build to make way.
// Under a systemd unit the host runs in its own `systemd-run --user --scope`, because a process left in
// splice.service's cgroup is killed with the unit (KillMode=mixed); elsewhere it runs under setsid. Every standard
// stream is discarded.
package splice.app.peer

import splice.app.console.INVOCATION_ID
import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.sessions.peer.helper.HelperFile
import splice.sessions.peer.helper.HelperFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The jar's one process entry, Main.kt: LayerMapLawTest allows no second `main` in :app, so the host starts there. */
internal const val HELPER_MAIN_CLASS = "splice.app.MainKt"

/** The command Main.kt hands to PeerHostRun, the one process that serves every peer; a name no user command takes. */
internal const val PEER_HOST_COMMAND = "peer-host"

/** Where the daemon keeps peers: the settings and state folders, the registry folders, and the runtime folder. */
internal data class PeerPlaces(
    val settingsDir: Path,
    val stateDir: Path,
    val registryDirs: List<Path>,
    val runtimeDir: Path?,
)

/** The build a daemon or helper runs: the release and the jar it was started from. The release alone stays 0.4.0
 *  across every beta, so the jar's size and time are what tell one install from the next. */
internal class PeerBuild {
    fun current(): String {
        val jar = classPath().split(File.pathSeparator).firstOrNull().orEmpty()
        val stamp = Cancellables.runCatchingCancellable {
            val path = Path.of(jar)
            "${Files.size(path)}-${Files.getLastModifiedTime(path).toMillis()}"
        }.fold(onSuccess = { it }, onFailure = { "unknown" })
        return "$GATEWAY_VERSION+$stamp"
    }

    /** The jar this process runs from, for a helper's class path. */
    fun classPath(): String = System.getProperty("java.class.path").orEmpty()
}

/** Asks the peer host for peers, and starts the host, detached from the daemon. */
internal class HelperLaunch(
    private val places: PeerPlaces,
    private val env: EnvReader,
    private val build: PeerBuild = PeerBuild(),
) {
    private val files = PeerHostFiles(places.stateDir)
    private val helpers = HelperFiles()

    /** The host this launcher last started, so starts in a row start one host, not one each. */
    @Volatile
    private var started: Process? = null

    /** Asks the host of this build to serve [name], starting that host when none runs; why it could not, or null. */
    fun launch(name: String): String? {
        val current = build.current()
        return Cancellables.runCatchingCancellable {
            files.request(name, current)
            val host = running()
            if (host?.version != current && started?.isAlive != true) {
                // A host of an older build makes way; the new one waits for its lock until it has.
                host?.let { ProcessHandle.of(it.pid).ifPresent { process -> process.destroy() } }
                started = ProcessBuilder(detach() + hostCommand(current))
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }
        }.exceptionOrNull()?.let { SafeFailureText.render(it) }
    }

    /** The host's pid while host.json names a host that runs, for the supervisor's SIGHUP. */
    fun hostPid(): Long? = running()?.pid

    /** host.json's host while its pid runs with the procStart it recorded, judged as a helper.json is. */
    private fun running(): HostFile? = files.host()?.takeIf { host ->
        helpers.alive(HelperFile(host.pid, host.procStart, socket = "", host.startedAt, host.version))
    }

    private fun hostCommand(current: String): List<String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        val registries = places.registryDirs.flatMap { listOf("--registry", it.toString()) }
        val runtime = places.runtimeDir?.let { listOf("--runtime", it.toString()) }.orEmpty()
        return listOf(java, "-Xmx128m", "-XX:+UseSerialGC", "-cp", build.classPath(), HELPER_MAIN_CLASS) +
            listOf(PEER_HOST_COMMAND) +
            listOf(PEER_SETTINGS_FLAG, places.settingsDir.toString(), PEER_STATE_FLAG, places.stateDir.toString()) +
            registries + runtime + listOf(PEER_BUILD_FLAG, current)
    }

    /** Its own systemd scope under a unit, so the unit's restart never reaches it; its own session elsewhere. */
    private fun detach(): List<String> = when {
        !env(INVOCATION_ID).isNullOrBlank() && onPath("systemd-run") ->
            listOf("systemd-run", "--user", "--scope", "--quiet", "--unit=splice-peer-host-${System.nanoTime()}", "--")
        onPath("setsid") -> listOf("setsid")
        else -> emptyList()
    }

    private fun onPath(binary: String): Boolean = env("PATH").orEmpty().split(File.pathSeparator)
        .any { dir -> dir.isNotEmpty() && Files.isExecutable(Path.of(dir, binary)) }
}
