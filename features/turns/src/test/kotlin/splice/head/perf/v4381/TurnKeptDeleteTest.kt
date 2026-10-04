package splice.head.perf.v4381

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.perf.PerfArchiveName
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.AsyncFileIo
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.perf.PerfRowMeta
import splice.head.perf.PerfStats
import splice.head.perf.SessionTotals
import splice.head.perf.TurnKeptRoutes
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MODEL = "model"
private const val TAG = "a6b15bd7"
private const val SESSION = "a6b15bd7-1c2d-4e5f-8a9b-0c1d2e3f4a5b"

class TurnKeptDeleteTest {
    @Test
    fun `delete removes all turn files only and the next live turn writes again`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val live = paths.perfStatsFile("h")
        val rolled = live.resolveSibling("${live.fileName}.1")
        val archived = paths.perfArchiveDir.resolve(PerfArchiveName(live.fileName.toString()).of(1_789_725_600_000L))
        Files.createDirectories(paths.perfArchiveDir)
        Files.writeString(live, """{"ts":1789725600000,"model":"m"}""" + "\n")
        Files.writeString(rolled, """{"ts":1789639200000,"model":"m"}""" + "\n")
        Files.writeString(archived, """{"ts":1789552800000,"model":"m"}""" + "\n")
        val totalsFile = paths.sessionTotalsFile("h")
        Files.writeString(totalsFile, "derived total")
        val unrelated = paths.stateDir.resolve("other-data.json")
        Files.writeString(unrelated, "unrelated")
        val outside = tmp.resolve("outside.jsonl")
        Files.writeString(outside, "private external bytes")
        val link = paths.perfStatsFile("symlink")
        Files.createSymbolicLink(link, outside)

        val response = TurnKeptRoutes(paths).delete()
        assertEquals(HttpStatusCode.OK, response.status, response.body)
        val body = Json.parseToJsonElement(response.body).jsonObject
        assertEquals("deleted", body.getValue("state").jsonPrimitive.content)
        assertEquals("turns deleted", body.getValue("reason").jsonPrimitive.content)
        assertEquals("0", body.getValue("rows").jsonPrimitive.content)
        for (file in listOf(live, rolled, archived, totalsFile, link)) {
            assertFalse(Files.exists(file), "still retained: $file")
        }
        assertEquals("unrelated", Files.readString(unrelated))
        assertEquals("private external bytes", Files.readString(outside))

        val next = Instant.parse("2026-09-28T03:00:00Z").toEpochMilli()
        val stats = PerfStats(live, clock = WallClock { next })
        stats.record(PerfRowMeta(MODEL, "ok", compact = false), turn())
        assertTrue(AsyncFileIo.drain(), "the next turn reached disk")
        val after = Json.parseToJsonElement(TurnKeptRoutes(paths).kept().body).jsonObject
        assertEquals("on", after.getValue("state").jsonPrimitive.content)
        assertEquals("1", after.getValue("rows").jsonPrimitive.content)
        assertFalse(Files.exists(paths.stateDir.resolve("turns.deleted")), "a new append clears deletion")
    }

    @Test
    fun `delete clears live totals and a pending flush cannot restore old sessions`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        var now = 1_789_725_600_000L
        val totals = SessionTotals(paths.sessionTotalsFile("h"), price(), WallClock { now }, LogSink {})
        totals.add(TAG, MODEL, mapOf(PerfKeys.IN_TOKENS to 100L), now)
        assertNotNull(totals.totalFor(SESSION))
        val response = TurnKeptRoutes(paths, liveTotals = mapOf("h" to totals)).delete()
        assertEquals(HttpStatusCode.OK, response.status, response.body)
        assertNull(totals.totalFor(SESSION), "deleting only the file leaves stale statusline cost in memory")
        assertFalse(Files.exists(paths.sessionTotalsFile("h")))
        val flushPassed = CountDownLatch(1)
        assertTrue(AsyncFileIo.submit(1_200L) { flushPassed.countDown() })
        assertTrue(flushPassed.await(5, TimeUnit.SECONDS), "the scheduled flush did not reach the lane")
        assertFalse(Files.exists(paths.sessionTotalsFile("h")), "an old scheduled flush restored deleted totals")
        totals.flushNow()
        assertFalse(Files.exists(paths.sessionTotalsFile("h")), "a head stop must not restore deleted totals")
        now += 1_000L
        totals.add(TAG, MODEL, mapOf(PerfKeys.IN_TOKENS to 2L), now)
        val reborn = totals.totalFor(SESSION)
        assertNotNull(reborn)
        assertEquals(now - 1_000L, reborn?.fromMs, "the returning tag begins after the deleted rows")
        totals.flushNow()
    }

    private fun price(): TurnPrice = TurnPrice(
        ModelCatalog(
            discoveryPrefix = "test--",
            models = listOf(ModelEntry(MODEL, contextWindow = 1000, rates = ModelRates(1.0, 0.1, 1.0))),
            defaultContextWindow = 1000,
            pinnedModel = MODEL,
        ),
    )

    private fun turn() = TurnPerf { 0L }.apply { setCount(PerfKeys.IN_TOKENS, 1L) }.snapshot()
}
