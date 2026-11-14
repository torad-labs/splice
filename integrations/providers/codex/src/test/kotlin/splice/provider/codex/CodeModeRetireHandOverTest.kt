// NEW: #525 (2026-11-12): a ChatGPT head that runs code mode finishes the answer it is giving at a restart, beside the
// next daemon, and the conversation's next request, which the next daemon answers, carries what that answer recorded.
// 2026-11-13: a conversation at rest at the restart is one the next daemon takes from the retiring one's list, its
// journal unread until that conversation's next request, which carries the script all the same.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `a script finished before the restart is in that conversation's next request to the next daemon`() = runTest {
        val steps = listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))
        // The default retention, as a head runs: conversations may wait on the disk alone.
        val retiring = bridge(ScriptedRuntime(ArrayDeque(steps)), retention = CodeModeRetention())
        val first = RecordingSink()
        retiring.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, first) { RoundResult.Outcome(outerOutcome()) }
        val id = first.tools.single().id
        retiring.interceptor(turn(id, "X"), disableParallel = false)
            .intercept(requestWithResult(id, "X"), RecordingSink()) { RoundResult.Outcome(completedOutcome()) }
        retiring.onHeadRetire()
        retiring.onProviderStop()

        val next = bridge(QueuedRuntime(ArrayDeque()), retention = CodeModeRetention())
        var sent = ""
        val asked = requestWithResult(id, "X").removeSuffix("]}") + """,{"role":"user","content":"Next."}]}"""
        next.interceptor(turn(), disableParallel = false).intercept(asked, RecordingSink()) {
            sent = it
            RoundResult.Outcome(completedOutcome())
        }
        assertTrue("outer-call" in sent, "the script the retiring daemon ran is in the next request: $sent")
        assertFalse(id in sent, "the call that script made of the client stays out of it: $sent")
        next.onHeadStop()
    }

    @Test
    fun `a script the restart stopped is told to that conversation's next request to the next daemon`() = runTest {
        // The script asked its client for a read and waits for the answer when the daemon retires. No turn of the
        // conversation is answering, so the retiring daemon lets it go, and the script is lost with its process.
        val steps = listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))
        val retiring = bridge(ScriptedRuntime(ArrayDeque(steps)), retention = CodeModeRetention())
        val first = RecordingSink()
        retiring.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, first) { RoundResult.Outcome(outerOutcome()) }
        val id = first.tools.single().id
        retiring.onHeadRetire()
        retiring.onProviderStop()

        val next = bridge(QueuedRuntime(ArrayDeque()), retention = CodeModeRetention())
        assertTrue(logLines.any { "1 conversation(s) taken from the retiring daemon's list" in it }, "$logLines")
        var sent = ""
        next.interceptor(turn(id, "X"), disableParallel = false)
            .intercept(requestWithResult(id, "X"), RecordingSink()) {
                sent = it
                RoundResult.Outcome(completedOutcome())
            }
        assertTrue("source was not rerun" in sent, "the model is told its script stopped and was not rerun: $sent")
        next.onHeadStop()
    }
}
