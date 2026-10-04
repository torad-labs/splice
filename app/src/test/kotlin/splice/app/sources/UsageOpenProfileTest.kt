// NEW: page-open quota workloads stay independent of perf history and reuse honestly dated probes.
package splice.app.sources

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// why: 1 MiB covers the measured 529,096-byte fresh-probe open with headroom and rejects history-sized allocation.
private const val USAGE_OPEN_ALLOCATION_LIMIT = 1_048_576L

class UsageOpenProfileTest {
    @Test
    fun `quota page opens read only small quota state beside the entire synthetic history`(@TempDir dir: Path) =
        runBlocking {
            val fixture = UsageOpenFixture(dir, this)
            repeat(3) { fixture.payloads.usageJson() }
            fixture.payloads.probeNowJson()
            fixture.elapsed += 60_000
            val profiler = PerfHistoryProfile()
            measureEndpoints(fixture, profiler)
            fixture.elapsed += 60_000
            profiler.diskPhase("usage_successful_open", dir.resolve("open.jfr"), fixture.readPaths) {
                fixture.payloads.usageJson() to runBlocking { fixture.payloads.probeNowJson() }
            }
            val openBytes = profiler.allocatedBytes
            val readBytes = profiler.diskBytes
            val writtenBytes = profiler.diskWriteBytes
            val expectedRead = Files.size(fixture.rateFile) * 4
            val expectedWrite = Files.size(fixture.trackerFile)
            val observedAt = fixture.now / 1_000
            fixture.now += 1_000
            fixture.elapsed += 1_000
            assertEquals(expectedRead, readBytes, "both snapshots read the small rate-limit file twice")
            assertEquals(expectedWrite, writtenBytes, "the successful probe persists its quota snapshot")
            val reused = profiler.diskPhase("usage_reused_open", dir.resolve("reused.jfr"), fixture.readPaths) {
                fixture.payloads.usageJson() to runBlocking { fixture.payloads.probeNowJson() }
            }
            val reusedQuota = usage(reused.second).getValue("quota").jsonObject.getValue("five_hour").jsonObject
            assertEquals(observedAt.toString(), reusedQuota.getValue("observed_at").jsonPrimitive.content)
            assertEquals(3, fixture.probes, "the repeated open must share the successful probe floor")
            assertEquals(0L, profiler.diskWriteBytes, "reuse must not rewrite or retimestamp the snapshot")
            assertEquals(readBytes, profiler.diskBytes)
            assertEquals(0, fixture.historyReads, "quota navigation must not load perf history")
            assertEquals(0L, fixture.perf.parsedLines)
            assertTrue(
                openBytes < USAGE_OPEN_ALLOCATION_LIMIT,
                "a quota open remains a small fixed workload, not history-sized",
            )
            assertTrue(profiler.allocatedBytes < USAGE_OPEN_ALLOCATION_LIMIT)
            println(
                "usage_successful_open_bytes=$openBytes reused_open_bytes=${profiler.allocatedBytes} " +
                    "read_bytes=$readBytes write_bytes=$writtenBytes history_bytes=" +
                    (Files.size(fixture.history.file) + Files.size(fixture.history.file.resolveSibling("${fixture.history.file.fileName}.1"))),
            )
            rejectHistoryRead(fixture, profiler)
        }

    private fun rejectHistoryRead(fixture: UsageOpenFixture, profiler: PerfHistoryProfile) {
        profiler.phase("usage_history_read_control") { fixture.perf.window(SCALE_SINCE) }
        assertThrows(AssertionError::class.java) { assertTrue(profiler.allocatedBytes < USAGE_OPEN_ALLOCATION_LIMIT) }
    }

    private fun measureEndpoints(fixture: UsageOpenFixture, profiler: PerfHistoryProfile) {
        val get = profiler.phase("usage_get") { fixture.payloads.usageJson() }
        val getBytes = profiler.allocatedBytes
        val usage = usage(get)
        assertEquals("300", usage.getValue("entries").jsonPrimitive.content)
        assertEquals("166720", usage.getValue("output_tokens_5h").jsonPrimitive.content)
        val post = profiler.phase("usage_probe_post") { runBlocking { fixture.payloads.probeNowJson() } }
        val quota = usage(post).getValue("quota").jsonObject.getValue("five_hour").jsonObject
        assertEquals("25", quota.getValue("used_pct").jsonPrimitive.content)
        assertEquals((fixture.now / 1_000).toString(), quota.getValue("observed_at").jsonPrimitive.content)
        println("usage_get_bytes=$getBytes probe_post_bytes=${profiler.allocatedBytes}")
    }

    private fun usage(payload: String): JsonObject =
        Json.parseToJsonElement(payload).jsonObject.getValue("heads").jsonArray.single().jsonObject
            .getValue("usage").jsonObject
}
