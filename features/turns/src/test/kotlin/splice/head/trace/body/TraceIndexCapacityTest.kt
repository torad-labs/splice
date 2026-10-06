package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.storage.DayBodyBudget
import splice.core.storage.DayVolumeSpace
import java.nio.file.Files
import java.nio.file.Path

// The bounded resident table has two long arrays, each with twice the admitted entry count.
private const val INDEX_TABLE_BYTES = 2L * 2 * Long.SIZE_BYTES * TRACE_PACK_MAX_ENTRIES

class TraceIndexCapacityTest {
    @Test
    fun `new chunks pass the resident index ceiling without growing its heap charge`(
        @TempDir dir: Path,
        reporter: TestReporter,
    ) {
        val heap = HeapBudget(Long.MAX_VALUE)
        val index = TracePackIndex(heap)
        val budget = DayBodyBudget(space = DayVolumeSpace { Long.MAX_VALUE })
        val file = dir.resolve("synthetic-2026-10-05.jsonl.bodies2")
        budget.withLock(dir) {
            TraceBodyPack(file, index, budget = budget).use { pack ->
                var last = pack.put(JsonPrimitive("synthetic 0").toString().toByteArray())
                for (number in 1..TRACE_PACK_MAX_ENTRIES) {
                    last = pack.put(JsonPrimitive("synthetic $number").toString().toByteArray())
                }
                assertEquals(TRACE_PACK_MAX_ENTRIES, index.size)
                assertEquals(INDEX_TABLE_BYTES, heap.limitBytes - heap.available.value)
                TraceBodyReader(file, TracePackFormat.V2, HeapBudget(Long.MAX_VALUE)).use { reader ->
                    val restored = reader.literal(JsonArray(listOf(last)))
                    assertEquals(JsonPrimitive("synthetic $TRACE_PACK_MAX_ENTRIES"), restored)
                }
                reporter.publishEntry(
                    mapOf(
                        "stored_chunks" to (TRACE_PACK_MAX_ENTRIES + 1).toString(),
                        "index_charged_bytes" to (heap.limitBytes - heap.available.value).toString(),
                        "stored_pack_bytes" to Files.size(file).toString(),
                    ),
                )
            }
        }
    }
}
