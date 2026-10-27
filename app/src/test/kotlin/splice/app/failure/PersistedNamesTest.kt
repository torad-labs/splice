// NEW: failure-handling slice 1. Pins the cause name each turn ending writes to its perf row today, and the usage
// console's Cut off and Given up figures over those rows, so the slice 1 refactor (one sealed cause, its persisted
// name moved to PersistedCause) cannot change a stored row. A characterization: every expected name is read from the
// site that writes it today (core/turn/FailureCause.kt:42-109 and the sites below), never copied from a run.
//
// One case for each ending in final-design.md section 6's persisted-name table that a scripted provider can reach:
//  - a status the upstream client gives up on is written by TurnKnownEnd.emitFailed with the classifier's cause
//    (UpstreamFailureClassifier), after ForeignCredential's rewrite;
//  - an in-band ending is written by TurnFinish with the translator's cause (ChatTerminalState, ChatStreamTranslator,
//    PassthroughTerminalState);
//  - a cancellation the head seals (stop, restart, the no-progress limit) and a local refusal write no cause.
// Not reachable here, and said so rather than skipped in silence: AUTH_REFRESH_FAILED, POOL_EXHAUSTED and
// ADMISSION_FULL have no producer today (no site constructs them), the code-mode machine runs only on a codex head
// with a runtime, and HoldOutlasted does not exist before slice 5. Failure-handling slice 2 changed one name on
// purpose: a 402 writes VENDOR_QUOTA_EXHAUSTED, the name of the payment it is, where slice 1 pinned the rate limit
// every retry layer still sees.
package splice.app.failure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.turn.ErrorType
import splice.head.scripted.ClientRequest
import splice.head.scripted.ClientStream
import splice.head.scripted.HeadShape
import splice.head.scripted.ReplyScript
import splice.head.scripted.ScriptedHead
import splice.head.scripted.ScriptedHeads
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** A first-output tier short enough to pass in one virtual step, and the step that passes it. */
private val FIRST_OUTPUT = 1.seconds
private val PAST_FIRST_OUTPUT = 2.seconds

/** Longer than the watchdog's real-time poll for a one-second tier (a third of the tier, Watchdog.pollInterval). */
private const val WATCHDOG_POLL_MS = 400L

/** How many clock steps a turn gets to end in before the test says where it stood. */
private const val MAX_STEPS = 60

class PersistedNamesTest {
    private val overloaded = OutcomeTags.failure(ErrorType.OVERLOADED)

    @Test
    fun `a turn cut off mid-answer writes UPSTREAM_TRUNCATED and counts in Cut off`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // ChatStreamTranslator.unfinishedOutcome: a read that ends without a finish_reason is a truncation, and
            // ChatResendOnce re-sends only a permanent failure, so the tear ends the turn.
            provider.script(reply { chatText("partial words"); hold(); cut() })
            val head = chat()
            val stream = head.client.open()
            stream.awaitText("partial words")
            provider.release()
            stream.finish()

