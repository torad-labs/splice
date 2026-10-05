// NEW: the real idle watchdog and head stop both reap disposed readers and cut their original posting rows.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.provider.codex.stream.CodeModeExecutionDisposed
import splice.provider.codex.stream.CodeModeSwitchingSink
import splice.upstream.ClientFrameEmitted
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RowRelease
import splice.upstream.Ticker
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.TurnWatchdog
import splice.upstream.retry.WatchdogFired
import splice.upstream.sse.WireSink
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

internal class CodeModeDrainWatchdogTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `the existing idle watchdog cuts a stalled drain on the original posting row`() = runBlocking {
        cutDrain(headStop = false)
    }

    @Test
    @Timeout(20)
    fun `real head stop still cancels a drained reader and releases its original posting row`() = runBlocking {
        cutDrain(headStop = true)
    }

    @Test
    fun `disposed wire output is discarded rather than filling the detached buffer`() = runBlocking {
        val sink = CodeModeSwitchingSink(RecordingSink(), CodeModeExecutionDisposed { true }) {}
        sink.detach()
        val fragment = "synthetic".repeat(8_192)
        repeat(CodeModeLimits.MAX_FRAME_BYTES / fragment.length + 2) {
            val index = sink.openText()
            sink.textDelta(index, fragment)
            sink.closeBlock(index)
        }
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

        override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
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

    private suspend fun cutDrain(headStop: Boolean) {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = GatedPost(sink)
        val posting = IdlePost(source)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, posting)
            withTimeout(1_500) { sink.callback.await() }
            val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray +
                Json.parseToJsonElement("""{"role":"user","content":"synthetic superseding request"}""")
            val next = withTimeout(1_500) {
                manager.interceptor(turn(), disableParallel = false).intercept(
                    JsonObject(mapOf("input" to JsonArray(input))).toString(),
                    RecordingSink(),
                    source,
                )
            } as TurnOutcome.Success
            assertTrue(logLines.any { "parked program was superseded" in it })
            assertFalse(source.stopped.isCompleted, "the response is still draining after the client step returned")
            assertFalse(posting.released.isCompleted)
            if (headStop) manager.onHeadStop() else posting.tick.complete(Unit)
            withTimeout(1_500) { source.stopped.await() }
            val usage = withTimeout(1_500) { posting.released.await() }
            assertEquals(1, checkNotNull(usage).cutRounds, "the original row must record the unfinished response")
            assertEquals(0L, usage.outputTokens)
            assertEquals(1, posting.releases.get())
            assertEquals(5L, next.usage.outputTokens, "the next step cannot absorb the old drain")
            assertEquals(1, runtime.starts)
            assertEquals(1, runtime.delivered.size)
            if (!headStop) assertTrue(posting.dog.fired is WatchdogFired.Idle, "the actual idle poller must fire")
        } finally {
            manager.onHeadStop()
        }
    }
}
