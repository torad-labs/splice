// NEW: v0.4.0 FEATURES.md §4 — the two identity facts Claude Code writes beside a session's pid so a
// pid is never mistaken for a process it does not name: `pidDomain` (`linux:<machine-id>:pid:[<pid
// namespace inode>]`, the domain the pid is meaningful in) and `procStart` (the kernel's start time
// of that process, /proc/<pid>/stat field 22 in clock ticks since boot). A registration from another
// domain (a container sharing ~/.claude) or a pid whose start time moved (reused after the session
// exited) is GONE, whatever the host process with that number is doing (review 2026-09-14: liveness
// read only the pid and a five-minute tolerance, and read a stranger's /proc environ as the head).
package splice.sessions.registry

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Start-time index in the fields after the `(comm)` of /proc/<pid>/stat: field 22 overall. */
private const val START_TIME_FIELD_AFTER_COMM = 19

/** When the process started (epoch ms), or null when unknown. Seam so tests decide without spawning. */
internal fun interface PidStartedAt {
    operator fun invoke(pid: Long): Long?
}

/** This host's pid domain and a pid's start time, as Claude Code spells them. The roots are the seam: production
 *  reads the real /proc and /etc/machine-id, and a test passes a tree it wrote and the wall-clock starts it wants. */
internal class ProcPidIdentity(
    private val procRoot: Path = Paths.get("/proc"),
    private val machineIdFile: Path = Paths.get("/etc/machine-id"),
    private val startedAtOf: PidStartedAt = PidStartedAt { pid ->
        ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)
    },
) : PidIdentity {
    private val domain: String? by lazy {
        val machineId = read(machineIdFile)?.trim()?.takeIf { it.isNotEmpty() }
        val pidNs = pidNamespace()
        if (machineId == null || pidNs == null) null else "linux:$machineId:$pidNs"
    }

    override fun hostDomain(): String? = domain

    override fun procStart(pid: Long): String? {
        val stat = read(procRoot.resolve(pid.toString()).resolve("stat")) ?: return null
        val afterComm = stat.substringAfterLast(')').trim().split(' ')
        return afterComm.getOrNull(START_TIME_FIELD_AFTER_COMM)
    }

    override fun startedAt(pid: Long): Long? = startedAtOf(pid)

    /** `pid:[<inode>]`, or null off Linux, where the link is absent or the filesystem has no symlinks. */
    private fun pidNamespace(): String? = try {
        Files.readSymbolicLink(procRoot.resolve("self").resolve("ns").resolve("pid")).toString()
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    /** The file's text, or null when it is not there to read (not Linux, or the pid is gone). */
    private fun read(path: Path): String? = try {
        Files.readString(path)
    } catch (_: IOException) {
        null
    }
}
