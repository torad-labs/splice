// NEW: failure-handling slice 3. Each provider failure is read once, by its API type's reader, and what a person reads
// follows from that one reading: a real head of each API type in front of the scripted provider, driven through the
// failures a person meets, asserting what the client's own wire shows (final-design.md sections 6, 7 and 12).
//
// Every head here says its times in America/Chicago (ScriptedHeads' LINE_ZONE), so a reset reads the same on any
// machine.
package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.head.scripted.ClientAnswer
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path

/** The prefix every line a person reads opens with. */
private const val PREFIX = "[splice] "

/** How a line says a provider closed its stream before any of its answer reached the person. */
private const val CLOSED_UNSEEN = "closed the connection before answering"

/** A Chat stream's opening chunk, which names the role and carries no text. */
private const val CHAT_ROLE_ONLY =
    """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted",""" +
        """"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}"""

/** An Anthropic stream's opening event, before any content block. */
private const val ANTHROPIC_MESSAGE_START =
    """{"type":"message_start","message":{"id":"msg_scripted","type":"message","role":"assistant",""" +
        """"model":"scripted","content":[],"stop_reason":null,"stop_sequence":null,""" +
        """"usage":{"input_tokens":12,"output_tokens":1}}}"""

/** A websocket's normal close; on HTTP the scripted answer simply ends there. */
private const val NORMAL_CLOSURE = 1000

class ReadOnceThroughHeadsTest {
    // Slice 2 finding 21: in 105 of 120 runs of a cut that landed before its text, the client saw no text and the line
    // still said the answer was cut off. A person who saw no answer begin is told the provider closed before answering.
    @Test
    fun `a Chat stream that ends after it began but before any text reached the client is not called cut off`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { event(CHAT_ROLE_ONLY); close(NORMAL_CLOSURE) })
            val head = chat()

            val answer = head.client.send()

            assertFalse(answer.contentBeforeError, answer.describe())
            assertTrue(answer.text.isEmpty(), answer.describe())
            assertUnseenClose(answer)
        }
    }

    @Test
    fun `an Anthropic stream that ends after message_start is not called cut off`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { event(ANTHROPIC_MESSAGE_START, "message_start"); close(NORMAL_CLOSURE) })
            val head = anthropic()

            val answer = head.client.send()

            assertFalse(answer.contentBeforeError, answer.describe())
            assertTrue(answer.text.isEmpty(), answer.describe())
            assertUnseenClose(answer)
        }
    }

    /** The line the client was given: an in-band error event's message, or the error body of a status. */
    private fun lineOf(answer: ClientAnswer): String {
        val message = answer.error?.message ?: ""
        assertTrue(message.startsWith(PREFIX), "a person reads one [splice] line: ${answer.describe()}")
        return message
    }

    private fun assertUnseenClose(answer: ClientAnswer) {
        val line = lineOf(answer)
        assertFalse("cut off" in line, "an answer the person never saw is not cut off: $line")
        assertTrue(CLOSED_UNSEEN in line, "says \"$CLOSED_UNSEEN\": $line")
    }
}
