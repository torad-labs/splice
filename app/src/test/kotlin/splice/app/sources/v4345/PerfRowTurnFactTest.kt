// NEW: V4-345 — the reader carries a row's trace turn id, by name, off real JSONL bytes: the id the
// console opens the row's request by. It is a string fact like the session tag, so it stays out of the
// numeric bag even when every character of it is a digit, as one random 12-hex id in about 280 is:
// the bag is the row's marks and counters, and a turn id read into it would sit among them as a count.
package splice.app.sources.v4345

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.sources.PerfRowsFileSource
import java.nio.file.Files
import java.nio.file.Path

class PerfRowTurnFactTest {

    @Test
    fun `a row's turn is read by name, and a row without one reads it as absent`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            """{"ts":1000,"model":"m","outcome":"ok","session":"sess-100","turn":"3f2a9c01d4e5","total":5}""" + "\n" +
                """{"ts":2000,"model":"m","outcome":"ok","total":7}""" + "\n",
        )

        val (traced, bare) = PerfRowsFileSource(file).window(0).rows

        assertEquals("3f2a9c01d4e5", traced.turn)
        assertEquals("sess-100", traced.session, "the turn is not read into its neighbour")
        assertNull(bare.turn, "no trace, no turn")
    }

    @Test
    fun `an id or a tag of digits alone stays out of the numeric bag`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        val line = """{"ts":1000,"outcome":"ok","session":"12345678","turn":"123456789012","total":5}"""
        Files.writeString(file, line + "\n")

        val row = PerfRowsFileSource(file).window(0).rows.single()

        assertEquals("123456789012", row.turn)
        assertEquals("12345678", row.session)
        assertFalse("turn" in row.fields, "a string fact read as a counter: ${row.fields}")
        assertFalse("session" in row.fields, "a string fact read as a counter: ${row.fields}")
        assertEquals(5L, row.fields["total"], "the counters are still the bag")
    }
}
