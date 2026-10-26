// NEW: failure-handling slice 0. Pins the wire every failure ending writes today, so the slice 1 refactor (one
// typed cause, one wire table) cannot change it: the status, the error type and x-should-retry, read as the
// family they put the client in, and never a word. Slices 4 and 5 rewrite these rows as they change them.
//
// Every expected value is read from today's table, never from a run:
//  - before content, RATE_LIMIT and a non-permanent API_ERROR are wired as OVERLOADED, and after content the type
//    is left alone (SseEmitter.kt:317-353);
//  - a stream=false answer carries the status its type maps to (CollectingTerminal.kt:156-164);
//  - a full gate answers 529 overloaded_error (AdmissionResponses.kt:50-56);
//  - an overflow found before the status went out answers 400 with x-should-retry false (TurnStreamer.kt:209-213,
//    PendingSse's Overflow decision);
//  - the operator's stop seals as INVALID_REQUEST and a restart as OVERLOADED (CancellationSeal.kt:127-146);
//  - a passthrough head reads Anthropic's api_error event as API_ERROR (PassthroughTerminalState.kt:68-82).
package splice.head.scripted

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private const val OK = 200
private const val BAD_REQUEST = 400
private const val TOO_MANY_REQUESTS = 429
private const val OVERLOADED = 529

class TodayWireCharacterizationTest {
    @Test
    fun `a server error mid-answer ends with today's type and family`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { anthropicStart(); anthropicText("partial words"); anthropicError("api_error", "Internal") },
            )

            val answer = anthropic().client.send()

            assertEquals(OK, answer.status, answer.describe())
            assertTrue(answer.contentBeforeError, answer.describe())
            assertEquals("api_error", answer.error?.type, answer.describe())
            assertNull(answer.shouldRetry, answer.describe())
            assertEquals(setOf(Family.R), answer.families(), answer.describe())
        }
    }

    @Test
    fun `an overflow before content answers HTTP 400 invalid_request_error`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    status(BAD_REQUEST)
                    openAiErrorBody(
                        "invalid_request_error",
                        "This model's maximum context length is 131072 tokens.",
                        "context_length_exceeded",
                    )
                },
            )

            val answer = chat().client.send()

            assertEquals(BAD_REQUEST, answer.status, answer.describe())
            assertEquals("invalid_request_error", answer.error?.type, answer.describe())
            assertEquals("false", answer.shouldRetry, answer.describe())
            // T differs from P only by its words, which this test never reads.
            assertEquals(setOf(Family.P, Family.T), answer.families(), answer.describe())
        }
    }

    @Test
    fun `a full head answers 529 overloaded_error`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatText("first turn"); hold(); chatEnd() })
            val head = chat(HeadShape(maxInflight = 1, maxQueued = 1))
            val running = head.client.open()
            running.awaitText("first turn")
            val queued = head.client.open()
            head.awaitQueued(1)

            val answer = head.client.send()

            assertEquals(OVERLOADED, answer.status, answer.describe())
            assertEquals("overloaded_error", answer.error?.type, answer.describe())
            assertNull(answer.shouldRetry, answer.describe())
            assertEquals(setOf(Family.R), answer.families(), answer.describe())
            provider.release()
            running.finish()
            queued.finish()
        }
    }

    @Test
    fun `a restart ends a running turn as overloaded_error`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatText("running"); hold(); chatEnd() })
            val head = localChat()
            val stream = head.client.open()
            stream.awaitText("running")

            head.restart()
            val answer = stream.finish()

            assertEquals(OK, answer.status, answer.describe())
            assertTrue(answer.contentBeforeError, answer.describe())
            assertEquals("overloaded_error", answer.error?.type, answer.describe())
            assertNull(answer.shouldRetry, answer.describe())
            assertEquals(setOf(Family.R), answer.families(), answer.describe())
        }
    }

    @Test
    fun `a console stop ends as invalid_request_error`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { responsesStart(); responsesText("running"); hold(); responsesEnd() })
            val head = responses()
            val stream = head.client.open()
            stream.awaitText("running")

            val turn = head.liveTurns.list().single()
            assertTrue(head.liveTurns.stop(turn.id), "the console found the running turn")
            val answer = stream.finish()

            assertEquals(OK, answer.status, answer.describe())
            assertTrue(answer.contentBeforeError, answer.describe())
            assertEquals("invalid_request_error", answer.error?.type, answer.describe())
            assertNull(answer.shouldRetry, answer.describe())
            assertEquals(setOf(Family.P, Family.T), answer.families(), answer.describe())
        }
    }

    @Test
    fun `a non-stream failure keeps its real status`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    status(TOO_MANY_REQUESTS)
                    openAiErrorBody("requests", "Rate limit reached.", "rate_limit_exceeded")
                },
            )

            val answer = chat().client.send(ClientRequest(stream = false))

            assertEquals(TOO_MANY_REQUESTS, answer.status, answer.describe())
            assertEquals("rate_limit_error", answer.error?.type, answer.describe())
            assertNull(answer.shouldRetry, answer.describe())
            assertEquals(setOf(Family.L), answer.families(), answer.describe())
        }
    }
}
