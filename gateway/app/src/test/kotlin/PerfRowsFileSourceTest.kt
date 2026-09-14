import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.PerfRowsFileSource
import java.nio.file.Files
import java.nio.file.Path

class PerfRowsFileSourceTest {

    @Test
    fun `both generations are read, rows before since are skipped, malformed lines are ignored`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            dir.resolve("head-perf.jsonl.1"),
            """{"ts":5, BROKEN""" + "\n" +
                """{"ts":100,"model":"m","outcome":"ok","total":5}""" + "\n" +
                """{"ts":900,"model":"m","outcome":"client_abort","total":7,"session":"abcd"}""" + "\n",
        )
        Files.writeString(
            file,
            """{"ts":1500,"model":"m","outcome":"ok","total":9,"first_byte":3}""" + "\n" +
                "{ torn" + "\n" +
                """{"ts":2000,"outcome":"error:upstream-failed","total":1}""" + "\n",
        )
        val read = PerfRowsFileSource(file).window(500)
        assertEquals(listOf(900L, 1500L, 2000L), read.rows.map { it.ts })
        assertEquals(listOf("client_abort", "ok", "error:upstream-failed"), read.rows.map { it.outcome })
        assertEquals(mapOf("ts" to 1500L, "total" to 9L, "first_byte" to 3L), read.rows[1].fields)
        assertEquals(100L, read.oldestHeldTs, "the rotated generation, VALID rows only")
        assertNull(read.readError, "both generations readable")
        Files.writeString(dir.resolve("head-perf.jsonl.1"), """{"ts":5, BROKEN""" + "\n")
        val oldest = PerfRowsFileSource(file).window(500).oldestHeldTs
        assertEquals(1500L, oldest, "a torn line with old digits is no evidence")
        val absent = PerfRowsFileSource(dir.resolve("absent.jsonl")).window(0)
        assertEquals(emptyList<Long>(), absent.rows.map { it.ts })
        assertNull(absent.oldestHeldTs)
        assertNull(absent.readError, "absence is quiet")
    }

    @Test
    fun `the parsed top-level unquoted ts is the only row authority`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            """{"ts": 1002,"outcome":"ok","total":50}""" + "\n" +
                """{"meta":{"ts":1003},"outcome":"ok","total":50}""" + "\n" +
                """{"ts":"1004","outcome":"ok","total":50}""" + "\n" +
                """{"ts":1005,"outcome":"ok","total":50}""" + "\n",
        )
        val read = PerfRowsFileSource(file).window(1000)
        assertEquals(listOf(1002L, 1005L), read.rows.map { it.ts }, "whitespace is JSON; nested and quoted are no ts")
        assertEquals(1002L, read.oldestHeldTs, "the same authority decides retention")
    }

    @Test
    fun `the last row before the cutoff is kept as the drops baseline, whether skipped by hint or parsed`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            """{"ts":990,"outcome":"ok","async_io_drops":3}""" + "\n" +
                """{"ts":999,"outcome":"ok","async_io_drops":8}""" + "\n" +
                """{"ts":1002,"outcome":"ok","async_io_drops":2}""" + "\n",
        )
        val hinted = PerfRowsFileSource(file).window(1000)
        assertEquals(listOf(1002L), hinted.rows.map { it.ts })
        assertEquals(8L, hinted.dropsBefore, "the newest pre-cutoff row's counter, skipped unparsed until now")
        Files.writeString(
            file,
            """{"ts": 999,"outcome":"ok","async_io_drops":8}""" + "\n" + """{"ts":1002,"outcome":"ok"}""" + "\n",
        )
        assertEquals(8L, PerfRowsFileSource(file).window(1000).dropsBefore, "a parsed pre-cutoff row counts the same")
        assertNull(PerfRowsFileSource(file).window(0).dropsBefore, "nothing before the cutoff: no baseline")
    }

    @Test
    fun `a torn or counter-less line before the cutoff never erases the baseline sample`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            """{"ts":990,"outcome":"ok","async_io_drops":8}""" + "\n" +
                """{"ts":999, BROKEN""" + "\n" +
                """{"ts":1002,"outcome":"ok","async_io_drops":9}""" + "\n",
        )
        assertEquals(8L, PerfRowsFileSource(file).window(1000).dropsBefore, "the torn line is not a sample")
        Files.writeString(
            file,
            """{"ts":990,"outcome":"ok","async_io_drops":8}""" + "\n" +
                """{"ts":999,"outcome":"ok"}""" + "\n" +
                """{"ts":1002,"outcome":"ok","async_io_drops":9}""" + "\n",
        )
        assertEquals(8L, PerfRowsFileSource(file).window(1000).dropsBefore, "a counter-less row is not a sample")
        Files.writeString(
            file,
            """{"ts":990,"outcome":"ok","async_io_drops": 8}""" + "\n" +
                """{"ts":1002,"outcome":"ok","async_io_drops":9}""" + "\n",
        )
        val spaced = PerfRowsFileSource(file).window(1000).dropsBefore
        assertEquals(8L, spaced, "a parsed row's counter, whatever its spacing")
        Files.writeString(
            file,
            """{"ts":100,"outcome":"ok","async_io_drops":1}""" + "\n" +
                """{"ts":990,"outcome":"ok","async_io_drops": 8}""" + "\n" +
                """{"ts":1002,"outcome":"ok","async_io_drops":9}""" + "\n",
        )
        val skipped = PerfRowsFileSource(file).window(1000).dropsBefore
        assertEquals(8L, skipped, "the same spaced row on the fast-skip path, after retention evidence")
    }

    @Test
    fun `retention is the minimum valid timestamp and rows keep their file order`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            """{"ts":2000,"outcome":"ok","async_io_drops":1}""" + "\n" +
                """{"ts":1000,"outcome":"ok","async_io_drops":2}""" + "\n" +
                """{"ts":500,"outcome":"ok","async_io_drops":3}""" + "\n",
        )
        val read = PerfRowsFileSource(file).window(900)
        assertEquals(500L, read.oldestHeldTs, "physical order is no retention premise; the 500 row is held")
        assertEquals(listOf(2000L, 1000L), read.rows.map { it.ts }, "file order, the counters' sampling order")
        assertNull(read.dropsBefore, "the 500 row was appended AFTER the window's rows: no baseline for them")
    }

    @Test
    fun `a torn multi-byte char costs one row, a replacement char in the outcome makes it unattributed`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("head-perf.jsonl")
        val good = """{"ts":10,"model":"m","outcome":"ok","total":1}""".toByteArray()
        val tornChar = byteArrayOf(0xe2.toByte(), 0x82.toByte())
        val torn = """{"ts":20,"model":"m""".toByteArray() + tornChar + "\n".toByteArray()
        val after = """{"ts":30,"model":"m","outcome":"ok","total":3}""".toByteArray()
        val badByte = byteArrayOf(0xff.toByte())
        val corrupt = """{"ts":40,"outcome":"o""".toByteArray() + badByte + """k","total":4}""".toByteArray()
        Files.write(file, good + "\n".toByteArray() + torn + after + "\n".toByteArray() + corrupt + "\n".toByteArray())
        val read = PerfRowsFileSource(file).window(0)
        assertEquals(listOf(10L, 30L, 40L), read.rows.map { it.ts }, "the strict decoder would have thrown")
        assertEquals("?", read.rows[2].outcome, "a decoded replacement char is not a failure tag")
        assertEquals(10L, read.oldestHeldTs)
        assertNull(read.readError)
    }

    @Test
    fun `an unreadable generation is reported by the scan that failed, absence stays quiet`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            """{"ts":10,"outcome":"ok","total":1}""" + "\n" + """{"ts":30,"outcome":"ok","total":3}""" + "\n",
        )
        Files.createDirectory(dir.resolve("head-perf.jsonl.1"))
        val read = PerfRowsFileSource(file).window(0)
        assertEquals(listOf(10L, 30L), read.rows.map { it.ts }, "the readable generation still counts")
        val error = checkNotNull(read.readError)
        assertTrue(error.startsWith("head-perf.jsonl.1:"), error)
        val empty = PerfRowsFileSource(dir.resolve("other-perf.jsonl")).window(0)
        assertNull(empty.readError)
        assertNull(empty.oldestHeldTs)
    }
}
