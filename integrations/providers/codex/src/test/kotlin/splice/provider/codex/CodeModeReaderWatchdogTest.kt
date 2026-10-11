// NEW: idle watchdog and head stop cut an unreported source on its original row when no client step disposes it.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.upstream.ClientFrameEmitted
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.RowRelease
import splice.upstream.Ticker
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.retry.WatchdogFired
import splice.upstream.sse.WireSink
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

internal class CodeModeReaderWatchdogTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `the idle watchdog cuts an unreported active source on its original posting row`() = runBlocking {
        cutSource(headStop = false)
    }

    @Test
    @Timeout(20)
    fun `head stop cancels an unreported source and releases its original posting row`() = runBlocking {
        cutSource(headStop = true)
    }

    private class IdlePost(private val target: RedirectableRoundPost) : RedirectableRoundPost by target {
        val tick = CompletableDeferred<Unit>()
        private val now = AtomicLong()
        private val clock = ElapsedClock(now::get)
        private val gate = InflightGate(LiveLimit { 0 }, clock = clock)
        val dog = TurnWatchdog(
            WatchdogBudget(1.seconds, 1.seconds, 10.seconds),
            clock = clock,
            ticker = Ticker {
                tick.await()
                now.set(2_000)
                true
            },
        )
        val released = CompletableDeferred<Usage?>()
        val releases = AtomicInteger()
        override val postingRow = PostingTurnRow {
            RowRelease {
                releases.incrementAndGet()
                released.complete(it)
            }
        }

        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            val slot = (gate.acquire() as InflightGate.Admission.Acquired).slot
            val context = currentCoroutineContext()
            val watchdog = dog.launchIn(CoroutineScope(context), slot, context.job, ClientFrameEmitted { true })
            return try {
                target.into(bodyJson, sink)
            } finally {
                watchdog.cancel()
                slot.release()
            }
        }
    }

    private suspend fun cutSource(headStop: Boolean) {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = GatedPost(sink)
        val posting = IdlePost(source)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, posting)
            withTimeout(30_000) { sink.callback.await() }
            assertFalse(source.stopped.isCompleted)
            assertFalse(posting.released.isCompleted)
            if (headStop) manager.onHeadStop() else posting.tick.complete(Unit)
            withTimeout(30_000) { source.stopped.await() }
            val usage = withTimeout(30_000) { posting.released.await() }
            assertEquals(1, checkNotNull(usage).cutRounds)
            assertEquals(0L, usage.outputTokens, "unreported tokens are never invented")
            assertEquals(1, posting.releases.get())
            assertEquals(1, runtime.starts)
            assertEquals(1, runtime.delivered.size)
            if (!headStop) assertTrue(posting.dog.fired is WatchdogFired.Idle)
        } finally {
            manager.onHeadStop()
        }
    }
}
