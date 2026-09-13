import org.junit.jupiter.api.Assertions.assertEquals
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
            """{"ts":100,"model":"m","outcome":"ok","total":5}""" + "\n" +
                """{"ts":900,"model":"m","outcome":"client_abort","total":7,"session":"abcd"}""" + "\n",
        )
        Files.writeString(
            file,
            """{"ts":1500,"model":"m","outcome":"ok","total":9,"first_byte":3}""" + "\n" +
                "{ torn" + "\n" +
                """{"ts":2000,"outcome":"error:upstream-failed","total":1}""" + "\n",
        )
        val rows = PerfRowsFileSource(file).rowsSince(500)
        assertEquals(listOf(900L, 1500L, 2000L), rows.map { it.ts })
        assertEquals(listOf("client_abort", "ok", "error:upstream-failed"), rows.map { it.outcome })
        assertEquals(mapOf("ts" to 1500L, "total" to 9L, "first_byte" to 3L), rows[1].fields)
        assertEquals(emptyList<Long>(), PerfRowsFileSource(dir.resolve("absent.jsonl")).rowsSince(0).map { it.ts })
    }
}
