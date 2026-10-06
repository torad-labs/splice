// NEW: real summary reads must retain availability truth without repeated chunk decompression.
package splice.head.trace.body

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.storage.DayBodyBudget
import splice.core.storage.DayFiles
import splice.core.util.WallClock
import splice.head.trace.TraceAsk
import splice.head.trace.TraceRows
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

class TraceSummaryValidationTest {
    @Test
    fun `an unchanged second list validates current bytes without decompressing again`(@TempDir dir: Path) {
        val fixture = fixture(dir)
        assertEquals(0, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        val cold = fixture.decoder.calls
        assertTrue(cold > 0, "a cold summary must exercise the real decoder")
        repeat(3) {
            assertEquals(0, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
            assertEquals(cold, fixture.decoder.calls, "unchanged lists must reuse validated literal certificates")
        }
    }

    @Test
    fun `duplicate selected references touch and decompress each actual chunk only once`(@TempDir dir: Path) {
        val fixture = fixture(dir, records = 8)
        val capture = dir.resolve("summary-reads.jfr")
        Recording().use { recording ->
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace()
            recording.start()
            val read = fixture.rows.summaries(dir, "synthetic", TraceAsk(8))
            assertEquals(8, read.turns.size)
            assertEquals(0, read.unavailableRecords)
            recording.stop()
            recording.dump(capture)
        }
        assertEquals(1, fixture.decoder.calls, "all selected records share one real packed chunk")
        val readBytes = RecordingFile.readAllEvents(capture)
            .filter { it.eventType.name == "jdk.FileRead" && it.getString("path") == fixture.pack.toString() }
            .sumOf { it.getLong("bytesRead") }
        val bound = Files.size(fixture.pack) + TRACE_PACK_V2_HEADER_BYTES
        assertTrue(readBytes > 0, "the touch control must observe actual pack reads")
        assertTrue(readBytes <= bound, "duplicate references reread payloads: $readBytes > $bound")
    }

    @Test
    fun `a warmed certificate cannot hide changed payload bytes even with the same header and timestamp`(
        @TempDir dir: Path,
    ) {
        val fixture = fixture(dir)
        assertEquals(0, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        val stamp = Files.getLastModifiedTime(fixture.pack)
        val bytes = Files.readAllBytes(fixture.pack)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(fixture.pack, bytes)
        Files.setLastModifiedTime(fixture.pack, stamp)
        repeat(2) {
            assertEquals(1, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        }
        val cold = TraceRows(heap = splice.head.syntheticHeapBudget())
        assertEquals(1, cold.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
    }

    @Test
    fun `unchanged corrupt chunks retain negative certificates without hiding unavailability`(@TempDir dir: Path) {
        val fixture = fixture(dir)
        val bytes = Files.readAllBytes(fixture.pack)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(fixture.pack, bytes)
        assertEquals(1, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        val cold = fixture.decoder.calls
        assertTrue(cold > 0)
        repeat(2) {
            assertEquals(1, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
            assertEquals(cold, fixture.decoder.calls, "unchanged invalid bytes must reuse a negative certificate")
        }
    }

    @Test
    fun `a missing pack cannot reuse a warm certificate`(@TempDir dir: Path) {
        val fixture = fixture(dir)
        assertEquals(0, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        Files.delete(fixture.pack)
        repeat(2) {
            assertEquals(1, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        }
    }

    @Test
    fun `valid chunk digests do not certify malformed or reordered complete literals`(@TempDir dir: Path) {
        val heap = splice.head.syntheticHeapBudget()
        val day = dir.resolve("synthetic-2026-10-06.jsonl")
        val pack = TracePackFormat.V2.pack(day)
        val parts = TraceBodyPack(pack, TracePackIndex(heap), budget = DayBodyBudget(minFreeBytes = 0)).use { writer ->
            listOf(writer.put("\"synthetic".toByteArray()), writer.put(" body\"".toByteArray()))
        }
        val valid = referenced(JsonArray(parts))
        val invalid = referenced(JsonArray(parts.reversed()))
        Files.writeString(day, valid.toString() + "\n")
        val rows = TraceRows(heap = heap)
        assertEquals(0, rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        Files.writeString(day, invalid.toString() + "\n")
        repeat(2) {
            assertEquals(1, rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        }
        assertFalse(rows.summaries(dir, "synthetic", TraceAsk(1)).turns.isEmpty())
    }

    @Test
    fun `composed certificates equal cold full scans across every lexer boundary state`(@TempDir dir: Path) {
        val cases = listOf(
            listOf(" ", "\"x\""),
            listOf("\"x", "y\""),
            listOf("\"x\\", "n\""),
            listOf("\"\\u", "0041\""),
            listOf("\"\\u0", "041\""),
            listOf("\"\\u00", "41\""),
            listOf("\"\\u004", "1\""),
            listOf("\"x\"", " "),
            listOf("\"x\\", "q\""),
            listOf("\"\\u00", "zz\""),
            listOf("\"x\"", "extra"),
            listOf("\"x", "unfinished"),
        )
        val heap = splice.head.syntheticHeapBudget()
        val pack = dir.resolve("synthetic-2026-10-06.jsonl.bodies2")
        val decoder = CountingDecoder()
        val validation = TraceBodyValidation(heap, decoder)
        val parts = TraceBodyPack(pack, TracePackIndex(heap), budget = DayBodyBudget(minFreeBytes = 0)).use { writer ->
            cases.map { chunks -> JsonArray(chunks.map { writer.put(it.toByteArray()) }) }
        }
        repeat(2) {
            TraceBodyReader(pack, TracePackFormat.V2, heap).use { reader ->
                cases.zip(parts).forEach { (fragments, reference) ->
                    val cold = TraceLiteralScan()
                    val valid = fragments.all { cold.feed(it.toByteArray()) } && cold.ended
                    if (valid) {
                        reader.validate(reference, decoder, validation)
                    } else {
                        assertThrows(IOException::class.java) { reader.validate(reference, decoder, validation) }
                    }
                }
            }
        }
        assertTrue(decoder.calls > 0, "state certificates must come from real decoded bytes")
    }

    @Test
    fun `header preserving truncate and regrow cannot reuse a stale body certificate`(@TempDir dir: Path) {
        val fixture = fixture(dir)
        assertEquals(0, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        val day = dir.resolve("synthetic-2026-10-06.jsonl")
        FileChannel.open(fixture.pack, StandardOpenOption.WRITE).use { it.truncate(TRACE_PACK_START_BYTES.toLong()) }
        TraceBodies(heap = splice.head.syntheticHeapBudget()).encode(record(1, "different body"), day)
        repeat(2) {
            assertEquals(1, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        }
    }

    @Test
    fun `real purge and eviction never report warm packed records available`(@TempDir dir: Path) {
        val purged = dir.resolve("purged").also(Files::createDirectories)
        val first = fixture(purged)
        assertEquals(0, first.rows.summaries(purged, "synthetic", TraceAsk(1)).unavailableRecords)
        DayFiles(purged, "synthetic").purge()
        assertFalse(Files.exists(first.pack))
        assertTrue(first.rows.summaries(purged, "synthetic", TraceAsk(1)).turns.isEmpty())

        val evicted = dir.resolve("evicted").also(Files::createDirectories)
        val second = fixture(evicted)
        assertEquals(0, second.rows.summaries(evicted, "synthetic", TraceAsk(1)).unavailableRecords)
        val bytes = Files.size(second.pack)
        val budget = DayBodyBudget(
            maxBytes = bytes,
            minFreeBytes = 0,
            clock = WallClock { Instant.parse("2026-10-07T12:00:00Z").toEpochMilli() },
        )
        budget.admit(TracePackFormat.V2.pack(evicted.resolve("other-2026-10-07.jsonl")), bytes)
        assertFalse(Files.exists(second.pack), "the actual budget must unlink the older body pack")
        repeat(2) {
            assertEquals(1, second.rows.summaries(evicted, "synthetic", TraceAsk(1)).unavailableRecords)
        }
    }

    @Test
    fun `transient read failures are never cached as corrupt bytes`(@TempDir dir: Path) {
        val failures = listOf(
            IOException("synthetic read"),
            EOFException("synthetic EOF"),
            InterruptedIOException("synthetic interruption"),
        )
        failures.forEachIndexed { index, failure ->
            val root = dir.resolve("failure-$index").also { Files.createDirectories(it) }
            fixture(root)
            val decoder = FailOnceDecoder(failure)
            val rows = TraceRows(heap = splice.head.syntheticHeapBudget(), decoder = decoder)
            assertEquals(1, rows.summaries(root, "synthetic", TraceAsk(1)).unavailableRecords)
            assertEquals(0, rows.summaries(root, "synthetic", TraceAsk(1)).unavailableRecords)
            assertEquals(2, decoder.calls, "healthy bytes must be decoded after the transient failure")
            assertEquals(0, rows.summaries(root, "synthetic", TraceAsk(1)).unavailableRecords)
            assertEquals(2, decoder.calls)
        }
    }

    @Test
    fun `a truncated pack read recovers on the same reader after its healthy bytes arrive`(@TempDir dir: Path) {
        val fixture = fixture(dir)
        val complete = Files.readAllBytes(fixture.pack)
        FileChannel.open(fixture.pack, StandardOpenOption.WRITE).use { it.truncate(complete.size - 1L) }
        assertEquals(1, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        assertEquals(0, fixture.decoder.calls, "the incomplete entry must fail before decoding or certification")
        Files.write(fixture.pack, complete)
        assertEquals(0, fixture.rows.summaries(dir, "synthetic", TraceAsk(1)).unavailableRecords)
        assertEquals(1, fixture.decoder.calls)
    }

    @Test
    fun `unknown reference fields neither retain payloads nor multiply chunk touches`(@TempDir dir: Path) {
        val heap = splice.head.syntheticHeapBudget()
        val decoder = CountingDecoder()
        val validation = TraceBodyValidation(heap, decoder)
        val pack = dir.resolve("synthetic-2026-10-06.jsonl.bodies2")
        val part = TraceBodyPack(pack, TracePackIndex(heap), budget = DayBodyBudget(minFreeBytes = 0)).use {
            it.put("\"synthetic body\"".toByteArray())
        }
        val marker = "synthetic ignored payload"
        TraceBodyReader(pack, TracePackFormat.V2, heap).use { reader ->
            for (extra in listOf(marker, "other ignored payload")) {
                val decorated = JsonObject(part + ("ignored" to kotlinx.serialization.json.JsonPrimitive(extra)))
                reader.validate(JsonArray(listOf(decorated)), decoder, validation)
            }
        }
        val keys = validation.javaClass.getDeclaredField("certificates").apply { isAccessible = true }
            .get(validation) as Map<*, *>
        assertFalse(keys.keys.toString().contains(marker), "certificate keys cannot retain unknown source payloads")
        assertEquals(1, decoder.calls, "aliases describe one actual chunk, not new payloads")
    }

    @Test
    fun `certificate metadata is charged bounded evictable and refunded on deletion`(@TempDir dir: Path) {
        val heap = HeapBudget(Long.MAX_VALUE, 1024 * 1024)
        val decoder = CountingDecoder()
        val validation = TraceBodyValidation(heap, decoder, maxBytes = 6000)
        val pack = dir.resolve("synthetic.bodies2")
        Files.write(pack, byteArrayOf(1))
        val generation = UUID(0, 1)
        val before = heap.available.value
        repeat(8) { index ->
            val raw = "\"synthetic-$index\"".toByteArray()
            val hash = TracePackBytes.hashOf(raw)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
            val stored = TracePackFormat.V2.encode(raw, digest).second
            val part = TraceChunkReference(index.toLong(), raw.size, hash)
            validation.certify(
                TraceChunkKey(pack, TracePackFormat.V2, generation, part),
                TracePackEntry(stored.size, raw.size, hash),
                stored,
            )
            assertTrue(validation.retainedBytes in 1..6000)
            assertEquals(validation.retainedBytes, before - heap.available.value)
        }
        assertEquals(8, decoder.calls)
        Files.delete(pack)
        validation.prune()
        assertEquals(0, validation.retainedBytes)
        assertEquals(before, heap.available.value)
    }

    private fun fixture(dir: Path, records: Int = 1): Fixture {
        val heap = splice.head.syntheticHeapBudget()
        val day = dir.resolve("synthetic-2026-10-06.jsonl")
        val writer = TraceBodies(heap = heap)
        val lines = (1..records).flatMap { index ->
            writer.encode(record(index, "synthetic body"), day).asIterable()
        }.toByteArray()
        Files.write(day, lines)
        val decoder = CountingDecoder()
        return Fixture(TraceRows(heap = heap, decoder = decoder), TracePackFormat.V2.pack(day), decoder)
    }

    private fun record(index: Int, text: String): JsonObject = buildJsonObject {
        put("kind", "turn")
        put("turn", "synthetic-$index")
        put("ts", 1_791_263_000_000L)
        put("client", buildJsonObject { put("body", text) })
    }

    private fun referenced(parts: JsonArray): JsonObject = buildJsonObject {
        put("kind", "turn")
        put("turn", "synthetic-reference")
        put("ts", 1_791_263_000_000L)
        put(
            "client",
            buildJsonObject {
                put(
                    "body",
                    buildJsonObject {
                        put(TRACE_REFERENCE_TAG, TracePackFormat.V2.version)
                        put("parts", parts)
                    },
                )
            },
        )
    }

    private data class Fixture(val rows: TraceRows, val pack: Path, val decoder: CountingDecoder)

    private class FailOnceDecoder(private val failure: IOException) : TraceChunkDecoder {
        var calls = 0
            private set

        override fun decode(format: TracePackFormat, bytes: ByteArray, length: Int): ByteArray? {
            calls++
            if (calls == 1) throw failure
            return format.decode(bytes, length)
        }
    }

    private class CountingDecoder : TraceChunkDecoder {
        var calls = 0
            private set

        override fun decode(format: TracePackFormat, bytes: ByteArray, length: Int): ByteArray? {
            calls++
            return format.decode(bytes, length)
        }
    }
}
