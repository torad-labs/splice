// NEW: QuotaPoller restart budget mirrors AuthProbeLoop — a loop-killing throwable is logged and
// restarted; exhaustion announces permanently down.
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.quota.MuseMintProbe
import splice.app.quota.MuseQuotaParser
import splice.app.quota.QuotaPoller
import splice.app.quota.QuotaProbe
import splice.app.quota.UsageFields
import splice.core.usage.QuotaSnapshot
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.gateway.usage.QuotaTracker
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaPollerTest {

    @Test
    fun `a loop-killing throwable restarts the probe under the budget`(@TempDir tmp: Path) = runTest {
        val calls = AtomicInteger(0)
        val probe = DyingOnceProbe(calls)
        val logs = mutableListOf<String>()
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "codex",
            probe = probe,
            tracker = QuotaTracker(tmp.resolve("quota.json"), WallClock { 0L }, LogSink { }),
            log = logs::add,
            intervalMs = 1_000,
            clock = WallClock { 0L },
        )
        poller.start()
        advanceTimeBy(3_500)
        poller.stop()
        assertTrue(logs.any { it.contains("loop died") && it.contains("restarting (1/5)") }, "$logs")
        assertTrue(calls.get() >= 2, "the restarted loop must tick again, saw ${calls.get()}")
    }

    @Test
    fun `restart budget exhaustion announces permanently down`(@TempDir tmp: Path) = runTest {
        val calls = AtomicInteger(0)
        val probe = AlwaysDiesProbe(calls)
        val logs = mutableListOf<String>()
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "codex",
            probe = probe,
            tracker = QuotaTracker(tmp.resolve("quota.json"), WallClock { 0L }, LogSink { }),
            log = logs::add,
            intervalMs = 1_000,
            clock = WallClock { 0L },
        )
        poller.start()
        advanceTimeBy(20_000)
        poller.stop()
        assertEquals(5, logs.count { it.contains("restarting (") }, "$logs")
        assertEquals(1, logs.count { it.contains("permanently down") }, "$logs")
        assertEquals(6, calls.get(), "5 restarts + the original = 6 first-ticks, then silence")
    }

    @Test
    fun `a malformed vendor date does not kill the poller`(@TempDir tmp: Path) = runTest {
        val calls = AtomicInteger()
        val fields = UsageFields {
            Json.parseToJsonElement(
                """{"weekly":{"used_percent":1,"resets_at":"not-a-date"}}""",
            ).jsonObject
        }
        val inner = MuseMintProbe(fields, MuseQuotaParser(), WallClock { 1_788_000_000_000L })
        val probe = CountingProbe(inner, calls)
        val logs = mutableListOf<String>()
        val tracker = QuotaTracker(tmp.resolve("quota.json"), WallClock { 0L }, LogSink { })
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "muse",
            probe = probe,
            tracker = tracker,
            log = logs::add,
            intervalMs = 1_000,
            clock = WallClock { 0L },
        )
        poller.start()
        advanceTimeBy(2_000)
        poller.stop()
        assertTrue(logs.none { it.contains("loop died") }, "$logs")
        assertEquals(2, calls.get())
        assertEquals(1.0, tracker.snapshot()!!.sevenDay!!.usedPercent, 1e-9)
        assertNull(tracker.snapshot()!!.sevenDay!!.resetsAt)
    }

    private class DyingOnceProbe(private val calls: AtomicInteger) : QuotaProbe {
        override suspend fun probe(): QuotaSnapshot? {
            if (calls.incrementAndGet() == 1) check(false) { "provider invariant blew up" }
            return null
        }
    }

    private class AlwaysDiesProbe(private val calls: AtomicInteger) : QuotaProbe {
        override suspend fun probe(): QuotaSnapshot? {
            calls.incrementAndGet()
            check(false) { "always dies" }
            return null
        }
    }

    private class CountingProbe(
        private val inner: QuotaProbe,
        private val calls: AtomicInteger,
    ) : QuotaProbe {
        override suspend fun probe(): QuotaSnapshot? {
            calls.incrementAndGet()
            return inner.probe()
        }
    }
}
