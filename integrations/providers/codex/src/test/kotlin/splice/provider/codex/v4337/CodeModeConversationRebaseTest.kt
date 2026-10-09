// NEW: V4-337 — a conversation that alone reached a bound lets its finished records go at the start of
// its turn, before that turn's history is built. Its scripts so far stay in its history as the ordinary
// tool calls the client saw, a script it starts in that turn is measured on that history and places on
// the next one, and nothing is abandoned, skipped or refused. Letting them go at the new script's
// admission instead would have left its baseline on canonical items that no longer exist.
package splice.provider.codex.v4337

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.BASE_REQUEST
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodeModeRetention
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.turn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

class CodeModeConversationRebaseTest : CodeModeBridgeTestSupport() {

    /** What three scripts in one conversation left: each script's start body, the fourth turn's post,
     *  and the client ids of the scripts' Read calls. */
    private data class Run(val starts: List<String>, val finalPost: String, val ids: List<String>)

    private val runtime = QueuedRuntime(
        ArrayDeque(
            (0..2).map { n ->
                ArrayDeque(
                    listOf(CodeModeStep.Calls(listOf(call("runtime-$n", "Read"))), CodeModeStep.Completed("done-$n")),
                )
            },
        ),
    )

    private fun history(items: List<String>) =
        """{"input":[{"role":"developer","content":"s"},${items.joinToString(",")}]}"""

    private fun read(id: String) =
        """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$id","output":"R"}"""

    private fun said(text: String) = """{"role":"user","content":"$text"}"""

    private fun results(ids: List<String>) = turn(results = ids.map { CodeModeResult(it, "R") })

    /** Three scripts in one conversation, one Read each, a user message before the second and third;
     *  script n's source is [sources][n]. */
    private suspend fun threeScripts(bridge: CodexCodeModeBridge, sources: List<String>): Run {
        val ids = mutableListOf<String>()
        val items = mutableListOf<String>()
        val starts = mutableListOf<String>()
        sources.forEachIndexed { n, source ->
            if (n > 0) items += said("script $n")
            val sink = RecordingSink()
            val started = bridge.interceptor(results(ids), null, disableParallel = false)
                .intercept(if (n == 0) BASE_REQUEST else history(items), sink) { body ->
                    starts += body
                    RoundResult.Outcome(
                        TurnOutcome.Success(false, false, Usage(), customCalls = listOf(outer("outer-$n", source))),
                    )
                }.turn()
            assertTrue((started as TurnOutcome.Success).hasToolUse, "script $n never reached its Read: $started")
            ids += sink.tools.single().id
            items += read(ids.last())
            val done = bridge.interceptor(results(ids), null, disableParallel = false)
                .intercept(history(items), RecordingSink()) { RoundResult.Outcome(completedOutcome()) }.turn()
            assertTrue(done is TurnOutcome.Success, "script $n did not complete: $done")
        }
        var finalPost = ""
        val last = bridge.interceptor(results(ids), null, disableParallel = false)
            .intercept(history(items + said("thanks")), RecordingSink()) { body ->
                finalPost = body
                RoundResult.Outcome(completedOutcome())
            }.turn()
        assertTrue(last is TurnOutcome.Success, "the turn after the third script failed: $last")
        return Run(starts, finalPost, ids)
    }

    /** The first two scripts went at the third's turn, the third placed on the next, nothing broke. */
    private fun assertStartedOver(run: Run) {
        assertEquals(3, runtime.starts, "a script was refused or rerun")
        assertTrue("outer-0" in run.starts[1], "the first script never placed, so nothing was tested")
        listOf(run.starts[2], run.finalPost).forEach { body ->
            assertFalse("outer-0" in body || "outer-1" in body, "a let-go script was still placed: $body")
            assertTrue(run.ids[0] in body && run.ids[1] in body, "a let-go script's calls left the history")
        }
        assertEquals(2, Regex("outer-2").findAll(run.finalPost).count(), "the script after the let-go did not place")
        assertFalse(run.ids[2] in run.finalPost, "the placed script's call was also sent as an ordinary one")
        assertFalse(logLines.any { "abandoned record" in it || "history rewrite skipped" in it }, "$logLines")
    }

    @Test
    fun `a conversation at its record bound starts over at its turn, and the script it starts there places`() =
        runTest {
            val bridge = bridge(runtime, retention = CodeModeRetention(perConversation = 2))

            assertStartedOver(threeScripts(bridge, listOf("a", "b", "c")))
            assertEquals(1, logLines.count { "let go (it reached 2 records)" in it }, "$logLines")
        }

    @Test
    fun `a conversation alone past the head's bytes starts over at its turn, and the script it starts there places`() =
        runTest {
            val big = "x".repeat(10_000)
            val bridge = bridge(runtime, retention = CodeModeRetention(bytes = 30_000))

            assertStartedOver(threeScripts(bridge, listOf(big, big, "c")))
            assertEquals(1, logLines.count { "it alone holds more than 30000 bytes" in it }, "$logLines")
        }
}
