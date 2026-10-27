// NEW: failure-handling slice 4. One engine decides every retry a turn makes, from the cause, where the turn stood and
// the turn's one ledger (final-design.md section 8). These are its routes: a real head of each API type in front of
// the scripted provider, on virtual time, driven through the failures section 8's table names, asserting what the
// provider sees (how many requests, and when) and what the person reads. Every expected number is the table's own:
// the waits are RetryBudgets' schedules, never a run's. The two routes of a cut mid-answer are not here: a cut
// after content still restarts the round from scratch, because the head's re-anchor runner decides its own rounds
// until it takes the engine's steps too (slice-4-report.md, open).
package splice.app.failure

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.HeadShape
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedReply
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The prefix every line a person reads opens with. */
private const val PREFIX = "[splice] "

/** What a line says where Claude Code sends the turn again itself. */
private const val RESENDS = "Claude Code sends this turn again."

/** What a repeated ending says: Claude Code already sent the turn again once, so the turn stopped. */
private const val STOPPED = "after Claude Code sent this turn again, so this turn stopped"

/** Where the scripted virtual wall clock starts (VirtualTime's default). */
private val START: Instant = Instant.parse("2026-10-26T12:00:00Z")

private const val TOO_MANY_REQUESTS = 429
private const val TEAPOT = 418
private const val INTERNAL_SERVER_ERROR = 500
private const val SERVICE_UNAVAILABLE = 503
private const val UNNAMED_SERVER_ERROR = 520

/** llama-server's answer while it loads a model (its unavailable_error), as LocalRuntimeReplies reads it. */
private const val LLAMA_LOADING = """{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}"""

/** Kimi's 5-hour limit, verbatim from the 429 of Oct 23, 3:41:54 PM CT (audit-maps.md:1075). */
private const val KIMI_FIVE_HOUR =
    "You've reached your 5-hour usage limit. Your quota will reset when the current 5-hour window ends. " +
        "To continue now, purchase extra usage or upgrade your plan: " +
        "https://www.kimi.com/membership/subscription?tab=quota"

/** A Responses stream's own failure event for a server error. */
private const val RESPONSES_SERVER_ERROR =
    """{"type":"response.failed","response":{"error":{"code":"server_error","message":"upstream exploded"}}}"""

