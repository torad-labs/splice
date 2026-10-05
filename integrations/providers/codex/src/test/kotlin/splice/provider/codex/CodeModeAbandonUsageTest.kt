// NEW: disposing execution must not cancel the already-posted reader before its terminal usage arrives.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeLiveRound
import splice.upstream.PostingTurnRow
import splice.upstream.RedirectableRoundPost
import splice.upstream.RowRelease
import java.util.concurrent.atomic.AtomicInteger

private enum class SourceDisposition { NATIVE, STEERING, SUPERSEDED }

internal class CodeModeAbandonUsageTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `native abandonment drains terminal usage without executing the remaining source`() = runBlocking {
        disposeAfterSource(SourceDisposition.NATIVE, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `native abandonment preserves usage the source already reported`() = runBlocking {
        disposeAfterSource(SourceDisposition.NATIVE, reportedBeforeDispose = true)
    }

    @Test
    @Timeout(20)
    fun `real steering drains terminal usage without executing the remaining source`() = runBlocking {
        disposeAfterSource(SourceDisposition.STEERING, reportedBeforeDispose = false)
    }

    @Test
    @Timeout(20)
    fun `superseded execution drains terminal usage without executing the remaining source`() = runBlocking {
        disposeAfterSource(SourceDisposition.SUPERSEDED, reportedBeforeDispose = false)
    }

    private class PostingRows(generated: RedirectableRoundPost) {
        val released = CompletableDeferred<Usage?>()
        private val releases = AtomicInteger()
        private val holds = AtomicInteger()
        private val laterHolds = AtomicInteger()
        val original = object : RedirectableRoundPost by generated {
            override val postingRow = PostingTurnRow {
                holds.incrementAndGet()
                RowRelease {
                    releases.incrementAndGet()
                    released.complete(it)
                }
            }
        }
        val later = object : RedirectableRoundPost by generated {
            override val postingRow = PostingTurnRow {
                laterHolds.incrementAndGet()
                RowRelease { error("the later posting row cannot own the old response") }
            }
        }

        suspend fun assertBilledOnce(next: TurnOutcome.Success) {
            val usage = withTimeout(1_500) { released.await() }
            assertNotNull(usage, "the already-posted source must reach its usage terminal")
            assertEquals(7L, checkNotNull(usage).outputTokens)
            assertEquals(100L, usage.inputTokens)
            assertEquals(5L, next.usage.outputTokens, "the later step bills only its own response")
            assertEquals(0L, next.usage.absorbed.outputTokens, "the later step must not absorb drained usage")
            assertEquals(1, holds.get())
            assertEquals(1, releases.get(), "the original posting row receives terminal usage exactly once")
            assertEquals(0, laterHolds.get(), "no later posting row owns this source")
        }
    }

    private suspend fun disposeAfterSource(disposition: SourceDisposition, reportedBeforeDispose: Boolean) {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val generated = GatedPost(sink)
        val rows = PostingRows(generated)
        val user = Json.parseToJsonElement("""{"role":"user","content":"synthetic request"}""")
        val input = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray + user
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonObject(mapOf("input" to JsonArray(input))).toString(), sink, rows.original)
            val callback = withTimeout(1_500) { sink.callback.await() }
            val round = retainedRound(manager)
            if (reportedBeforeDispose) {
                finishSource(generated)
                withTimeout(1_500) { rows.released.await() }
            }
            val callbackItems = Json.parseToJsonElement(history(listOf(callback))).jsonObject
                .getValue("input").jsonArray.drop(1)
            val changed = JsonObject(mapOf("input" to JsonArray(changedHistory(input, callbackItems, disposition))))
            val answering = if (disposition == SourceDisposition.SUPERSEDED) turn() else turn(callback.id, "result-0")
            val next = withTimeout(1_500) {
                manager.interceptor(answering, disableParallel = false)
                    .intercept(changed.toString(), RecordingSink(), rows.later)
            } as TurnOutcome.Success
            if (!reportedBeforeDispose) {
                assertFalse(generated.stopped.isCompleted, "the next step must finish while the old reader is open")
                assertFalse(rows.released.isCompleted, "the original posting row remains owed its open response")
            }
            val disposed = stateFiles.records().single()
            val captured = round.source.text
            assertDisposition(disposition)
            if (!reportedBeforeDispose) finishSource(generated)
            rows.assertBilledOnce(next)
            assertEquals(captured, round.source.text, "disposed capture must not retain even unstaged source fragments")
            assertNull(round.localFailure, "skipped capture must not turn a disposed response into an internal failure")
            assertDrained(disposed, runtime, generated)
        } finally {
            manager.onHeadStop()
        }
    }

    /** Observe the real retained reader, including in-memory capture that has not crossed a durable boundary. */
    private fun retainedRound(manager: CodexCodeModeBridge): CodeModeLiveRound {
        val field = CodexCodeModeBridge::class.java.getDeclaredField("registry").apply { isAccessible = true }
        val registry = field.get(manager) as CodexCodeModeRegistry
        val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
        val lease = checkNotNull(registry.recordsFor(key).single().sourceEnd)
        return lease.javaClass.getDeclaredField("round").apply { isAccessible = true }.get(lease) as CodeModeLiveRound
    }

    private fun assertDisposition(disposition: SourceDisposition) {
        val expected = when (disposition) {
            SourceDisposition.NATIVE -> "native discovery history was edited"
            SourceDisposition.STEERING -> "interrupted extra=STEERING"
            SourceDisposition.SUPERSEDED -> "parked program was superseded"
        }
        assertTrue(logLines.any { expected in it }, "the intended source disposition must be exercised")
    }

    private fun assertDrained(disposed: JsonObject, runtime: IncrementalRuntime, generated: GatedPost) {
        val drained = stateFiles.records().single()
        assertEquals(disposed["source"], drained["source"], "disposed capture must skip every remaining fragment")
        assertEquals(disposed["outer"], drained["outer"], "disposed capture must not install the terminal call")
        assertEquals(disposed["error"], drained["error"], "draining must not replace the execution disposition")
        assertEquals(disposed["phase"], drained["phase"])
        assertEquals(1, runtime.starts, "disposed execution must never rerun source")
        assertEquals(1, runtime.delivered.size, "only the first exposed statement executed")
        assertEquals(2, generated.posts, "only the original source and replacement history were posted")
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
