// NEW: aggregate-only source phase samples and exact per-phase thread allocation measurements.
package splice.app.sources

import com.sun.management.ThreadMXBean
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonWire
import splice.usage.perf.PerfRow
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneId

internal class PerfHistoryProfile {
    private val allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val thread = Thread.currentThread().threadId()
    internal var diskBytes: Long = 0L
        private set
    internal var sourceBytes: Long = 0L
        private set
    internal var diskWriteBytes: Long = 0L
        private set

    internal var allocatedBytes: Long = 0L
        private set

    internal fun source(source: PerfRowsFileSource, path: Path): List<PerfRow> =
        diskPhase("source", path) { source.window(SCALE_SINCE).rows }

    internal fun <T> diskPhase(name: String, path: Path, readPaths: Set<Path> = emptySet(), action: () -> T): T {
        Recording().use { recording ->
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1))
            recording.enable("jdk.ObjectAllocationSample")
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO)
            recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO)
            recording.start()
            val rows = phase(name, action)
            recording.stop()
            recording.dump(path)
            samples(path, readPaths)
            Files.delete(path)
            return rows
        }
    }

    internal fun payload(rows: List<PerfRow>): Int {
        // Invoke the actual internal usage fold, not a benchmark copy of its implementation.
        val type = Class.forName("splice.usage.perf.TurnUsage")
        val constructor = type.declaredConstructors.single()
        val method = type.getDeclaredMethod("json")
        val payload = phase("aggregation") {
            method.invoke(constructor.newInstance(rows, null, ZoneId.of("America/Chicago"))) as JsonObject
        }
        return phase("serialization") { JsonWire.string(payload).toByteArray(Charsets.UTF_8).size }
    }

    internal fun <T> phase(name: String, action: () -> T): T {
        val startBytes = allocations.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        val result = action()
        val nanos = System.nanoTime() - started
        val bytes = allocations.getThreadAllocatedBytes(thread) - startBytes
        allocatedBytes = bytes
        if (name == "source") sourceBytes = bytes
        println("perf_phase=$name elapsed_ns=$nanos allocated_bytes=$bytes")
        return result
    }

    private fun samples(path: Path, readPaths: Set<Path>) {
        val totals = Samples(readPaths.mapTo(HashSet()) { it.toString() })
        RecordingFile(path).use { recording ->
            while (recording.hasMoreEvents()) totals.accept(recording.readEvent())
        }
        totals.print()
        diskBytes = totals.diskBytes
        diskWriteBytes = totals.diskWriteBytes
    }

    private class Samples(private val readPaths: Set<String>) {
        private val cpuSamples = mutableMapOf<String, Long>()
        private val sampledBytes = mutableMapOf<String, Long>()
        private var diskNanos = 0L
        var diskBytes = 0L
            private set
        var diskWriteBytes = 0L
            private set

        fun accept(event: RecordedEvent) {
            when (event.eventType.name) {
                "jdk.FileRead" -> if (readPaths.isEmpty() || event.getString("path") in readPaths) {
                    diskNanos += event.duration.toNanos()
                    diskBytes += event.getLong("bytesRead").coerceAtLeast(0)
                }
                "jdk.FileWrite" -> diskWriteBytes += event.getLong("bytesWritten").coerceAtLeast(0)
                "jdk.ExecutionSample" -> {
                    val phase = phase(event)
                    cpuSamples[phase] = (cpuSamples[phase] ?: 0) + 1
                }
                "jdk.ObjectAllocationSample" -> {
                    val phase = phase(event)
                    sampledBytes[phase] = (sampledBytes[phase] ?: 0) + event.getLong("weight")
                }
            }
        }

        private fun phase(event: RecordedEvent): String {
            val names = event.stackTrace?.frames.orEmpty().map { it.method.type.name + "." + it.method.name }
            return when {
                names.any { "PerfRowsFileSource\$Scan.parse" in it || "PerfRowsFileSource\$Scan.decode" in it } -> "decode"
                names.any { "PerfLineReader" in it || "PerfPrefixDigest" in it } -> "disk_read_and_framing"
                names.any { "PerfRowsCache" in it || "PerfCachedLine" in it } -> "cache_and_selection"
                else -> "other"
            }
        }

        fun print() {
            println(
                "perf_disk_read_ns=$diskNanos perf_disk_read_bytes=$diskBytes perf_disk_write_bytes=$diskWriteBytes",
            )
            println("perf_cpu_samples=$cpuSamples perf_sampled_allocation_bytes=$sampledBytes")
        }
    }
}
