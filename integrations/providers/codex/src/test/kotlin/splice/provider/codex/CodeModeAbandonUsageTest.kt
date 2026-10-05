// NEW: disposing a posted source frees its reader and counts its cut once on the disposing client step.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RowRelease
import splice.upstream.transport.UpstreamFailed
import java.util.concurrent.atomic.AtomicInteger

private enum class SourceDisposition { NATIVE, STEERING, SUPERSEDED }

internal class CodeModeAbandonUsageTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `native abandonment cancels its reader and counts the cut on the abandoning step`() = runBlocking {
        disposeAfterSource(SourceDisposition.NATIVE, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `native abandonment preserves already reported usage without inventing a cut`() = runBlocking {
        disposeAfterSource(SourceDisposition.NATIVE, reportedBeforeDispose = true)
    }

    @Test
    @Timeout(20)
    fun `real steering cancels its reader and counts the cut on the steering step`() = runBlocking {
        disposeAfterSource(SourceDisposition.STEERING, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `supersession cancels its reader and counts the cut on the superseding step`() = runBlocking {
        disposeAfterSource(SourceDisposition.SUPERSEDED, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `an upstream refusal after abandonment still counts the cut on the failing client step`() = runBlocking {
        cutWithFailedContinuation(UpstreamFailed("synthetic refusal", status = 400))
    }

    @Test
    @Timeout(20)
    fun `cancellation after abandonment still counts the cut on the cancelled client step`() = runBlocking {
        cutWithFailedContinuation(CancellationException("synthetic client cancellation"))
    }

    private suspend fun cutWithFailedContinuation(refusal: RuntimeException) {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = GatedPost(sink)
        val row = PostingRow(source)
        val user = Json.parseToJsonElement("""{"role":"user","content":"synthetic request"}""")
        val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray + user
        val perf = TurnPerf(ElapsedClock { 0 }, WallClock { 0 })
        val refusing = object : RedirectableRoundPost by source {
            override val perf = perf
            override suspend fun into(bodyJson: String, sink: splice.upstream.sse.WireSink): TurnOutcome = throw refusal
        }
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonObject(mapOf("input" to JsonArray(input))).toString(), sink, row.original)
            val callback = withTimeout(1_500) { sink.callback.await() }
            val callbacks = Json.parseToJsonElement(history(listOf(callback))).jsonObject
                .getValue("input").jsonArray.drop(1)
            val items = changedHistory(input, callbacks, SourceDisposition.NATIVE)
            val changed = JsonObject(mapOf("input" to JsonArray(items)))
            val failure = assertThrows(refusal::class.java) {
                runBlocking {
                    manager.interceptor(turn(callback.id, "result-0"), disableParallel = false)
                        .intercept(changed.toString(), RecordingSink(), refusing)
                }
            }
            if (refusal is UpstreamFailed) assertTrue(failure === refusal, "the upstream refusal propagates unchanged")
            withTimeout(1_500) { source.stopped.await() }
            row.assertOriginal(reported = false)
            val cuts = perf.snapshot().counters[PerfKeys.CUT_SOURCE_ROUNDS]
            assertEquals(1L, cuts, "the failed cutting turn owns the cut")
            assertFalse(source.sent[1].isCompleted)
        } finally {
            manager.onHeadStop()
        }
    }

    private class PostingRow(generated: RedirectableRoundPost) {
        val released = CompletableDeferred<Usage?>()
        private val releases = AtomicInteger()
        val original = object : RedirectableRoundPost by generated {
            override val postingRow = PostingTurnRow {
                RowRelease {
                    releases.incrementAndGet()
                    released.complete(it)
                }
            }
        }

        suspend fun assertOriginal(reported: Boolean) {
            val usage = withTimeout(1_500) { released.await() }
            if (reported) {
                assertEquals(7L, checkNotNull(usage).outputTokens)
                assertEquals(0, usage.cutRounds)
            } else {
                assertNull(usage, "the posting step neither reported tokens nor cut its source")
            }
            assertEquals(1, releases.get(), "the original row releases exactly once")
        }
    }

    private suspend fun disposeAfterSource(disposition: SourceDisposition, reportedBeforeDispose: Boolean) {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val source = GatedPost(sink)
        val row = PostingRow(source)
        val user = Json.parseToJsonElement("""{"role":"user","content":"synthetic request"}""")
        val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray + user
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonObject(mapOf("input" to JsonArray(input))).toString(), sink, row.original)
            val callback = withTimeout(1_500) { sink.callback.await() }
            if (reportedBeforeDispose) {
                finishSource(source)
                withTimeout(1_500) { row.released.await() }
            }
            val callbackItems = Json.parseToJsonElement(history(listOf(callback))).jsonObject
                .getValue("input").jsonArray.drop(1)
            val changed = JsonObject(mapOf("input" to JsonArray(changedHistory(input, callbackItems, disposition))))
            val answering = if (disposition == SourceDisposition.SUPERSEDED) turn() else turn(callback.id, "result-0")
            val next = withTimeout(1_500) {
                manager.interceptor(answering, disableParallel = false)
                    .intercept(changed.toString(), RecordingSink(), source)
            } as TurnOutcome.Success
            withTimeout(1_500) { source.stopped.await() }
            assertDisposition(disposition)
            row.assertOriginal(reportedBeforeDispose)
            assertCut(next, if (reportedBeforeDispose) 0 else 1)
            if (!reportedBeforeDispose) assertFalse(source.sent[1].isCompleted, "no unread source may be generated")
            assertEquals(1, runtime.starts)
            assertEquals(1, runtime.delivered.size, "no remaining statement may execute")
            assertEquals(2, source.posts)
            val retry = manager.interceptor(answering, disableParallel = false)
                .intercept(changed.toString(), RecordingSink(), source) as TurnOutcome.Success
            assertCut(retry, 0)
        } finally {
            manager.onHeadStop()
        }
    }

    private fun assertCut(outcome: TurnOutcome.Success, cuts: Long) {
        assertEquals(cuts, outcome.usage.cutRounds)
        assertEquals(150L, outcome.usage.inputTokens)
        assertEquals(5L, outcome.usage.outputTokens, "the disposing step bills only its own response")
        assertEquals(0L, outcome.usage.absorbed.outputTokens, "unreported source tokens are never invented")
    }

    private fun assertDisposition(disposition: SourceDisposition) {
        val expected = when (disposition) {
            SourceDisposition.NATIVE -> "native discovery history was edited"
            SourceDisposition.STEERING -> "interrupted extra=STEERING"
            SourceDisposition.SUPERSEDED -> "parked program was superseded"
        }
        assertTrue(logLines.any { expected in it }, "the intended disposition must be exercised")
    }

    private fun changedHistory(
        input: List<JsonElement>,
        callbacks: List<JsonElement>,
        disposition: SourceDisposition,
    ): List<JsonElement> {
        val newUser = Json.parseToJsonElement("""{"role":"user","content":"new synthetic direction"}""")
        return when (disposition) {
            SourceDisposition.NATIVE -> listOf(
                input.first(),
                Json.parseToJsonElement(
                    """{"type":"reasoning","id":"synthetic-unexpected","encrypted_content":"synthetic"}""",
                ),
                input.last(),
            ) + callbacks
            SourceDisposition.STEERING -> input + callbacks + newUser
            SourceDisposition.SUPERSEDED -> input + newUser
        }
    }

    private suspend fun finishSource(post: GatedPost) {
        post.gates[1].complete(Unit)
        post.gates[2].complete(Unit)
        post.complete.complete(Unit)
        withTimeout(1_500) { post.stopped.await() }
    }
}
