// NEW: desktop-antigravity. Claude Desktop restarting itself on Linux (an in-app update or relaunch) leaves its process
// with the kernel's no-new-privileges bit set, and every Code tab session it starts inherits it, so nothing in the
// session can gain privileges it was not born with (a sandbox helper, `sudo`). When this process carries the bit and
// the user's service manager is there, `splice desktop launch` runs itself again as a transient user service, which
// the bit does not reach:
//
//   systemd-run --user --pipe --wait --quiet --collect --same-dir --unit=<name> -E NAME ... -- <this command line>
//
// Its standard streams stay Desktop's (--pipe), it ends when the session does (--wait) and leaves no unit behind
// (--collect). The environment goes by NAME alone, so systemd-run copies each value from its own environment and no
// key or token is ever on a command line. SPLICE_DESKTOP_ESCAPED marks the run inside, so it never escapes twice.
package splice.app.cli.desktop

import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Whether and how a launch leaves the no-new-privileges bit: reads [status] (this process's), finds programs on
 *  [env]'s PATH, and applies only where [linux]. */
internal class PrivilegeEscape(
    private val env: EnvReader,
    private val status: Path = Path.of(PROC_STATUS),
    private val linux: Boolean = System.getProperty(OS_NAME).orEmpty().lowercase().startsWith(LINUX),
) {
    /** systemd-run's command line that runs [self] again as user unit [unit], given every one of [names] by name;
     *  null when this process does not carry the bit, already escaped, or has no systemd-run to escape with. */
    fun command(self: List<String>, names: Collection<String>, unit: String): List<String>? {
        if (!linux || env(ESCAPED_ENV) == ESCAPED) return null
        // The user's service manager is reached through the session's runtime folder; without one there is none.
        val manager = self.isNotEmpty() && !env(RUNTIME_DIR).isNullOrBlank()
        val run = program(SYSTEMD_RUN)?.takeIf { manager && noNewPrivs() } ?: return null
        val passed = (names.filterNot { it in UNIT_OWN || it.isBlank() } + ESCAPED_ENV).distinct().sorted()
        return listOf(run.toString()) + RUN_FLAGS + "--unit=$unit" + passed.flatMap { listOf("-E", it) } + "--" + self
    }

    /** The program [name] on PATH, or null when PATH holds none. */
    fun program(name: String): Path? = env(PATH).orEmpty().split(':').filter(String::isNotBlank).firstNotNullOfOrNull {
        try {
            Path.of(it, name).takeIf(Files::isExecutable)
        } catch (_: InvalidPathException) {
            null
        }
    }

    /** Whether [status] says `NoNewPrivs: 1`. */
    private fun noNewPrivs(): Boolean = try {
        Files.readAllLines(status).any { it.startsWith(NO_NEW_PRIVS) && it.substringAfter(':').trim() == "1" }
    } catch (_: IOException) {
        false
    }
}

/** The variable that marks a launch already run outside the bit, and its value there. */
internal const val ESCAPED_ENV = "SPLICE_DESKTOP_ESCAPED"
internal const val ESCAPED = "1"

/** The service manager's own program for a transient unit, and its controller, which forwards signals to it. */
internal const val SYSTEMD_RUN = "systemd-run"
internal const val SYSTEMCTL = "systemctl"

private val RUN_FLAGS = listOf("--user", "--pipe", "--wait", "--quiet", "--collect", "--same-dir")

/** Variables that belong to the unit a process runs in, which the service manager sets for the new one itself. */
private val UNIT_OWN = setOf(
    "NOTIFY_SOCKET", "LISTEN_PID", "LISTEN_FDS", "LISTEN_FDNAMES", "INVOCATION_ID", "JOURNAL_STREAM",
    "SYSTEMD_EXEC_PID", "MANAGERPID", "WATCHDOG_PID", "WATCHDOG_USEC",
)

private const val PROC_STATUS = "/proc/self/status"
private const val NO_NEW_PRIVS = "NoNewPrivs:"
private const val PATH = "PATH"
private const val RUNTIME_DIR = "XDG_RUNTIME_DIR"
private const val OS_NAME = "os.name"
private const val LINUX = "linux"
