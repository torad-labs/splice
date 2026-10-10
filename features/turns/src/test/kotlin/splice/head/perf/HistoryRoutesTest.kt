// WALLS for the history row on Settings > Your data. Every test here pins the one property the row
// lives or dies by: the number a person is shown before they say yes is the number of turns that
// then go. A count that is right "to the day" would promise a whole day splice keeps most of, and a
// deletion that cut somewhere else would make the confirmation a guess.
package splice.head.perf

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.perf.HistoryWindow
import splice.core.perf.PerfArchiveName
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime

private val UTC: ZoneId = ZoneId.of("UTC")

/** 3:12:30 PM on Oct 10, 2026: the time of day a person happens to open the page at, which is what
 *  makes the cut of a whole-day window land in the middle of a day. */
private val NOW = ZonedDateTime.of(2026, 10, 10, 15, 12, 30, 0, UTC).toInstant().toEpochMilli()

private const val DAY = 24 * 60 * 60 * 1000L
private const val MINUTE = 60 * 1000L

/** A seven-day window from [NOW] cuts at 3:12 PM on Oct 3, on the minute and not on the second. */
private val SEVEN_DAY_CUT = NOW - 7 * DAY - 30_000

private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, UTC).toInstant().toEpochMilli()

private fun row(ts: Long): String = """{"ts":$ts,"model":"m","outcome":"ok","in_tokens":7}"""

class HistoryRoutesTest {
    @Test
    fun `what splice holds reads back by day, from the oldest record's own moment`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(
            paths,
            at(2026, 10, 8, 9, 30),
            at(2026, 10, 8, 17, 5),
            at(2026, 10, 10, 15, 11),
        )

        val body = ok(routes(paths).read(HistoryWindow(7, UTC)))

