// NEW: QuotaPoller restart budget mirrors AuthProbeLoop — a loop-killing throwable is logged and
// restarted; exhaustion announces permanently down.
//
// V4-117 REWROTE TWO OF THESE to the contract the poller actually implements. V4-118's census moved
// pollOnce onto runCatchingBestEffort, which captures everything but CancellationException and
// Error, so a THROWING PROBE is a log line rather than a loop death: the bars keep the last snapshot
// and the next tick tries again. That is RETRY DEFAULT IS TOTAL applied to the poller, and a loop
// that died five times and went permanently down was the opposite of it. The budget still guards the
// seams runCatchingBestEffort does NOT wrap, and the ticker is one — so that half moved onto the
// ticker rather than being deleted.
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import splice.spi.Ticker
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaPollerTest {

    @Test
    fun `a throwing probe logs once and the loop keeps ticking - RETRY DEFAULT IS TOTAL`(@TempDir tmp: Path) = runTest {
        // A probe failure is a LOG LINE, never a loop death: the bars keep the last snapshot and the
        // next tick tries again. The once-log is re-armed by any accepted snapshot, so a recovery
        // followed by a fresh failure is reported again rather than swallowed by the first line.
        val calls = AtomicInteger(0)
        val logs = mutableListOf<String>()
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "codex",
            probe = FailsTwiceThenRecoversProbe(calls),
            tracker = QuotaTracker(tmp.resolve("quota.json"), WallClock { 0L }, LogSink { }),
            log = logs::add,
            intervalMs = 1_000,
            clock = WallClock { 0L },
        )
        poller.start()
        advanceTimeBy(4_500)
        poller.stop()

        assertTrue(logs.none { it.contains("loop died") }, "a throwing probe must not kill the loop: $logs")
        assertEquals(2, logs.count { it.contains("usage probe failed") }, "one line per episode: $logs")
        assertTrue(calls.get() >= 4, "the loop keeps ticking through the failures, saw ${calls.get()}")
    }

    @Test
    fun `the restart budget still guards a seam runCatchingBestEffort does not wrap`(@TempDir tmp: Path) = runTest {
        // The supervisor is NOT dead code: the budget guards whatever sits outside pollOnce, and the
        // ticker is exactly that — the LOOP calls awaitTick, so nothing catches it. A ticker that
        // dies once kills the loop, the supervisor restarts it under the budget, and the restarted
        // loop ticks again. This is the surviving half of the old restart-budget test, moved onto
        // the seam where the behaviour is still real rather than deleted with the stale half.
        val logs = mutableListOf<String>()
        val ticks = AtomicInteger(0)
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "codex",
            probe = NeverFailsProbe(),
            tracker = QuotaTracker(tmp.resolve("quota.json"), WallClock { 0L }, LogSink { }),
            log = logs::add,
            intervalMs = 1_000,
            ticker = Ticker { intervalMs ->
                // SUSPENDS first, like ProcessTicker: a fake that returned true without delaying
                // would spin the loop against virtual time and hang runTest rather than exercising
                // a restart at all.
                delay(intervalMs)
                if (ticks.incrementAndGet() == 1) error("ticker blew up")
                true
            },
            clock = WallClock { 0L },
        )
        poller.start()
        advanceTimeBy(3_500)
        poller.stop()
        assertTrue(logs.any { it.contains("loop died") && it.contains("restarting (1/5)") }, "$logs")
        assertTrue(ticks.get() >= 2, "the restarted loop must tick again, saw ${ticks.get()}")
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

    /** Fails on its first two calls, then returns a SNAPSHOT — which is what re-arms the once-log,
     *  since only an accepted snapshot clears it — then fails again. The three phases are the whole
     *  point: one line per failing episode, silence while it keeps failing, and a second line after
     *  a recovery rather than the first line standing for the process's life. */
    private class FailsTwiceThenRecoversProbe(private val calls: AtomicInteger) : QuotaProbe {
        override suspend fun probe(): QuotaSnapshot? = when (calls.incrementAndGet()) {
            1, 2 -> error("provider invariant blew up")
            3 -> QuotaSnapshot(updatedAt = 0L)
            else -> error("provider invariant blew up again")
        }
    }

    private class NeverFailsProbe : QuotaProbe {
        override suspend fun probe(): QuotaSnapshot? = null
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
