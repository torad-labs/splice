// NEW: Oct 10, 2026 — the row-exact cut over a day store, which is what Marlin's ruling needs and
// what a whole-day delete cannot give: a UTC day file straddles the person's own midnight, so
// "Today only" in Chicago has to cut INSIDE the day that began at 19:00 the evening before.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

private const val PREFIX = "edges"
private const val DAY_MS = 24L * 60 * 60 * 1000

class DayLinesKeptTest {

    /** `{"at":<ms>}`, which is the only field the cut's own predicate reads here. */
    private fun row(at: Long) = """{"at":$at}"""

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        Instant.parse("%04d-%02d-%02dT%02d:00:00Z".format(year, month, day, hour)).toEpochMilli()

    private fun stampOf(line: String): Long? =
        Regex("\"at\":(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()

    /** A store written through the real writer, so the files under test are named and laid out the
     *  way the daemon writes them rather than the way a test imagines. */
    private fun write(dir: Path, stamps: List<Long>) {
        for (stamp in stamps) {
            ActivityDays(dir, PREFIX, retentionDays = 3650, clock = WallClock { stamp }).append(row(stamp))
        }
        assertTrue(AsyncFileIo.drain(), "the writes settle before the cut reads the directory")
    }

    private fun linesOn(dir: Path): List<Long> =
        DayFiles(dir, PREFIX).lines().mapNotNull(::stampOf).sorted().toList()

    private fun before(momentMs: Long) = DayLineKeep { line -> (stampOf(line) ?: return@DayLineKeep true) >= momentMs }

    @Test
    fun `a cut inside a day keeps that day's later rows and deletes the days before it`(@TempDir tmp: Path) {
        val monday = at(2026, 10, 5, 2)
        val tuesdayEvening = at(2026, 10, 6, 23)
        val wednesdayMorning = at(2026, 10, 7, 9)
        write(tmp, listOf(monday, tuesdayEvening, wednesdayMorning))

        // Chicago's midnight on Oct 7 is 05:00 UTC, which falls inside the UTC day that already held
        // Tuesday 23:00: the day that straddles the cut has to lose a row and keep none of the other.
        val gone = DayLinesKept(tmp, PREFIX).keepOnly(before(at(2026, 10, 7, 5)))

        assertEquals(listOf(wednesdayMorning), linesOn(tmp), "only the rows at or after the moment are left")
        assertEquals(2L, gone.lines, "Monday's row and Tuesday evening's both went")
        assertEquals(2, gone.files, "both of those days lost every row they had, so both files went")
    }

    @Test
    fun `a day that loses nothing is not rewritten, so its own last-written time still says when`(@TempDir tmp: Path) {
        val today = at(2026, 10, 10, 14)
        write(tmp, listOf(today))
        val file = DayFiles(tmp, PREFIX).files().single { Files.isRegularFile(it) }
        val written = Files.getLastModifiedTime(file)

        val gone = DayLinesKept(tmp, PREFIX).keepOnly(before(at(2026, 10, 10, 0)))

        assertEquals(0L, gone.lines, "nothing was older than the moment")
        assertEquals(written, Files.getLastModifiedTime(file), "an untouched day keeps its mtime")
        assertEquals(listOf(today), linesOn(tmp))
    }

    @Test
    fun `a row the store cannot read is kept, because its moment is unknown`(@TempDir tmp: Path) {
        // Both rows land in ONE UTC day, with the cut between them, so that day is the one rewritten
        // row by row. The torn row has to come through THAT rewrite, not through a day nothing
        // touched: a row with no readable moment cannot be shown to be one of the rows the person
        // was counted and asked about, so it stays.
        val morning = at(2026, 10, 9, 2)
        val evening = at(2026, 10, 9, 20)
        write(tmp, listOf(morning, evening))
        val day = DayFiles(tmp, PREFIX).files().single { Files.isRegularFile(it) }
        Files.writeString(day, Files.readString(day) + "{\"at\":\n")

        val gone = DayLinesKept(tmp, PREFIX).keepOnly(before(at(2026, 10, 9, 12)))

        assertEquals(listOf(evening), linesOn(tmp), "the dated row after the moment survives")
        assertTrue(Files.readString(day).contains("{\"at\":\n"), "and so does the row with no moment")
        assertEquals(1L, gone.lines, "only the dated row before the moment was counted as gone")
        assertEquals(0, gone.files, "the day was rewritten, not deleted: it still holds two lines")
    }

    @Test
    fun `a cut that keeps nothing empties the store`(@TempDir tmp: Path) {
        write(tmp, listOf(at(2026, 10, 5, 2), at(2026, 10, 6, 2), at(2026, 10, 7, 2)))

        val gone = DayLinesKept(tmp, PREFIX).keepOnly(DayLineKeep { false })

        assertEquals(emptyList<Long>(), linesOn(tmp))
        assertEquals(3L, gone.lines)
        assertEquals(3, gone.files)
        assertFalse(
            DayFiles(tmp, PREFIX).files().any { Files.isRegularFile(it) },
            "every day file is gone, not emptied",
        )
    }

    @Test
    fun `a second cut counts from what the first one left`(@TempDir tmp: Path) {
        val stamps = (1..4).map { at(2026, 10, 3 + it, 2) }
        write(tmp, listOf(stamps[0], stamps[1], stamps[2], stamps[3]))
        val cut = DayLinesKept(tmp, PREFIX)

        val first = cut.keepOnly(before(stamps[2]))
        val second = cut.keepOnly(before(stamps[3]))

        assertEquals(2L, first.lines, "the two oldest days went")
        assertEquals(1L, second.lines, "and the next cut sees only what the first left")
        assertEquals(listOf(stamps[3]), linesOn(tmp))
    }

    @Test
    fun `a day written in a zone where midnight falls inside an hour is still cut at the moment`(@TempDir tmp: Path) {
        // Kolkata is UTC+5:30, so its midnight is 18:30 UTC the day before: inside a UTC hour, and
        // inside a UTC day. The cut is a moment, not a day boundary, so the zone cannot move it.
        val kolkata = ZoneOffset.ofHoursMinutes(5, 30)
        val midnight = Instant.parse("2026-10-10T00:00:00Z").atOffset(kolkata).toLocalDate()
            .atStartOfDay(kolkata).toInstant().toEpochMilli()
        val justBefore = midnight - 1
        val justAfter = midnight + 1
        write(tmp, listOf(justBefore - DAY_MS, justBefore, justAfter))

        val gone = DayLinesKept(tmp, PREFIX).keepOnly(before(midnight))

        assertEquals(listOf(justAfter), linesOn(tmp), "the row one millisecond before the moment went")
        assertEquals(2L, gone.lines)
    }
}