        assertEquals(3, body.getValue("held").jsonObject.getValue("turns").jsonPrimitive.int)
        assertEquals(
            at(2026, 10, 8, 9, 30),
            body.getValue("held").jsonObject.getValue("oldest_epoch_ms").jsonPrimitive.long,
            "the bar begins at the oldest record's own minute, which is what Since names",
        )
        val days = body.getValue("days").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(at(2026, 10, 8, 0, 0), at(2026, 10, 10, 0, 0)),
            days.map { it.getValue("start_epoch_ms").jsonPrimitive.long },
            "a day splice wrote nothing in is no segment: Oct 9 is absent, not a zero",
        )
        assertEquals(listOf(2, 1), days.map { it.getValue("turns").jsonPrimitive.int })
        assertTrue(
            days.all { it.getValue("bytes").jsonPrimitive.long > 0 },
            "a segment is sized by the bytes deleting that day would free",
        )
        assertNull(body["cut"], "nothing is proposed, so nothing is counted as going")
    }

    /** The one property the confirmation rests on. The cut moment is the time of day the person is
     *  reading at, so it lands mid-day, and only the turns BEFORE it are the ones being offered. */
    @Test
    fun `a proposed window counts the turns before its own minute, not the whole day it cuts`(
        @TempDir tmp: Path,
    ) {
        val paths = paths(tmp)
        write(
            paths,
            at(2026, 10, 1, 12, 0), // gone: days before the cut
            at(2026, 10, 3, 15, 10), // gone: the cut day, two minutes before the cut
            at(2026, 10, 3, 15, 11), // gone: one minute before it
            at(2026, 10, 3, 15, 13), // kept: a minute after it, on the same day
            at(2026, 10, 9, 8, 0), // kept
        )

        val body = ok(routes(paths).read(HistoryWindow(35, UTC), proposedDays = "7"))
        val cut = body.getValue("cut").jsonObject

        assertEquals(
            SEVEN_DAY_CUT / MINUTE * MINUTE,
            cut.getValue("cutoff_epoch_ms").jsonPrimitive.long,
            "the moment is floored to the minute, so the seconds a person spends reading cannot move it",
        )
        assertEquals(
            3,
            cut.getValue("turns").jsonPrimitive.int,
            "the three turns before 3:12 PM on Oct 3, and not the fourth one 2 minutes later",
        )
        assertEquals(
            "7",
            body.getValue("window").jsonObject.getValue("text").jsonPrimitive.content,
            "the window on a proposal is the one being chosen",
        )
        assertEquals(
            "35",
            body.getValue("saved").jsonObject.getValue("text").jsonPrimitive.content,
            "and the one in force is still there beside it",
        )
    }

    @Test
    fun `a yes saves the window and deletes exactly the turns that were counted`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        val old = at(2026, 10, 1, 12, 0)
        write(paths, old, at(2026, 10, 3, 15, 10), at(2026, 10, 3, 15, 13), at(2026, 10, 9, 8, 0))
        val archived = paths.perfArchiveDir.resolve(
            PerfArchiveName(paths.perfStatsFile("h").fileName.toString()).of(old),
        )
        Files.writeString(archived, row(old) + "\n")
        val saved = ArrayList<String>()
        val routes = routes(paths) { saved += it; null }
        val offered = ok(routes.read(HistoryWindow(35, UTC), proposedDays = "7")).getValue("cut").jsonObject
        val moment = offered.getValue("cutoff_epoch_ms").jsonPrimitive.long

        val body = ok(routes.save("""{"days":7,"delete_before_epoch_ms":$moment}"""))

        assertEquals(listOf("7"), saved, "the window the person chose is what was persisted")
        assertEquals(
            offered.getValue("turns").jsonPrimitive.int,
            body.getValue("cut").jsonObject.getValue("turns").jsonPrimitive.int,
            "what went is what was offered: the confirmation was not a guess",
        )
        assertEquals(
            listOf(at(2026, 10, 3, 15, 13), at(2026, 10, 9, 8, 0)),
            Files.readAllLines(paths.perfStatsFile("h")).map { stampOf(it) },
            "the straddled generation keeps its newer rows and loses only the older ones",
        )
        assertFalse(Files.exists(archived), "a generation with nothing left in it goes whole")
        assertEquals(2, body.getValue("held").jsonObject.getValue("turns").jsonPrimitive.int)
    }

    /** hitstop: shortened again before the first sweep settles, the second count is of what is left. */
    @Test
    fun `a second cut is counted from what the first one left`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(
            paths,
            at(2026, 10, 1, 12, 0),
            at(2026, 10, 3, 15, 10),
            at(2026, 10, 9, 8, 0),
            at(2026, 10, 10, 9, 0),
        )
        val routes = routes(paths)

        val first = ok(routes.read(HistoryWindow(35, UTC), proposedDays = "7")).getValue("cut").jsonObject
        assertEquals(2, first.getValue("turns").jsonPrimitive.int)
        val firstMoment = first.getValue("cutoff_epoch_ms").jsonPrimitive.long
        ok(routes.save("""{"days":7,"delete_before_epoch_ms":$firstMoment}"""))

        val second = ok(routes.read(HistoryWindow(7, UTC), proposedDays = "1")).getValue("cut").jsonObject

        assertEquals(
            1,
            second.getValue("turns").jsonPrimitive.int,
            "one turn is left before the one-day cut; the two the first sweep took are not offered twice",
        )
    }

    @Test
    fun `a moment the window does not reach is refused, and nothing is saved or deleted`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(paths, at(2026, 10, 3, 15, 10), at(2026, 10, 9, 8, 0))
        val saved = ArrayList<String>()
        val routes = routes(paths) { saved += it; null }

        // A moment inside the window: picking 7 days can never authorize deleting yesterday.
        val refused = routes.save("""{"days":7,"delete_before_epoch_ms":${NOW - DAY}}""")

        assertEquals(HttpStatusCode.Conflict, refused.status, refused.body)
        assertEquals(emptyList<String>(), saved, "a refused save persists nothing")
        assertEquals(2, Files.readAllLines(paths.perfStatsFile("h")).size, "and deletes nothing")
    }

    @Test
    fun `a turn with no readable timestamp is never counted into a cut and never deleted`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(paths, at(2026, 10, 1, 12, 0))
        Files.writeString(
            paths.perfStatsFile("h"),
            row(at(2026, 10, 1, 12, 0)) + "\n" + """{"model":"m","outcome":"torn"}""" + "\n",
        )
        val routes = routes(paths)

        val body = ok(routes.read(HistoryWindow(35, UTC), proposedDays = "7"))
        assertEquals(2, body.getValue("held").jsonObject.getValue("turns").jsonPrimitive.int, "it is on the disk")
        assertEquals(1, body.getValue("held").jsonObject.getValue("unknown_turns").jsonPrimitive.int)
        assertEquals(
            1,
            body.getValue("cut").jsonObject.getValue("turns").jsonPrimitive.int,
            "splice does not offer to delete a row it cannot date",
        )

        ok(routes.save("""{"days":7,"delete_before_epoch_ms":${SEVEN_DAY_CUT / MINUTE * MINUTE}}"""))

        assertEquals(
            listOf("""{"model":"m","outcome":"torn"}"""),
            Files.readAllLines(paths.perfStatsFile("h")),
            "and does not delete it either: the count and the deletion read a line the same way",
        )
    }

    @Test
    fun `a reading that is not whole says so and refuses to delete on it`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(paths, at(2026, 10, 3, 15, 10))
        // A directory wearing a record's name: something is there, and splice cannot read it.
        Files.createDirectories(paths.stateDir.resolve("other-perf.jsonl"))
        val saved = ArrayList<String>()
        val routes = routes(paths) { saved += it; null }

        val read = ok(routes.read(HistoryWindow(35, UTC), proposedDays = "7"))
        assertTrue(
            read.getValue("reason").jsonPrimitive.content.startsWith("the records did not read whole"),
            "a figure that is partial must not read as a total",
        )

        val refused = routes.save("""{"days":7,"delete_before_epoch_ms":${SEVEN_DAY_CUT / MINUTE * MINUTE}}""")

        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status, refused.body)
        assertEquals(emptyList<String>(), saved, "a count that is not whole cannot authorize a deletion")
        assertEquals(1, Files.readAllLines(paths.perfStatsFile("h")).size)
    }

    @Test
    fun `a window that is not a number of days or the word forever is refused by name`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(paths, at(2026, 10, 9, 8, 0))

        assertEquals(HttpStatusCode.BadRequest, routes(paths).read(HistoryWindow(7, UTC), "a month").status)
        assertEquals(HttpStatusCode.BadRequest, routes(paths).save("""{"days":"forver"}""").status)
        assertEquals(HttpStatusCode.BadRequest, routes(paths).save("not json at all").status)
    }

    @Test
    fun `forever names no cut, so a save under it deletes nothing`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(paths, at(2026, 1, 1, 1, 0), at(2026, 10, 9, 8, 0))
        val saved = ArrayList<String>()
        val routes = routes(paths) { saved += it; null }

        val body = ok(routes.read(HistoryWindow(35, UTC), proposedDays = "forever"))
        assertNull(body["cut"], "there is no moment to offer, so there is nothing to confirm")
        assertTrue(body.getValue("window").jsonObject.getValue("forever").jsonPrimitive.content.toBoolean())

        assertEquals(HttpStatusCode.OK, routes.save("""{"days":"forever"}""").status)
        assertEquals(listOf("forever"), saved)
        assertEquals(2, Files.readAllLines(paths.perfStatsFile("h")).size)
    }

    /** "About N MB a month" is a PACE, so it is measured over the last week of writing and not over
     *  everything held: a person who wrote heavily in January and lightly since is told what the
     *  next month costs them, not what the archive already cost. */
    @Test
    fun `the rate is measured over the last week, not over everything held`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(paths, at(2026, 1, 1, 1, 0), at(2026, 10, 9, 8, 0))

        val body = ok(routes(paths).read(HistoryWindow(null, UTC)))

        val held = body.getValue("held").jsonObject.getValue("bytes").jsonPrimitive.long
        val rate = body.getValue("rate_bytes_per_month").jsonPrimitive.long
        assertEquals(
            held / 2 * 30 / 7,
            rate,
            "one of the two records was written this week, and it is the one the pace is read from",
        )
    }

    /** Keeping nothing is a choice the row offers, and it means today: the day that is running where
     *  the daemon runs, because a daily budget still has to say what today cost. */
    @Test
    fun `keeping nothing cuts at today's own midnight and keeps today`(@TempDir tmp: Path) {
        val paths = paths(tmp)
        write(
            paths,
            at(2026, 10, 9, 23, 59), // yesterday, a minute before midnight: gone
            at(2026, 10, 10, 0, 1), // today, a minute after it: kept
            at(2026, 10, 10, 15, 11), // today: kept
        )
        val routes = routes(paths)

        val proposed = ok(routes.read(HistoryWindow(35, UTC), proposedDays = "0"))
        val cut = proposed.getValue("cut").jsonObject
        assertEquals(at(2026, 10, 10, 0, 0), cut.getValue("cutoff_epoch_ms").jsonPrimitive.long)
        assertEquals(1, cut.getValue("turns").jsonPrimitive.int, "only yesterday's turn is on offer")
        assertTrue(proposed.getValue("window").jsonObject.getValue("nothing").jsonPrimitive.content.toBoolean())

        ok(routes.save("""{"days":0,"delete_before_epoch_ms":${at(2026, 10, 10, 0, 0)}}"""))

        assertEquals(
            listOf(at(2026, 10, 10, 0, 1), at(2026, 10, 10, 15, 11)),
            Files.readAllLines(paths.perfStatsFile("h")).map { stampOf(it) },
            "today's spend is still there, which is what the Day figure is measured against",
        )
    }

    private fun paths(tmp: Path): StatePaths = StatePaths(baseOverride = tmp.resolve("state")).also {
        Files.createDirectories(it.perfArchiveDir)
    }

    private fun write(paths: StatePaths, vararg stamps: Long) {
        Files.writeString(paths.perfStatsFile("h"), stamps.joinToString("\n") { row(it) } + "\n")
    }

    private fun routes(paths: StatePaths, store: HistoryWindowStore = HistoryWindowStore { null }) = HistoryRoutes(
        paths,
        store,
        WallClock { NOW },
        UTC,
        LogSink { },
    )

    private fun ok(reply: splice.http.JsonReply) = Json.parseToJsonElement(
        reply.body.also { assertEquals(HttpStatusCode.OK, reply.status, it) },
    ).jsonObject

    private fun stampOf(line: String): Long = Json.parseToJsonElement(line).jsonObject
        .getValue("ts").jsonPrimitive.long
}
