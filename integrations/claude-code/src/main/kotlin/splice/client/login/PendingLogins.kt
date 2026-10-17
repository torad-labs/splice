// NEW: macOS parity — ProcessHandle argv and launcher declarations safely identify pending hook logins.
package splice.client.login

import splice.core.process.LaunchOwner
import splice.core.process.LaunchOwners
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The hook refuses foreign or unkillable sign-ins rather than starting a duplicate. */
public enum class PendingLoginOutcome { Clear, Foreign, Restarted, Stuck }

// After JVM properties, the owned invocation is -jar, jar path, login and head.
private const val LOGIN_ARG_COUNT = 4

// The head follows -jar, jar path and login in that invocation.
private const val HEAD_OFFSET = 3

/** Portable argv inspection; only a birth-validated launcher declaration grants cancellation. */
public class PendingLogins(private val owners: LaunchOwners) {
    /** Cancel hook-owned logins for [heads] in the exact [jar] invocation, never application lookalikes. */
    public fun cancel(jar: Path, heads: List<String>): PendingLoginOutcome {
        val matches = ProcessHandle.allProcesses().use { processes ->
            processes.filter { matches(it.info(), jar, heads) }.toList()
        }
        val owned = matches.map { process -> process to owners.read(process.pid()) }
        if (owned.any { (_, owner) -> !hookOwned(owner, heads) }) {
            return PendingLoginOutcome.Foreign
        }
        for ((process, owner) in owned) {
            if (!stop(process, requireNotNull(owner))) return PendingLoginOutcome.Stuck
        }
        return if (owned.isEmpty()) PendingLoginOutcome.Clear else PendingLoginOutcome.Restarted
    }

    private fun hookOwned(owner: LaunchOwner?, heads: List<String>): Boolean =
        owner?.kind == "login" && owner.origin == "hook" && owner.head in heads

    private fun stop(process: ProcessHandle, owner: LaunchOwner): Boolean {
        if (same(process, owner)) process.destroy()
        awaitExit(process, owner, 2)
        if (same(process, owner)) process.destroyForcibly()
        awaitExit(process, owner, 1)
        return !same(process, owner)
    }

    private fun awaitExit(process: ProcessHandle, owner: LaunchOwner, seconds: Long) {
        if (!same(process, owner)) return
        try {
            process.onExit().get(seconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            // A bounded TERM wait expires into the forceful phase; the final wait reports Stuck.
        }
    }

    private fun same(process: ProcessHandle, owner: LaunchOwner): Boolean =
        process.isAlive && process.info().startInstant().orElse(null)?.toString() == owner.startedAt

    private fun loginHead(args: List<String>): String? {
        val label = args.indexOf("--label")
        return args.filterIndexed { index, word ->
            word != "--discard" && word != "--label" && (label < 0 || index != label + 1)
        }.singleOrNull()
    }

    private fun matches(info: ProcessHandle.Info, jar: Path, heads: List<String>): Boolean {
        val executable = info.command().orElse(null) ?: return false
        val argv = info.arguments().orElse(null) ?: return false
        // Only launcher JVM properties may precede -jar. A classpath, class name or another
        // jar ends the JVM prefix; searching later would accept application arguments.
        val first = argv.indexOfFirst { !it.startsWith("-D") }
        return Path.of(executable).fileName.toString() == "java" &&
            first >= 0 && argv.size >= first + LOGIN_ARG_COUNT &&
            argv[first] == "-jar" && argv[first + 1] == jar.toString() &&
            argv[first + 2] == "login" && loginHead(argv.drop(first + HEAD_OFFSET)) in heads
    }
}
