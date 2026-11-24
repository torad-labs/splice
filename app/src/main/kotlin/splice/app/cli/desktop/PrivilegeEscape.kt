// NEW: desktop-antigravity. Claude Desktop restarting itself on Linux (an in-app update or relaunch) leaves its process
// with the kernel's no-new-privileges bit set, and every Code tab session it starts inherits it, so nothing in the
// session can gain privileges it was not born with (a sandbox helper, `sudo`). When this process carries the bit and
// the user's service manager can be reached, `splice desktop launch` runs itself again as a transient user service,
// which the bit does not reach:
//
//   systemd-run --user --pipe --wait --quiet --collect --same-dir --unit=<name> -E NAME ... -- <this command line>
//
// Its standard streams stay Desktop's (--pipe), it ends when the session does (--wait) and leaves no unit behind
// (--collect). The environment goes by NAME alone, so systemd-run copies each value from its own environment and no
// key or token is ever on a command line; a name that is not a shell identifier (an exported bash function,
// `BASH_FUNC_x%%`) is one systemd refuses, so it stays behind. SPLICE_DESKTOP_ESCAPED marks the run inside, so it
// never escapes twice. With no user bus to reach, it never tries, and the session runs here as before.
package splice.app.cli.desktop

import splice.app.desktop.setup.PROC_STATUS
import splice.core.util.EnvReader
import splice.core.util.PathProbe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Whether and how a launch leaves the no-new-privileges bit: reads [status] (this process's), finds programs on
 *  [env]'s PATH, and applies only where [linux]. */
internal class PrivilegeEscape(
    private val env: EnvReader,
    private val status: Path = Path.of(PROC_STATUS),
    private val linux: Boolean = System.getProperty(OS_NAME).orEmpty().lowercase().startsWith(LINUX),
) {
    /** systemd-run's command line that runs [self] again as user unit [unit], given every one of [names] it can pass
     *  by name; null when this process does not carry the bit, already escaped, or has no user manager to escape to. */
    fun command(self: List<String>, names: Collection<String>, unit: String): List<String>? {
        if (!linux || env(ESCAPED_ENV) == ESCAPED) return null
        val reachable = self.isNotEmpty() && userBus()
        val run = program(SYSTEMD_RUN_PROGRAM)?.takeIf { reachable && noNewPrivs() } ?: return null
        val passed = (names.filter { IDENTIFIER.matches(it) && it !in UNIT_OWN } + ESCAPED_ENV).distinct().sorted()
        return listOf(run.toString()) + RUN_FLAGS + "--unit=$unit" + passed.flatMap { listOf("-E", it) } + "--" + self
    }

    /** The program [name] on PATH, a file that runs, or null when PATH holds none. */
    fun program(name: String): Path? = env(ESCAPE_PATH_ENV).orEmpty().split(':').filter(String::isNotBlank)
        .firstNotNullOfOrNull { dir ->
            PathProbe.spelled(dir)?.resolve(name)?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
        }

    /** Whether the user's service manager can be reached: its bus or its private socket in the session's runtime
     *  folder, or a session bus address. */
    private fun userBus(): Boolean {
        if (!env(DBUS_ADDRESS_ENV).isNullOrBlank()) return true
        val runtime = env(XDG_RUNTIME_DIR_ENV)?.takeIf(String::isNotBlank)?.let(PathProbe::spelled) ?: return false
        return Files.exists(runtime.resolve("bus")) || Files.exists(runtime.resolve("systemd").resolve("private"))
    }

    /** Whether [status] says `NoNewPrivs: 1`. */
    private fun noNewPrivs(): Boolean = try {
        Files.readAllLines(status).any { it.startsWith(NO_NEW_PRIVS) && it.substringAfter(':').trim() == "1" }
    } catch (_: IOException) {
        false
    }
}

// The variable that marks a launch already run outside the bit, and its value there.
internal const val ESCAPED_ENV = "SPLICE_DESKTOP_ESCAPED"
internal const val ESCAPED = "1"

// The service manager's program for a transient unit, and its controller, which forwards signals to that unit.
internal const val SYSTEMD_RUN_PROGRAM = "systemd-run"
internal const val SYSTEMCTL_PROGRAM = "systemctl"

// The system property naming the platform, read where a launch or a managed file differs by platform.
internal const val OS_NAME = "os.name"

private val RUN_FLAGS = listOf("--user", "--pipe", "--wait", "--quiet", "--collect", "--same-dir")

/** A name systemd-run takes for -E: a shell identifier. */
private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/** Variables that belong to the unit a process runs in, which the service manager sets for the new one itself. */
private val UNIT_OWN = setOf(
    "NOTIFY_SOCKET", "LISTEN_PID", "LISTEN_FDS", "LISTEN_FDNAMES", "INVOCATION_ID", "JOURNAL_STREAM",
    "SYSTEMD_EXEC_PID", "MANAGERPID", "WATCHDOG_PID", "WATCHDOG_USEC",
)

// The `/proc/self/status` line that carries the bit.
private const val NO_NEW_PRIVS = "NoNewPrivs:"

// Where programs are found, and where the user's service manager is reached.
private const val ESCAPE_PATH_ENV = "PATH"
private const val XDG_RUNTIME_DIR_ENV = "XDG_RUNTIME_DIR"
private const val DBUS_ADDRESS_ENV = "DBUS_SESSION_BUS_ADDRESS"

// How the JVM's os.name begins on Linux, lowercased.
private const val LINUX = "linux"
