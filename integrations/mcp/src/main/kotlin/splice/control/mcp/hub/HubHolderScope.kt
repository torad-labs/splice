package splice.control.mcp.hub

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val SYSTEMCTL = "systemctl"
private const val USER_MANAGER = "--user"
private const val SCOPE_ACTIVE = "active"
private const val SCOPE_FAILED = "failed"
private const val SCOPE_INACTIVE = "inactive"
private const val SCOPE_NOT_FOUND = "not-found"

// why: scope stop must settle before an incomplete startup intent may be retired or reused.
private const val SCOPE_STOP_WAIT_MS = 5_000L

// why: a systemd query that cannot answer quickly cannot prove a scope's active state.
private const val SYSTEMCTL_QUERY_WAIT_MS = 1_000L

// why: a short probe cadence observes a just-stopped scope without holding the lifecycle lock for long.
private const val SCOPE_STOP_POLL_MS = 100L

/** systemd is the scope's source of truth, while /proc binds legacy records to the live scope that owns them. */
internal class HubHolderScope {
    private data class ScopeState(val active: String?, val tasks: Long?)

    fun name(key: String, at: Long): String =
        "splice-mcp-${key.take(KEY_HEX)}-$at-${UUID.randomUUID().toString().replace("-", "")}.scope"

    fun of(pid: Long): String? = try {
        Files.readAllLines(Path.of("/proc", pid.toString(), "cgroup"))
            .asSequence()
            .map { line -> line.substringAfterLast('/') }
            .firstOrNull { unit -> unit.endsWith(".scope") }
    } catch (_: IOException) {
        null
    }

    fun running(scopeName: String): Boolean = status(scopeName)?.active == SCOPE_ACTIVE

    fun exited(scopeName: String): Boolean = status(scopeName)?.let { state ->
        state.active in setOf(SCOPE_INACTIVE, SCOPE_NOT_FOUND) ||
            state.active == SCOPE_FAILED && state.tasks == 0L
    } == true

    fun stop(scopeName: String): Boolean = command(listOf("stop", "--no-block", scopeName)) == 0

    fun waitForExit(scopeName: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SCOPE_STOP_WAIT_MS)
        while (!exited(scopeName) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(SCOPE_STOP_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return exited(scopeName)
    }

    private fun status(scopeName: String): ScopeState? = try {
        val process = ProcessBuilder(
            SYSTEMCTL,
            USER_MANAGER,
            "show",
            "--property=ActiveState",
            "--property=TasksCurrent",
            scopeName,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(SYSTEMCTL_QUERY_WAIT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().use { it.readLines() }
                .takeIf { process.exitValue() == 0 }
                ?.let { lines ->
                    val properties = lines.mapNotNull { line ->
                        val separator = line.indexOf('=')
                        if (separator < 0) null else line.substring(0, separator) to line.substring(separator + 1)
                    }.toMap()
                    ScopeState(properties["ActiveState"], properties["TasksCurrent"]?.toLongOrNull())
                }
        }
    } catch (_: IOException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    private fun command(args: List<String>): Int = try {
        val process = ProcessBuilder(listOf(SYSTEMCTL, USER_MANAGER) + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor(SYSTEMCTL_QUERY_WAIT_MS, TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            -1
        }
    } catch (_: IOException) {
        -1
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        -1
    }
}
