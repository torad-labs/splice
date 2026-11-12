// NEW: failure-handling slice 3. The readings every head of an API type shares, driven through a real head in front of
// the scripted provider: the rows no vendor deviates from, the stop reasons the Anthropic standard reads, the
// foreign-key rule every API type shares, and the refusals that answer before any provider request
// (final-design.md sections 6, 7 and 12). The vendor deviations are ReadOnceThroughHeadsTest's.
package splice.app.failure

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadReplies
import splice.core.turn.ReasoningDisplay
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.dialect.responses.failure.ResponsesReplies
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.Family
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderTuning
import splice.upstream.failure.Replies
import java.nio.file.Path

/** The prefix every line a person reads opens with. */
private const val LINE_PREFIX = "[splice] "

/** A 400 that names the field it refused: the request as sent is what it rejects. */
private const val FIELD_REJECTED =
    """{"error":{"type":"invalid_request_error","message":"messages.3.content.0.text: field required"}}"""

/** The Responses 400 for reasoning this conversation can no longer send (the reasoning-cache rejection, RC-4). */
private const val STALE_REASONING =
    """{"error":{"type":"invalid_request_error","code":"invalid_value","message":"Item 'rs_68d2' of type """ +
        """'reasoning' was provided without its required 'encrypted_content'."}}"""

/** What a line says where the record shows Claude Code sending the turn again itself. */
private const val RESENDS = "Claude Code sends this turn again."

/** A conversation past its first exchange: only its overflow line may say the words Claude Code compacts on. */
private const val LATER_EXCHANGE =
    """{"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"hello"},""" +
        """{"role":"user","content":"go on"}]}"""

/** The session the stale-reasoning conversation runs in: the head keeps a round's reasoning per session. */
private const val STALE_SESSION = "stale-reasoning"

/** The tool the stale-reasoning conversation declares, its opening, and the call and result that follow. */
private const val RUN_TOOL = """{"name":"run","description":"run","input_schema":{"type":"object","properties":{}}}"""
private const val OPENING = """{"role":"user","content":"fix the failing build"}"""
private const val CALLED =
    """{"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"run","input":{}}]}"""
private const val RESULT =
    """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"ok"}]}"""

/** A tool round as a Responses backend streams it: its encrypted reasoning, then the call it planned. */
private val TOOL_ROUND = listOf(
    """{"type":"response.created","response":{"id":"resp_round","status":"in_progress","output":[]}}""",
    """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}""",
    """{"type":"response.output_item.done","output_index":0,""" +
        """"item":{"type":"reasoning","id":"rs_stale","encrypted_content":"cipher-stale","summary":[]}}""",
    """{"type":"response.output_item.added","output_index":1,""" +
        """"item":{"type":"function_call","call_id":"call_1","name":"run"}}""",
    """{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{}"}""",
    """{"type":"response.completed","response":{"id":"resp_round","usage":{"input_tokens":100,"output_tokens":7}}}""",
)

/** A Responses head with the reasoning cache on, as codex's own head runs it (RC-4): a request carries the reasoning of
 *  the round before it with that round's call, which a provider that can no longer decrypt it refuses. */
private class ReasoningCacheResponses(tuning: ProviderTuning, log: LogSink) : ResponsesProvider(
    tuning,
    ReasoningSettings(ReasoningDisplay.TEXT, replay = false, effort = "high", summary = "detailed"),
    ResponsesQuirks(
        providerTag = tuning.key,
        lite = ResponsesLiteQuirks(),
        roundTrip = ResponsesRoundTripQuirks(reasoningCache = true),
    ),
    log = log,
    replies = Replies(ResponsesReplies),
)

/** A request's messages, and the tool the stale-reasoning conversation declares. */
private fun conversation(vararg messages: String): JsonObject =
    Json.parseToJsonElement("""{"messages":[${messages.joinToString(",")}],"tools":[$RUN_TOOL]}""").jsonObject

/** V4-242: a 401 naming masked keys, none of which the login this head sent can be (the Codex outage of Sep 25). */
private const val FOREIGN_KEY_BODY =
    """{"error":{"type":"invalid_request_error","code":"invalid_api_key",""" +
        """"message":"Incorrect API key provided: sk-svcac****fvMA."}}"""

/** The head's own login never carries these, so no fragment of them may reach a person's line. */
private const val FOREIGN_KEY_HEAD = "sk-svcac"
private const val FOREIGN_KEY_TAIL = "fvMA"

