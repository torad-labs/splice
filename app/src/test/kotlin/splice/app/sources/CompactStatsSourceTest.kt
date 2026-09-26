// NEW (2026-09-23, 19fa4d52): the compact tail CompactStatsSource serves carries a string's content.
// The arm landed in app's FileSourcesTest on feat/v0.4.0; LAYOUT-01 moved that file's log-tail arms
// to features/diagnostics, while CompactStatsSource stays in app, so its arm lives here.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.head.compact.CompactStats
import java.nio.file.Files

class CompactStatsSourceTest {

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
