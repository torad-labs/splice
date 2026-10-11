// NEW: Oct 10, 2026 — Your data counts the stores that are not turn records (reasoning between turns, code mode
// work) beside the records, and the save says what it took from them.
package splice.head.perf

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.perf.HistoryWindow
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("UTC")
private val THEN = ZonedDateTime.of(2026, 10, 10, 15, 12, 30, 0, ZONE).toInstant().toEpochMilli()

class HistoryOtherStoresTest {

    private class Held(var held: HeldStores, val summaries: HeldStores = HeldStores(emptyList(), null)) :
        HistoryStores {
        val counted = ArrayList<Long>()
        val trimmed = ArrayList<Long>()
        override fun trimBefore(momentMs: Long): String? {
            trimmed += momentMs
            held = HeldStores(emptyList(), null)
            return null
        }

        override fun summariesHeld(): HeldStores = summaries

        override fun heldBefore(momentMs: Long): HeldStores {
            counted += momentMs
            return held
        }
    }

    private fun paths(tmp: Path): StatePaths = StatePaths(baseOverride = tmp.resolve("state")).also {
        Files.createDirectories(it.perfArchiveDir)
        val old = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZONE).toInstant().toEpochMilli()
        Files.writeString(
            it.perfStatsFile("h"),
            """{"ts":$old,"model":"m","outcome":"ok","in_tokens":7}""" + "\n",
        )
    }

    private fun routes(paths: StatePaths, others: HistoryStores) =
        HistoryRoutes(paths, HistoryWindowStore { null }, others, WallClock { THEN }, ZONE, LogSink { })

    private fun ok(reply: splice.http.JsonReply) = Json.parseToJsonElement(
        reply.body.also { assertEquals(HttpStatusCode.OK, reply.status, it) },
    ).jsonObject

    @Test
    fun `a proposed cut lists each store that holds something, in bytes, and leaves out the empty ones`(
        @TempDir tmp: Path,
    ) {
        val others = Held(HeldStores(listOf(HeldStore("reasoning", 2048), HeldStore("code_mode", 0)), null))
        val cut = ok(routes(paths(tmp), others).read(HistoryWindow(35, ZONE), proposedDays = "7"))
            .getValue("cut").jsonObject

        val stores = cut.getValue("stores").jsonArray.map { it.jsonObject }
        assertEquals(listOf("reasoning"), stores.map { it.getValue("key").jsonPrimitive.content })
        assertEquals(2048L, stores.single().getValue("bytes").jsonPrimitive.long)
        assertNull(cut["stores_reason"])
    }

    @Test
    fun `compaction summaries sit beside the band as one total, and say why when they could not be counted`(
        @TempDir tmp: Path,
    ) {
        val parked = HeldStores(listOf(HeldStore("compaction_summaries", 99)), null)
        val counted = Held(HeldStores(emptyList(), null), parked)
        val body = ok(routes(paths(tmp), counted).read(HistoryWindow(35, ZONE), proposedDays = null))
        assertEquals(99L, body.getValue("compaction_summaries").jsonObject.getValue("bytes").jsonPrimitive.long)

        val failed = Held(HeldStores(emptyList(), null), HeldStores(emptyList(), "compaction_summaries: unreadable"))
        val reason = ok(routes(paths(tmp), failed).read(HistoryWindow(35, ZONE), proposedDays = null))
        assertNull(reason["compaction_summaries"])
        val said = reason.getValue("compaction_summaries_reason").jsonPrimitive.content
        assertEquals("compaction_summaries: unreadable", said)
    }

    @Test
    fun `a store that could not be counted says why, so a missing row is never read as empty`(@TempDir tmp: Path) {
        val others = Held(HeldStores(emptyList(), "code mode work is unreadable"))
        val cut = ok(routes(paths(tmp), others).read(HistoryWindow(35, ZONE), proposedDays = "7"))
            .getValue("cut").jsonObject

        assertEquals("code mode work is unreadable", cut.getValue("stores_reason").jsonPrimitive.content)
    }

    @Test
    fun `the save reports what the stores held before it trimmed them, not what is left`(@TempDir tmp: Path) {
        val others = Held(HeldStores(listOf(HeldStore("reasoning", 512)), null))
        val routes = routes(paths(tmp), others)
        val moment = ok(routes.read(HistoryWindow(35, ZONE), proposedDays = "7"))
            .getValue("cut").jsonObject.getValue("cutoff_epoch_ms").jsonPrimitive.long

        val saved = ok(routes.save("""{"days":7,"delete_before_epoch_ms":$moment}"""))

        val stores = saved.getValue("cut").jsonObject.getValue("stores").jsonArray
        assertEquals(512L, stores.single().jsonObject.getValue("bytes").jsonPrimitive.long)
        assertEquals(moment, others.counted.last(), "counted at the moment that was on the screen")
        assertEquals(listOf(moment), others.trimmed)
    }
}
