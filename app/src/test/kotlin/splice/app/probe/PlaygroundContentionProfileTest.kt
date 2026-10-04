// Phase profiles under real console reads. All upstream work is synthetic and MockEngine-owned.
package splice.app.probe

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal const val PROFILE_TIMEOUT_MS = 120_000L
private const val CONTROLLED_DELAY_MS = 100L

class PlaygroundContentionProfileTest {
    @Test
    fun `console reads and mock upstream waits have separate measured phases`(@TempDir root: Path) = runBlocking {
        withTimeout(PROFILE_TIMEOUT_MS) {
            PlaygroundContentionFixture(root, this).use { fixture ->
                fixture.start()
                repeat(2) { fixture.playground() }
                scenario(fixture, "idle", 0)
                fixture.coldReads()
                scenario(fixture, "cold-console", 4)
                scenario(fixture, "warm-console", 4)

                fixture.headerDelayMs = CONTROLLED_DELAY_MS
                scenario(fixture, "held-headers", 0)
                assertTrue(fixture.samples.headers - fixture.samples.posted >= 50_000_000L)

                fixture.headerDelayMs = 0
                fixture.bodyDelayMs = CONTROLLED_DELAY_MS
                scenario(fixture, "held-completion", 0)
                assertTrue(fixture.samples.completed - fixture.samples.headers >= 50_000_000L)
            }
        }
    }

    private suspend fun scenario(fixture: PlaygroundContentionFixture, label: String, count: Int) = coroutineScope {
        fixture.samples.reset()
        fixture.beginReads()
        val controls = List(count) { async { fixture.controlRead() } }
        if (count > 0) {
            fixture.readStarted.await()
            assertTrue(controls.any { !it.isCompleted }, "the sample must overlap a console read")
        }
        val result = Json.parseToJsonElement(fixture.playground()).jsonObject
        assertTrue("response" in result, "the sample must receive the actual Playground result")
        fixture.samples.print(label, count)
        val reads = controls.awaitAll()
        assertTrue(reads.all { "heads" in Json.parseToJsonElement(it).jsonObject }, "every control read must succeed")
    }
}