class OneEngineThroughHeadsTest {
    @Test
    fun `a 5 s Retry-After is waited out in silence and the answer streams`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    status(TOO_MANY_REQUESTS)
                    header("Retry-After", "5")
                    openAiErrorBody("rate_limit_error", "Rate limit reached for requests.", "rate_limit_exceeded")
                },
                reply { chatAnswer("the answer after five seconds") },
            )
            val head = chat()

            val answer = head.client.send()

            assertEquals("the answer after five seconds", answer.text, answer.describe())
            assertEquals(listOf(5.seconds), gaps(provider.requests), "the stated 5 s, waited once")
            assertEquals(emptyList<String>(), lines(answer), "a wait under 10 s is waited in silence")
        }
    }

    @Test
    fun `an overload with no time stated is sent again at 2, 4, 8, 15 and 30 s, and only the 10 s wait is told`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(*Array(5) { overloaded() }, reply { chatAnswer("the answer after a minute") })
            val head = chat()

            val answer = head.client.send()

            assertEquals("the answer after a minute", answer.text, answer.describe())
            assertEquals(listOf(2, 4, 8, 15, 30).map { it.seconds }, gaps(provider.requests))
            val told = lines(answer)
            assertEquals(1, told.size, "one line for the episode, written when the first wait of 10 s began: $told")
            assertTrue("This turn tries again in 15 seconds." in told.single(), told.single())
        }
    }

    @Test
    fun `a model still loading is waited for up to five minutes and its answer streams`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                val loading = reply { status(SERVICE_UNAVAILABLE); body(LLAMA_LOADING) }
                provider.script(*Array(9) { loading }, reply { chatAnswer("loaded") })
                val head = localChat(replies = HeadReplies.localChat)

                val answer = head.client.send()

                assertEquals("loaded", answer.text, answer.describe())
                // The overload waits first, then a minute at a time while five minutes in all are not yet spent.
                assertEquals(listOf(2, 4, 8, 15, 30, 60, 60, 60, 60).map { it.seconds }, gaps(provider.requests))
            }
        }

    @Test
    fun `a local model server that comes back after 43 s is reached and the answer streams`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                provider.script(reply { chatAnswer("the server is back") })
                val head = localChat(HeadShape(relayed = true), replies = HeadReplies.localChat)
                head.relay.stopListening()
                time.at(43.seconds) { head.relay.resumeListening() }

                val answer = head.client.send()

                assertEquals("the server is back", answer.text, answer.describe())
                // Refused at 0, 1, 3, 7, 15 and 31 s, and reached on the wait that ends past 43 s.
                assertEquals(1, provider.requests.size, "a refused connect never reaches the provider")
                assertEquals(61.seconds, provider.requests.single().at)
            }
        }

    @Test
    fun `a local model server that never comes back ends with the not-running line after about two minutes`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val head = localChat(HeadShape(relayed = true), replies = HeadReplies.localChat)
            head.relay.stopListening()

            val answer = head.client.send()

            assertLine(answer, "model server is not running")
            assertEquals(0, provider.requests.size)
            // 1, 2, 4, 8, 16, 30, 30 and 30 s of waiting: the eight waits a path that does not answer is given.
            assertEquals(121.seconds, time.passed())
        }
    }

    @Test
    fun `a server error before the stream began is sent again after 200, 400 and 800 ms`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                val failed = reply {
                    status(INTERNAL_SERVER_ERROR)
                    openAiErrorBody("server_error", "The server had an error.", "server_error")
                }
                provider.script(failed, failed, failed, reply { chatAnswer("the fourth send answered") })
                val head = chat()

                val answer = head.client.send()

                assertEquals("the fourth send answered", answer.text, answer.describe())
                assertEquals(listOf(200, 400, 800).map { it.milliseconds }, gaps(provider.requests))
            }
        }

    @Test
    fun `an error no table names is sent once more, then ends quoting the provider`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    status(UNNAMED_SERVER_ERROR)
                    // Words a person may read: a sentence naming "upstream" is never quoted (ProviderWordsFilter).
                    openAiErrorBody("odd_error", "Something odd happened on our side.", "odd_code")
                },
            )
            val head = chat()

            val answer = head.client.send()

            assertEquals(2, provider.requests.size, "tried once more")
            assertLine(answer, "Something odd happened on our side.")
        }
    }

    @Test
    fun `an unknown 4xx is sent once more, then ends with a status Claude Code does not send again`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { status(TEAPOT); openAiErrorBody("teapot_error", "I am a teapot.", "teapot") },
            )
            val head = chat()

            val answer = head.client.send()

            assertEquals(2, provider.requests.size, "tried once more")
            assertEquals("false", answer.shouldRetry, answer.describe())
        }
    }

    @Test
    fun `a reset stated hours away ends at once with the interim line, and Claude Code sends the turn again`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(limitedUntil(START.plusSeconds(3.hours.inWholeSeconds)))
            val head = chat()

            val answer = head.client.send()

            assertEquals(1, provider.requests.size, "a hold ends the turn on its first answer until slice 5")
            assertLine(answer, RESENDS)
        }
    }

    @Test
    fun `a request whose identical predecessor ended for Claude Code to send again stops, and says so`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(limitedUntil(START.plusSeconds(3.hours.inWholeSeconds)))
            val head = chat()
            val turn = ClientRequest(session = "sess-resent-by-claude-code")

            val first = head.client.send(turn)
            val again = head.client.send(turn)

            assertLine(first, RESENDS)
            assertLine(again, STOPPED)
            assertFalse(RESENDS in lineOf(again), "the re-send ends with an answer Claude Code does not send again")
        }
    }

    @Test
    fun `Kimi's 5-hour limit ends the turn after one provider request`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(TOO_MANY_REQUESTS); anthropicErrorBody("rate_limit_error", KIMI_FIVE_HOUR) })
            val kimi = anthropic(replies = HeadReplies.kimi)

            val answer = kimi.client.send()

            // A named window with no time stated is long: no wait, no second request. The passthrough head still
            // relays the provider's own 429 until slice 5 writes the window's line on it.
            assertEquals(1, provider.requests.size, answer.describe())
            assertEquals(TOO_MANY_REQUESTS, answer.status, answer.describe())
        }
    }

    @Test
    fun `a server error after the answer began is continued from what arrived, five times at most`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    responsesStart()
                    responsesText("partial words")
                    event(RESPONSES_SERVER_ERROR)
                },
                reply {
                    responsesStart()
                    event(RESPONSES_SERVER_ERROR)
                },
            )
            val head = responses()

            val answer = head.client.send()

            assertEquals(1 + 5, provider.requests.size, "the first send and five recoveries: ${answer.describe()}")
            assertTrue(answer.contentBeforeError, answer.describe())
            assertTrue(lineOf(answer).startsWith(PREFIX), answer.describe())
        }
    }

    /** The time between each request the provider received and the one before it. */
    private fun gaps(requests: List<ProviderRequest>): List<Duration> = requests.zipWithNext { a, b -> b.at - a.at }

    /** The lines splice wrote into the answer's notice blocks. */
    private fun lines(answer: ClientAnswer): List<String> = answer.notices.filter { it.startsWith(PREFIX) }

    /** The line the client was given: an in-band error event's message, or the error body of a status. */
    private fun lineOf(answer: ClientAnswer): String = answer.error?.message.orEmpty()

    private fun assertLine(answer: ClientAnswer, fragment: String) {
        val line = lineOf(answer)
        assertTrue(line.startsWith(PREFIX), "a person reads one [splice] line: ${answer.describe()}")
        assertTrue(fragment in line, "says \"$fragment\": $line")
        assertFalse("attempt" in line, line)
    }

    /** A 503 that states no time: an overload every API type's reader reads as one. */
    private fun overloaded(): ScriptedReply = reply {
        status(SERVICE_UNAVAILABLE)
        openAiErrorBody("server_error", "The server is overloaded.", "overloaded")
    }

    /** A 429 whose Retry-After names [resetsAt] in its date form (RFC 7231), which every reader reads. */
    private fun limitedUntil(resetsAt: Instant): ScriptedReply = reply {
        status(TOO_MANY_REQUESTS)
        header("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(resetsAt.atOffset(ZoneOffset.UTC)))
        openAiErrorBody("rate_limit_error", "Rate limit reached for this plan.", "rate_limit_exceeded")
    }
}
