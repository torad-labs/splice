// A chat head that meets an HTTP 200 with no stream in it, only an error object naming a failure splice knows (an
// expired login, a full context window), sends the turn to the provider once. The 0.4.0 changelog promises it: "An
// unknown vendor error code is tried once more, a known one never." Review finding 12 of PR #247, round 6.
//
// Failure-handling slice 4: which failures are sent again is RetryEngine's, by the cause the head's reader names. A
// body with no error object in it (a proxy's login page, plain words) names nothing: it is a stream that closed before
// sending anything, sent fresh twice (OneEngineThroughHeadsTest), and a rate limit takes the limit's burst waits.
package splice.head.transport

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path

/** Where a chat head sends each request. */
private const val CHAT_PATH = "/chat/completions"

/** An expired session, as OpenAI's error object names it. */
private const val EXPIRED_LOGIN =
    """{"error":{"code":"invalid_api_key","message":"Your session token has expired, please sign in again."}}"""

/** A full context window, as OpenAI's error object names it. */
private const val FULL_WINDOW =
    """{"error":{"code":"context_length_exceeded","message":"This model's maximum context length is 8192 tokens."}}"""

class ZeroEventKnownFailureSentOnceTest {
    @Test
    fun `an expired login in a 200 with no stream is sent to the provider once`(@TempDir tmp: Path) =
        sentOnce(tmp, EXPIRED_LOGIN)

    @Test
    fun `a full context window in a 200 with no stream is sent to the provider once`(@TempDir tmp: Path) =
        sentOnce(tmp, FULL_WINDOW)

    private fun sentOnce(tmp: Path, said: String): TestResult = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { body(said) })

            val answer = chat().client.send()

            assertEquals(
                listOf(CHAT_PATH),
                provider.requests.map { it.path },
                "a known failure is never tried once more: ${answer.describe()}",
            )
        }
    }
}
