package splice.provider.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.TurnOutcome

class CodexCodeModeSourceTerminalTest : CodeModeStatementStreamSupport() {
    @ParameterizedTest
    @ValueSource(strings = ["changed-prefix", "changed-id", "changed-name", "changed-item", "incomplete"])
    @Timeout(20)
    fun `terminal source corruption never releases a completed executable suffix`(problem: String) = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        post.terminalProblem = problem
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            post.gates.drop(1).forEach { it.complete(Unit) }
            withTimeout(1_500) { post.sent.last().await() }
            post.complete.complete(Unit)
            withTimeout(1_500) { post.stopped.await() }
            val next = StepSink()
            val outcome = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), next, post)
            assertFalse(next.callback.isCompleted, "invalid terminal source must not execute a later Edit")
            assertTrue(outcome is TurnOutcome.Success || outcome is TurnOutcome.Failure)
            assertEquals(1, runtime.starts)
            assertFalse(stateFiles.records().single()["sourceState"].toString().contains("\"complete\":true"))
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `item completion before response completion cannot finalize source or upstream billing`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            post.gates.drop(1).forEach { it.complete(Unit) }
            withTimeout(1_500) { post.itemDone.await() }
            assertSourcePending()
            assertFalse(post.stopped.isCompleted)
            post.complete.complete(Unit)
            withTimeout(1_500) { post.stopped.await() }
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a genuinely cancelled client-result request cancels its step and never advances later source`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            val resumed = async {
                manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                    .intercept(history(listOf(first)), StepSink(), post)
            }
            withTimeout(1_500) { while (runtime.delivered.size < 2) kotlinx.coroutines.yield() }
            resumed.cancel()
            resumed.join()
            assertTrue(resumed.isCancelled)
            withTimeout(1_500) { post.stopped.await() }
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `whole source with no early statement stays gated until the real response terminal`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        post.wholeOnly = true
        try {
            val request = async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            }
            withTimeout(1_500) { post.itemDone.await() }
            assertFalse(request.isCompleted)
            assertFalse(sink.callback.isCompleted)
            post.complete.complete(Unit)
            val outcome = withTimeout(1_500) { request.await() } as TurnOutcome.Success
            assertBilling(outcome.usage)
            assertEquals(1, runtime.starts)
            assertEquals(2, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `a streamed continuation cannot reissue an already completed outer call`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val sink = StepSink()
        val post = GatedPost(sink)
        post.wholeOnly = true
        post.repeatOuter = true
        post.complete.complete(Unit)
        try {
            val outcome = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            assertTrue(outcome is TurnOutcome.Failure, outcome.toString())
            assertEquals(1, runtime.starts, "a duplicate outer cannot dispatch a second runtime")
            assertEquals(2, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `expiring a parked source disposes its reader and cannot recreate the expired record`() = runBlocking {
        val runtime = IncrementalRuntime()
        val clock = MutableClock(1_000)
        val manager = bridge(runtime, ttl = kotlin.time.Duration.parse("1s"), clock = clock)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            clock.now += 2_000
            sweepOwnHistory(manager)
            withTimeout(1_500) { post.stopped.await() }
            assertTrue(stateFiles.records().isEmpty())
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(20)
    fun `poisoning a live source at its explicit round bound cancels the reader and cannot dispatch twice`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime, maxRounds = 1)
        val sink = StepSink()
        val post = GatedPost(sink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post)
            val first = sink.callback.await()
            val outcome = manager.interceptor(turn(first.id, "result-0"), disableParallel = false)
                .intercept(history(listOf(first)), StepSink(), post)
            assertTrue(outcome is TurnOutcome.Failure)
            withTimeout(1_500) { post.stopped.await() }
            assertFalse(manager.interceptor(turn(first.id, "result-0"), disableParallel = false).resumesSource())
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }
}
