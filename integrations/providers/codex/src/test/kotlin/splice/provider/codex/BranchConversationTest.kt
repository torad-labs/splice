// Claude Code can drive two branches of one agent's conversation at once: a message sent to a running subagent rides
// beside a tool result on one branch while the other branch goes on without it, and both keep going for many steps
// (claudex, Oct 26-27: about 940 of the waits of 5 s or more on the code-mode turn lock). Codex keeps one thread per
// conversation and a fork is a thread of its own, so a branch never waits on its sibling's turn. Two requests on one
// branch still take turns, and a branch's script resumes on its own branch's history, never on its sibling's.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

private const val OPENING = """{"role":"developer","content":"s"}"""
private const val SIDE_NOTE = "the coordinator sent a message while you were working"

class BranchConversationTest : CodeModeBridgeTestSupport() {

    @Test
    fun `two branches of one conversation run their turns at once`() = runTest {
        val manager = bridge(QueuedRuntime(ArrayDeque(listOf(script(), script()))))
        val main = listOf(OPENING, user("start"))
        val held = Held()
        val first = async {
            manager.interceptor(turn(), outer("outer-main"), disableParallel = false)
                .intercept(body(main), RecordingSink()) { held.answer { outerOutcome("outer-main") } }
        }
        held.posted.await()
        val sidePosted = CompletableDeferred<Unit>()
        val second = async {
            manager.interceptor(turn(), outer("outer-side"), disableParallel = false)
                .intercept(body(main + user(SIDE_NOTE)), RecordingSink()) {
                    sidePosted.complete(Unit)
                    RoundResult.Outcome(outerOutcome("outer-side"))
                }
        }
        testScheduler.advanceUntilIdle()
        val ranAtOnce = sidePosted.isCompleted
        held.release()
        first.await()
        second.await()
        assertTrue(ranAtOnce, "the side branch went upstream while the main branch was still answering")
    }

    @Test
    fun `two requests on one branch still take turns`() = runTest {
        val manager = bridge(QueuedRuntime(ArrayDeque(listOf(script(), script()))))
        val main = listOf(OPENING, user("start"))
        val held = Held()
        val first = async {
            manager.interceptor(turn(), outer(), disableParallel = false)
                .intercept(body(main), RecordingSink()) { held.answer { outerOutcome() } }
        }
        held.posted.await()
        val againPosted = CompletableDeferred<Unit>()
        val again = async {
            manager.interceptor(turn(), outer(), disableParallel = false)
                .intercept(body(main), RecordingSink()) {
                    againPosted.complete(Unit)
                    RoundResult.Outcome(outerOutcome())
                }
        }
        testScheduler.advanceUntilIdle()
        val ranAtOnce = againPosted.isCompleted
        held.release()
        first.await()
        again.await()
        assertFalse(ranAtOnce, "the same branch's second request waited for the first to finish")
    }

    @Test
    fun `each branch's script resumes on its own branch's history`() = runTest {
        val manager = bridge(QueuedRuntime(ArrayDeque(listOf(script(), script()))))
        val main = listOf(OPENING, user("start"))
        val side = main + user(SIDE_NOTE)
        val held = Held()
        val mainSink = RecordingSink()
        val first = async {
            manager.interceptor(turn(), outer("outer-main"), disableParallel = false)
                .intercept(body(main), mainSink) { held.answer { outerOutcome("outer-main") } }
        }
        held.posted.await()
        val sideSink = RecordingSink()
        val second = async {
            manager.interceptor(turn(), outer("outer-side"), disableParallel = false)
                .intercept(body(side), sideSink) { RoundResult.Outcome(outerOutcome("outer-side")) }
        }
        testScheduler.advanceUntilIdle()
        val ranAtOnce = second.isCompleted
        held.release()
        first.await()
        second.await()
        assertTrue(ranAtOnce, "the side branch ran while the main branch was still answering")

        val mainId = mainSink.tools.single().id
        val sideId = sideSink.tools.single().id
        var mainResumed = ""
        manager.interceptor(turn(mainId, "A"), disableParallel = false)
            .intercept(body(main + read(mainId) + output(mainId, "A")), RecordingSink()) { posted ->
                mainResumed = posted
                RoundResult.Outcome(completedOutcome())
            }
        var sideResumed = ""
        manager.interceptor(turn(sideId, "B"), disableParallel = false)
            .intercept(body(side + read(sideId) + output(sideId, "B")), RecordingSink()) { posted ->
                sideResumed = posted
                RoundResult.Outcome(completedOutcome())
            }
        assertFalse(SIDE_NOTE in mainResumed, "the main branch's script resumed on the side branch's history")
        assertTrue(SIDE_NOTE in sideResumed, "the side branch's script resumed on its own history")
        assertFalse("outer-main" in sideResumed, "the side branch's history holds none of the main branch's script")
    }

    /** An upstream answer that waits until the test lets it go, so a second request arrives while it is open. */
    private class Held {
        val posted = CompletableDeferred<Unit>()
        private val answered = CompletableDeferred<Unit>()

        suspend fun answer(outcome: () -> splice.core.turn.TurnOutcome): RoundResult {
            posted.complete(Unit)
            answered.await()
            return RoundResult.Outcome(outcome())
        }

        fun release() {
            answered.complete(Unit)
        }
    }

    private fun script() = ArrayDeque<CodeModeStep>(
        listOf(
            CodeModeStep.Calls(listOf(call("runtime-1", "Read"))),
            CodeModeStep.Completed("done"),
        ),
    )

    private fun body(items: List<String>): String = """{"input":[${items.joinToString(",")}]}"""

    private fun user(text: String): String = """{"role":"user","content":"$text"}"""

    private fun read(id: String): String = """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""

    private fun output(id: String, text: String): String =
        """{"type":"function_call_output","call_id":"$id","output":"$text"}"""
}
