package splice.app.cli.status

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.PublishedRates
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneId

/** Nov 12, 2026: `splice status` says which price table is in use, and when and from where it was fetched. */
class PriceTableLineTest {
    private val shipped = checkNotNull(javaClass.getResourceAsStream("/published-rates.tsv")).bufferedReader()
        .use { it.readText() }

    @TempDir
    lateinit var dir: Path

    private val copy: Path get() = dir.resolve("published-rates.tsv")
    private val day = PublishedRates.table().updated

    @AfterEach
    fun theBuildsTableAgain() {
        PublishedRates.take(shipped)
    }

    @Test
    fun `with no table fetched the line names the copy in the build and whether refresh is on`() {
        assertEquals(
            "prices   the table of $day, the copy in this build; not refreshed from raw.githubusercontent.com yet",
            PriceTableLine(copy, on = true).line(),
        )
        assertEquals(
            "prices   the table of $day, the copy in this build; refresh is off (refresh_prices = false)",
            PriceTableLine(copy, on = false).line(),
        )
    }

    @Test
    fun `a fetched table is named with when it was fetched and from where`() {
        Files.writeString(copy, shipped)
        Files.setLastModifiedTime(copy, FileTime.from(Instant.parse("2026-11-12T19:42:00Z")))

        assertEquals(
            "prices   the table of $day, refreshed Nov 12, 1:42 PM from raw.githubusercontent.com",
            PriceTableLine(copy, on = true, zone = ZoneId.of("America/Chicago")).line(),
        )
    }

    @Test
    fun `a kept file that is not the table reads as no table fetched`() {
        Files.writeString(copy, "<html>Not Found</html>")

        assertEquals(
            "prices   the table of $day, the copy in this build; not refreshed from raw.githubusercontent.com yet",
            PriceTableLine(copy, on = true).line(),
        )
    }
}
