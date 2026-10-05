package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.SpliceNotice
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeStep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CodexCodeModeStatementStreamTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `three callbacks precede completion and results stay on the same live round`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sinks = List(3) { StepSink() }
        val itemCompletion = CompletableDeferred<Unit>()
        val post = GatedPost(sinks.first()).apply { itemCompletionGate = itemCompletion }
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
                assertFalse(post.itemDone.isCompleted, "streaming callbacks must precede item completion")
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
            itemCompletion.complete(Unit)
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
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Timeout(20)
    fun `a reader that dies on an unnamed throwable fails its source instead of hanging the script`(
        afterLoss: Boolean,
    ) = runBlocking {
        val runtime = IncrementalRuntime()
        val death = ReaderDeathOrder(runtime)
        val manager = death.manager
        val firstSink = StepSink()
        val post = GatedPost(firstSink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            val first = firstSink.callback.await()
            post.dieAfterFirst = true
            post.gates[1].complete(Unit)
            death.beforeResume(afterLoss)
            val next = StepSink()
            val resumed = async {
                manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), next, post)
            }
            death.afterResume(afterLoss)
            val outcome = withTimeout(5_000) { resumed.await() }
            assertTrue(outcome is TurnOutcome.Success, "outcome=$outcome")
            assertEquals(2, post.posts)
            assertTrue(post.continuation.contains("source was not rerun"))
            val persisted = stateFiles.records().single()
            assertTrue(persisted.toString().contains("source was not rerun"))
            assertEquals(CodeModePhase.COMPLETED.name, persisted["phase"]?.jsonPrimitive?.content)
            assertEquals(if (afterLoss) 1 else 2, runtime.delivered.size)
            if (!afterLoss) assertEquals("result-0", runtime.delivered.last().single().output)
            assertFalse(next.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertTrue(logLines.any { "ConcurrentModificationException" in it }, logLines.toString())
        } finally {
            death.release.countDown()
            manager.onHeadStop()
        }
    }

    /** Stops death cleanup before source.fail, then places result advance on either side of persisted loss. */
    private inner class ReaderDeathOrder(runtime: IncrementalRuntime) {
        val release = CountDownLatch(1)
        private val dying = CompletableDeferred<Unit>()
        private val advancing = CompletableDeferred<Unit>()
        private val watched = object : CodeModeRuntime by runtime {
            override suspend fun startStreamingSession(
                sessionKey: String,
                source: CodeModeSource,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                val cell = runtime.startStreamingSession(sessionKey, source, tools, descriptions)
                return object : CodeModeCell by cell {
                    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                        if (results.isNotEmpty()) advancing.complete(Unit)
                        return cell.advance(results)
                    }
                }
            }
        }
        val manager = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                { watched },
                stateLocation(),
                log = LogSink { line ->
                    logLines += line
                    if ("upstream source reader died" in line) {
                        dying.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                },
            ),
        )

        suspend fun beforeResume(afterLoss: Boolean) {
            withTimeout(1_500) { dying.await() }
            if (!afterLoss) return
            release.countDown()
            withTimeout(1_500) {
                while (stateFiles.records().single()["phase"]?.jsonPrimitive?.content != CodeModePhase.LOST.name) {
                    yield()
                }
            }
        }

        suspend fun afterResume(afterLoss: Boolean) {
            if (afterLoss) return
            withTimeout(1_500) { advancing.await() }
            release.countDown()
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
            assertFalse(post.sent[1].isCompleted, "steering generates no unread source")
            assertEquals(1, runtime.starts)
            assertTrue(post.continuation.contains("stop this script"))
            assertTrue(post.continuation.contains("additional client content arrived"))
        } finally {
            manager.onHeadStop()
        }
    }
}
