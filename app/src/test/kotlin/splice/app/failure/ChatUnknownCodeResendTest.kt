// NEW: failure-handling slice 3 (2026-10-26). A Chat stream's own error whose code no table names is the backend's
// report, not its verdict on the request (OpenAiReplies), so before any content splice sends the request once more
// (final-design.md section 8: Unrecognized is sent again once, by position), and the answer of that send is what the
// person reads. A code-less error is not re-sent, and keeps the retryable wire V4-164 promised it
// (InBandErrorClassifiedTest).
package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path

/** A Chat stream's error chunk naming a kind of its own that no table names, before any content. */
private const val UNKNOWN_KIND = """{"error":{"type":"backend_hiccup","message":"The backend hiccuped."}}"""

class ChatUnknownCodeResendTest {
    @Test
    fun `a Chat stream's error naming a kind no table names is sent once more and the answer streams`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { event(UNKNOWN_KIND) },
                reply { chatAnswer("the answer after one more send") },
            )
            val head = chat()

            val answer = head.client.send()

            assertNull(answer.error, "the second send answered, so the turn ends on its answer: ${answer.error}")
            assertEquals("the answer after one more send", answer.text)
        }
    }
}
