package splice.head.perf.v4381

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.perf.PerfArchiveName
import splice.head.perf.TurnKeptRoutes
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class TurnKeptRoutesTest {
    @Test
    fun `an empty turn store names its on state instead of claiming deletion`(@TempDir tmp: Path) {
        val response = TurnKeptRoutes(StatePaths(baseOverride = tmp.resolve("state"))).kept()
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Json.parseToJsonElement(
                """{"store":"turns","state":"on","days":0,"rows":0,"oldest":null,"ages_out":null}""",
            ),
            Json.parseToJsonElement(response.body),
        )
    }

    @Test
    fun `live rolled and archived lines determine days while derived totals add no rows`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.stateDir)
        val live = paths.perfStatsFile("claudex")
        val first = Instant.parse("2026-09-25T01:00:00Z").toEpochMilli()
        val second = Instant.parse("2026-09-26T02:00:00Z").toEpochMilli()
        val third = Instant.parse("2026-09-27T03:00:00Z").toEpochMilli()
        Files.writeString(live, """{"ts":$third,"model":"m"}""" + "\n")
        Files.writeString(live.resolveSibling("${live.fileName}.1"), """{"ts":$second,"model":"m"}""" + "\n")
        Files.createDirectories(paths.perfArchiveDir)
        val archived = paths.perfArchiveDir.resolve(PerfArchiveName(live.fileName.toString()).of(third))
        Files.writeString(archived, """{"ts":$first,"model":"m"}""" + "\n")
        Files.writeString(paths.sessionTotalsFile("claudex"), "derived total")
        Files.writeString(paths.stateDir.resolve("other-data.jsonl"), "not turn statistics")
        val outside = tmp.resolve("outside.jsonl")
        Files.writeString(outside, """{"ts":$first}""" + "\n")
        Files.createSymbolicLink(paths.perfStatsFile("external"), outside)

        val response = TurnKeptRoutes(paths).kept()
        assertEquals(HttpStatusCode.OK, response.status, response.body)
        val body = Json.parseToJsonElement(response.body).jsonObject
        assertEquals("3", body.getValue("days").jsonPrimitive.content)
        assertEquals("3", body.getValue("rows").jsonPrimitive.content)
        assertEquals("2026-09-25", body.getValue("oldest").jsonPrimitive.content)
        assertEquals("null", body.getValue("ages_out").toString())
        assertEquals("on", body.getValue("state").jsonPrimitive.content)
        assertEquals("""{"ts":$first}""" + "\n", Files.readString(outside))
    }

    @Test
    fun `session totals alone keep an otherwise empty turn store on`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.stateDir)
        Files.writeString(paths.sessionTotalsFile("gone"), "derived total")
        val body = Json.parseToJsonElement(TurnKeptRoutes(paths).kept().body).jsonObject
        assertEquals("on", body.getValue("state").jsonPrimitive.content)
        assertEquals("0", body.getValue("rows").jsonPrimitive.content)
    }

    @Test
    fun `a directory disguised as a perf file fails rather than reading as empty`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(paths.perfStatsFile("disguised"))
        val response = TurnKeptRoutes(paths).kept()
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.body.contains("not a regular turn file"), response.body)
    }
}
