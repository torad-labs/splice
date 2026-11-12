// A live perf row carries 25 to 79 fields; the streaming reader must take them, or every row pays the tree reader.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerfRowDecodeTest {
    private fun row(extra: Int): String {
        val counters = (1..extra).joinToString(",") { """"counter_$it":$it""" }
        return """{"ts":1791707160100,"model":"synthetic","outcome":"ok",$counters}"""
    }

    @Test
    fun `a row with the most fields a live perf file holds is read by the streaming reader`() {
        val decode = PerfRowDecode(PerfFieldNames(1_048_576L))
        val read = decode.decode(row(extra = 76))
        val why = read.exceptionOrNull()?.message
        assertTrue(read.isSuccess, "a 79-field row must not fall to the tree reader: $why")
        assertEquals(1791707160100L, read.getOrThrow().row.ts)
    }
}
