// NEW: failure-handling slice 0. The harness works when what a scripted provider does reaches the client through
// a real head of each API type, and when hours of waiting pass on the virtual clock in no real time. Every
// assertion reads what the client received, or what the provider was asked.
package splice.head.scripted

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** RFC 6455 section 7.4.1: the server met a condition that kept it from fulfilling the request. */
private const val INTERNAL_ERROR_CLOSE = 1011

class ScriptedProviderTest {
    @Test
    fun `a scripted Anthropic error reaches the client through a passthrough head`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(529); anthropicErrorBody("overloaded_error", "Overloaded") })

            val answer = anthropic().client.send()

            assertEquals("overloaded_error", answer.error?.type, answer.describe())
            assertTrue(provider.requests.isNotEmpty(), "the head never asked the provider: ${answer.describe()}")
            assertTrue(
                provider.requests.all { it.transport == Transport.HTTP && it.path == "/v1/messages" },
                "the head asks over Anthropic Messages: ${provider.requests.map { it.path }}",
            )
        }
    }

    @Test
    fun `a scripted Chat stream cut mid-answer reaches the client as a cut`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // Held before the cut, and released once the client holds the words, so the cut falls after them.
            provider.script(reply { chatText("partial words"); hold(); cut() })
            val stream = chat().client.open()

            stream.awaitText("partial words")
            provider.release()
            val answer = stream.finish()

            assertEquals("partial words", answer.text, "the words before the cut stay, once: ${answer.describe()}")
            assertFalse(answer.completed, "a cut answer never reads as finished: ${answer.describe()}")
            assertTrue(answer.error != null || answer.cut, "the answer ends on the cut: ${answer.describe()}")
        }
    }

    @Test
    fun `a scripted websocket close reaches a Responses head`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { responsesStart(); responsesText("partial words"); close(INTERNAL_ERROR_CLOSE) })

            val answer = responsesOverWebSocket().client.send()

            assertEquals(Transport.WEBSOCKET, provider.requests.firstOrNull()?.transport, "the round rode the socket")
            // How often the words arrive is the head's to decide: it may resume the round, and the provider answers
            // every resume with the same closing reply. The words the first round sent reach the client first.
            assertTrue(
                answer.text.startsWith("partial words"),
                "the words before the close reached the client: ${answer.describe()}",
            )
            assertFalse(answer.completed, "a closed round never reads as finished: ${answer.describe()}")
        }
    }

    @Test
    fun `virtual time moves a five-hour wait without real time passing`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp, VirtualTime()) {
            provider.script(reply { delay(5.hours); responsesAnswer("after five hours") })
            val real = TimeSource.Monotonic.markNow()
            val stream = responses().client.open()

            provider.awaitRequests(1)
            assertFalse(stream.finished, "the answer came before the clock moved: ${stream.soFar().describe()}")
            time.advanceBy(5.hours)
            val answer = stream.finish()

            assertEquals("after five hours", answer.text, answer.describe())
            assertTrue(answer.completed, answer.describe())
            assertEquals(5.hours, time.passed(), "the clock moved exactly the wait")
            assertTrue(real.elapsedNow() < 1.minutes, "five virtual hours took ${real.elapsedNow()} of real time")
        }
    }
}
