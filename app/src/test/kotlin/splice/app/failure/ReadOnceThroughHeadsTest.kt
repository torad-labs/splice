// NEW: failure-handling slice 3. Each provider failure is read once, by its API type's reader, and what a person reads
// follows from that one reading: a real head of each API type in front of the scripted provider, driven through the
// failures a person meets, asserting what the client's own wire shows (final-design.md sections 6, 7 and 12).
//
// This file holds the vendor deviations: each recorded reply is replayed through the head whose vendor declared its
// own reading of it, and through a sibling head of the same API type that declared none, so the deviation is what
// makes the two differ. The standard readings are ReadOnceStandardThroughHeadsTest's.
//
// Every head here says its times in America/Chicago (ScriptedHeads' LINE_ZONE), so a reset reads the same on any
// machine.
package splice.app.failure

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.HeadSays
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import java.time.Instant

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

/** Kimi's plan-tier answer, which it spells as a 401 (KimiOAuth.isPlanTierRejection). */
private const val KIMI_PLAN_TIER =
    "Your current subscription does not have access to kimi-k3-thinking. Upgrade your plan to use it."

/** xAI's words for an expired OAuth token, which it answers with a 403 (the grok-dead-head incident). */
private const val XAI_DEAD_TOKEN = "The OAuth2 access token could not be validated. unauthenticated:bad-credentials"

/** Muse's 429, verbatim from the window of Oct 25, 10:16:47 PM CT (audit-maps.md:426): its body names the reset. */
private const val MUSE_LIMIT_BODY =
    """{"error":{"code":"rate_limit_exceeded","message":"Subscription quota exhausted. Your usage window resets """ +
        """at 2026-10-26T05:25:35Z.","resets_at":1792992335}}"""

/** The reset that body names, as a line says it in Chicago. */
private const val MUSE_RESET_LINE = "Oct 26, 12:25 AM"

/** Muse's own reset is 2 hours out, so the clock starts before it: the window of Oct 25, 10 PM CT. */
private val MUSE_WINDOW_START: Instant = Instant.parse("2026-10-26T03:00:00Z")

/** codex's spent weekly window: its 429 names the reset and the window's own length (V4-377). */
private const val CODEX_WEEKLY_LIMIT =
    """{"type":"usage_limit_reached","plan_type":"pro","resets_in_seconds":172800,""" +
        """"limit_window_minutes":10080,"message":"You've hit your usage limit."}"""

/** What a line says where the record shows Claude Code sending the turn again itself. */
private const val RESENDS = "Claude Code sends this turn again."

/** A conversation past its first exchange: only its overflow line may say the words Claude Code compacts on. */
private const val LATER_EXCHANGE =
    """{"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"},""" +
        """{"role":"user","content":"go on"}]}"""

/** What a Muse head's lines name: the command that signs it in, and the provider a person renews with. */
private const val MUSE_LOGIN = "splice login muse"
private const val MUSE_NAME = "Muse"

