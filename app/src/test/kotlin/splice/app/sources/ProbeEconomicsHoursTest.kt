// An hour of a command's history is judged on its own evidence against the request log.
package splice.app.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.usage.UsageHeads
import splice.usage.economics.EconomicsPayloads
import splice.usage.economics.EconomicsRead
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ProbeEconomicsHoursTest {
    // An hour before the newest probe whose rollup is ahead of the log may hide a probe the log lost, so it reads Not read.
    @Test
    fun `a mismatch before the newest probe refuses only its own hour`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            atHour(19 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) +
                atHour(20 * PROBE_HOUR + 123, MODELED_WORK_ROW) +
                atHour(21 * PROBE_HOUR + 123, SYNTHETIC_PROBE_ROW, MODELED_WORK_ROW, LOCAL_WORK_ROW),
        )
        var now = 19 * PROBE_HOUR + 123
        val store = store(dir, WallClock { now })
        recordWork(store)
        now = 20 * PROBE_HOUR + 123
        recordWork(store)
        now = 21 * PROBE_HOUR + 123
        store.record(turn("", 0, 30, 15, 2L to 1L), outcome = "error:upstream-failed")
        recordWork(store)
        val read = shown(store, file)
        assertEquals(listOf(19 * PROBE_HOUR, 21 * PROBE_HOUR), read.rows.map { it.hour })
        assertEquals(listOf(20 * PROBE_HOUR), read.notRead, "the log holds one request of the two the rollup counted")
    }

    // Live, Oct 11: claude-splice's archives hold probes from Oct 1, and that held every later hour. Probes are retired, so
    // an hour more than one past the newest one has none to take off.
    @Test
    fun `an hour well after the newest probe stands though an older hour holds one`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            atHour(19 * PROBE_HOUR + 123, MODELED_WORK_ROW) +
                atHour(20 * PROBE_HOUR + 123, SYNTHETIC_PROBE_ROW, MODELED_WORK_ROW, LOCAL_WORK_ROW) +
                atHour(22 * PROBE_HOUR + 123, MODELED_WORK_ROW),
        )
        var now = 19 * PROBE_HOUR + 123
        val store = store(dir, WallClock { now })
        recordWork(store)
        now = 20 * PROBE_HOUR + 123
        store.record(turn("", 0, 30, 15, 2L to 1L), outcome = "error:upstream-failed")
        recordWork(store)
        now = 22 * PROBE_HOUR + 123
        recordWork(store)
        val read = shown(store, file)
        assertEquals(listOf(22 * PROBE_HOUR), read.rows.map { it.hour }.filter { it > 20 * PROBE_HOUR })
        assertEquals(
            emptyList<Long>(),
            read.notRead.filter { it > 20 * PROBE_HOUR },
            "the rollup stands after the probe",
        )
    }

    // Live, Oct 11: a hard stop tore request-log lines the rollup had already counted. A log with no probe row has nothing
    // to take off, so the rollup's own count stands for every hour after the one the log begins in.
    @Test
    fun `a rollup ahead of a log that holds no probe stands for the hours after the log begins`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            atHour(20 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) +
                atHour(21 * PROBE_HOUR + 123, MODELED_WORK_ROW),
        )
        var now = 20 * PROBE_HOUR + 123
        val store = store(dir, WallClock { now })
        recordWork(store)
        now = 21 * PROBE_HOUR + 123
        recordWork(store)
        val read = shown(store, file)
        assertEquals(listOf(20 * PROBE_HOUR, 21 * PROBE_HOUR), read.rows.map { it.hour })
        assertEquals(emptyList<Long>(), read.notRead, "no probe is in the span, so the rollup stands")
        assertEquals(1L, read.rows.last().localSteps, "the figure is the rollup's, as counted")
    }

    @Test
    fun `the hour a log begins in is partial and stays not read when its rollup is ahead`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            atHour(20 * PROBE_HOUR + 123, MODELED_WORK_ROW) +
                atHour(21 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW),
        )
        var now = 20 * PROBE_HOUR + 123
        val store = store(dir, WallClock { now })
        recordWork(store)
        now = 21 * PROBE_HOUR + 123
        recordWork(store)
        val read = shown(store, file)
        assertEquals(listOf(21 * PROBE_HOUR), read.rows.map { it.hour })
        assertEquals(listOf(20 * PROBE_HOUR), read.notRead, "the log may have begun part-way through that hour")
    }

    @Test
    fun `a torn line that could be a probe refuses only the hour it was written in`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        val torn = """{"ts":${21 * PROBE_HOUR + 5},"model":"","outcome":"error:upstream-failed","req_bytes":30"""
        Files.writeString(
            file,
            atHour(20 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) +
                atHour(21 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) + torn + "\n",
        )
        var now = 20 * PROBE_HOUR + 123
        val store = store(dir, WallClock { now })
        recordWork(store)
        now = 21 * PROBE_HOUR + 123
        recordWork(store)
        val read = shown(store, file)
        assertEquals(listOf(20 * PROBE_HOUR), read.rows.map { it.hour })
        assertEquals(listOf(21 * PROBE_HOUR), read.notRead)
        val source = EconomicsStoreSource(store, PerfRowsFileSource(file))
        val heads = UsageHeads { listOf(head("a", source)) }
        val payload = Json.parseToJsonElement(EconomicsPayloads(heads).economicsJson())
        val named = payload.jsonObject.getValue("heads").jsonArray.single().jsonObject.getValue("not_read_hours")
        assertEquals(listOf(21 * PROBE_HOUR), named.jsonArray.map { it.jsonPrimitive.long }, "the route names them")
    }

    // A power loss leaves NUL bytes in the live file, and the next start appends after them. The row that line held was
    // written before the first row after it, so every hour after that row's is read, not every hour since the crash.
    @Test
    fun `a torn line with no time in the live file costs only the hours up to the row written after it`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            atHour(20 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) + "\u0000".repeat(944) + "\n" +
                atHour(21 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) +
                atHour(24 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW),
        )
        var now = 20 * PROBE_HOUR + 123
        val store = store(dir, WallClock { now })
        recordWork(store)
        now = 21 * PROBE_HOUR + 123
        recordWork(store)
        now = 24 * PROBE_HOUR + 123
        recordWork(store)
        val read = shown(store, file)
        assertEquals(listOf(24 * PROBE_HOUR), read.rows.map { it.hour }, "the hour after them is read")
        assertEquals(
            listOf(20 * PROBE_HOUR, 21 * PROBE_HOUR),
            read.notRead,
            "the hours from the row before the line to the row after it are not read",
        )
    }

    // Each look reads from the oldest hour it has not judged, so a torn line long before it lies before the read, and so
    // do the rows written after it, which the read passes over unparsed, and a later look skips whole from the cache.
    // Live, Oct 23: claude-grok's NUL line of Jul 20 kept every hour from Oct 13 to Oct 22 from being read.
    @Test
    fun `a torn line with no time before the read costs no hour the read reaches`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            atHour(20 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) + "\u0000".repeat(944) + "\n" +
                atHour(21 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW) +
                atHour(29 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW, MODELED_WORK_ROW, LOCAL_WORK_ROW) +
                atHour(30 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW),
        )
        var now = 30 * PROBE_HOUR + 123
        val store = store(dir, WallClock { now })
        recordWork(store)
        val source = EconomicsStoreSource(store, PerfRowsFileSource(file))
        val first = assertInstanceOf(EconomicsRead.Rows::class.java, source.read())
        assertEquals(listOf(30 * PROBE_HOUR), first.rows.map { it.hour }, "the first look reads its hour")
        assertEquals(emptyList<Long>(), first.notRead)
        Files.writeString(
            file,
            atHour(31 * PROBE_HOUR + 123, MODELED_WORK_ROW, LOCAL_WORK_ROW),
            StandardOpenOption.APPEND,
        )
        now = 31 * PROBE_HOUR + 123
        recordWork(store)
        val later = assertInstanceOf(EconomicsRead.Rows::class.java, source.read())
        assertEquals(listOf(30 * PROBE_HOUR, 31 * PROBE_HOUR), later.rows.map { it.hour }, "a later look reads both")
        assertEquals(emptyList<Long>(), later.notRead)
    }
}
