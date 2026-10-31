// NEW: failure-handling slice 2. What a person actually reads, read off the client's own wire: a real head of each
// API type in front of the scripted provider, driven through the failures a person meets, asserting the one [splice]
// line each one ends or recovers with (final-design.md section 10, "One example line per kind"). The property test
// beside it (core SpliceLinesPropertyTest) proves the grammar over every leaf; this one proves the ROUTE: that the
// line a head writes to a client is the line SpliceLines composed, with the head's own command, its login command,
// the provider a person pays and the machine's zone in it, and that nothing a person cannot use survives the trip.
//
// Every head here says its times in America/Chicago (ScriptedHeads' LINE_ZONE), so a reset reads the same on any
// machine, and the shapes come from traces: Muse's 429 of Oct 25, 2026 10:16:47 PM CT with a reset of Oct 26,
// 12:25 AM CDT, and DeepSeek's 402 ("Insufficient Balance", request id dropped).
package splice.app.failure

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.SHARE_NODE_TOKEN
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.HeadSays
import splice.head.scripted.HeadShape
import splice.head.scripted.ScriptedReply
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The prefix every line a person reads opens with. */
private const val PREFIX = "[splice] "

/** When Muse answered its 429 (Oct 25, 10:16:47 PM CT), the reset it stated in its words (trace 0768bed1a506), and
 *  how a person reads that reset in Chicago. */
private val MUSE_ANSWERED: Instant = Instant.parse("2026-10-26T03:16:47Z")
private val MUSE_RESET: Instant = Instant.parse("2026-10-26T05:25:35Z")
private const val MUSE_RESET_READS = "Oct 26, 12:25 AM CDT"

/** How a recovery line says the turn picks its answer up where it broke off. */
private const val CARRIES_ON = "This turn continues from where it stopped."

/** What a line says where the record shows Claude Code sending the turn again itself. */
private const val RESENDS = "Claude Code sends this turn again."

/** What a limit handed to Claude Code with its reset says (family L, one login). */
private const val HANDED_OVER = "Claude Code retries at that time."

/** The phrase that makes Claude Code compact, which only a plain prompt-too-long line may carry. */
private const val COMPACT_TRIGGER = "prompt is too long"

/** A rejection naming a masked key in the shape a Codex backend masks one; no token a head here sends. */
private val MASKED_KEY = "Incorrect API key provided: sk-test0" + "*".repeat(40) + "Zq9x."

/** A context overflow in the words and code OpenAI's APIs use. */
private const val OVERFLOW_WORDS = "This model's maximum context length is 131072 tokens."
private const val OVERFLOW_CODE = "context_length_exceeded"

/** A conversation that already holds an answer, so the turn it asks for is a later one, not its first exchange. */
private val LATER_TURN = buildJsonObject {
    putJsonArray("messages") {
        addJsonObject {
            put("role", "user")
            put("content", "Start.")
        }
        addJsonObject {
            put("role", "assistant")
            put("content", "Started.")
        }
        addJsonObject {
            put("role", "user")
            put("content", "Say something.")
        }
    }
}

/** Claude Code's summarizer instruction, which is what makes a request a compaction (compactMarkers). */
private const val COMPACTION_ASK = "Your task is to create a detailed summary of this conversation."

private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val PAYMENT_REQUIRED = 402
private const val TOO_MANY_REQUESTS = 429
private const val INTERNAL_SERVER_ERROR = 500

/** Nothing a person cannot use or must not see (the grammar's never list, the four this route can produce). */
private val NEVER = listOf("[SPLICE-", "Reason:", "attempt", "upstream")

class LinesThroughHeadsTest {
    @Test
    fun `a server error mid-answer shows one recovery line, saying the turn carries on, and one ending line`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            // One episode: the answer breaks off, and every try to pick it up fails again before a word more reaches
            // the client. A word that did reach it would end the episode, and the next failure would rightly say so.
            // A Responses head carries an answer on from where it stopped; a Chat head re-sends only an unknown code,
            // and only before content, so it would end at the first failure with no recovery to tell. The stream's
            // own server_error is read by the head's reader, which has named it differently across the slices,
            // so this asserts the episode, not the cause's name.
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

