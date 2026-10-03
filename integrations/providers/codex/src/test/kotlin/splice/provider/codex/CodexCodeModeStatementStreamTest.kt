package splice.provider.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.SpliceNotice
import splice.core.turn.TurnOutcome
import splice.upstream.codemode.CodeModeResult

class CodexCodeModeStatementStreamTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `three callbacks precede completion and results stay on the same live round`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sinks = List(3) { StepSink() }
        val post = GatedPost(sinks.first())
        val callbacks = mutableListOf<SeenTool>()
        try {
            for (step in sinks.indices) {
                val results = callbacks.mapIndexed { index, call -> CodeModeResult(call.id, "result-$index") }
                val request = async {
                    manager.interceptor(turn(results = results), disableParallel = false)
                        .intercept(history(callbacks), sinks[step], post)
                }
                if (step > 0) post.gates[step].complete(Unit)
                val callback = withTimeout(1_500) { sinks[step].callback.await() }
                callbacks += callback
                val outcome = withTimeout(1_500) { request.await() } as TurnOutcome.Success
                assertTrue(outcome.hasToolUse)
                assertEquals(0L, outcome.usage.outputTokens)
                assertFalse(post.complete.isCompleted, "tool callback must precede response.completed")
                assertEquals(1, post.posts, "results must not POST merely to receive another statement")
                assertTrue(stateFiles.records().single().toString().contains(callback.id), "callback must be durable")
                assertTrue(sinks[step].progress.isNotEmpty(), "each attached client sees source progress")
                assertTrue(SpliceNotice.SIGNATURE in sinks[step].signatures, "progress closes with its replay marker")
            }
            assertSourcePending()
            val final = async {
                manager.interceptor(
                    turn(results = callbacks.mapIndexed { index, call -> CodeModeResult(call.id, "result-$index") }),
                    disableParallel = false,
                ).intercept(history(callbacks), StepSink(), post)
            }
            post.complete.complete(Unit)
            val outcome = withTimeout(1_500) { final.await() } as TurnOutcome.Success
            assertEquals(2, post.posts, "only script completion permits a continuation POST")
            assertBilling(outcome.usage)
            assertEquals(
                listOf("result-0", "result-1", "result-2"),
                runtime.delivered.flatten().map(CodeModeResult::output),
            )
            assertFinalIdentity(post, outcome)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `source written while detached is delivered to the next client without stalling its reader`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val firstSink = StepSink()
        val post = GatedPost(firstSink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            val first = firstSink.callback.await()
            post.gates[1].complete(Unit)
            withTimeout(1_500) { post.sent[1].await() }
            val second = StepSink()
            manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), second, post)
            assertTrue(second.progress.toString().contains("Waiting"))
            assertTrue(SpliceNotice.SIGNATURE in second.signatures)
            assertEquals(1, post.posts)
            assertEquals(1, runtime.starts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `typed failed boot reopens the consumed source cursor without another POST`() = runBlocking {
        val runtime = IncrementalRuntime(failFirst = true)
        val manager = bridge(runtime)
        val firstSink = StepSink()
        val post = GatedPost(firstSink)
        try {
            val failed = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            assertTrue(failed is TurnOutcome.Failure)
            assertEquals("STARTING", stateFiles.records().single().getValue("phase").jsonPrimitive.content)
            val secondSink = StepSink()
            val retried = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, secondSink, post)
            assertTrue(retried is TurnOutcome.Success, retried.toString())
            assertTrue((retried as TurnOutcome.Success).hasToolUse)
            assertEquals("Read", secondSink.callback.await().name)
            assertEquals(2, runtime.starts)
            assertEquals(1, post.posts)
            assertEquals(runtime.firstReads[0], runtime.firstReads[1], "failed-open consumption is not dispatch")
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a torn or restarted partial source never starts again`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
        val callback = sink.callback.await()
        manager.onHeadStop()
        val restored = bridge(runtime)
        try {
            var continued = ""
            restored.interceptor(turn(callback.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(callback)), RecordingSink()) {
                    continued = it
                    completedOutcome()
                }
            assertEquals(1, runtime.starts)
            assertTrue(continued.contains("source was not rerun"))
            assertTrue(continued.contains("result-0"))
        } finally {
            restored.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a failed source save cannot publish executable bytes`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            stateFiles.block()
            post.gates[1].complete(Unit)
            withTimeout(1_500) { post.sent[1].await() }
            // Buffered producer bytes need not write; keep disk blocked through the executable read boundary.
            val next = StepSink()
            val outcome = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), next, post)
            assertTrue(outcome is TurnOutcome.Failure, outcome.toString())
            assertFalse(next.callback.isCompleted, "uncommitted source must never issue its Edit call")
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            stateFiles.unblock()
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `callback save failure prevents publication and retries captured calls without execution`() = runBlocking {
        val runtime = IncrementalRuntime(failCallSave = true)
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            val failed = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            assertTrue(failed is TurnOutcome.Failure)
            assertFalse(sink.callback.isCompleted)
            stateFiles.unblock()
            val retriedSink = StepSink()
            val retried = manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, retriedSink, post)
            assertTrue(retried is TurnOutcome.Success, retried.toString())
            assertTrue(retriedSink.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertEquals(1, runtime.delivered.size)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `failed result batch exposes no callback and retries the captured live worker step once`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            stateFiles.block()
            post.gates[1].complete(Unit)
            val failedSink = StepSink()
            val failed = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), failedSink, post)
            assertTrue(failed is TurnOutcome.Failure)
            assertFalse(failedSink.callback.isCompleted, "no callback can escape a failed batch commit")
            assertEquals(listOf("result-0"), runtime.delivered.flatten().map(CodeModeResult::output))
            stateFiles.unblock()
            val resumed = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), StepSink(), post)
            assertTrue(resumed is TurnOutcome.Success, resumed.toString())
            assertEquals(listOf("result-0"), runtime.delivered.flatten().map(CodeModeResult::output))
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a transport tear after statement one cannot execute another statement or rerun its source`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val firstSink = StepSink()
        val post = GatedPost(firstSink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            val first = firstSink.callback.await()
            post.tearAfterFirst = true
            post.gates[1].complete(Unit)
            withTimeout(1_500) { post.stopped.await() }
            val next = StepSink()
            manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), next, post)
            assertFalse(next.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertTrue(post.continuation.contains("source was not rerun"))
            assertTrue(post.continuation.contains("result-0"))
        } finally {
            manager.onHeadStop()
        }
    }

    /** Oct 2: a ConcurrentModificationException out of the record's save killed the reader, which none of its
     *  catches names. The source had no terminal, so the next step's cell read it forever and the session
     *  hung with nothing logged. Any throwable that ends the reader now fails the source and is named. */
    @Test
    @Timeout(20)
    fun `a reader that dies on an unnamed throwable fails its source instead of hanging the script`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val firstSink = StepSink()
        val post = GatedPost(firstSink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            val first = firstSink.callback.await()
            post.dieAfterFirst = true
            post.gates[1].complete(Unit)
            withTimeout(1_500) { post.stopped.await() }
            val next = StepSink()
            withTimeout(5_000) {
                manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), next, post)
            }
            assertFalse(next.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertTrue(post.continuation.contains("source was not rerun"))
            assertTrue(logLines.any { "ConcurrentModificationException" in it }, logLines.toString())
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `steering interrupts a live source and still continues without swallowing client cancellation`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val firstSink = StepSink()
        val post = GatedPost(firstSink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            val first = firstSink.callback.await()
            val body = Json.parseToJsonElement(history(listOf(first))).jsonObject
            val input = body.getValue("input").jsonArray + JsonObject(
                mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("stop this script")),
            )
            val outcome = withTimeout(1_500) {
                manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(JsonObject(mapOf("input" to JsonArray(input))).toString(), StepSink(), post)
            }
            assertTrue(outcome is TurnOutcome.Success, outcome.toString())
            withTimeout(1_500) { post.stopped.await() }
            assertEquals(1, runtime.starts)
            assertTrue(post.continuation.contains("stop this script"))
            assertTrue(post.continuation.contains("additional client content arrived"))
        } finally {
            manager.onHeadStop()
        }
    }
}