            val records = HeadRecords(head)
            val ended = records.ended()
            assertEquals(overloaded, ended.outcome)
            assertEquals("UPSTREAM_TRUNCATED", ended.cause)
            assertEquals(1, records.figure("cutoff"))
            assertEquals(0, records.figure("gaveup"))
        }
    }

    @Test
    fun `a turn that stalled writes UPSTREAM_STALLED and counts in Given up`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // A silent round is reaped only on a path that cannot answer (Watchdog.launchIn, PathLiveness.agrees): the
            // provider's address refuses a connect while the round's own connection stays open. ChatStreamTranslator
            // then ends the turn as stalled, and ChatResendOnce does not re-send it. The head holds its status until
            // content or a failure (PendingSse), so the request reaching the provider is the sign the round is open.
            provider.script(reply { event(ROLE_ONLY_CHUNK); hold(); chatEnd() })
            val head = chat(HeadShape(firstOutput = FIRST_OUTPUT, relayed = true))
            val stream = head.client.open()
            provider.awaitRequests(1)
            head.relay.stopListening()
            advanceUntilEnded(head, stream)

            val records = HeadRecords(head)
            val ended = records.ended()
            assertEquals(overloaded, ended.outcome)
            assertEquals("UPSTREAM_STALLED", ended.cause)
            assertEquals(1, records.figure("gaveup"))
            assertEquals(0, records.figure("cutoff"))
        }
    }

    @Test
    fun `a console stop writes no cause`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // CancellationSeal.endingOf: the operator's stop records its tag and no cause.
            provider.script(reply { responsesStart(); responsesText("running"); hold(); responsesEnd() })
            val head = responses()
            val stream = head.client.open()
            stream.awaitText("running")
            head.liveTurns.stop(head.liveTurns.list().single().id)
            stream.finish()

            val ended = HeadRecords(head).ended(OutcomeTags.error("stopped"))
            assertNull(ended.cause)
        }
    }

    @Test
    fun `a restart writes no cause`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatText("running"); hold(); chatEnd() })
            val head = localChat()
            val stream = head.client.open()
            stream.awaitText("running")
            head.restart()
            stream.finish()

            val ended = HeadRecords(head).ended(OutcomeTag.RESTARTED.wire)
            assertNull(ended.cause)
        }
    }

    @Test
    fun `a turn the no-progress limit ended writes no cause and counts in Given up`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // No round is open while the head waits for the status, so nothing holds the limit (Watchdog.capHolds),
            // and CancellationSeal records the turn cap with no cause. TurnsTest counts that tag as Given up.
            provider.script(reply { hold(); chatAnswer("too late") })
            val head = chat(HeadShape(progressCap = FIRST_OUTPUT))
            val stream = head.client.open()
            provider.awaitRequests(1)
            advanceUntilEnded(head, stream)

            val records = HeadRecords(head)
            val ended = records.ended()
            assertEquals(OutcomeTag.TURN_CAP.wire, ended.outcome)
            assertNull(ended.cause)
            assertEquals(1, records.figure("gaveup"))
        }
    }

    @Test
    fun `a request turned away at capacity writes no cause`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatText("first turn"); hold(); chatEnd() })
            val head = chat(HeadShape(maxInflight = 1, maxQueued = 1))
            val running = head.client.open()
            running.awaitText("first turn")
            val queued = head.client.open()
            head.awaitQueued(1)
            head.client.send()
            provider.release()
            running.finish()
            queued.finish()

            val ended = HeadRecords(head).ended(OutcomeTag.AT_CAPACITY.wire)
            assertNull(ended.cause)
        }
    }

    // A status the upstream client gave up on: TurnKnownEnd.emitFailed writes the classifier's cause
    // (UpstreamFailureClassifier.classifyContent and statusFallback) under the tag endingTag picks: a 429 with no
    // reply to relay is RATE_LIMITED, any other status UPSTREAM_FAILED.

    @Test
    fun `a rate limit writes VENDOR_RATE_LIMITED`(@TempDir tmp: Path) = runTest {
        val ended = statusEnding(tmp, TOO_MANY_REQUESTS, "requests", "Rate limit reached.", "rate_limit_exceeded")
        assertEquals(OutcomeTag.RATE_LIMITED.wire, ended.outcome)
        assertEquals("VENDOR_RATE_LIMITED", ended.cause)
    }

    @Test
    fun `an overloaded provider writes UPSTREAM_STATUS_5XX`(@TempDir tmp: Path) = runTest {
        val ended = statusEnding(tmp, SERVICE_UNAVAILABLE, "server_error", "Model at capacity.", OVERLOAD_CODE)
        assertEquals(OutcomeTag.UPSTREAM_FAILED.wire, ended.outcome)
        assertEquals("UPSTREAM_STATUS_5XX", ended.cause)
    }

    @Test
    fun `a server error writes UPSTREAM_STATUS_5XX`(@TempDir tmp: Path) = runTest {
        val ended = statusEnding(tmp, INTERNAL_SERVER_ERROR, "server_error", "Internal error.", "internal_error")
        assertEquals(OutcomeTag.UPSTREAM_FAILED.wire, ended.outcome)
        assertEquals("UPSTREAM_STATUS_5XX", ended.cause)
    }

    @Test
    fun `a rejected request writes UPSTREAM_STATUS_4XX`(@TempDir tmp: Path) = runTest {
        val ended = statusEnding(tmp, BAD_REQUEST, "invalid_request_error", "Invalid value.", "invalid_value")
        assertEquals(OutcomeTag.UPSTREAM_FAILED.wire, ended.outcome)
        assertEquals("UPSTREAM_STATUS_4XX", ended.cause)
    }

    @Test
    fun `a forbidden request writes UPSTREAM_STATUS_4XX`(@TempDir tmp: Path) = runTest {
        val ended = statusEnding(tmp, FORBIDDEN, "permission_error", "Not allowed for this project.", "denied")
        assertEquals("UPSTREAM_STATUS_4XX", ended.cause)
    }

    @Test
    fun `a missing model writes UPSTREAM_STATUS_4XX`(@TempDir tmp: Path) = runTest {
        val ended =
            statusEnding(tmp, NOT_FOUND, "invalid_request_error", "The model does not exist.", "model_not_found")
        assertEquals("UPSTREAM_STATUS_4XX", ended.cause)
    }

    @Test
    fun `a payment the provider wants writes VENDOR_QUOTA_EXHAUSTED`(@TempDir tmp: Path) = runTest {
        // A payment the provider wants is held as a limit is (V4-73), so the row keeps the rate-limit tag endingTag
        // gives it by cause since failure-handling slice 3, and the turn ends on the payment the provider asked for,
        // whose persisted name is VENDOR_QUOTA_EXHAUSTED (final-design.md section 6).
        val ended = statusEnding(tmp, PAYMENT_REQUIRED, "billing_error", "Add credit to continue.", "payment_required")
        assertEquals(OutcomeTag.RATE_LIMITED.wire, ended.outcome)
        assertEquals("VENDOR_QUOTA_EXHAUSTED", ended.cause)
    }

    @Test
    fun `a refused login writes AUTH_MISSING`(@TempDir tmp: Path) = runTest {
        val ended = statusEnding(tmp, UNAUTHORIZED, "invalid_request_error", "Invalid authentication.", "invalid_auth")
        assertEquals(OutcomeTag.UPSTREAM_FAILED.wire, ended.outcome)
        assertEquals("AUTH_MISSING", ended.cause)
    }

    @Test
    fun `a 401 naming a key the login did not send writes UPSTREAM_REPORTED`(@TempDir tmp: Path) = runTest {
        // ForeignCredential.upstreamsOwn: no masked key in the body can be the token the head sent, so the refusal is
        // the upstream's own and not the login's.
        val ended = statusEnding(tmp, UNAUTHORIZED, "invalid_request_error", FOREIGN_KEY_WORDS, "invalid_api_key")
        assertEquals(OutcomeTag.UPSTREAM_FAILED.wire, ended.outcome)
        assertEquals("UPSTREAM_REPORTED", ended.cause)
    }

    @Test
    fun `a context overflow on a request that does not stream writes REQUEST_TOO_LARGE`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(
                reply { status(BAD_REQUEST); openAiErrorBody("invalid_request_error", OVERFLOW_WORDS, OVERFLOW_CODE) },
            )
            val head = chat()
            head.client.send(ClientRequest(stream = false))

            assertEquals("REQUEST_TOO_LARGE", HeadRecords(head).ended().cause)
        }
    }

    // An ending the provider reported inside the stream: TurnFinish writes the cause the dialect latched, under the
    // type WireType.of gives it once content went out.

    @Test
    fun `an error event after content writes UPSTREAM_REPORTED`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // PassthroughTerminalState.onError: a type Anthropic's table does not name is the upstream's own report.
            // Since failure-handling slice 3 api_error is named, the 500 it stands for (AnthropicReplies), so the
            // event here carries a type no row names.
            provider.script(
                reply {
                    anthropicStart()
                    anthropicText("partial words")
                    anthropicError("upstream_hiccup", "Internal")
                },
            )
            val head = anthropic()
            head.client.send()

            val ended = HeadRecords(head).ended()
            assertEquals(OutcomeTags.failure(ErrorType.API_ERROR), ended.outcome)
            assertEquals("UPSTREAM_REPORTED", ended.cause)
        }
    }

    @Test
    fun `a model that stops on refusal writes MODEL_REFUSED`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // PassthroughFailureRules.stopReasonFailure: the refusal stop_reason is the model's verdict on the request.
            provider.script(reply { anthropicStart(); anthropicText("I will not"); anthropicEnd("refusal") })
            val head = anthropic()
            head.client.send()

            val ended = HeadRecords(head).ended()
            assertEquals(OutcomeTags.failure(ErrorType.INVALID_REQUEST), ended.outcome)
            assertEquals("MODEL_REFUSED", ended.cause)
        }
    }

    @Test
    fun `a content-policy refusal writes CONTENT_FILTERED`(@TempDir tmp: Path) = runTest {
        // OpenAiReplies: OpenAI's own content-policy code outranks the status. Codex's cyber_policy is the ChatGPT
        // backend's (CodexReplies) and reads as a refusal on claudex alone since failure-handling slice 3.
        val ended =
            statusEnding(tmp, BAD_REQUEST, "invalid_request_error", "Request rejected.", "content_policy_violation")
        assertEquals(OutcomeTag.UPSTREAM_FAILED.wire, ended.outcome)
        assertEquals("CONTENT_FILTERED", ended.cause)
    }

    @Test
    fun `a context overflow on a streamed request writes REQUEST_TOO_LARGE`(@TempDir tmp: Path) = runTest {
        // The classifier's overflow arm, written by TurnKnownEnd.emitFailed; the 400 the client gets is PendingSse's.
        val ended = statusEnding(tmp, BAD_REQUEST, "invalid_request_error", OVERFLOW_WORDS, OVERFLOW_CODE)
        assertEquals(OutcomeTag.UPSTREAM_FAILED.wire, ended.outcome)
        assertEquals("REQUEST_TOO_LARGE", ended.cause)
    }

    @Test
    fun `a head whose login is gone writes no cause`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // UpstreamClient finds no credential, and TurnKnownEnd.emitAuthMissing records its tag with no failure.
            val head = signedOutChat()
            head.client.send()

            val ended = HeadRecords(head).ended()
            assertEquals(OutcomeTag.AUTH_MISSING.wire, ended.outcome)
            assertNull(ended.cause)
            assertEquals(0, provider.requests.size)
        }
    }

    @Test
    fun `a node's word refused before any provider request writes no cause`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // PreDriveRefusals.refuseIfNodeWordUnheld records a LocalRefusal: a tag and a detail, never a cause.
            val head = forwardingAnthropic()
            head.client.send(ClientRequest(bearer = SHARE_NODE_TOKEN))

            val ended = HeadRecords(head).ended()
            assertEquals(OutcomeTag.AUTH_MISSING.wire, ended.outcome)
            assertNull(ended.cause)
        }
    }

    @Test
    fun `a provider whose address refuses the connection writes CONNECT_REFUSED`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // TurnConnEnd: every attempt met a refused connect on a loopback address with its own port.
            val head = chat(HeadShape(relayed = true))
            head.relay.stopListening()
            head.client.send()

            val ended = HeadRecords(head).ended()
            assertEquals(OutcomeTags.error("conn-reset"), ended.outcome)
            assertEquals("CONNECT_REFUSED", ended.cause)
        }
    }

    @Test
    fun `a model's refusal in a chat stream writes MODEL_REFUSED`(@TempDir tmp: Path) = runTest {
        // ChatTerminalState.providerFailure: the refusal the deltas carried is the model's verdict on the request.
        val ended = chatEnding(tmp) { chatText("partial words"); event(REFUSAL_CHUNK); chatEnd() }
        assertEquals(OutcomeTags.failure(ErrorType.INVALID_REQUEST), ended.outcome)
        assertEquals("MODEL_REFUSED", ended.cause)
    }

    @Test
    fun `a filtered chat answer writes CONTENT_FILTERED`(@TempDir tmp: Path) = runTest {
        // ChatTerminalState.onFinish: finish_reason content_filter.
        val ended = chatEnding(tmp) { chatText("partial words"); chatEnd("content_filter") }
        assertEquals(OutcomeTags.failure(ErrorType.INVALID_REQUEST), ended.outcome)
        assertEquals("CONTENT_FILTERED", ended.cause)
    }

    @Test
    fun `a tool call whose arguments never close writes TOOL_TEAR`(@TempDir tmp: Path) = runTest {
        // ChatStreamTranslator.driveTurn parses each opened tool's arguments at the end: these are not JSON.
        val ended = chatEnding(tmp) {
            chatText("Let me look.")
            event(TOOL_OPEN_CHUNK)
            event(TOOL_ARGS_CHUNK)
            chatEnd("tool_calls")
        }
        assertEquals(OutcomeTags.failure(ErrorType.API_ERROR), ended.outcome)
        assertEquals("TOOL_TEAR", ended.cause)
    }

    @Test
    fun `an error the provider reported inside a chat stream writes UPSTREAM_REPORTED`(@TempDir tmp: Path) = runTest {
        // ChatTerminalState.onError hands the error to the head's reader, whose floor for an error no row names is the
        // upstream's own report (Unrecognized). Since failure-handling slice 3 a server_error type is the 500 it
        // stands for (OpenAiReplies), so the error here names no type.
        val ended = chatEnding(tmp) { chatText("partial words"); event(IN_BAND_ERROR) }
        assertEquals(OutcomeTags.failure(ErrorType.API_ERROR), ended.outcome)
        assertEquals("UPSTREAM_REPORTED", ended.cause)
    }

    @Test
    fun `an answer with nothing in it writes no cause`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // EmptyAnswerRetry posts the empty round once more, then StreamPromote ends it as empty_model with no
            // cause.
            provider.script(reply { event(ROLE_ONLY_CHUNK); chatEnd() })
            val head = chat()
            head.client.send()

            val ended = HeadRecords(head).ended()
            assertEquals(OutcomeTag.EMPTY_MODEL.wire, ended.outcome)
            assertNull(ended.cause)
        }
    }

    @Test
    fun `a custom tool call the dialect cannot carry writes DIALECT_UNSUPPORTED`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // RoundStrategy: a custom_tool_call item on a round with no interceptor for it.
            provider.script(reply { responsesStart(); event(CUSTOM_CALL_DONE); responsesEnd() })
            val head = responses()
            head.client.send()

            val ended = HeadRecords(head).ended()
            assertEquals(OutcomeTags.failure(ErrorType.INVALID_REQUEST), ended.outcome)
            assertEquals("DIALECT_UNSUPPORTED", ended.cause)
        }
    }

    /** A chat head whose provider streams [script], and the row the turn wrote once the client's answer ended. */
    private suspend fun chatEnding(tmp: Path, script: ReplyScript.() -> Unit): EndedTurn =
        withScriptedHeads(tmp) {
            provider.script(reply(script))
            val head = chat()
            head.client.send()
            HeadRecords(head).ended()
        }

    /** A chat head whose provider answers every attempt with [status] and an OpenAI error body, and the row the turn
     *  wrote once the client's answer ended. */
    private suspend fun statusEnding(tmp: Path, status: Int, type: String, message: String, code: String): EndedTurn =
        withScriptedHeads(tmp) {
            provider.script(reply { status(status); openAiErrorBody(type, message, code) })
            val head = chat()
            head.client.send()
            HeadRecords(head).ended()
        }

    /** Moves the head's clock past the first-output tier until the answer ends. The watchdog samples that clock on a
     *  real-time poll, and a byte still in flight when the clock moves starts the silence again, so one step may not
     *  be the last. */
    private suspend fun ScriptedHeads.advanceUntilEnded(head: ScriptedHead, stream: ClientStream) {
        withContext(Dispatchers.Default) {
            repeat(MAX_STEPS) {
                if (stream.finished) return@withContext
                time.advanceBy(PAST_FIRST_OUTPUT)
                delay(WATCHDOG_POLL_MS)
            }
            fail<Unit>("the turn did not end: ${stream.soFar().describe()}\n${head.log.joinToString("\n")}")
        }
    }
}

