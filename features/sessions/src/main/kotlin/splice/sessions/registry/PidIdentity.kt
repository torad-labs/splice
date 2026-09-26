// NEW: v0.4.0 FEATURES.md §4 — the two identity facts Claude Code writes beside a session's pid so a
// pid is never mistaken for a process it does not name: `pidDomain` (`linux:<machine-id>:pid:[<pid
// namespace inode>]`, the domain the pid is meaningful in) and `procStart` (the kernel's start time
// of that process, /proc/<pid>/stat field 22 in clock ticks since boot). A registration from another
// domain (a container sharing ~/.claude) or a pid whose start time moved (reused after the session
// exited) is GONE, whatever the host process with that number is doing (review 2026-09-14: liveness
// read only the pid and a five-minute tolerance, and read a stranger's /proc environ as the head).
package splice.sessions.registry

import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Start-time index in the fields after the `(comm)` of /proc/<pid>/stat: field 22 overall. */
private const val START_TIME_FIELD_AFTER_COMM = 19

/** This host's pid domain and a pid's start time, as Claude Code spells them; a seam for tests. */
public interface PidIdentity {
    /** `linux:<machine-id>:pid:[<inode>]`, or null when the facts are not readable (not Linux). */
    public fun hostDomain(): String?

    /** The kernel start time of [pid] as the decimal string /proc/<pid>/stat carries, or null. */
    public fun procStart(pid: Long): String?
}

public class ProcPidIdentity(
    private val procRoot: Path = Paths.get("/proc"),
    private val machineIdFile: Path = Paths.get("/etc/machine-id"),
) : PidIdentity {
    private val domain: String? by lazy {
        val machineId = read(machineIdFile)?.trim()?.takeIf { it.isNotEmpty() }
        // ast-grep-ignore: kt-no-silent-result-collapse -- null is this interface's documented answer when the fact is not readable (not Linux)
        val pidNs = Cancellables.runCatchingCancellable {
            Files.readSymbolicLink(procRoot.resolve("self").resolve("ns").resolve("pid")).toString()
        }.getOrNull()
        if (machineId == null || pidNs == null) null else "linux:$machineId:$pidNs"
    }

    override fun hostDomain(): String? = domain

    override fun procStart(pid: Long): String? {
        val stat = read(procRoot.resolve(pid.toString()).resolve("stat")) ?: return null
        val afterComm = stat.substringAfterLast(')').trim().split(' ')
        return afterComm.getOrNull(START_TIME_FIELD_AFTER_COMM)
    }

    // ast-grep-ignore: kt-no-silent-result-collapse -- null is this interface's documented answer when the fact is not readable (not Linux, or the pid is gone)
    private fun read(path: Path): String? = Cancellables.runCatchingCancellable { Files.readString(path) }.getOrNull()
}
