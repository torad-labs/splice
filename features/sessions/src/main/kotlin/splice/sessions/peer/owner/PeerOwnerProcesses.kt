package splice.sessions.peer.owner

import splice.sessions.peer.codex.NativeProcess
import splice.sessions.registry.PidIdentity
import splice.sessions.registry.ProcPidIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: a registration runs a handful of processes below its harness; match the existing Codex hook's bounded walk.
private const val PEER_OWNER_ANCESTORS = 16

/** Native ancestry and kernel identity, independent of the detached helper or the daemon that starts it. */
public class PeerOwnerProcesses(
    private val identity: PidIdentity = ProcPidIdentity(),
    private val proc: Path = Path.of("/proc"),
) {
    /** The nearest actual ancestor with a recognized executable; an unknown shell remains an unowned registration. */
    public fun ancestor(programs: Set<String>): NativeProcess? =
        generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
            .take(PEER_OWNER_ANCESTORS)
            .firstOrNull { program(it.pid()) in programs }
            ?.let(::capture)

    /** Capture a live pid's whole kernel identity; unknown identity is never replaced by pid alone. */
    public fun capture(pid: Long): NativeProcess? =
        if (pid <= 0L) null else ProcessHandle.of(pid).orElse(null)?.let(::capture)

    /** All three parts must still identify this live process, including after a pid has been reused. */
    public fun live(owner: NativeProcess): Boolean = capture(owner.pid) == owner

    /** The executable's name. Claude Code's native installer runs `.../claude/versions/<version>`, whose own name is a
     *  version number, so that layout reads as `claude`; an update that replaced it leaves ` (deleted)` on the link. */
    private fun program(pid: Long): String? = try {
        val exe = Files.readSymbolicLink(proc.resolve(pid.toString()).resolve("exe"))
        val versions = exe.parent
        if (versions?.fileName?.toString() == "versions" && versions.parent?.fileName?.toString() == "claude") {
            "claude"
        } else {
            exe.fileName?.toString()?.removeSuffix(" (deleted)")
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun capture(process: ProcessHandle): NativeProcess? {
        val start = identity.procStart(process.pid())
        val domain = identity.hostDomain()
        if (start == null || domain == null) return null
        if (!process.isAlive) return null
        return NativeProcess(process.pid(), start, domain)
    }
}
