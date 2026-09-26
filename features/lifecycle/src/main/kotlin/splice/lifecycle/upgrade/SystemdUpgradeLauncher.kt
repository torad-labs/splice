// NEW: V4-220 item 4 (2026-09-25) — the console's `splice upgrade` in a transient user scope:
//
//   systemd-run --user --scope --collect --quiet --unit=splice-upgrade-<id>
//     -- /bin/sh -c RUN_SCRIPT sh <dir> <java> -jar <live jar> upgrade [--to vX | --rollback]
//
// A SCOPE, NOT A SERVICE. The run is the daemon's child and inherits its whole environment (where the
// install lives, the release base, the state dir, PATH for gh), so nothing is listed and no value is on an
// argv; but it lives in its own cgroup, so stopping the daemon's unit does not stop it. A scope has no main
// process to wait on either: a daemon the run cold-starts (the restart verb's path, for a daemon no unit
// runs) keeps the scope alive and stays up, as it would after a `splice upgrade` typed in a terminal.
//
// systemd-run --scope becomes the command rather than returning, so the launch is judged by the run's own
// shell: once it has written its pid the run started (and UpgradeRuns reads the rest off disk); a process
// gone before that never started, and the log it wrote says why.
package splice.lifecycle.upgrade

import splice.core.util.EnvReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: long enough for systemd-run to create the scope and the shell to write its pid on a loaded box, and
// short enough that a POST whose run could not start answers with why instead of a run that never begins.
private const val LAUNCH_SETTLE_MS = 3_000L

// why: how often the launch looks for the shell's pid while it settles; a started run answers within one.
private const val LAUNCH_POLL_MS = 25L

/** The run's shell: $1 is its directory, the rest the command. Output goes to the run's log, its pid in
 *  first and its exit code last, renamed in so a reader never sees half of it. */
internal const val RUN_SCRIPT =
    "d=\"\$1\"; shift; exec >> \"\$d/$RUN_OUTPUT\" 2>&1 < /dev/null; echo \$\$ > \"\$d/$RUN_PID\"; \"\$@\"; " +
        "echo \$? > \"\$d/$RUN_EXIT.tmp\" && mv \"\$d/$RUN_EXIT.tmp\" \"\$d/$RUN_EXIT\""

/** Starts [command] without waiting for it, its output appended to [log]. */
internal fun interface DetachedSpawn {
    fun spawn(command: List<String>, log: Path): Process
}

/** The console's launcher: this JVM's java and the install's live jar. */
public class SystemdUpgradeLauncher(env: EnvReader) : UpgradeRunLauncher by SystemdScope(
    ProcessHandle.current().info().command().orElse("java"),
    UpgradeLayout(env).liveJar,
    DetachedSpawn { command, log ->
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
    },
)

internal class SystemdScope(
    private val java: String,
    private val liveJar: Path,
    private val spawn: DetachedSpawn,
    private val settleMs: Long = LAUNCH_SETTLE_MS,
) : UpgradeRunLauncher {

    override fun launch(dir: Path, args: List<String>): String? {
        val command = listOf(
            "systemd-run", "--user", "--scope", "--collect", "--quiet", "--unit=splice-upgrade-${dir.fileName}",
            "--", "/bin/sh", "-c", RUN_SCRIPT, "sh", dir.toString(), java, "-jar", liveJar.toString(),
        ) + args
        val process = spawn.spawn(command, dir.resolve(RUN_OUTPUT))
        val pid = dir.resolve(RUN_PID)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMs)
        while (!Files.exists(pid) && System.nanoTime() < deadline) {
            if (process.waitFor(LAUNCH_POLL_MS, TimeUnit.MILLISECONDS)) break
        }
        if (Files.exists(pid) || process.isAlive) return null
        val said = Files.readAllLines(dir.resolve(RUN_OUTPUT)).lastOrNull { it.isNotBlank() }?.trim()
        return "systemd-run exited ${process.exitValue()}" + (said?.let { " ($it)" } ?: "")
    }
}
