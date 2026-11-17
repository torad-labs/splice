// A provider paid by the hour in a usage report (docs/specs/server-mode.md, F10): the hours the host was up inside the
// report's window, from its boot time read when the report is made, times its hourly price.
package splice.server.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class FixedMeterTest {
    @TempDir lateinit var folder: Path

    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun hoursAgo(hours: Double): Instant = now.minusMillis((hours * 3_600_000).toLong())

    private fun lastHours(hours: Double) = UsageWindow(hoursAgo(hours), now)

    private fun meter(booted: Instant) = FixedMeter({ booted }, clock)

    @Test
    fun `a host up for part of the window is metered for that part, and one up all of it for the whole window`() {
        val box = mapOf("box" to 2.5)
        assertEquals(listOf(FixedCost("box", 0.5, 1.25)), meter(hoursAgo(0.5)).of(box, lastHours(1.0)))
        assertEquals(listOf(FixedCost("box", 3.0, 7.5)), meter(hoursAgo(30.0)).of(box, lastHours(3.0)))
    }

    @Test
    fun `each provider paid by the hour has its row, over the same hours`() {
        val rows = meter(hoursAgo(2.0)).of(mapOf("box" to 2.5, "spare" to 0.4), lastHours(1.0))
        assertEquals(listOf(FixedCost("box", 1.0, 2.5), FixedCost("spare", 1.0, 0.4)), rows)
    }

    @Test
    fun `no hour past the report's own moment and none before the boot is metered`() {
        val box = mapOf("box" to 2.0)
        val reachingAhead = UsageWindow(hoursAgo(1.0), now.plusSeconds(7_200))
        assertEquals(listOf(FixedCost("box", 1.0, 2.0)), meter(hoursAgo(5.0)).of(box, reachingAhead))
        val beforeBoot = UsageWindow(hoursAgo(10.0), hoursAgo(6.0))
        assertEquals(listOf(FixedCost("box", 0.0, 0.0)), meter(hoursAgo(5.0)).of(box, beforeBoot))
    }

    @Test
    fun `a server with no hourly price has no fixed row`() {
        assertEquals(emptyList<FixedCost>(), meter(hoursAgo(5.0)).of(emptyMap(), lastHours(1.0)))
    }

    @Test
    fun `the boot time is the kernel's btime, read each time, and the daemon's start where none can be read`() {
        val stat = folder.resolve("stat")
        Files.writeString(stat, "cpu  1 2 3\nbtime 1763290800\nprocesses 42\n")
        val started = Instant.parse("2026-11-16T11:00:00Z")
        val boot = KernelBoot(stat, started)
        assertEquals(Instant.ofEpochSecond(1_763_290_800), boot.at())
        Files.writeString(stat, "btime 1763294400\n")
        assertEquals(Instant.ofEpochSecond(1_763_294_400), boot.at(), "a host booted again since is read again")
        assertEquals(started, KernelBoot(folder.resolve("absent"), started).at())
    }
}