/** A message_delta whose stop_reason is the model's own refusal, with the details it carries (trace fae124199450). */
private const val REFUSAL_DELTA =
    """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_sequence":null,"stop_details":""" +
        """{"type":"refusal","category":"harmful_content","explanation":"The assistant declined to continue"}},""" +
        """"usage":{"output_tokens":9}}"""

/** A message_delta that ends the answer because it outgrew the window, and one that only pauses the turn. */
private const val WINDOW_DELTA =
    """{"type":"message_delta","delta":{"stop_reason":"model_context_window_exceeded","stop_sequence":null},""" +
        """"usage":{"output_tokens":4096}}"""
private const val PAUSE_DELTA =
    """{"type":"message_delta","delta":{"stop_reason":"pause_turn","stop_sequence":null},""" +
        """"usage":{"output_tokens":12}}"""

private const val MESSAGE_STOP = """{"type":"message_stop"}"""

/** xAI's spending-limit 402 of Oct 20: its `error` is a plain string, and its code names the block. */
private const val XAI_SPENDING_LIMIT =
    """{"code":"personal-team-blocked:spending-limit","error":"Your team has been blocked from making requests """ +
        """because it reached its spending limit. Raise it in the console to continue."}"""

/** Muse's 403, whose own sentence names the plan limit it reached (MuseOAuth.isPlanTierRejection). */
private const val MUSE_FORBIDDEN =
    """{"error":{"code":"forbidden","message":"You have reached your plan limit for this model on Muse."}}"""

