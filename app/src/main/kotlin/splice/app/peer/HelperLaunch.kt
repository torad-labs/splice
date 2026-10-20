// NEW: peer messaging, package B (docs/specs/peer-messaging.md 4.1) — how the daemon starts a peer helper, moved verbatim
// out of PeerSupervisor.kt: where peers live, which build a helper runs, and the detached command line. Under a systemd
// unit a helper runs in its own `systemd-run --user --scope`, because a process left in splice.service's cgroup is killed
// with the unit (KillMode=mixed); elsewhere it runs under setsid. Every standard stream is discarded.
package splice.app.peer

import splice.app.console.INVOCATION_ID
import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The main class a helper runs as (spec 4.1). */
internal const val HELPER_MAIN_CLASS = "splice.app.peer.PeerHelperMain"

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

/** Starts helper processes, detached from the daemon. */
internal class HelperLaunch(
    private val places: PeerPlaces,
    private val env: EnvReader,
    private val build: PeerBuild = PeerBuild(),
) {
    /** Starts [name]'s helper; why it could not, or null. */
    fun launch(name: String): String? {
        val argv = detach(name) + helperCommand(name)
        return Cancellables.runCatchingCancellable {
            ProcessBuilder(argv)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.exceptionOrNull()?.let { SafeFailureText.render(it) }
    }

    private fun helperCommand(name: String): List<String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        val registries = places.registryDirs.flatMap { listOf("--registry", it.toString()) }
        val runtime = places.runtimeDir?.let { listOf("--runtime", it.toString()) }.orEmpty()
        return listOf(java, "-Xmx48m", "-XX:+UseSerialGC", "-cp", build.classPath(), HELPER_MAIN_CLASS, name) +
            listOf("--settings", places.settingsDir.toString(), "--state", places.stateDir.toString()) +
            registries + runtime + listOf("--build", build.current())
    }

    /** Its own systemd scope under a unit, so the unit's restart never reaches it; its own session elsewhere. */
    private fun detach(name: String): List<String> = when {
        !env(INVOCATION_ID).isNullOrBlank() && onPath("systemd-run") ->
            listOf("systemd-run", "--user", "--scope", "--quiet", "--unit=splice-peer-$name-${System.nanoTime()}", "--")
        onPath("setsid") -> listOf("setsid")
        else -> emptyList()
    }

    private fun onPath(binary: String): Boolean = env("PATH").orEmpty().split(File.pathSeparator)
        .any { dir -> dir.isNotEmpty() && Files.isExecutable(Path.of(dir, binary)) }
}
