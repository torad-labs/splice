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
package splice.usage.quota

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.usage.QuotaSnapshot
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.Ticker
import java.util.concurrent.atomic.AtomicInteger

private const val CODEX_BODY = """{"rate_limit":{"primary_window":{"used_percent":1,"limit_window_seconds":18000}}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaPollerTest {

    @Test
    fun `a throwing probe logs once and the loop keeps ticking - RETRY DEFAULT IS TOTAL`() = runTest {
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
            sink = QuotaSnapshotSink { },
            log = logs::add,
            cadence = QuotaCadence(intervalMs = 1_000),
            clocks = QuotaClocks(wall = WallClock { 0L }, elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        poller.start()
        advanceTimeBy(4_500)
        poller.stop()

        assertTrue(logs.none { it.contains("loop died") }, "a throwing probe must not kill the loop: $logs")
        assertEquals(2, logs.count { it.contains("usage probe failed") }, "one line per episode: $logs")
        assertTrue(calls.get() >= 4, "the loop keeps ticking through the failures, saw ${calls.get()}")
    }

    @Test
    fun `the restart budget still guards a seam runCatchingBestEffort does not wrap`() = runTest {
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
            sink = QuotaSnapshotSink { },
            log = logs::add,
            cadence = QuotaCadence(
                intervalMs = 1_000,
                ticker = Ticker { intervalMs ->
                    // SUSPENDS first, like ProcessTicker: a fake that returned true without delaying
                    // would spin the loop against virtual time and hang runTest rather than exercising
                    // a restart at all.
                    delay(intervalMs)
                    if (ticks.incrementAndGet() == 1) error("ticker blew up")
                    true
                },
            ),
            clocks = QuotaClocks(wall = WallClock { 0L }, elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        poller.start()
        advanceTimeBy(3_500)
        poller.stop()
        assertTrue(logs.any { it.contains("loop died") && it.contains("restarting (1/5)") }, "$logs")
        assertTrue(ticks.get() >= 2, "the restarted loop must tick again, saw ${ticks.get()}")
    }

    @Test
    fun `a malformed vendor date does not kill the poller`() = runTest {
        val calls = AtomicInteger()
        val fields = UsageFields {
            Json.parseToJsonElement(
                """{"weekly":{"used_percent":1,"resets_at":"not-a-date"}}""",
            ).jsonObject
        }
        val inner = MuseMintProbe(fields, MuseQuotaParser(), WallClock { 1_788_000_000_000L })
        val probe = CountingProbe(inner, calls)
        val logs = mutableListOf<String>()
        val recorded = mutableListOf<QuotaSnapshot>()
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "muse",
            probe = probe,
            sink = QuotaSnapshotSink(recorded::add),
            log = logs::add,
            cadence = QuotaCadence(intervalMs = 1_000),
            clocks = QuotaClocks(wall = WallClock { 0L }, elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        poller.start()
        advanceTimeBy(2_000)
        poller.stop()
        assertTrue(logs.none { it.contains("loop died") }, "$logs")
        assertEquals(2, calls.get())
        assertEquals(1.0, recorded.last().sevenDay!!.usedPercent, 1e-9)
        assertNull(recorded.last().sevenDay!!.resetsAt)
    }

    // A daemon that starts before the network does (every reboot on 2026-10-01 and 2026-10-03) had its
    // boot probe refused, then waited the whole interval: the GPT head drew no 7d bar for five minutes.
    @Test
    fun `a failed probe retries within seconds and a success restores the full interval`() = runTest {
        val calls = AtomicInteger(0)
        val recorded = mutableListOf<QuotaSnapshot>()
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "claudex",
            probe = RefusedTwiceThenAnswersProbe(calls),
            sink = QuotaSnapshotSink(recorded::add),
            log = { },
            cadence = QuotaCadence(intervalMs = QUOTA_POLL_INTERVAL_MS),
            clocks = QuotaClocks(wall = WallClock { 0L }, elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        poller.start()
        // stop() in finally: a failed assertion that skipped it left the loop running, and runTest's
        // final drain of the shared virtual scheduler then looped forever filling `recorded` (OOM).
        try {
            advanceTimeBy(31_000)
            assertEquals(3, calls.get(), "refused at 0s and 10s, answered at 30s")
            assertEquals(1, recorded.size, "the bars have a reading 30s after a refused boot probe")
            advanceTimeBy(QUOTA_POLL_INTERVAL_MS - 2_000)
            assertEquals(3, calls.get(), "after an answer the poller waits the full interval again")
            advanceTimeBy(2_000)
            assertEquals(4, calls.get())
        } finally {
            poller.stop()
        }
    }

    // An HTTP refusal is an answer: a 429 asks for less traffic and a 401 does not heal in seconds, so
    // only an endpoint that could not be reached is retried early.
    @Test
    fun `an HTTP refusal waits the full interval`() = runTest {
        val calls = AtomicInteger(0)
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() +
                CoroutineExceptionHandler { _, _ -> },
        )
        val poller = QuotaPoller(
            scope = scope,
            head = "claudex",
            probe = AlwaysRefusedProbe(calls),
            sink = QuotaSnapshotSink { },
            log = { },
            cadence = QuotaCadence(intervalMs = QUOTA_POLL_INTERVAL_MS),
            clocks = QuotaClocks(wall = WallClock { 0L }, elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        poller.start()
        try {
            advanceTimeBy(QUOTA_POLL_INTERVAL_MS - 1_000)
            assertEquals(1, calls.get(), "a refused poll is not retried before the interval")
            advanceTimeBy(2_000)
            assertEquals(2, calls.get())
        } finally {
            poller.stop()
        }
    }

    // V4-296: a usage endpoint answering 401 on every poll read as "nothing to record", so the bars froze on
    // the last snapshot and no line said why, while a thrown failure logged once.
    @Test
    fun `a usage endpoint refusing every poll logs once with its status and keeps the bars - V4-296`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond(CODEX_BODY, HttpStatusCode.OK) else respond("", HttpStatusCode.Unauthorized)
        }
        val logs = mutableListOf<String>()
        val recorded = mutableListOf<QuotaSnapshot>()
        val probe = CodexQuotaProbe(HttpClient(engine), "https://chatgpt.com/backend-api/codex", BearerAuth(), { 0L })
        var elapsed = 0L
        val poller = QuotaPoller(
            this,
            "codex",
            probe,
            QuotaSnapshotSink(recorded::add),
            logs::add,
            clocks = QuotaClocks(wall = { 0L }, elapsed = ElapsedClock { elapsed }),
        )

        repeat(4) {
            poller.pollOnce()
            elapsed += QUOTA_POLL_INTERVAL_MS
        }

        assertEquals(1, recorded.size, "the bars keep the last snapshot: $recorded")
        val failed = logs.filter { "usage probe failed" in it }
        assertEquals(1, failed.size, "one line for three refused polls: $logs")
        assertTrue("HTTP 401" in failed.single(), failed.single())
    }

    @Test
    fun `overlapping opens share a slow probe even after its floor passes`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val observation = QuotaSnapshot(updatedAt = 1_788_000_000_000L)
        val poller = QuotaPoller(
            backgroundScope,
            "synthetic",
            QuotaProbe {
                calls++
                release.await()
                observation
            },
            QuotaSnapshotSink { },
            { },
            clocks = QuotaClocks(elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        val first = async { poller.probeNow() }
        runCurrent()
        val second = async { poller.probeNow() }
        runCurrent()
        advanceTimeBy(61_000)
        release.complete(Unit)
        assertEquals(observation, first.await())
        assertEquals(observation, second.await())
        assertEquals(1, calls, "a waiting page shares the active attempt even if it ran past the floor")
        assertEquals(observation, poller.probeNow(), "completion starts the reuse floor")
        assertEquals(1, calls)
    }

    @Test
    fun `a scheduled waiter shares an unreachable page-open attempt`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val poller = QuotaPoller(
            backgroundScope,
            "synthetic",
            QuotaProbe {
                calls++
                release.await()
                throw java.net.ConnectException("synthetic unreachable")
            },
            QuotaSnapshotSink { },
            { },
            clocks = QuotaClocks(elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        val page = async { poller.probeNow() }
        runCurrent()
        val tick = async { poller.pollOnce() }
        runCurrent()
        release.complete(Unit)
        assertNull(page.await())
        assertEquals(false, tick.await())
        assertEquals(1, calls, "a scheduled waiter shares the failed attempt, not just successful answers")
        assertEquals(false, poller.pollOnce(), "a later boot retry may still run early")
        assertEquals(2, calls)
    }

    @Test
    fun `an on-open HTTP refusal preserves the observation until its cooldown ends`() = runTest {
        var calls = 0
        val observation = QuotaSnapshot(updatedAt = 1_788_000_000_000L)
        val recorded = mutableListOf<QuotaSnapshot>()
        val poller = QuotaPoller(
            backgroundScope,
            "synthetic",
            QuotaProbe { if (++calls == 1) observation else throw QuotaEndpointRefused(429) },
            QuotaSnapshotSink(recorded::add),
            { },
            clocks = QuotaClocks(elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        assertEquals(observation, poller.probeNow())
        advanceTimeBy(60_000)
        assertEquals(observation, poller.probeNow())
        advanceTimeBy(60_000)
        assertEquals(observation, poller.probeNow())
        assertEquals(2, calls, "page-open cannot retry a refusal inside the full poll interval")
        assertTrue(poller.pollOnce(), "the scheduled path shares the same refusal cooldown")
        assertEquals(2, calls)
        assertEquals(listOf(observation), recorded, "a refusal must never write or retimestamp a reading")
        advanceTimeBy(QUOTA_POLL_INTERVAL_MS)
        poller.probeNow()
        assertEquals(3, calls, "the cooldown expires")
    }

    @Test
    fun `a probe with no new observation keeps the last successful snapshot`() = runTest {
        var calls = 0
        val observation = QuotaSnapshot(updatedAt = 1_788_000_000_000L)
        val recorded = mutableListOf<QuotaSnapshot>()
        val poller = QuotaPoller(
            backgroundScope,
            "synthetic",
            QuotaProbe { if (++calls == 1) observation else null },
            QuotaSnapshotSink(recorded::add),
            { },
            clocks = QuotaClocks(elapsed = ElapsedClock { testScheduler.currentTime }),
        )
        assertEquals(observation, poller.probeNow())
        advanceTimeBy(60_000)
        assertEquals(observation, poller.probeNow())
        assertEquals(listOf(observation), recorded, "an absent provider observation cannot clear or redate a reading")
        assertEquals(observation, poller.probeNow())
        assertEquals(2, calls)
    }

    private class BearerAuth : AuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("tok")
        override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
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

    /** Refused like a boot before the network is up, twice, then answers on every later call. */
    private class RefusedTwiceThenAnswersProbe(private val calls: AtomicInteger) : QuotaProbe {
        override suspend fun probe(): QuotaSnapshot? {
            if (calls.incrementAndGet() <= 2) throw java.net.ConnectException("Connection refused")
            return QuotaSnapshot(updatedAt = 0L)
        }
    }

    /** The usage endpoint answers 429 on every call. */
    private class AlwaysRefusedProbe(private val calls: AtomicInteger) : QuotaProbe {
        override suspend fun probe(): QuotaSnapshot? {
            calls.incrementAndGet()
            throw QuotaEndpointRefused(429)
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
