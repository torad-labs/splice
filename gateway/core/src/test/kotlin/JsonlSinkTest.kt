import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonlSink
import java.nio.file.Files
import java.nio.file.Path

class JsonlSinkTest {
    @Test
    fun `append rotates one bounded generation`(@TempDir tmp: Path) {
        val file = tmp.resolve("perf.jsonl")
        JsonlSink.appendLine(file, "1234567890", maxBytes = 22)
        JsonlSink.appendLine(file, "abcdefghij", maxBytes = 22)
        JsonlSink.appendLine(file, "new", maxBytes = 22)

        val rolled = file.resolveSibling("perf.jsonl.1")
        assertTrue(Files.exists(rolled))
        assertEquals(listOf("1234567890", "abcdefghij"), Files.readAllLines(rolled))
        assertEquals(listOf("new"), Files.readAllLines(file))
    }
}
