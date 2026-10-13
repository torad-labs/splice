// NEW: Mac containment for hosted MCP servers (Temple bundling list, Oct 13). On Linux a hosted child runs in the slice
// the host caps (McpContainment); macOS has no systemd and no cgroup, and it does not enforce the address-space or
// resident-set rlimits, so nothing the kernel offers an unprivileged process can cap a child's memory. This bound needs
// no root: every few seconds one `ps -A -o pid=,rss=` gives each process's resident memory, each hosted child is summed
// with its descendants (an `npx` server's work is in the node under it), and a child past the bound is stopped, its
// descendants first, and said in the log. The host then sees its stdout close and answers the pending calls as for any
// crash, and the next call starts it again under the crash-loop backoff, so one runaway cannot take the machine.
//
// THE BOUND is a quarter of physical memory per server: well above what a working server holds, and low enough that
// one runaway leaves the machine three quarters of its memory. The same ps read holds for every child.
//
// UNTESTED ON A MAC: written against ps(1) on a Linux machine. Off macOS McpContainment never builds this.
package splice.control.mcp

import com.sun.management.OperatingSystemMXBean
import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// why: ps's resident size is in KiB.
private const val KIB = 1024L

// why: the log names sizes in MiB, the unit a person reads memory in.
private const val MIB = 1024L * 1024L

// why: a hosted server may hold at most this share of physical memory with its descendants (a quarter, see the header).
private const val SHARE = 4L

// why: the bound when the JVM cannot say how much memory the machine has, a size no working MCP server reaches.
private const val FALLBACK_BOUND_BYTES = 4L * 1024L * MIB

// why: how often the children are measured; a runaway grows for at most this long past the bound before it is stopped.
private const val BOUND_INTERVAL_MS = 5_000L

// why: how long one ps may take before the look is skipped; ps answers in milliseconds.
private const val PS_WAIT_MS = 2_000L

/** Every process's resident memory in bytes, by pid; null when it could not be read this time. */
internal fun interface ResidentMemory {
    operator fun invoke(): Map<Long, Long>?
}

/** `/bin/ps -A -o pid=,rss=`, which any user may run. */
internal class PsResidentMemory : ResidentMemory {
    private val blank = Regex("\\s+")

    override fun invoke(): Map<Long, Long>? = try {
        val process = ProcessBuilder("/bin/ps", "-A", "-o", "pid=,rss=")
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val exited = process.waitFor(PS_WAIT_MS, TimeUnit.MILLISECONDS)
        if (!exited) process.destroyForcibly()
        out.takeIf { exited && process.exitValue() == 0 }?.let(::parse)
    } catch (_: IOException) {
        null
    }

    private fun parse(out: String): Map<Long, Long> = out.lineSequence()
        .map { it.trim().split(blank) }
        .filter { it.size == 2 }
        .mapNotNull { (pid, kib) -> pid.toLongOrNull()?.let { p -> kib.toLongOrNull()?.let { p to it * KIB } } }
        .toMap()
}

/** Watches each hosted child on macOS and stops one whose memory, with its descendants', passes [boundBytes]. */
internal class McpDarwinBound(
    private val log: LogSink,
    private val boundBytes: Long = (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
        ?.totalMemorySize?.takeIf { it > 0 }?.div(SHARE) ?: FALLBACK_BOUND_BYTES,
    private val resident: ResidentMemory = PsResidentMemory(),
) {
    private val watched = ConcurrentHashMap<Process, String>()
    private val started = AtomicBoolean(false)

    /** Bounds [process], the hosted server [name]; the first child starts the watch and says the bound once. */
    fun watch(name: String, process: Process) {
        watched[process] = name
        if (!started.compareAndSet(false, true)) return
        val bound = (boundBytes / MIB).toString()
        val every = (BOUND_INTERVAL_MS / MS_PER_S).toString()
        log(
            "[mcp-host] macOS has no slice to cap hosted MCP servers, so each is bounded at " +
                "${LogSafe.str(bound)} MiB with its children, measured every ${LogSafe.str(every)} s, " +
                "and stopped past it\n",
        )
        Executors.defaultThreadFactory().newThread(::loop).apply {
            isDaemon = true
            name = "mcp-host-bound"
        }.start()
    }

    /** One look: each live child with its descendants, against the bound. Internal so a test can drive a look. */
    internal fun check() {
        watched.keys.removeIf { !it.isAlive }
        if (watched.isEmpty()) return
        val rss = resident() ?: return
        watched.forEach { (process, name) ->
            val tree = listOf(process.pid()) + process.descendants().map { it.pid() }.toList()
            val bytes = tree.sumOf { rss[it] ?: 0L }
            if (bytes > boundBytes) stop(name, process, bytes)
        }
    }

    private fun stop(name: String, process: Process, bytes: Long) {
        watched.remove(process)
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        val held = (bytes / MIB).toString()
        val bound = (boundBytes / MIB).toString()
        log(
            "[mcp-host] ${LogSafe.str(name)}: held ${LogSafe.str(held)} MiB with its children, past the " +
                "${LogSafe.str(bound)} MiB a hosted server may hold on macOS, so splice stopped it; the next call " +
                "starts it again\n",
        )
    }

    private fun loop() {
        try {
            while (true) {
                Thread.sleep(BOUND_INTERVAL_MS)
                check()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

// why: the log names the interval in seconds.
private const val MS_PER_S = 1_000L
