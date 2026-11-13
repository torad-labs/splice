package splice.lifecycle.prices

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.PublishedRates
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

/** Nov 12, 2026: the hourly look. What a person can check of it: the table kept in the state folder is the one that
 *  was served, the switch in splice.toml stops the request, and a start uses the kept table. */
class PriceRefreshTest {
    private val shipped = checkNotNull(javaClass.getResourceAsStream("/published-rates.tsv")).bufferedReader()
        .use { it.readText() }
    private val served = shipped + "api.anthropic.com\tclaude-not-yet-9\t3\t0.3\t15\t3.75\t6\t\t\t\t\t\t\t\t\t\n"

    @TempDir
    lateinit var dir: Path

    private val copy: Path get() = dir.resolve("published-rates.tsv")
    private val said = StringBuilder()
    private var asked = 0

    private fun refresh(on: Boolean?, text: String = served) =
        PriceRefresh(copy, { on }, LogSink { said.append(it) }) { text.also { asked++ } }

    /** The build's own text is the same day as the served one, so it is taken back. */
    @AfterEach
    fun theBuildsTableAgain() {
        PublishedRates.take(shipped)
    }

    @Test
    fun `a look keeps the served table and says it once`() {
        val rows = PublishedRates.table().rows

        refresh(on = true).look()
        refresh(on = true).look()

        assertEquals(served, Files.readString(copy))
        assertEquals(rows + 1, PublishedRates.table().rows)
        assertEquals(1, Regex("refreshed").findAll(said).count(), said.toString())
    }

    @Test
    fun `refresh_prices = false makes no request and keeps nothing`() {
        refresh(on = false).look()
        refresh(on = null).look()

        assertEquals(0, asked)
        assertFalse(Files.exists(copy))
    }

    @Test
    fun `a start uses the table an earlier look kept, and leaves a damaged one aside`() {
        val rows = PublishedRates.table().rows
        Files.writeString(copy, "<html>Not Found</html>")
        refresh(on = true).seed()
        assertEquals(rows, PublishedRates.table().rows)
        assertTrue("left aside" in said, said.toString())

        Files.writeString(copy, served)
        refresh(on = true).seed()
        assertEquals(rows + 1, PublishedRates.table().rows)
    }

    @Test
    fun `a served file that is not the table is not kept`() {
        refresh(on = true, text = "<html>Not Found</html>").look()

        assertFalse(Files.exists(copy))
        assertTrue("left aside" in said, said.toString())
    }
}
