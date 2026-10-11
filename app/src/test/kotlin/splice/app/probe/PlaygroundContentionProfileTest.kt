// Phase profiles under real console reads. All upstream work is synthetic and MockEngine-owned.
package splice.app.probe

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal const val PROFILE_TIMEOUT_MS = 120_000L

// Four concurrent history reads must not spend hundreds of idle-route intervals on the request executor.
private const val ROUTE_IDLE_MULTIPLE = 50L

// A scan long enough to expose request-worker contention without the full 87,559-request profile.
private const val CONTENTION_HISTORY_REQUESTS = 8_192

class PlaygroundContentionProfileTest {
    @Test
    fun `four concurrent console reads cannot withhold the Playground route`(@TempDir root: Path) = runBlocking {
        withTimeout(PROFILE_TIMEOUT_MS) {
            PlaygroundContentionFixture(root, this, historyRequests = CONTENTION_HISTORY_REQUESTS).use { fixture ->
                fixture.start()
                repeat(2) { fixture.playground() }
                val idle = List(5) { scenario(fixture, "idle-loaded", 0) }.sorted()[2]
                val expected = Json.parseToJsonElement(fixture.controlRead()).jsonObject
                fixture.coldReads()
                val cold = scenario(fixture, "cold-loaded-console", 4, expected)
                val warm = scenario(fixture, "warm-loaded-console", 4, expected)
                assertTrue(
                    maxOf(cold, warm) <= idle * ROUTE_IDLE_MULTIPLE,
                    "route ns: idle=$idle cold=$cold warm=$warm; limit=${idle * ROUTE_IDLE_MULTIPLE}",
                )
            }
        }
    }

    private suspend fun scenario(
        fixture: PlaygroundContentionFixture,
        label: String,
        count: Int,
        expectedSummary: JsonObject? = null,
    ): Long = coroutineScope {
        fixture.samples.reset()
        fixture.beginReads()
        val readsStarted = System.nanoTime()
        val controls = List(count) { async { fixture.controlRead() } }
        if (count > 0) {
            fixture.readStarted.await()
            assertTrue(controls.all { !it.isCompleted }, "the sample must overlap every console read")
        }
        val result = Json.parseToJsonElement(fixture.playground()).jsonObject
        assertTrue("response" in result, "the sample must receive the actual Playground result")
        fixture.samples.print(label, count)
        val reads = controls.awaitAll()
        if (count > 0) {
            println(
                "playground_readers_profile case=$label readers=$count group_ns=${System.nanoTime() - readsStarted}",
            )
        }
        assertTrue(reads.all { "heads" in Json.parseToJsonElement(it).jsonObject }, "every control read must succeed")
        expectedSummary?.let { expected ->
            reads.forEach { assertEquals(expected, Json.parseToJsonElement(it).jsonObject) }
        }
        fixture.samples.routed - fixture.samples.started
    }
}
