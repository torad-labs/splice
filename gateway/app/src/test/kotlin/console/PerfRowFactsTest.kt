// NEW: V4-127 — the reader's five string-and-flag facts, off real JSONL bytes.
//
// WHY THIS IS ITS OWN FILE AND NOT A CASE IN PerfRowsFileSourceTest: that test owns the row's
// NUMERIC projection and the window's coverage evidence. These five facts are the second half of the
// row — the half no control-plane consumer could see before this row, because `fields` was built by
// asking every value whether it parses as a Long and these do not. A regression here is invisible to
// every test that reads `fields`, which is the whole reason the facts are pinned separately.
//
// THE FIXTURE VALUES ARE ALL DIFFERENT ON PURPOSE. Four of the five are nullable and two of the
// strings are adjacent, so a reader that assigned them by position rather than by name would compile,
// pass a fixture whose values shared a shape, and report a session tag where an account label
// belongs. Distinct values are what turn that swap into a failure rather than a coincidence.
package console

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.PerfRowsFileSource
import java.nio.file.Files
import java.nio.file.Path

class PerfRowFactsTest {

    @Test
    fun `each fact is read by name, and an absent flag stays absent`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            """{"ts":1000,"model":"m-1000","outcome":"ok","compact":true,"session":"sess-1000",""" +
                """"account":"acct-1000","cache_cold":true,"total":5}""" + "\n" +
                // No account and no session: the writer emits cache_cold ONLY alongside an account
                // (PerfStats.record), so for this row the question was never asked.
                """{"ts":2000,"model":"m-2000","outcome":"ok","compact":false,"total":7}""" + "\n",
        )
        val rows = PerfRowsFileSource(file).window(0).rows
        assertEquals(listOf(1000L, 2000L), rows.map { it.ts })

        val full = rows[0]
        assertEquals("m-1000", full.model)
        assertEquals("sess-1000", full.session)
        assertEquals("acct-1000", full.account)
        assertEquals(true, full.cacheCold)
        assertEquals(true, full.compact)
        assertEquals("ok", full.outcome, "the outcome is unchanged by the widening")

        // A RECORDED false is a fact; an absent flag is not a false. The writer emits `compact`
        // unconditionally, so false here is real — and cache_cold, never written for this row, must
        // come back as null. Reading it as false would report "the cache was warm" about a turn where
        // nothing looked, the did-not-run-wearing-a-legitimate-answer class this row keeps finding.
        val bare = rows[1]
        assertEquals(false, bare.compact)
        assertNull(bare.cacheCold, "an unrecorded cache_cold is null, never false")
        assertEquals("m-2000", bare.model, "model is unrelated to the account and must still be read")
        assertNull(bare.session)
        assertNull(bare.account)
    }

    @Test
    fun `a legacy row without the facts reads them as absent, never as empty`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        // The row shape before these keys existed: ts, outcome and the numeric marks only.
        Files.writeString(file, """{"ts":1500,"outcome":"ok","total":9}""" + "\n")
        val row = PerfRowsFileSource(file).window(0).rows.single()
        assertNull(row.model, "an absent model is null — an empty string would be a value the file never held")
        assertNull(row.session)
        assertNull(row.account)
        assertNull(row.cacheCold)
        assertNull(row.compact)
        assertEquals(9L, row.fields["total"], "and the numeric half is untouched by the widening")
    }

    @Test
    fun `a torn fact reads absent rather than carrying a replacement character`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        // A torn multi-byte char inside the model. The reader decodes with REPLACE, so the value is
        // present as text — it is just not what was written, and a payload the operator is meant to
        // trust must not carry U+FFFD as if it were a model name.
        Files.writeString(file, """{"ts":1000,"model":"m�del","outcome":"ok","total":5}""" + "\n")
        val row = PerfRowsFileSource(file).window(0).rows.single()
        assertNull(row.model, "a torn value is absent, never the replacement character")
    }
}
