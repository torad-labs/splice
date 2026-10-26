package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.storage.DayBodyBudget
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Random

// why: the SHA-256 digest a v2 entry header carries; the stored size does not depend on its value.
private const val DIGEST_BYTES = 32

// why: a fixed seed, so the incompressible body is the same bytes on every run.
private const val SEED = 20_261_005L

class TracePackBudgetTest {
    @Test
    fun `capacity retains metadata marks new bodies unavailable and still reuses stored bodies`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val text = "synthetic first"
        val stored = TracePackFormat.V2.encode("\"$text\"".toByteArray(), ByteArray(DIGEST_BYTES)).second.size
        val capacity = TRACE_PACK_START_BYTES + TRACE_PACK_V2_HEADER_BYTES + stored.toLong()
        val bodies = TraceBodies(capacity, heap = splice.head.syntheticHeapBudget())
        val first = encoded(bodies, day, text)
        val full = Files.size(dir.resolve("${day.fileName}.bodies2"))
        assertEquals(capacity, full, "the positive control fills the pack exactly")
        val omitted = encoded(bodies, day, "synthetic other")
        assertEquals(full, Files.size(dir.resolve("${day.fileName}.bodies2")))
        val reference = omitted.getValue("client").jsonObject.getValue("body").jsonObject
        assertEquals("true", reference["unavailable"]?.jsonPrimitive?.content)
        assertEquals("true", reference["truncated"]?.jsonPrimitive?.content)
        assertEquals("m", omitted["model"]?.jsonPrimitive?.content)
        assertEquals(omitted, bodies.hydrate(omitted, day), "capacity markers remain explicit on reads")
        assertEquals(first, encoded(bodies, day, text), "full packs still admit deduplicated bodies")
        assertEquals(record(text), bodies.hydrate(first, day))
    }

    @Test
    fun `a multi chunk body cannot cross the daily cap or return a successful partial literal`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val capacity = TRACE_PACK_START_BYTES + TRACE_PACK_V2_HEADER_BYTES + CHUNK_MAX.toLong()
        val bodies = TraceBodies(capacity, heap = splice.head.syntheticHeapBudget())
        // Seeded random Base64 stays about three quarters of its size through zstd, so it crosses the cap.
        val noise = ByteArray(CHUNK_MAX * 3).also { Random(SEED).nextBytes(it) }
        val text = Base64.getEncoder().encodeToString(noise) + "λ"
        val encoded = encoded(bodies, day, text)
        assertTrue(Files.size(dir.resolve("${day.fileName}.bodies2")) <= capacity)
        val body = encoded.getValue("client").jsonObject.getValue("body").jsonObject
        assertEquals("true", body["unavailable"]?.jsonPrimitive?.content)
        assertEquals("true", body["truncated"]?.jsonPrimitive?.content)
        assertTrue(body["parts"] == null, "an incomplete JSON literal cannot be published as readable parts")
        assertEquals(encoded, bodies.hydrate(encoded, day))
    }

    @Test
    fun `a stored chunk is reused when the heap has no room to copy it, and a new one is refused for heap capacity`(
        @TempDir dir: Path,
    ) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val file = day.resolveSibling("${day.fileName}.bodies2")
        val random = Random(SEED)
        val held = ByteArray(CHUNK_MAX).also { random.nextBytes(it) }
        val fresh = ByteArray(CHUNK_MAX).also { random.nextBytes(it) }
        // Room for the index's first table and one chunk's copy, until the other owners below take it.
        val heap = HeapBudget(heapLimitBytes = 1L shl 30, budgetBytes = 4L * CHUNK_MAX)
        TraceBodyPack(file, TracePackIndex(heap), budget = DayBodyBudget(minFreeBytes = 0)).use { pack ->
            val stored = pack.put(held)
            val size = Files.size(file)
            // The daemon's other owners take the heap down to one byte less than a chunk, so no chunk can be copied.
            checkNotNull(heap.reserve(heap.available.value - (CHUNK_MAX - 1))).use {
                assertEquals(stored, pack.put(held), "a chunk the pack holds is named without a copy")
                assertThrows(
                    HeapCapacityException::class.java,
                    { pack.put(fresh) },
                    "a new chunk is refused for heap, never as a full pack, which stores a body as an omission",
                )
                assertEquals(size, Files.size(file), "the refused chunk appends nothing")
            }
            val hash = pack.put(fresh).getValue("hash").jsonPrimitive.content
            assertEquals(TracePackBytes.hashOf(fresh), hash, "once the heap has room, the refused chunk stores")
        }
    }

    private fun encoded(bodies: TraceBodies, day: Path, text: String): JsonObject =
        Json.parseToJsonElement(bodies.encode(record(text), day).decodeToString()).jsonObject

    private fun record(text: String): JsonObject = buildJsonObject {
        put("model", "m")
        put("client", buildJsonObject { put("body", text) })
    }
}
