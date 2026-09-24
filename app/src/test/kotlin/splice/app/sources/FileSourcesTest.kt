package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.head.compact.CompactStats
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

    @Test
    fun `compact tail rows carry a string's content, not its quoted JSON text`() {
        val file = Files.createTempDirectory("splice-compact-source").resolve("compact.jsonl")
        Files.writeString(
            file,
            """{"ts":1790199390460,"outcome":"model_text","ms":171490,"chars":20532,"instructions_source":"client","error":null}""" + "\n",
        )

        val view = CompactStatsSource(CompactStats(file)).summary(10)

        val row = view.tail.single()
        assertEquals("model_text", row["outcome"])
        assertEquals("client", row["instructions_source"])
        // the numeric fields still parse where the payload turns them back into numbers
        assertEquals("1790199390460", row["ts"])
        assertEquals("20532", row["chars"])
        // a JSON null is an absent field, never the word "null"
        assertFalse(row.containsKey("error"))
        // and the tail names the outcome the totals name
        assertEquals(setOf("model_text"), view.byOutcome.keys)
    }
}
