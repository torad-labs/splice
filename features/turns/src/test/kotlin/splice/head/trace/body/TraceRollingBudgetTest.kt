package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.BODY_BUDGET_EVICTED_REASON
import splice.core.storage.DAY_BODY_SUFFIX
import splice.core.storage.DayBodyBudget
import splice.core.storage.DayBodyCapacityException
import splice.core.storage.DayVolumeSpace
import splice.core.util.WallClock
import splice.head.syntheticHeapBudget
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// Synthetic stored bytes and clocks keep these tests independent of the host volume and provider traffic.
private const val ROLLING_FIXTURE_BYTES = 256L
private const val ROLLING_FIXTURE_FLOOR = 64L
private val ROLLING_NOW = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()

// why: every synthetic index file holds this many bytes, so an index cap counts files
private const val INDEX_FILE_BYTES = 4096L

// why: more than any line a short synthetic record makes, and less than one head's day of index files
private const val LINE_MARGIN = 8192L

class TraceRollingBudgetTest {
    @Test
    fun `a shared budget replaces the exhausted per head cap`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-10-05.jsonl")
        val budget = budget(ROLLING_FIXTURE_BYTES)
        // The old per-head bound is intentionally smaller than a single synthetic body.
        val bodies = TraceBodies(
            TRACE_PACK_START_BYTES.toLong(),
            budget = budget,
            heap = splice.head.syntheticHeapBudget(),
        )
        val encoded = encoded(bodies, day, "synthetic body retained by shared capacity")
        assertFalse(body(encoded)["unavailable"]?.jsonPrimitive?.content == "true")
        assertEquals(record("synthetic body retained by shared capacity"), bodies.hydrate(encoded, day))
    }

    @Test
    fun `the volume floor refuses the whole record while stored bodies stay reusable`(@TempDir dir: Path) {
        var free = Long.MAX_VALUE
        val policy = DayBodyBudget(
            ROLLING_FIXTURE_BYTES,
            ROLLING_FIXTURE_FLOOR,
            DayVolumeSpace { free },
            WallClock { ROLLING_NOW },
        )
        val day = dir.resolve("synthetic-2026-10-05.jsonl")
        val bodies = TraceBodies(budget = policy, heap = splice.head.syntheticHeapBudget())
        val first = encoded(bodies, day, "synthetic first")
        free = ROLLING_FIXTURE_FLOOR
        // Below the floor neither a new body nor the index line of a stored one is written.
        for (text in listOf("synthetic other", "synthetic first")) {
            val refused = assertThrows(DayBodyCapacityException::class.java) { encoded(bodies, day, text) }
            assertEquals("trace volume free-space floor reached", refused.reason)
        }
        free = Long.MAX_VALUE
        assertEquals(first, encoded(bodies, day, "synthetic first"))
        assertEquals(record("synthetic first"), bodies.hydrate(first, day))
    }

    @Test
    fun `oldest bodies go before newer days while metadata and current bodies remain`(@TempDir dir: Path) {
        val oldest = dir.resolve("a-2026-10-03.jsonl")
        val other = dir.resolve("b-2026-10-03.jsonl")
        val yesterday = dir.resolve("c-2026-10-04.jsonl")
        val today = dir.resolve("a-2026-10-05.jsonl")
        val writer = TraceBodies(budget = budget(Long.MAX_VALUE), heap = splice.head.syntheticHeapBudget())
        val old = encoded(writer, oldest, "synthetic old")
        Files.writeString(oldest, old.toString() + "\n")
        Files.write(other.resolveSibling("${other.fileName}$DAY_BODY_SUFFIX"), ByteArray(ROLLING_FIXTURE_BYTES.toInt()))
        val recent = encoded(writer, yesterday, "synthetic recent")
        Files.writeString(yesterday, recent.toString() + "\n")
        val current = encoded(writer, today, "synthetic current")
        val packs = listOf(oldest, yesterday, today).map(TracePackFormat.V2::pack)
        val size = packs.sumOf(Files::size) + ROLLING_FIXTURE_BYTES
        val policy = budget(size)
        val bodies = TraceBodies(budget = policy, heap = splice.head.syntheticHeapBudget())
        val next = encoded(bodies, today, "synthetic new current body")
        assertFalse(Files.exists(TracePackFormat.V2.pack(oldest)))
        assertFalse(Files.exists(other.resolveSibling("${other.fileName}$DAY_BODY_SUFFIX")))
        assertTrue(Files.exists(oldest), "body pressure preserves its trace metadata")
        assertTrue(Files.exists(TracePackFormat.V2.pack(yesterday)))
        assertTrue(Files.exists(TracePackFormat.V2.pack(today)))
        assertEquals(record("synthetic current"), bodies.hydrate(current, today))
        assertEquals(record("synthetic new current body"), bodies.hydrate(next, today))
        val omitted = bodies.hydrate(old, oldest)
        assertEquals(BODY_BUDGET_EVICTED_REASON, body(omitted)["reason"]?.jsonPrimitive?.content)
        assertEquals("true", body(omitted)["unavailable"]?.jsonPrimitive?.content)
        assertEquals(old.toString() + "\n", Files.readString(oldest), "eviction leaves the metadata byte-identical")
        val rolled = oldest.resolveSibling("${oldest.fileName}.1")
        assertEquals(BODY_BUDGET_EVICTED_REASON, body(bodies.hydrate(old, rolled))["reason"]?.jsonPrimitive?.content)
        val reopened = encoded(writer, oldest, "synthetic old")
        assertEquals(BODY_BUDGET_EVICTED_REASON, body(reopened)["reason"]?.jsonPrimitive?.content)
        assertFalse(Files.exists(TracePackFormat.V2.pack(oldest)), "a stale writer never resurrects an evicted pack")
    }

    @Test
    fun `past the index cap the oldest completed day goes whole across heads and today and the day being written stay`(
        @TempDir dir: Path,
    ) {
        val oldest = listOf("a", "b").flatMap { head ->
            val day = dir.resolve("$head-2026-10-03.jsonl")
            listOf("", ".1", ".2").map { index(dir.resolve("${day.fileName}$it")) } + listOf(
                Files.createFile(dir.resolve("${day.fileName}.lock")),
                Files.write(TracePackFormat.V2.pack(day), ByteArray(ROLLING_FIXTURE_BYTES.toInt())),
                Files.writeString(dir.resolve("${day.fileName}.bodies-evicted"), BODY_BUDGET_EVICTED_REASON + "\n"),
            )
        }
        val kept = listOf("a", "b").flatMap { head ->
            listOf("2026-10-04", "2026-10-05").map { date -> index(dir.resolve("$head-$date.jsonl")) }
        } + listOf(
            Files.createFile(dir.resolve("b-2026-10-04.jsonl.lock")),
            Files.write(dir.resolve("b-2026-10-04.jsonl.bodies2"), ByteArray(ROLLING_FIXTURE_BYTES.toInt())),
        )
        // The four index files of today and yesterday, and room for any line one short record makes.
        val today = dir.resolve("a-2026-10-05.jsonl")
        val bodies = TraceBodies(budget = indexBudget(4 * INDEX_FILE_BYTES + LINE_MARGIN), heap = syntheticHeapBudget())
        val line = encoded(bodies, today, "synthetic record past the index cap")
        assertEquals(record("synthetic record past the index cap"), bodies.hydrate(line, today))
        oldest.forEach { assertFalse(Files.exists(it), "$it goes with its whole day") }
        kept.forEach { assertTrue(Files.exists(it), "$it stays: its day is the newest past day or today") }
        // Now only today and the day being written hold index bytes, and together they fill the cap.
        val before = Files.list(dir).use { it.toList() }
        val full = TraceBodies(budget = indexBudget(4 * INDEX_FILE_BYTES), heap = syntheticHeapBudget())
        val refused = assertThrows(DayBodyCapacityException::class.java) {
            encoded(full, dir.resolve("a-2026-10-04.jsonl"), "synthetic record for yesterday")
        }
        assertEquals("shared trace index budget exhausted by the day being written", refused.reason)
        before.forEach { assertTrue(Files.exists(it), "$it stays: a refusal deletes nothing") }
    }

    private fun indexBudget(cap: Long): DayBodyBudget = DayBodyBudget(
        Long.MAX_VALUE,
        0,
        DayVolumeSpace { Long.MAX_VALUE },
        WallClock { ROLLING_NOW },
        indexMaxBytes = cap,
    )

    private fun index(file: Path): Path = Files.write(file, ByteArray(INDEX_FILE_BYTES.toInt()))

    private fun budget(capacity: Long): DayBodyBudget =
        DayBodyBudget(capacity, 0, DayVolumeSpace { Long.MAX_VALUE }, WallClock { ROLLING_NOW })

    private fun encoded(bodies: TraceBodies, day: Path, text: String): JsonObject =
        Json.parseToJsonElement(bodies.encode(record(text), day).decodeToString()).jsonObject

    private fun body(record: JsonObject): JsonObject = record.getValue("client").jsonObject.getValue("body").jsonObject

    private fun record(text: String): JsonObject = buildJsonObject {
        put("model", "synthetic")
        put("client", buildJsonObject { put("body", text) })
    }
}
