// NEW: V4-444 — a large transcript reads only its tail and an unchanged poll reads no bytes.
package splice.client.v4444

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TranscriptTailBudgetTest {
    @Test
    fun `fifty megabytes and a split reply stay bounded warm and growth aware`(@TempDir tmp: Path) {
        val fixture = ActivityTranscript(tmp)
        fixture.write(emptyList())
        val filler = """{"type":"attachment","text":"${"x".repeat(1024)}"}""" + "\n"
        Files.newBufferedWriter(fixture.file).use { out ->
            repeat(51_200) { out.write(filler) }
            out.write(ACTIVITY_USER + "\n" + ACTIVITY_FIRST + "\n")
            out.write(
                """{"type":"assistant","message":{"id":"synthetic-reply","content":[{"type":"thinking","thinking":"${"x".repeat(70_000)}"}]}}""" + "\n",
            )
            out.write(ACTIVITY_LAST + "\n")
        }
        assertTrue(Files.size(fixture.file) >= 50L * 1024 * 1024)
        val last = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals("First token=[redacted]\n\nLast", last?.text)
        assertTrue(fixture.bytes in 1..262_144, "read ${fixture.bytes} bytes")
        assertTrue(fixture.offsets.all { it > 0 }, "no prefix scan: ${fixture.offsets}")
        val cold = fixture.bytes
        assertEquals(last, fixture.reader.last(ACTIVITY_ID, listOf(tmp)))
        assertEquals(cold, fixture.bytes, "an unchanged poll reads zero bytes")
        fixture.append(
            """{"type":"user","timestamp":"1970-01-01T00:00:00.009Z","message":{"content":"new activity"}}""",
        )
        val grown = fixture.reader.last(ACTIVITY_ID, listOf(tmp))
        assertEquals("new activity", grown?.text)
        assertEquals(9L, grown?.ts)
        assertTrue(fixture.bytes - cold in 1..262_144, "growth reads the tail, never the old prefix")
    }
}
