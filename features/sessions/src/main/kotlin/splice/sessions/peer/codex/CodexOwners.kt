// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): the Codex process an attach binds its thread to. It is
// read from the attach's own ancestry, never from an argument, an event or the environment: the nearest process above
// it whose program is `codex`, as Fence's lifecycle binding names a native owner (Fence's lifecycle-owner-binding.md).
// A hook Codex runs has that process above it; a shell outside Codex has none, and its attach binds no owner.
package splice.sessions.peer.codex

import splice.sessions.registry.PidIdentity
import splice.sessions.registry.ProcPidIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: the Temple's own walk stops at 16 ancestors; a hook sits a handful below its Codex process.
private const val MAX_ANCESTORS = 16

private const val CODEX_PROGRAM = "codex"

/** The kernel's mark on a program replaced on disk while it runs: it is still that program. */
private const val REPLACED = " (deleted)"

/** Finds the Codex process above this one. */
public class CodexOwners(
    private val identity: PidIdentity = ProcPidIdentity(),
    private val proc: Path = Path.of("/proc"),
) {
    /** The nearest ancestor running `codex`, named as the registry names a process; null when there is none. */
    public fun find(): NativeProcess? =
        generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
            .take(MAX_ANCESTORS)
            .firstOrNull { program(it.pid()) == CODEX_PROGRAM }
            ?.let { named(it.pid()) }

    /** The name of the program [pid] runs, or null when the kernel will not show it (another user's process). */
    private fun program(pid: Long): String? = try {
        Files.readSymbolicLink(proc.resolve(pid.toString()).resolve("exe")).fileName?.toString()?.removeSuffix(REPLACED)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun named(pid: Long): NativeProcess? {
        val start = identity.procStart(pid)
        val domain = identity.hostDomain()
        return if (start == null || domain == null) null else NativeProcess(pid, start, domain)
    }
}
