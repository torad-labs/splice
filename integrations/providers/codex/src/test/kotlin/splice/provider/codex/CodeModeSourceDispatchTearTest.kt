// NEW: source loss between runtime return and durable call issuance cannot emit another callback.
package splice.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome

class CodeModeSourceDispatchTearTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `a source torn during call validation cannot save or emit the buffered batch`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val firstSink = StepSink()
        val post = GatedPost(firstSink)
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, firstSink, post)
            val first = firstSink.callback.await()
            val continuation = turn(first.id, "result-0")
            val tools = object : Set<String> by continuation.tools {
                override fun contains(element: String): Boolean {
                    if (element == "Edit") {
                        post.tearAfterFirst = true
                        post.gates[2].complete(Unit)
                        runBlocking {
                            withTimeout(5_000) {
                                while (!stateFiles.records().single().toString().contains("LOST")) yield()
                            }
                        }
                    }
                    return element in continuation.tools
                }
            }
            post.gates[1].complete(Unit)
            val next = StepSink()
            val outcome = manager.interceptor(continuation.copy(tools = tools), disableParallel = false)
                .intercept(history(listOf(first)), next, post).turn() as TurnOutcome.Failure
            assertEquals(FailureCause.UPSTREAM_CONN_RESET, outcome.cause)
            assertFalse(next.callback.isCompleted)
            assertEquals(1, runtime.starts)
            assertEquals(1, post.posts)
        } finally {
            manager.onHeadStop()
        }
    }
}
