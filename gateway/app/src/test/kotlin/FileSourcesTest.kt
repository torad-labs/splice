import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.LogFileSource
import java.nio.file.Files

class FileSourcesTest {
    @Test
    fun `log source filters a shared bounded tail without reading the whole file`() {
        val file = Files.createTempFile("splice-log-source", ".log")
        val rows = buildString {
            repeat(3_000) { index ->
                append(if (index % 2 == 0) "[codex]" else "[grok]")
                append(" row-")
                append(index)
                append('\n')
            }
        }
        Files.writeString(file, rows)

        val source = LogFileSource(file, "[codex]")
        val tail = source.tail(Int.MAX_VALUE).lines().filter { it.isNotEmpty() }

        assertEquals(1_500, tail.size)
        assertTrue(tail.last().contains("row-2998"))
        assertFalse(tail.any { "[grok]" in it })
        assertEquals(file.toString(), source.path())
    }

    @Test
    fun `missing log and non-positive tail are empty`() {
        val missing = Files.createTempDirectory("splice-log-source").resolve("missing.log")
        val source = LogFileSource(missing)
        assertEquals("", source.tail(10))

        Files.writeString(missing, "[codex] one\n")
        assertEquals("", source.tail(0))
    }
}
