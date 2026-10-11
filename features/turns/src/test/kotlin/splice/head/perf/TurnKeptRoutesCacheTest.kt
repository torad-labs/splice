package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** The count the console reads, asked again and again over a store that grows and rotates: it must always equal what a
 *  fresh count of the files on disk says, however much of it was already counted. */
class TurnKeptRoutesCacheTest {
    private fun row(day: String) = """{"ts":${Instant.parse("${day}T12:00:00Z").toEpochMilli()},"model":"m"}""" + "\n"

    private fun counted(paths: StatePaths, tallies: TurnTallies): Pair<String, String> {
        val body = Json.parseToJsonElement(TurnKeptRoutes(paths, tallies = tallies).kept().body).jsonObject
        return body.getValue("rows").jsonPrimitive.content to body.getValue("days").jsonPrimitive.content
    }

    private fun fresh(paths: StatePaths) = counted(paths, TurnTallies())

    @Test
    fun `rows appended to a counted file are counted and nothing is counted twice`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.stateDir)
        val live = paths.perfStatsFile("claudex")
        Files.writeString(live, row("2026-10-01") + row("2026-10-01"))
        val tallies = TurnTallies()
        assertEquals("2" to "1", counted(paths, tallies))
        assertEquals("2" to "1", counted(paths, tallies))

        Files.writeString(live, row("2026-10-02") + row("2026-10-03"), StandardOpenOption.APPEND)
        assertEquals("4" to "3", counted(paths, tallies))
        assertEquals(fresh(paths), counted(paths, tallies))
    }

    @Test
    fun `a row a writer has not finished counts now and once when it is finished`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.stateDir)
        val live = paths.perfStatsFile("claudex")
        val whole = row("2026-10-02")
        Files.writeString(live, row("2026-10-01") + whole.dropLast(10))
        val tallies = TurnTallies()
        // the cut-off row counts as a row with no day yet
        assertEquals("2" to "1", counted(paths, tallies))
        Files.writeString(live, whole.takeLast(10), StandardOpenOption.APPEND)
        assertEquals("2" to "2", counted(paths, tallies))
        assertEquals(fresh(paths), counted(paths, tallies))
    }

    @Test
    fun `a file replaced by another is counted from its own rows`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.stateDir)
        val live = paths.perfStatsFile("claudex")
        Files.writeString(live, row("2026-10-01") + row("2026-10-02"))
        val tallies = TurnTallies()
        assertEquals("2" to "2", counted(paths, tallies))

        // a rotation: the live file moves aside and a longer one takes its path
        Files.move(live, live.resolveSibling("${live.fileName}.1"), StandardCopyOption.ATOMIC_MOVE)
        Files.writeString(live, row("2026-10-05") + row("2026-10-05") + row("2026-10-05") + row("2026-10-06"))
        assertEquals("6" to "4", counted(paths, tallies))
        assertEquals(fresh(paths), counted(paths, tallies))
    }

    @Test
    fun `a file rewritten in place with other rows is counted from its own rows`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.stateDir)
        val live = paths.perfStatsFile("claudex")
        Files.writeString(live, row("2026-10-01") + row("2026-10-02"))
        val tallies = TurnTallies()
        assertEquals("2" to "2", counted(paths, tallies))

        // the same file, longer, with none of its old bytes: an edit, not an append
        Files.writeString(live, row("2026-09-20") + row("2026-09-21") + row("2026-09-22"))
        assertEquals("3" to "3", counted(paths, tallies))
    }

    @Test
    fun `deleting the store resets the count`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.stateDir)
        val live = paths.perfStatsFile("claudex")
        Files.writeString(live, row("2026-10-01"))
        val tallies = TurnTallies()
        assertEquals("1" to "1", counted(paths, tallies))
        TurnKeptRoutes(paths, tallies = tallies).delete()
        assertEquals("0" to "0", counted(paths, tallies))
        Files.writeString(live, row("2026-10-04") + row("2026-10-05"))
        assertEquals("2" to "2", counted(paths, tallies))
    }
}