            val notices = answer.notices.filter { it.startsWith(PREFIX) }
            assertEquals(1, notices.size, "one line per recovery episode: ${answer.notices}")
            assertTrue(notices.single().endsWith(CARRIES_ON), notices.single())
            assertTrue(answer.contentBeforeError, answer.describe())
            assertFalse(CARRIES_ON in lineOf(answer), "the turn ends with its own line: ${answer.describe()}")
        }
    }

    // The reply holds after its text until the client has it, so the person saw the answer start before the cut. A cut
    // with no hold usually reached the head before the text it followed. The provider's close reaches the turn either
    // as a stream that ended without its finish or as a reset past the handoff, run to run, and either way the line
    // says it was cut off.
    @Test
    fun `a cut-off stream ends with the cut-off line`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatText("partial words"); hold(); cut() })
            val head = chat()
            val stream = head.client.open()
            stream.awaitText("partial words")
            provider.release()

            val answer = stream.finish()

            assertEquals("partial words", answer.text, answer.describe())
            assertTrue(answer.contentBeforeError, answer.describe())
            assertLine(answer, "cut off")
        }
    }

    @Test
    fun `an overflow before content answers HTTP 400 with a line that says the prompt is too long`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(overflow())
            val head = chat()

            // A streaming turn after an answer: its status is still splice's to choose, and it can compact.
            val answer = head.client.send(ClientRequest(extra = LATER_TURN))

            assertEquals(BAD_REQUEST, answer.status)
            assertLine(answer, COMPACT_TRIGGER)
        }
    }

    @Test
    fun `a compaction that overflows is answered without the phrase that makes a client compact`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(overflow())
            val head = chat()

            val answer = head.client.send(ClientRequest(text = COMPACTION_ASK))

            assertEquals(BAD_REQUEST, answer.status, "a compaction's overflow keeps the status every overflow has")
            val line = lineOf(answer)
            assertFalse(COMPACT_TRIGGER in line, "a compaction's own overflow never asks for a compaction: $line")
            assertTrue("too long" in line && "summarize" in line, line)
        }
    }

    @Test
    fun `a full head answers with the capacity line`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatText("first turn"); hold(); chatEnd() })
            val head = chat(HeadShape(maxInflight = 1, maxQueued = 1))
            val running = head.client.open()
            running.awaitText("first turn")
            val queued = head.client.open()
            head.awaitQueued(1)

            val answer = head.client.send()

            assertLine(answer, "already running 1 turn, its limit")
            provider.release()
            running.finish()
            queued.finish()
        }
    }

    @Test
    fun `a head with no login names the command that signs it in`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            val head = signedOutChat(says = HeadSays(loginCommand = "claude-scripted-signed-out login"))

            val answer = head.client.send()

            assertLine(answer, "has no login yet")
            assertLine(answer, "Run claude-scripted-signed-out login, then send the message again.")
            assertEquals(0, provider.requests.size)
        }
    }

    @Test
    fun `a request larger than the head accepts names both sizes`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            val head = chat()

            val answer = head.client.send(ClientRequest(text = "x".repeat(LARGE_REQUEST_CHARS)))

            val line = lineOf(answer)
            assertTrue("more than the" in line, line)
            assertTrue("MB" in line || "KB" in line, line)
        }
    }

    @Test
    fun `a stated reset reads as a clock time in the machine's zone and says the client retries at that time`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime(autoAdvance = true, startWall = MUSE_ANSWERED)) {
            provider.script(limited(MUSE_RESET))
            val head = chat()

            val answer = head.client.send()

            // Slice 5: this session carries no wall, so its 960 s cannot hold two hours; the limit is handed to
            // Claude Code with its reset (family L), and the line says the time and that Claude Code retries then.
            assertLine(answer, MUSE_RESET_READS)
            assertLine(answer, HANDED_OVER)
        }
    }

    @Test
    fun `a limit with no stated time ends at the session's wall with a line that gives no time`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                provider.script(limited(null))
                val head = chat()

                val answer = head.client.send()

                // Slice 5: the limit holds after its bursts, probed, until the session's wall; the line gives no
                // time, and Claude Code's re-send holds again.
                assertLine(answer, "without saying when it resets")
                assertLine(answer, RESENDS)
            }
        }

    @Test
    fun `a spent account ends with the credit line naming the provider, and the client's re-send reaches it`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(credit())
            val head = chat(says = HeadSays(providerName = "DeepSeek"))

            val answer = head.client.send()

            // Slice 5: a spent account holds, probed every 5 minutes, until the session's wall; the line names the
            // provider and its words, and Claude Code's re-send holds again.
            assertLine(answer, "DeepSeek account cannot pay for this turn")
            assertLine(answer, "Insufficient Balance")
            assertLine(answer, RESENDS)
            provider.script(reply { chatAnswer("the answer after the credit") })
            val requests = provider.requests.size
            assertEquals("the answer after the credit", head.client.send().text)
            assertTrue(provider.requests.size > requests, "the client's re-send reaches the provider")
        }
    }

    @Test
    fun `a turn shared from another machine with no login held for it shows its own line`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                val head = forwardingAnthropic()

                val answer = head.client.send(ClientRequest(bearer = SHARE_NODE_TOKEN))

                assertLine(answer, "no free login for a turn shared from another machine")
                assertEquals(0, provider.requests.size)
            }
        }

    @Test
    fun `a stopped turn's re-send shows the console line`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatText("running"); hold(); chatEnd() })
            val head = chat()
            val turn = ClientRequest(session = "sess-stopped-from-console")
            val stream = head.client.open(turn)
            stream.awaitText("running")
            head.liveTurns.stop(head.liveTurns.list().single().id)
            stream.finish()

            // Claude Code's one re-send of a stream the operator stopped: the same messages, stream false (V4-319).
            val answer = head.client.send(turn.copy(stream = false))

            assertLine(answer, "stopped from the splice console")
        }
    }

    @Test
    fun `a retry that followed a compaction that did not finish shows the compaction line`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                provider.script(reply { chatText("summary so far"); hold(); chatEnd() })
                val head = chat()
                val compaction = ClientRequest(text = COMPACTION_ASK, session = "sess-unfinished-compaction")
                val first = head.client.open(compaction)
                first.awaitText("summary so far")
                first.hangUp()
                val retry = head.client.open(compaction)
                retry.awaitText("summary so far")

                head.liveTurns.stop(head.liveTurns.list().single().id)

                assertLine(retry.finish(), "The summary this request was waiting on did not finish")
            }
        }

    @Test
    fun `a 401 naming a key this login never sent shows no fragment of it`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            val refusal = reply {
                status(UNAUTHORIZED)
                openAiErrorBody("invalid_request_error", MASKED_KEY, "invalid_api_key")
            }
            provider.script(refusal, refusal, refusal)
            val head = chat()

            val answer = head.client.send()

            val line = lineOf(answer)
            assertFalse("sk-test0" in line, line)
            assertFalse("Zq9x" in line, line)
            assertFalse("*" in line, line)
            assertTrue("a fault on its side" in line, line)
        }
    }

    @Test
    fun `no line a client receives holds an internal code, Reason, attempt or upstream`(@TempDir tmp: Path) =
        runTest {
            withScriptedHeads(tmp) {
                val answers = mutableListOf<ClientAnswer>()
                provider.script(limited(MUSE_RESET))
                answers += chat().client.send()
                provider.script(overflow())
                answers += chat().client.send(ClientRequest(stream = false))
                provider.script(reply { chatText("partial words"); cut() })
                answers += chat().client.send()
                answers += signedOutChat().client.send()
                val serverError = reply { status(INTERNAL_SERVER_ERROR); openAiErrorBody("server_error", "Boom.", "x") }
                provider.script(serverError, serverError, serverError)
                answers += chat().client.send()

                answers.forEach { answer ->
                    val line = lineOf(answer)
                    NEVER.forEach { assertFalse(it in line, "holds $it: $line") }
                }
            }
        }

    /** The line the client was given: an in-band error event's message, or the error body of a status. */
    private fun lineOf(answer: ClientAnswer): String {
        val message = answer.error?.message ?: ""
        assertTrue(message.startsWith(PREFIX), "a person reads one [splice] line: ${answer.describe()}")
        return message
    }

    private fun assertLine(answer: ClientAnswer, fragment: String) {
        val line = lineOf(answer)
        assertTrue(fragment in line, "says \"$fragment\": $line")
    }

    /** A 429 that states its reset in Retry-After's date form (RFC 7231), which every API type's reader reads, or a
     *  limit that states none. Muse's words name the same reset in the body (trace 0768bed1a506), and since
     *  failure-handling slice 3 only the providers that write a reset that way read it there (MuseReplies), so on this
     *  head the header states it. It goes to a translated head: a passthrough head still relays a provider's 429 whole
     *  until slice 5. */
    private fun limited(resetsAt: Instant?): ScriptedReply = reply {
        status(TOO_MANY_REQUESTS)
        resetsAt?.let {
            header("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(it.atOffset(ZoneOffset.UTC)))
        }
        openAiErrorBody(
            "rate_limit_error",
            resetsAt?.let { "Subscription quota exhausted. Your usage window resets at $it." }
                ?: "Rate limit reached for this plan.",
            "rate_limit_exceeded",
        )
    }

    /** DeepSeek's 402, request id and all. */
    private fun credit(): ScriptedReply = reply {
        status(PAYMENT_REQUIRED)
        openAiErrorBody(
            "billing_error",
            "Insufficient Balance (request_id: 6f1c2a84-9b31-4a1e-8f55-0d2e7b3c91aa).",
            "insufficient_balance",
        )
    }

    /** A context overflow the provider answers before any content. */
    private fun overflow(): ScriptedReply = reply {
        status(BAD_REQUEST)
        openAiErrorBody("invalid_request_error", OVERFLOW_WORDS, OVERFLOW_CODE)
    }
}

/** Longer than the head's own request ceiling, so admission refuses it before any provider request. */
private const val LARGE_REQUEST_CHARS = 40_000_000

/** A Responses stream's own failure event for a server error, in the words a backend gives it. */
private const val RESPONSES_SERVER_ERROR =
    """{"type":"response.failed","response":{"error":{"code":"server_error","message":"upstream exploded"}}}"""