/** A client-forwarded login the provider refuses; the head holds no login of its own to renew. */
private const val CLIENT_LOGIN_REFUSED =
    """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

class ReadOnceStandardThroughHeadsTest {
    @Test
    fun `a 400 naming a body field reaches the provider once`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(400); body(FIELD_REJECTED) })
            val head = chat()

            val answer = head.client.send()

            assertLine(answer, "rejected the request as sent")
            assertEquals(1, provider.requests.size, "a request the provider named is not sent again")
        }
    }

    // RC-4 through the head's reading: the request after a tool round carries that round's reasoning, the provider
    // refuses it as stale (ResponsesReplies reads Rejected(staleReasoning)), and the head drops the reasoning and sends
    // the request once more. A request with no reasoning in it has nothing to amend, so the conversation has a round.
    @Test
    fun `a Responses reply naming encrypted_content is amended once and the answer streams`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val head = custom("cached") { tuning, log -> ReasoningCacheResponses(tuning, log) }
            provider.script(reply { TOOL_ROUND.forEach { event(it) } })
            head.client.send(ClientRequest(session = STALE_SESSION, extra = conversation(OPENING)))
            provider.script(
                reply { status(400); body(STALE_REASONING) },
                reply { responsesAnswer("the answer after the amendment") },
            )

            val answer = head.client.send(
                ClientRequest(session = STALE_SESSION, extra = conversation(OPENING, CALLED, RESULT)),
            )

            assertEquals("the answer after the amendment", answer.text, answer.describe())
            val bodies = provider.requests.map { it.body }
            assertEquals(3, bodies.size, "the round, the refused request and its one amended resend: $bodies")
            assertTrue("rs_stale" in bodies[1], "the refused request carried the round's reasoning: ${bodies[1]}")
            assertFalse("rs_stale" in bodies[2], "the amendment drops the reasoning refused as stale: ${bodies[2]}")
        }
    }

    @Test
    fun `a forwarded login's 401 answers 401 authentication_error, and splice renews nothing`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(401); body(CLIENT_LOGIN_REFUSED) })
            val head = forwardingAnthropic()

            val answer = head.client.send(ClientRequest(bearer = "the-caller-own-login"))

            assertEquals(401, answer.status, answer.describe())
            assertEquals("authentication_error", answer.error?.type, answer.describe())
            assertEquals(1, provider.requests.size, "a login splice does not own is not renewed")
        }
    }

    @Test
    fun `a 401 naming a masked key this login never sent is the provider's own fault, re-sent, with no fragment`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(401); body(FOREIGN_KEY_BODY) })
            val head = chat()

            val answer = head.client.send()

            val line = lineOf(answer)
            assertTrue("a fault on its side" in line, line)
            assertFalse(FOREIGN_KEY_HEAD in line || FOREIGN_KEY_TAIL in line, "no key fragment reaches a line: $line")
            assertTrue(provider.requests.size > 1, "a server error is asked again, and the login stays in the pool")
        }
    }

    @Test
    fun `a stop_reason refusal ends with the model's refusal, quoting what it said, and is not re-sent`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    anthropicStart()
                    event(REFUSAL_DELTA, "message_delta")
                    event(MESSAGE_STOP, "message_stop")
                },
            )
            val head = anthropic()

            val answer = head.client.send()

            assertLine(answer, "The model on")
            assertLine(answer, "declined to answer")
            assertLine(answer, "The assistant declined to continue (harmful_content)")
            assertFalse(Family.R in answer.families(), "a refusal is not re-sent: ${answer.describe()}")
        }
    }

    @Test
    fun `a stop_reason model_context_window_exceeded after content says the answer filled the window`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply {
                    anthropicStart()
                    anthropicText("The first part of the answer")
                    event(WINDOW_DELTA, "message_delta")
                    event(MESSAGE_STOP, "message_stop")
                },
            )
            val head = anthropic()

            // A first message's line leaves the compaction words out (failure-handling slice 2), so this one has an
            // exchange before it.
            val answer = head.client.send(ClientRequest(extra = Json.parseToJsonElement(LATER_EXCHANGE).jsonObject))

            assertTrue(answer.contentBeforeError, answer.describe())
            assertLine(answer, "context window before it finished")
        }
    }

    @Test
    fun `a pause_turn ends as today, an overload the client may send again`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // Slice 0b's record: Claude Code 2.1.310 does not continue a paused turn, so this keeps today's reading.
            provider.script(
                reply {
                    anthropicStart()
                    event(PAUSE_DELTA, "message_delta")
                    event(MESSAGE_STOP, "message_stop")
                },
            )
            val head = anthropic()

            val answer = head.client.send()

            assertLine(answer, "overloaded")
            assertTrue(Family.R in answer.families(), "today's reading lets the client send it again")
        }
    }

    @Test
    fun `xAI's spending-limit 402 ends exactly as a sibling head's does, with the credit line and today's retry`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(402); body(XAI_SPENDING_LIMIT) })
            val grok = chat(replies = HeadReplies.grokChat)

            val answer = grok.client.send()

            assertLine(answer, "cannot pay for this turn")
            // Until slice 5's credit hold the line ends as today's retry does (final-design.md section 10, the interim
            // credit line); "Add credit" is the hold's own line.
            assertLine(answer, RESENDS)
            assertTrue(Family.R in answer.families(), "V4-73's retry of a spent account is kept")
            // No xAI rule reads this reply: a sibling head of the same API type ends with the same line.
            assertEquals(lineOf(answer), lineOf(chat().client.send()))
        }
    }

    @Test
    fun `Muse's 403 shows Muse's own sentence`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { status(403); body(MUSE_FORBIDDEN) })
            val head = responses(replies = HeadReplies.muse)

            val answer = head.client.send()

            assertLine(answer, "may not use this model")
            assertLine(answer, "plan limit")
        }
    }

    @Test
    fun `a codex tool choice naming no declared tool is refused before any provider request`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val head = custom("codex") { tuning, log ->
                CodexProvider(tuning, ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"), log = log)
            }

            val answer = head.client.send(
                ClientRequest(
                    extra = buildJsonObject {
                        put(
                            "tool_choice",
                            buildJsonObject {
                                put("type", "tool")
                                put("name", "a_tool_this_request_never_declared")
                            },
                        )
                    },
                ),
            )

            assertLine(answer, "cannot guarantee the tool choice this request asks for")
            assertEquals(0, provider.requests.size, "a request splice refuses never reaches the provider")
        }
    }

    // The Responses standard carries a json_schema output format and nothing else, so a format it cannot carry is a
    // request feature the head refuses before any provider sees it (ResponsesOutputSchema,
    // RequestFeature.OUTPUT_FORMAT).
    @Test
    fun `a Responses output format the head cannot carry is refused before any provider request`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            val head = responses()

            val answer = head.client.send(
                ClientRequest(
                    extra = buildJsonObject {
                        put("output_format", buildJsonObject { put("type", "json_object") })
                    },
                ),
            )

            assertLine(answer, "cannot carry the output format this request asks for")
            assertEquals(0, provider.requests.size, "a request splice refuses never reaches the provider")
        }
    }

    /** The line the client was given: an in-band error event's message, or the error body of a status. */
    private fun lineOf(answer: ClientAnswer): String {
        val message = answer.error?.message ?: ""
        assertTrue(message.startsWith(LINE_PREFIX), "a person reads one [splice] line: ${answer.describe()}")
        return message
    }

    private fun assertLine(answer: ClientAnswer, fragment: String) {
        val line = lineOf(answer)
        assertTrue(fragment in line, "says \"$fragment\": $line")
    }
}
