// NEW: a conversation that alone reached a bound lets its finished records go at the start of
// its turn, before that turn's history is built. What GPT was already sent stays as it was sent, the way
// Codex builds each request from what it recorded (codex-rs core/src/session/turn.rs:1618): the let-go
// scripts stay their exec calls and outputs, a script started in that turn places on the next, each later
// post extends the last, and nothing is abandoned, skipped or refused. Until 2026-10-23 the let-go scripts
// went back to the client's ordinary tool calls, which rewrote the cached prefix; that rule is replaced.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
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
                        TurnOutcome.Success(
                            false,
                            false,
                            Usage(),
                            handoffs = RoundHandoffs(customCalls = listOf(outer("outer-$n", source))),
                        ),
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

    /** The let-go scripts stay as GPT was sent them, the third places on the next post, and the posts after the
     *  let-go only extend what was sent. */
    private fun assertKeptAsSent(run: Run) {
        assertEquals(3, runtime.starts, "a script was refused or rerun")
        assertTrue("outer-0" in run.starts[1], "the first script never placed, so nothing was tested")
        listOf(run.starts[2], run.finalPost).forEach { body ->
            assertTrue("outer-0" in body && "outer-1" in body, "a let-go script left what GPT was sent: $body")
            assertFalse(run.ids[0] in body || run.ids[1] in body, "a let-go script's calls came back: $body")
        }
        val sent = input(run.starts[2])
        assertEquals(sent, input(run.finalPost).take(sent.size), "the post after the let-go rewrote what was sent")
        assertEquals(2, Regex("outer-2").findAll(run.finalPost).count(), "the script after the let-go did not place")
        assertFalse(run.ids[2] in run.finalPost, "the placed script's call was also sent as an ordinary one")
        assertFalse(logLines.any { "abandoned record" in it || "history rewrite skipped" in it }, "$logLines")
    }

    private fun input(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.toList()

    @Test
    fun `a conversation at its record bound keeps what GPT was sent, and the script it starts there places`() =
        runTest {
            val bridge = bridge(runtime, retention = CodeModeRetention(perConversation = 2))

            assertKeptAsSent(threeScripts(bridge, listOf("a", "b", "c")))
            assertEquals(1, logLines.count { "let go (it reached 2 records)" in it }, "$logLines")
        }

    @Test
    fun `a conversation alone past the head's bytes keeps what GPT was sent, and the script it starts there places`() =
        runTest {
            val big = "x".repeat(10_000)
            val bridge = bridge(runtime, retention = CodeModeRetention(bytes = 30_000))

            assertKeptAsSent(threeScripts(bridge, listOf(big, big, "c")))
            assertEquals(1, logLines.count { "it alone holds more than 30000 bytes" in it }, "$logLines")
        }
}
