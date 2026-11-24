package splice.sessions.peer.owner

import splice.sessions.peer.codex.NativeProcess
import splice.sessions.registry.PidIdentity
import splice.sessions.registry.ProcPidIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: a registration runs a handful of processes below its harness; match the existing Codex hook's bounded walk.
private const val PEER_OWNER_ANCESTORS = 16

/** Built-in harness executable names recognized for automatic owner-bound peer registration. */
private val BUILTIN_HARNESS_PROGRAMS: Set<String> = setOf("codex", "claude", "agy")

/** Interactive/login shells that are never a harness owner: binding to them would either retire too early or leak. */
private val PLAIN_SHELL_PROGRAMS: Set<String> = setOf("sh", "bash", "zsh", "fish", "dash")

/** A matched harness ancestor: its normalized executable name and kernel-verified process identity. */
public data class OwnedHarness(val program: String, val process: NativeProcess)

/** Native ancestry and kernel identity, independent of the detached helper or the daemon that starts it. */
public class PeerOwnerProcesses(
    private val identity: PidIdentity = ProcPidIdentity(),
    private val proc: Path = Path.of("/proc"),
) {
    /** Built-in harness binary names (`claude`, `codex`, `agy`) plus non-shell [extra] binary names. */
    public fun harnessPrograms(extra: Set<String> = emptySet()): Set<String> =
        BUILTIN_HARNESS_PROGRAMS + extra.filterNot(::isPlainShell)

    /** True when [program] names a plain shell (`sh`, `bash`, `zsh`, `fish`, `dash`) rather than a harness. */
    public fun isPlainShell(program: String): Boolean {
        val base = program.trim().substringAfterLast('/').removePrefix("-").lowercase()
        return base in PLAIN_SHELL_PROGRAMS
    }

    /** The nearest ancestor running a known harness binary (plus [extra]), with both its binary name and identity. */
    public fun harnessAncestor(extra: Set<String> = emptySet()): OwnedHarness? {
        val programs = harnessPrograms(extra)
        return generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
            .take(PEER_OWNER_ANCESTORS)
            .firstNotNullOfOrNull { handle ->
                val name = program(handle.pid())?.takeIf { it in programs } ?: return@firstNotNullOfOrNull null
                capture(handle)?.let { OwnedHarness(name, it) }
            }
    }

    /** The nearest actual ancestor with a recognized executable; an unknown shell remains an unowned registration. */
    public fun ancestor(programs: Set<String>): NativeProcess? =
        generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
            .take(PEER_OWNER_ANCESTORS)
            .firstOrNull { program(it.pid()) in programs }
            ?.let(::capture)

    /** Live kernel identities of ancestor processes, nearest first (bounded by [PEER_OWNER_ANCESTORS]). */
    public fun ancestors(): List<NativeProcess> =
        generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
            .take(PEER_OWNER_ANCESTORS)
            .mapNotNull(::capture)
            .toList()

    /** Capture a live pid's whole kernel identity; unknown identity is never replaced by pid alone. */
    public fun capture(pid: Long): NativeProcess? =
        if (pid <= 0L) null else ProcessHandle.of(pid).orElse(null)?.let(::capture)

    /** All three parts must still identify this live process, including after a pid has been reused. */
    public fun live(owner: NativeProcess): Boolean = capture(owner.pid) == owner

    /** The executable's name. Claude Code's native installer runs `.../claude/versions/<version>`, whose own name is a
     *  version number, so that layout reads as `claude`; an update that replaced it leaves ` (deleted)` on the link. */
    private fun program(pid: Long): String? {
        val exe = executablePath(pid) ?: return null
        val versions = exe.parent
        return if (versions?.fileName?.toString() == "versions" && versions.parent?.fileName?.toString() == "claude") {
            "claude"
        } else {
            exe.fileName?.toString()?.removeSuffix(" (deleted)")
        }
    }

    private fun executablePath(pid: Long): Path? = if (Files.isDirectory(proc)) {
        try {
            Files.readSymbolicLink(proc.resolve(pid.toString()).resolve("exe"))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    } else {
        ProcessHandle.of(pid).flatMap { it.info().command() }.map(Path::of).orElse(null)
    }

    private fun capture(process: ProcessHandle): NativeProcess? {
        val start = identity.procStart(process.pid())
        val domain = identity.hostDomain()
        if (start == null || domain == null) return null
        if (!process.isAlive) return null
        return NativeProcess(process.pid(), start, domain)
    }
}
