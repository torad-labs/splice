// NEW: V4-365 — arm a raw CLI cold start after an unsupervised daemon has finished its own drain.
package splice.lifecycle.restart

import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** Arms a successor before a self-drain; false leaves the serving daemon untouched. */
public fun interface DaemonSuccessor {
    public fun start(): Boolean
}

/** The raw side of the CLI's cold start, reached only after this daemon exits. The shell waits for
 *  its parent's exit before invoking `splice restart --now`: that verb resolves the same config and
 *  waits for the released port and lock before spawning its own detached daemon. Starting the CLI
 *  earlier would see this daemon's /health and stop it from inside its own restart request. */
public class DetachedDaemonSuccessor(
    private val jar: Path?,
    private val home: Path,
    private val config: Path?,
    private val state: Path,
    private val controlPort: Int,
    private val logs: Path,
    private val log: LogSink,
    private val parentPid: Long = ProcessHandle.current().pid(),
) : DaemonSuccessor {
    override fun start(): Boolean {
        if (jar == null || !Files.isRegularFile(jar)) {
            log("[control] restart: the running jar is not readable; leaving the daemon up\n")
            return false
        }
        if (!Files.isReadable(jar)) {
            log("[control] restart: the running jar is not readable; leaving the daemon up\n")
            return false
        }
        return Cancellables.runCatchingCancellable {
            val _ = SecureFile.ownerOnlyDirectory(logs)
            val builder = ProcessBuilder(command(jar))
                .directory(home.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("daemon-boot.log").toFile()))
                .redirectErrorStream(true)
            builder.environment().putAll(selectors(jar))
            builder.start()
        }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                log("[control] restart: could not start successor (${LogSafe.str(SafeFailureText.render(failure))})\n")
                false
            },
        )
    }

    internal fun command(jar: Path): List<String> = listOf(
        "sh",
        "-c",
        WAIT_FOR_EXIT,
        "sh",
        parentPid.toString(),
        jar.toString(),
    )

    internal fun selectors(jar: Path): Map<String, String> = buildMap {
        put("HOME", home.toString())
        config?.let { put("SPLICE_CONFIG", it.toString()) }
        put("SPLICE_STATE_DIR", state.toString())
        put("SPLICE_CONTROL_PORT", controlPort.toString())
        // Forces SupervisedStart's Raw route even if this host has a unit for another install.
        put("SPLICE_JAR", jar.toString())
    }
}

/** `ps` marks an orphan as Z before init reaps it. Waiting only on kill -0 hangs on that zombie. */
private const val WAIT_FOR_EXIT =
    "while kill -0 \"\$1\" 2>/dev/null; do " +
        "case \$(ps -o stat= -p \"\$1\" 2>/dev/null) in *Z*) break;; esac; " +
        "sleep 0.25; done; exec java -jar \"\$2\" restart --now"
