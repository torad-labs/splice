package splice.head.trace.body

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.head.trace.TraceAsk
import splice.head.trace.TraceRows
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

private const val SCOPE_RECORDS = 64

class TraceReadScopeTest {
    @Test
    fun `one selected read scans each pack header once and closes its pack descriptors`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val pack = dir.resolve("${day.fileName}.bodies")
        val bodies = TraceBodies()
        val lines = (1..SCOPE_RECORDS).flatMap { number ->
            bodies.encode(record(number), day).asIterable()
        }.toByteArray()
        Files.write(day, lines)
        val baseline = descriptors(pack)
        val capture = dir.resolve("reads.jfr")
        Recording().use { recording ->
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace()
            recording.start()
            val turns = TraceRows().turns(dir, "synthetic", TraceAsk(last = SCOPE_RECORDS))
            assertEquals(SCOPE_RECORDS, turns.size)
            assertEquals(baseline, descriptors(pack), "every selected-read pack descriptor closes")
            recording.stop()
            recording.dump(capture)
        }
        val bytes = RecordingFile.readAllEvents(capture)
            .filter { it.eventType.name == "jdk.FileRead" && it.getString("path") == pack.toString() }
            .sumOf { it.getLong("bytesRead") }
        val packBytes = Files.size(pack)
        val bound = packBytes + SCOPE_RECORDS.toLong() * TRACE_PACK_HEADER_BYTES
        assertTrue(bytes >= packBytes, "positive control must observe actual pack reads: $bytes/$packBytes")
        assertTrue(bytes <= bound, "repeated header scans exceed the linear bound: $bytes > $bound")
        println("TRACE_PACK_READ bytes=$bytes linear_bound=$bound")
    }

    private fun descriptors(pack: Path): Long = Files.list(Path.of("/proc/self/fd")).use { files ->
        files.filter { fd ->
            try {
                Files.readSymbolicLink(fd) == pack
            } catch (_: java.io.IOException) {
                false
            }
        }.count()
    }

    private fun record(number: Int): JsonObject = buildJsonObject {
        put("kind", "turn")
        put("turn", "synthetic-$number")
        put("ts", 1_789_725_600_000L)
        put("model", "m")
        put("client", buildJsonObject { put("body", "synthetic body $number") })
    }
}
