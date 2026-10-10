// A count labelled "requests" counts exactly the rows the Requests page lists, and that page leaves out the steps
// splice answered itself. The data page's held total and "Delete N requests" cut tally are such counts. A local
// step still takes disk, so a cut still frees its bytes, but it is no request in the number.
package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset

private const val FIRST_MS = 1_789_725_600_000L
private const val A_MINUTE_MS = 60_000L

private fun request(ts: Long) = """{"ts":$ts,"model":"m","outcome":"ok","in_tokens":7}"""

private fun localStep(ts: Long) = """{"ts":$ts,"outcome":"ok","local_step":1,"in_tokens":0}"""

class HistoryLocalStepsTest {
    @Test
    fun `the held total and the cut tally count the requests and not the local steps`(@TempDir tmp: Path) {
        val state = Files.createDirectories(tmp.resolve("state"))
        val archive = Files.createDirectories(tmp.resolve("archive"))
        val lines = listOf(
            request(FIRST_MS),
            localStep(FIRST_MS + A_MINUTE_MS),
            localStep(FIRST_MS + 2 * A_MINUTE_MS),
            request(FIRST_MS + 3 * A_MINUTE_MS),
        )
        Files.writeString(state.resolve("h-perf.jsonl"), lines.joinToString("\n") + "\n")

        val held = HistoryDays(ZoneOffset.UTC).held(state, archive)

        assertEquals(2, held.turns, "two requests, however many local steps sit between them")
        val cut = held.before(FIRST_MS + 10 * A_MINUTE_MS)
        assertEquals(2, cut.turns, "a cut announces the requests it deletes")
        assertEquals(lines.sumOf { it.toByteArray().size + 1L }, cut.bytes, "and still frees every byte on disk")
        assertTrue(held.days.sumOf { it.turns } == 2L)
    }
}
