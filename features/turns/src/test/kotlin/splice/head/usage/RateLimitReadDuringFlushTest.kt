// A read of the rate limit answers the newest round's headers while a flush is writing them to disk (pre-push gate,
// Oct 29: UsageStoreSourceTest read no rate limit at all when the coalesced flush landed between the round and the
// read). The status line and the usage route read it on every tick, so a read that lands mid-flush must still answer
// the round splice last carried: never nothing on a first write, never the round before on a later one. Over generated
// rounds on one store, each round's flush raced by a reader that reads for as long as the flush runs.
package splice.head.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RateLimitReadDuringFlushTest {

    private val rounds = 40

    @Test
    fun `every read during a flush answers the newest round, never nothing and never the round before`(
        @TempDir tmp: Path,
    ) {
        var now = 1_789_312_411_660L
        val store = UsageStore(tmp.resolve("codex-usage.json"), tmp.resolve("codex-ratelimit.json"), clock = { now })
        val misses = ConcurrentLinkedQueue<String>()
        repeat(rounds) { round ->
            now += 1_000L * (round + 1)
            val limit = 5_000L + round * 7
            store.persistRateLimit { name -> mapOf("x-ratelimit-limit-tokens" to "$limit")[name] }
            val done = AtomicBoolean(false)
            val reading = CountDownLatch(1)
            val reader = Thread {
                while (!done.get()) {
                    val read = store.readRateLimit()
                    if (read?.limitTokens != limit) {
                        misses += "round $round read ${read?.limitTokens ?: "nothing"}, not $limit"
                    }
                    reading.countDown()
                }
            }
            reader.start()
            reading.await(5, TimeUnit.SECONDS)
            store.flushNow()
            done.set(true)
            reader.join()
            val after = store.readRateLimit()
            if (after?.limitTokens != limit) {
                misses += "round $round read ${after?.limitTokens ?: "nothing"} after its flush, not $limit"
            }
        }
        assertEquals(emptyList<String>(), misses.toList().distinct().take(10))
    }
}
