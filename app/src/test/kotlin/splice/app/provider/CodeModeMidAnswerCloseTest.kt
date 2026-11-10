// Marcos, Nov 9 2026: "GPT responses on splice keeps dropping all the time". At 23:22 CT a gpt-6-astra answer on the
// websocket was closed by the backend with 1012 (service restart) 51 s in, after three reasoning summary parts and 766
// deltas of its final answer reached the client; the client was handed "Server error mid-response" and the answer
// stopped there. A close like that is the backend's restart, not the request's fault: the head carries the answer on
// from what arrived, and the client reads one whole answer that ends clean.
//
// Driven the way Claude Code drives a codex head in code mode, through /v1/messages, in front of a scripted Responses
// backend that streams the 23:22 shape. What is checked is what the client receives.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import splice.core.turn.ReasoningDisplay
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path

private val CUT_PIECES = listOf("The first ", "words of ", "the answer")
private const val REST_WORDS = " and the rest of it."
private val SUMMARIES = listOf("**Reading the task**", "**Checking the parts**", "**Writing the answer**")

class CodeModeMidAnswerCloseTest {

    @ParameterizedTest
    @CsvSource(
        // close code, how reasoning is shown, whether a script's client tool call came just before the answer
        "1012, THINKING, true",
        "1012, THINKING, false",
        "1012, OFF, true",
        "1011, THINKING, true",
    )
    fun `an answer the backend closes mid-way on the websocket reaches the client whole and ends clean`(
        code: Int,
        display: ReasoningDisplay,
        afterScript: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val gpt = ScriptedGpt()
            val ledger = BackendLedger(this, gpt)
            val rig = CodeModeRig.start(this, tmp, ledger, CodeModeRigHead(webSocket = true, display = display))
            val talk = CodeModeConversation("synthetic-mid-answer-close-$code", "Explain the synthetic thing.")
            if (afterScript) {
                // The 23:22 turn answered a script's Read: GPT's exec asked the client for a tool, the client ran it,
                // and the request that drew the cut answer carried its result.
                val asked = rig.turn(talk, gpt.exec("cell:c1 read"))
                talk.result(ledger.toolCall(talk, asked, rig), "synthetic file contents")
            } else {
                talk.answered(rig.turn(talk, gpt.answer("A first synthetic answer.")))
                talk.user("Go on.")
            }

            val answer = rig.turn(talk, gpt.closeMidAnswer(CUT_PIECES, code, SUMMARIES), gpt.answer(REST_WORDS))

            val cut = CUT_PIECES.joinToString("")
            val context = "close $code mid-answer, $display, after a script $afterScript\n${answer.describe()}\n" +
                ledger.summary(rig)
            assertNull(answer.error, context)
            assertTrue(answer.completed, context)
            assertTrue(answer.text.startsWith(cut), context)
            assertTrue(REST_WORDS in answer.text, context)
            assertEquals(1, Regex(Regex.escape(cut)).findAll(answer.text).count(), context)
        }
    }
}
