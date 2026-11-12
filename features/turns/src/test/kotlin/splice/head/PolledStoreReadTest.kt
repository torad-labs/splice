// NEW: polled stores return memory or last committed rows while the shared write lane is held.
package splice.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.TurnPerf
import splice.core.util.AsyncFileIo
import splice.head.compact.CompactStats
import splice.head.perf.PerfRowMeta
import splice.head.perf.PerfStats
import splice.head.usage.UsageStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PolledStoreReadTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `compaction polls return committed rows without waiting for queued writes`() {
        val stats = CompactStats(tmp.resolve("compact.jsonl"), clock = { 1L })
        stats.record(mapOf("outcome" to "model_text"))
        assertTrue(AsyncFileIo.drain())
        val summary = whileLaneHeld {
            stats.record(mapOf("outcome" to "empty_model"))
            stats.read()
        }
        assertEquals(1, summary.total)
        assertEquals(mapOf("model_text" to 1), summary.byOutcome)
        assertEquals(2, stats.read().total)
    }

    @Test
    fun `rate limit polls return committed or pending state without waiting for the lane`() {
        val store = UsageStore(tmp.resolve("usage.json"), tmp.resolve("rate.json"))
        store.persistRateLimit { name ->
            mapOf("x-ratelimit-limit-tokens" to "5000", "x-ratelimit-remaining-tokens" to "1200")[name]
        }
        store.flushNow()
        whileLaneHeld {
            assertEquals(1200, store.readRateLimit()?.remainingTokens)
            store.persistRateLimit { name ->
                mapOf("x-ratelimit-limit-tokens" to "5000", "x-ratelimit-remaining-tokens" to "900")[name]
            }
            assertEquals(900, store.readRateLimit()?.remainingTokens)
        }
        store.flushNow()
        val fresh = UsageStore(tmp.resolve("usage.json"), tmp.resolve("rate.json"))
        assertEquals(900, fresh.readRateLimit()?.remainingTokens)
    }

    @Test
    fun `perf polls return committed rows without waiting for their queued append`() {
        val stats = PerfStats(tmp.resolve("perf.jsonl"), clock = { 1L })
        val meta = PerfRowMeta("synthetic", "ok", compact = false, session = "synthetic")
        val snapshot = TurnPerf { 0L }.snapshot()
        stats.record(meta, snapshot)
        assertTrue(AsyncFileIo.drain())
        whileLaneHeld {
            stats.record(meta, snapshot)
            assertEquals(1, stats.tailNumeric().size)
            assertEquals(1, stats.sessionTail("synthetic").turns.size)
        }
        assertEquals(2, stats.tailNumeric().size)
    }

    @Test
    fun `a perf write failure is reported without needing a polling read`() {
        val parent = tmp.resolve("not-a-directory")
        Files.writeString(parent, "synthetic")
        val notices = java.util.concurrent.CopyOnWriteArrayList<String>()
        val stats = PerfStats(parent.resolve("perf.jsonl"), log = { notices += it })
        stats.record(PerfRowMeta("synthetic", "ok", compact = false), TurnPerf { 0L }.snapshot())
        assertTrue(AsyncFileIo.drain())
        assertEquals(1, notices.count { "file lane failed to write a turn row" in it })
    }

    private fun <T> whileLaneHeld(read: () -> T): T {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = Executors.newSingleThreadExecutor()
        assertTrue(
            AsyncFileIo.submit {
                entered.countDown()
                release.await()
            },
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the write lane must be held before the read")
            return reader.submit<T> { read() }.get(15, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            reader.shutdown()
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(AsyncFileIo.drain())
        }
    }
}