private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val PAYMENT_REQUIRED = 402
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val TOO_MANY_REQUESTS = 429
private const val INTERNAL_SERVER_ERROR = 500
private const val SERVICE_UNAVAILABLE = 503

/** A code the classifier reads as an overload by its shape (UpstreamFailureClassifier, FailureCodes). */
private const val OVERLOAD_CODE = "server_is_overloaded"

/** A rejection naming a masked key in the shape the Codex backend masks one: a visible head, a run of stars, a visible
 *  tail. It is no token a scripted head sends. */
private val FOREIGN_KEY_WORDS = "Incorrect API key provided: sk-test0" + "*".repeat(40) + "Zq9x."

/** A context overflow in the words and code OpenAI's APIs use (UpstreamFailureClassifier's overflow arm). */
private const val OVERFLOW_WORDS = "This model's maximum context length is 131072 tokens."
private const val OVERFLOW_CODE = "context_length_exceeded"

/** A chunk whose delta is the model's refusal (ChatProseFold). */
private const val REFUSAL_CHUNK =
    """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted","choices":""" +
        """[{"index":0,"delta":{"refusal":"I will not help with that."},"finish_reason":null}]}"""

/** A tool call opened with the start of its arguments, and the rest of them, which never close the object. */
private const val TOOL_OPEN_CHUNK =
    """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted","choices":[{"index":0,""" +
        """"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"Read",""" +
        """"arguments":"{\"path\": "}}]},"finish_reason":null}]}"""
private const val TOOL_ARGS_CHUNK =
    """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted","choices":[{"index":0,""" +
        """"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"src"}}]},"finish_reason":null}]}"""

/** An error object in a chat stream, with a kind and no status. */
private const val IN_BAND_ERROR = """{"error":{"message":"upstream exploded"}}"""

/** A Responses output item no Anthropic block can carry. */
private const val CUSTOM_CALL_DONE =
    """{"type":"response.output_item.done","output_index":1,"item":{"type":"custom_tool_call",""" +
        """"call_id":"call_1","name":"apply_patch","input":"x"}}"""

/** A Chat Completions chunk that opens the assistant's message and carries no content. */
private const val ROLE_ONLY_CHUNK =
    """{"id":"chatcmpl-scripted","object":"chat.completion.chunk","model":"scripted","choices":""" +
        """[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}"""
