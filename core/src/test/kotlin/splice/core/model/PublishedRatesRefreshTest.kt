package splice.core.model

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nov 12, 2026: a daemon takes a refreshed copy of the price table. What a person sees of it is a price: a model
 *  the build could not price gets one, and a file that is damaged or older changes nothing. */
class PublishedRatesRefreshTest {
    private val shipped = checkNotNull(javaClass.getResourceAsStream("/published-rates.tsv")).bufferedReader()
        .use { it.readText() }
    private val anthropic = "https://api.anthropic.com"
    private val newRow = "api.anthropic.com\tclaude-not-yet-9\t3\t0.3\t15\t3.75\t6\t\t\t\t\t\t\t\t\t\n"

    /** The build's own text is the same day as every text a test took, so it is taken back. */
    @AfterEach
    fun theBuildsTableAgain() {
        PublishedRates.take(shipped)
    }

    @Test
    fun `a model the refreshed table names is priced, and the models the build priced still are`() {
        assertNull(PublishedRates.of(anthropic, "claude-not-yet-9"))

        val taken = assertInstanceOf(RateRefresh.Taken::class.java, PublishedRates.take(shipped + newRow))

        assertEquals(15.0, PublishedRates.of(anthropic, "claude-not-yet-9")?.output)
        assertNotNull(PublishedRates.of(anthropic, "claude-fable-5"))
        assertSame(taken.table, PublishedRates.table())
    }

    @Test
    fun `a table from a day before the one in use is ignored`() {
        val older = (shipped + newRow).replace(Regex("#! updated \\d{4}-\\d{2}-\\d{2}"), "#! updated 2020-01-01")

        val ignored = assertInstanceOf(RateRefresh.Ignored::class.java, PublishedRates.take(older))

        assertTrue("2020-01-01" in ignored.why, ignored.why)
        assertNull(PublishedRates.of(anthropic, "claude-not-yet-9"))
    }

    @Test
    fun `a damaged table is ignored whole`() {
        val damaged = listOf(
            "a web page" to "<html><body>Not Found</body></html>",
            "no day" to (shipped + newRow).replace(Regex("#! updated [^\n]*\n"), ""),
            "a row short of a cell" to shipped + newRow.replaceFirst("\t15\t", "\t"),
            "a price that is no number" to shipped + newRow.replaceFirst("\t15\t", "\tfifteen\t"),
            "a negative price" to shipped + newRow.replaceFirst("\t15\t", "\t-15\t"),
            "a price past any price" to shipped + newRow.replaceFirst("\t15\t", "\t1e300\t"),
            "the same model twice" to shipped + newRow + newRow,
        )

        for ((what, text) in damaged) {
            assertInstanceOf(RateRefresh.Ignored::class.java, PublishedRates.take(text), what)
            assertNull(PublishedRates.of(anthropic, "claude-not-yet-9"), what)
        }
    }
}
