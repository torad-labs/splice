// NEW: different conversation keys never queue behind a whole upstream drive on a shared hash stripe.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.upstream.RedirectableRoundPost
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

@OptIn(ExperimentalCoroutinesApi::class)
internal class CodexCodeModeTurnIsolationTest : CodeModeBridgeTestSupport() {
    @Test
    fun `hash-colliding conversation progresses while the other upstream post is blocked`() = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            manager.interceptor(turn(sessionId = "synthetic-session-1"), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) {
                    entered.complete(Unit)
                    release.await()
                    completedOutcome()
                }
        }
        entered.await()
        val second = async {
            manager.interceptor(turn(sessionId = "synthetic-session-6"), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        }
        runCurrent()
        val independent = second.isCompleted
        release.complete(Unit)
        first.await()
        second.await()
        assertTrue(independent, "the synthetic keys share old stripe 57, but not a conversation")
    }

    @Test
    fun `reverse hash collision leaves the first conversation independent too`() = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            manager.interceptor(turn(sessionId = "synthetic-session-6"), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) {
                    entered.complete(Unit)
                    release.await()
                    completedOutcome()
                }
        }
        entered.await()
        val second = async {
            manager.interceptor(turn(sessionId = "synthetic-session-1"), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { completedOutcome() }
        }
        runCurrent()
        val independent = second.isCompleted
        release.complete(Unit)
        first.await()
        second.await()
        assertTrue(independent)
    }

    @Test
    fun `same session changed history can start while another instance is starting`() = runBlocking {
        overlappingInstances(starting = true)
    }

    @Test
    fun `same session changed model can advance while another instance is borrowed`() = runBlocking {
        overlappingInstances(starting = false)
    }

    private suspend fun overlappingInstances(starting: Boolean) = kotlinx.coroutines.coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = OverlapRuntime(starting, entered, release)
        val manager = bridge(runtime)
        val first = async {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink(), streamed("synthetic-first"))
        }
        try {
            withTimeout(10_000) { entered.await() }
            val other = if (starting) {
                turn().copy(conversationKey = "synthetic-other-history")
            } else {
                turn(model = "gpt-6-sol")
            }
            val second = withTimeout(10_000) {
                manager.interceptor(other, disableParallel = false)
                    .intercept(BASE_REQUEST, RecordingSink(), streamed("synthetic-second"))
            }
            assertTrue(second is TurnOutcome.Success, second.toString())
            assertEquals(2, runtime.starts, "both independent instances must dispatch once before the first releases")
            assertFalse(first.isCompleted, "the second must not terminate or serialize behind the first")
        } finally {
            release.complete(Unit)
            first.await()
            manager.onHeadStop()
        }
    }

    @Test
    fun `another conversation in the same session never retires a parked sibling without pressure`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("synthetic-a", "Read"))))),
                    ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("synthetic-b", "Read"))))),
                ),
            ),
        )
        val manager = bridge(runtime)
        try {
            manager.interceptor(turn(), outer("synthetic-first"), false)
                .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("synthetic-first") }
            val second = manager.interceptor(
                turn().copy(conversationKey = "synthetic-sibling"),
                outer("synthetic-second"),
                false,
            ).intercept(BASE_REQUEST, RecordingSink()) { outerOutcome("synthetic-second") }
            assertTrue(second is TurnOutcome.Success && second.hasToolUse)
            assertEquals(2, runtime.starts)
            assertTrue(runtime.cells.none { it.closed }, "an independent instance does not prove supersession")
        } finally {
            manager.onHeadStop()
        }
    }

    private fun streamed(id: String): RedirectableRoundPost = object : RedirectableRoundPost {
        private var posted = false
        override suspend fun invoke(bodyJson: String): TurnOutcome = error("redirected stream required")

        override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome {
            if (posted) return completedOutcome()
            posted = true
            val call = outer(id)
            sink.customToolSource(CustomToolSource.Started(call.copy(input = "")))
            sink.customToolSource(CustomToolSource.Completed(call))
            return outerOutcome(id)
        }
    }

    private class OverlapRuntime(
        private val starting: Boolean,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : CodeModeRuntime {
        var starts = 0
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            val first = starts++ == 0
            if (first && starting) {
                entered.complete(Unit)
                release.await()
            }
            return object : CodeModeCell {
                override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                    if (first && !starting) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return CodeModeStep.Completed("synthetic done")
                }
                override fun close() = Unit
            }
        }
        override fun close() = Unit
    }

    @Test
    fun `cancelled same-key waiter never replaces a lock still owned by another turn`() = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
                entered.complete(Unit)
                release.await()
                completedOutcome()
            }
        }
        entered.await()
        val cancelled = async {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
                error("cancelled waiter never posts")
            }
        }
        runCurrent()
        cancelled.cancelAndJoin()
        val next = async {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
                completedOutcome()
            }
        }
        runCurrent()
        assertFalse(next.isCompleted, "a cancelled queued caller cannot split the same-key lock")
        release.complete(Unit)
        first.await()
        next.await()
        val afterRelease = async {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
                completedOutcome()
            }
        }
        runCurrent()
        assertTrue(afterRelease.isCompleted, "last-user cleanup cannot strand a future turn")
        afterRelease.await()
    }
}
