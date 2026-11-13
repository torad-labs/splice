// NEW: #525 (2026-11-12): a ChatGPT head that runs code mode finishes the answer it is giving at a restart, beside the
// next daemon, and the conversation's next request, which the next daemon answers, carries what that answer recorded.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

internal class CodeModeRetireHandOverTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a script turn the retiring daemon finishes beside the next one is in that conversation's next request`() =
        runTest {
            val runtime = ScriptedRuntime(
                ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))),
            )
            val retiring = bridge(runtime)
            val answering = CompletableDeferred<Unit>()
            val upstreamAnswers = CompletableDeferred<Unit>()
            val first = RecordingSink()
            val kept = async {
                retiring.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, first) {
                    answering.complete(Unit)
                    upstreamAnswers.await()
                    RoundResult.Outcome(outerOutcome())
                }
            }
            answering.await()
            retiring.onHeadRetire()
            // The hand-over ends the daemon's probe scope right after the heads retire, which is the provider's stop.
            retiring.onProviderStop()
            // The next daemon starts while the retiring one is still answering that turn.
            val next = bridge(QueuedRuntime(ArrayDeque()))
            upstreamAnswers.complete(Unit)
            kept.await()
            val id = first.tools.single().id

            var sent = ""
            next.interceptor(turn(id, "X"), disableParallel = false)
                .intercept(requestWithResult(id, "X"), RecordingSink()) {
                    sent = it
                    RoundResult.Outcome(completedOutcome())
                }
            assertTrue("outer-call" in sent, "the script the retiring daemon ran is in the next request: $sent")
            assertTrue("source was not rerun" in sent, "the script parked at the hand-over is told once as lost: $sent")
            next.onHeadStop()
            retiring.onHeadStop()
        }
}
