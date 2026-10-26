// A chat head that meets an HTTP 200 with no stream in it, only a body that names a failure splice knows (an expired
// login, a full context window, a rate limit), sends the turn to the provider once. The 0.4.0 changelog promises it:
// "An unknown vendor error code is tried once more, a known one never." Review finding 12 of PR #247, round 6.
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

/** An expired session's login page, sent as a 200 by the provider or a proxy in front of it. */
private const val EXPIRED_LOGIN =
    "<html><body>401 Unauthorized: your session token has expired, please sign in again.</body></html>"

private const val FULL_WINDOW = "context window exceeded"

private const val RATE_LIMITED = "rate limit exceeded"

class ZeroEventKnownFailureSentOnceTest {
    @Test
    fun `an expired login in a 200 with no stream is sent to the provider once`(@TempDir tmp: Path) =
        sentOnce(tmp, EXPIRED_LOGIN)

    @Test
    fun `a full context window in a 200 with no stream is sent to the provider once`(@TempDir tmp: Path) =
        sentOnce(tmp, FULL_WINDOW)

    @Test
    fun `a rate limit in a 200 with no stream is sent to the provider once`(@TempDir tmp: Path) =
        sentOnce(tmp, RATE_LIMITED)

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
