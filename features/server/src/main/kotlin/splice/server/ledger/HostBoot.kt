// NEW: server mode (docs/specs/server-mode.md, F10 fixed-cost providers). When the host this daemon runs on last
// booted, read from the kernel each time a report asks and never kept: a box rented by the hour is billed from when it
// came up, which a daemon restart does not change, and a stored time would go stale the first time the box is stopped
// and started again.
package splice.server.ledger

import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** When the host came up, as it reads now. */
internal fun interface HostBoot {
    fun at(): Instant
}

/** The Linux kernel's own boot time: the `btime` line of [stat], in seconds since the epoch. Where that cannot be read
 *  (a host that is not Linux), [started], when this daemon's JVM started: the latest the host can have come up, so
 *  the meter counts no hour it cannot show and counts from the daemon's start there. */
internal class KernelBoot(
    private val stat: Path = Path.of(PROC_STAT),
    private val started: Instant = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime),
) : HostBoot {
    private val btime = Regex("""^btime (\d+)$""", RegexOption.MULTILINE)

    override fun at(): Instant = kernel() ?: started

    private fun kernel(): Instant? {
        val text = try {
            Files.readString(stat)
        } catch (_: IOException) {
            return null
        }
        return btime.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let(Instant::ofEpochSecond)
    }
}

private const val PROC_STAT = "/proc/stat"
