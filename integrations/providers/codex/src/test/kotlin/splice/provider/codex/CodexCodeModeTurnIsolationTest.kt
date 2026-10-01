// NEW: different conversation keys never queue behind a whole upstream drive on a shared hash stripe.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