/** llama-server's 503 while it loads the model, and its 400 for a prompt past the context it has. */
private const val LLAMA_LOADING = """{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}"""
private const val LLAMA_CONTEXT =
    """{"error":{"code":400,"message":"the request exceeds the available context size. try increasing the """ +
        """context size or enable context shift","type":"exceed_context_size_error","n_ctx":4096}}"""

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

    @Test
    fun `Kimi's plan-tier 401 says the plan excludes the model and spends no refresh, where a sibling head signs in`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(401); anthropicErrorBody("authentication_error", KIMI_PLAN_TIER) })
            val kimi = anthropic(replies = HeadReplies.kimi)

            val answer = kimi.client.send()

            assertLine(answer, "account may not use this model")
            assertEquals(1, provider.requests.size, "a plan a refresh cannot fix is asked once")
            // The same reply on a head that declared no deviation is the dead login its status spells.
            val sent = provider.requests.size
            val sibling = anthropic().client.send()
            assertLine(sibling, "login expired")
            assertTrue(provider.requests.size > sent + 1, "the standard reading renews the login and asks again")
        }
    }

    @Test
    fun `xAI's expired-token 403 renews the login once and the answer streams, where a sibling head is forbidden`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { status(403); openAiErrorBody("invalid_request_error", XAI_DEAD_TOKEN, "unauthenticated") },
                reply { chatAnswer("the answer after the renewal") },
            )
            val grok = chat(replies = HeadReplies.grokChat)

            val answer = grok.client.send()

            assertEquals("the answer after the renewal", answer.text, answer.describe())
            assertEquals(2, provider.requests.size, "one renewal, then the same turn again")
            // The same 403 on a head that declared no deviation is a permission the login cannot change.
            provider.script(reply { status(403); openAiErrorBody("invalid_request_error", XAI_DEAD_TOKEN, "x") })
            val sent = provider.requests.size
            assertLine(chat().client.send(), "account may not use this model")
            assertEquals(sent + 1, provider.requests.size, "a forbidden answer is asked once")
        }
    }

    @Test
    fun `Muse's 429 is a soft limit until the reset its body names, where a sibling head reads its Retry-After`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp, VirtualTime(autoAdvance = true, startWall = MUSE_WINDOW_START)) {
            provider.script(reply { status(429); header("retry-after", "7728"); body(MUSE_LIMIT_BODY) })
            val muse = responses(replies = HeadReplies.muse)

            val answer = muse.client.send()

            assertLine(answer, MUSE_RESET_LINE)
            val sibling = lineOf(responses().client.send())
            assertFalse(MUSE_RESET_LINE in sibling, "a standard head reads the firm Retry-After instead: $sibling")
        }
    }

    @Test
    fun `codex's usage_limit_reached names the weekly window it spent and when it resets`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(429); body(CODEX_WEEKLY_LIMIT) })
            val codex = responses(replies = HeadReplies.codex)

            val answer = codex.client.send()

            assertLine(answer, "its weekly limit")
            assertLine(answer, "which resets Oct 28")
        }
    }

    @Test
    fun `a local runtime loading its model says so, where a hosted sibling head only says it is overloaded`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(503); body(LLAMA_LOADING) })
            val local = localChat(replies = HeadReplies.localChat)

            val answer = local.client.send()

            assertLine(answer, "is still loading its model")
            val sibling = lineOf(chat().client.send())
            assertTrue("overloaded" in sibling, "a hosted head reads a plain overload: $sibling")
            assertFalse("loading its model" in sibling, sibling)
        }
    }

    @Test
    fun `a local runtime's context refusal says the prompt is too long and names the window it holds`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(400); body(LLAMA_CONTEXT) })
            val local = localChat(replies = HeadReplies.localChat)

            // A first message's line leaves the compaction words out (failure-handling slice 2), so this one has an
            // exchange before it.
            val answer = local.client.send(ClientRequest(extra = Json.parseToJsonElement(LATER_EXCHANGE).jsonObject))

            assertLine(answer, "This prompt is too long for")
            assertLine(answer, "holds 4,096 tokens")
        }
    }

    // Muse mints a key from the login before each turn, and a mint that grants nothing is the login's own refusal, with
    // the reason it gave (MuseMintVerdict): the person reads that reason instead of being told to sign in, which is
    // what a signed-in person was told until slice 3 (final-design.md section 12, defect 24). Until slice 5's credit
    // hold, whose line says to renew with Muse, the line ends as today's retry does (the interim credit line).
    @Test
    fun `an inactive Muse subscription says so and never names the login command`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val muse = refusedResponses(subscription = true, says = HeadSays(MUSE_LOGIN, MUSE_NAME))

            val answer = muse.client.send()

            assertLine(answer, "$MUSE_NAME subscription is not active")
            assertLine(answer, RESENDS)
            val line = lineOf(answer)
            assertFalse(MUSE_LOGIN in line, "a signed-in person is never told to sign in again: $line")
            assertEquals(0, provider.requests.size, "a login its own mint refused sends no request")
        }
    }

    @Test
    fun `a Muse mint that denied the login ends with the forbidden line`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            val muse = refusedResponses(subscription = false, says = HeadSays(MUSE_LOGIN, MUSE_NAME))

            val answer = muse.client.send()

            assertLine(answer, "may not use this model")
            val line = lineOf(answer)
            assertFalse(MUSE_LOGIN in line, "a login the provider refused is not a login to renew: $line")
            assertEquals(0, provider.requests.size, "a login its own mint refused sends no request")
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

    private fun assertUnseenClose(answer: ClientAnswer) {
        val line = lineOf(answer)
        assertFalse("cut off" in line, "an answer the person never saw is not cut off: $line")
        assertTrue(CLOSED_UNSEEN in line, "says \"$CLOSED_UNSEEN\": $line")
    }
}
