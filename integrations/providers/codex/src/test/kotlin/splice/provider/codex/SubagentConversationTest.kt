// Two Claude Code subagents of one session often open with identical words: the same CLAUDE.md context comes first in
// each. They are two conversations all the same, the way codex keeps one thread per conversation, so neither waits on
// the other's turn, and neither is ever served the other's script.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

private const val OPENING = """{"role":"developer","content":"s"}"""

class SubagentConversationTest : CodeModeBridgeTestSupport() {

    @Test
    fun `two subagents that open with the same words run their turns at once`() = runTest {
        val manager = bridge(QueuedRuntime(ArrayDeque(listOf(script(), script()))))
        val opening = body(listOf(OPENING, user("start")))
        val firstPosted = CompletableDeferred<Unit>()
        val firstAnswered = CompletableDeferred<Unit>()
        val first = async {
            manager.interceptor(ofAgent(turn(), "agent-a"), outer(), disableParallel = false)
                .intercept(opening, RecordingSink()) {
                    firstPosted.complete(Unit)
                    firstAnswered.await()
                    RoundResult.Outcome(outerOutcome())
                }
        }
        firstPosted.await()
        val secondPosted = CompletableDeferred<Unit>()
        val second = async {
            manager.interceptor(ofAgent(turn(), "agent-b"), outer(), disableParallel = false)
                .intercept(opening, RecordingSink()) {
                    secondPosted.complete(Unit)
                    RoundResult.Outcome(outerOutcome())
                }
        }
        testScheduler.advanceUntilIdle()
        val ranAtOnce = secondPosted.isCompleted
        firstAnswered.complete(Unit)
        first.await()
        second.await()
        assertTrue(ranAtOnce, "the second subagent's turn went upstream while the first one's was still answering")
    }

    @Test
    fun `a subagent is never served another subagent's script, and each resumes its own`() = runTest {
        val runtime = QueuedRuntime(ArrayDeque(listOf(script(), script())))
        val manager = bridge(runtime)
        val opening = body(listOf(OPENING, user("start")))
        val firstSink = RecordingSink()
        manager.interceptor(ofAgent(turn(), "agent-a"), outer(), disableParallel = false)
            .intercept(opening, firstSink) { RoundResult.Outcome(outerOutcome()) }
        val firstId = firstSink.tools.single().id

        val secondSink = RecordingSink()
        var secondPosted = false
        manager.interceptor(ofAgent(turn(), "agent-b"), outer(), disableParallel = false)
            .intercept(opening, secondSink) {
                secondPosted = true
                RoundResult.Outcome(outerOutcome())
            }
        val secondId = secondSink.tools.single().id
        assertTrue(secondPosted, "the second subagent's request went upstream instead of replaying the first's step")
        assertNotEquals(firstId, secondId, "each subagent got a callback of its own script")

        val resumed = RecordingSink()
        manager.interceptor(ofAgent(turn(firstId, "A"), "agent-a"), disableParallel = false)
            .intercept(body(listOf(OPENING, user("start"), read(firstId), output(firstId, "A"))), resumed) {
                RoundResult.Outcome(completedOutcome())
            }
        assertEquals(2, runtime.cells[0].advances, "the first subagent's script advanced on its own callback")
        assertEquals(1, runtime.cells[1].advances, "the second subagent's script stayed parked")
        assertTrue(logLines.none { "abandoned record" in it }, logLines.joinToString("\n"))
    }

    /** [turn] as the head builds it for a request headed with [agentId]. */
    private fun ofAgent(turn: CodexCodeModeBridge.Turn, agentId: String) =
        turn.copy(conversationKey = CodeModeTurnIdentity().conversationKey(turn.conversationKey, agentId))

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
