// NEW: V4-41 — the mid-stream re-anchor layer this dialect never had. The operator saw deepseek
// "just stop, no retry at all": three truncations in one minute, attempts=1 on each, because a
// stream that EOFs without message_stop is a 2xx whose handler RETURNS a Failure, so none of the
// three retry budgets can see it and Provider.reanchorController was left at its null default on
// every passthrough head. These tests pin the controller that closes that hole — and, above all,
// the ONE state where appending naively would duplicate what the client already read: a reasoning
// turn, whose prefill is ignored unless the continuation round turns thinking off.
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.dialect.passthrough.PassthroughReanchorController
import splice.spi.ReanchorRound

class PassthroughReanchorTest {

    // The PREFILL-shaped vendor, which kimi and deepseek are by measurement. The restart-only path
    // and the bare default are constructed EXPLICITLY in their own tests below, so a test can never
    // pass on the default constructor by accident.
    private val controller = PassthroughReanchorController(prefill = true)

    private val originalMessages = buildJsonArray {
        add(
            buildJsonObject {
                put("role", "user")
                put("content", "count from 1 to 10")
            },
        )
    }

    /** A request body with every field the continuation must leave alone carrying a DISTINCT value,
     *  so a field that gets dropped, reordered or re-serialized differently cannot pass unnoticed. */
    private fun body(messages: JsonArray = originalMessages, thinking: String? = null): JsonObject = buildJsonObject {
        put("model", "deepseek-flash")
        put("max_tokens", 8192)
        put("temperature", 0.7)
        put("stream", true)
        put("system", buildJsonArray { add(JsonPrimitive("SYSTEM-PROMPT")) })
        put(
            "tools",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "Bash")
                        put("description", "run a command")
                    },
                )
            },
        )
        if (thinking != null) {
            put(
                "thinking",
                buildJsonObject {
                    put("type", thinking)
                    put("budget_tokens", 2048)
                },
            )
        }
        put("messages", messages)
    }

    private fun partial(
        bodyText: String = "",
        toolTearOpen: Boolean = false,
        hasToolUse: Boolean = false,
    ) = TurnOutcome.PartialRound(bodyText = bodyText, toolTearOpen = toolTearOpen, hasToolUse = hasToolUse)

    private fun round(
        body: JsonObject,
        partial: TurnOutcome.PartialRound?,
        attempt: Int = 0,
        // V4-117: the helper varies the CAUSE now, not the type. The controller keys its decision on
        // the failure's type (`type !in RETRYABLE`), and the type is DERIVED from (cause, phase) — so
        // choosing a cause is the only way to state the case, and the default here maps to the same
        // API_ERROR this helper defaulted to before.
        cause: FailureCause = FailureCause.UPSTREAM_REPORTED,
    ) = ReanchorRound(
        requestBody = body,
        failure = TurnOutcome.Failure(
            "stream ended without message_stop",
            cause = cause,
            phase = FailurePhase.MID_OUTPUT,
            partial = partial,
        ),
        attempt = attempt,
    )

    /** The wire shape the client must receive: ONE trailing assistant message of type text. Built by
     *  hand rather than by calling the controller's own builder, so this pins the shape instead of
     *  agreeing with whatever the implementation happens to emit. */
    private fun expectedPrefill(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            },
        )
    }

    @Test
    fun `a salvaged text answer continues as a trailing assistant prefill`() {
        val continued = controller.continuationForFailure(round(body(), partial(bodyText = "1\n2\n3")))
        assertNotNull(continued, "a clean text salvage must be continuable")
        val messages = continued!!["messages"] as JsonArray
        assertEquals(originalMessages.size + 1, messages.size)
        assertEquals(originalMessages.toList(), messages.dropLast(1), "the original messages ride untouched")
        assertEquals(expectedPrefill("1\n2\n3"), messages.last(), "exactly one trailing assistant text message")
        // Probe C (api.deepseek.com/anthropic, 2026-09-16) is why the field is WRITTEN even though
        // this request never carried one: with the thinking key ABSENT this model reasons by
        // default, emits a thinking block and RESTARTS, so absent behaved exactly like enabled —
        // the old code would have appended a continuation to a restarting model and duplicated
        // everything the client had already read. Asserting the VALUE, not the key's presence:
        // omission is a different and wrong behaviour, and that distinction is the whole finding.
        assertEquals(THINKING_OFF, continued["thinking"], "thinking must be written off, never omitted")
    }

    @Test
    fun `the continuation changes messages and thinking and leaves every other field byte-for-byte`() {
        val original = body()
        val continued = controller.continuationForFailure(round(original, partial(bodyText = "1\n2")))
        assertNotNull(continued)
        val continuedBody = continued!!
        // Exactly ONE field may be added — thinking, written off so the continuation round does not
        // re-plan the answer. Everything the request already declared must survive untouched.
        assertEquals(original.keys + THINKING_KEY, continuedBody.keys, "only thinking may be added")
        for (key in original.keys - MESSAGES) {
            assertEquals(original[key], continuedBody[key], "field '$key' must ride through unchanged")
        }
        // model, max_tokens, system and tools are the four the packet names; assert them by name so
        // a rename that moved one into messages would fail here rather than pass by omission.
        assertEquals(JsonPrimitive("deepseek-flash"), continuedBody["model"])
        assertEquals(JsonPrimitive(8192), continuedBody["max_tokens"])
        assertEquals(original["system"], continuedBody["system"])
        assertEquals(original["tools"], continuedBody["tools"])
    }

    @Test
    fun `trailing whitespace on the resume text is trimmed`() {
        // A final assistant message ending in whitespace is rejected upstream, so the prefill must
        // carry the text with its tail stripped — and only its tail: leading text is content.
        val continued = controller.continuationForFailure(
            round(body(), partial(bodyText = "  1\n2\n3 \n\t ")),
        )
        val messages = continued!!["messages"] as JsonArray
        assertEquals(expectedPrefill("  1\n2\n3"), messages.last())
    }

    // ---- V4-41: the per-vendor shape. PREFILL is earned by measurement; RESTART-ONLY is the floor.

    @Test
    fun `restart-only refuses a visible salvage, and that is the muse case`() {
        // MUSE was measured REJECTING the trailing assistant prefill outright: HTTP 400,
        // invalid_request_error, assistant prefill is not supported by this server — and it rejects
        // thinking disabled as well. The controller attaches to the DIALECT, so without this knob
        // every muse truncation would turn into a 400 of the one error class the client does NOT
        // retry, which is worse than the honest overloaded_error it shows today. So PREFILL is
        // opt-in and RESTART-ONLY is the default: a vendor earns the prefill by being measured.
        val restartOnly = PassthroughReanchorController(prefill = false)
        assertNull(restartOnly.continuationForFailure(round(body(), partial(bodyText = "1\n2\n3"))))
    }

    @Test
    fun `restart-only still restarts the whole stream when nothing visible was salvaged`() {
        // RESTART-ONLY is not "do nothing". The verbatim restart duplicates nothing by construction,
        // so it stays available to EVERY vendor, including the ones that cannot take a prefill —
        // and this is the arm that comes before the shape check, so the knob must not reach it.
        val original = body()
        val restartOnly = PassthroughReanchorController(prefill = false)
        val continued = restartOnly.continuationForFailure(round(original, partial(bodyText = "")))
        assertSame(original, continued)
    }

    @Test
    fun `the default constructor is restart-only, so an unmeasured vendor cannot be made worse`() {
        // THE SAFETY PROPERTY OF THIS ROW, asserted against the DEFAULT rather than inferred from a
        // call site: quirks.reanchorPrefill defaults to false and this constructor defaults to
        // false, so a vendor nobody has measured gets exactly today's behaviour. Flipping either
        // default to true must fail HERE, not silently in production on an unmeasured head — which
        // is also why this test uses the bare constructor while the one above passes false
        // explicitly, so a flipped default reddens this one alone.
        val defaulted = PassthroughReanchorController()
        assertNull(defaulted.continuationForFailure(round(body(), partial(bodyText = "1\n2"))))
    }

    @Test
    fun `no partial at all is not eligible`() {
        assertNull(controller.continuationForFailure(round(body(), partial = null)))
    }

    @Test
    fun `an open tool tear is not eligible`() {
        // A tool_use block swept shut with PARTIAL argument JSON: something was already committed to
        // the wire and there is no clean boundary to splice onto. Nothing recoverable exists here.
        assertNull(controller.continuationForFailure(round(body(), partial(toolTearOpen = true))))
    }

    @Test
    fun `a committed tool use is not eligible`() {
        // Refused for a DIFFERENT reason from the tear above: the call itself completed, so a
        // continuation would carry a tool_use whose tool_result cannot exist yet, and re-emitting it
        // risks double-dispatching a tool the client may already be running.
        assertNull(controller.continuationForFailure(round(body(), partial(hasToolUse = true))))
    }

    @Test
    fun `an empty salvage restarts the whole stream verbatim`() {
        // Nothing the client can SEE was salvaged, so a fresh round appends new blocks after the
        // closed ones and duplicates nothing — the request goes back exactly as it came in.
        val original = body()
        val continued = controller.continuationForFailure(round(original, partial(bodyText = "")))
        assertSame(original, continued, "the verbatim restart must be the original body, not a copy")
    }

    @Test
    fun `attempt four still continues`() {
        assertNotNull(controller.continuationForFailure(round(body(), partial(bodyText = "1"), attempt = 4)))
    }

    @Test
    fun `attempt five and beyond stop`() {
        // Pinned from BOTH sides against the budget of 5: attempt 4 above must continue, and these
        // must not, so a budget that moves either way turns one of the two tests red.
        assertNull(controller.continuationForFailure(round(body(), partial(bodyText = "1"), attempt = 5)))
        assertNull(controller.continuationForFailure(round(body(), partial(bodyText = "1"), attempt = 6)))
    }

    @Test
    fun `a failure type outside the retryable set is not eligible`() {
        val requests = round(body(), partial(bodyText = "1"), cause = FailureCause.UPSTREAM_STATUS_4XX)
        assertNull(controller.continuationForFailure(requests))
    }

    @Test
    fun `overloaded is retryable, so the set is pinned from both sides`() {
        val overloaded = round(body(), partial(bodyText = "1"), cause = FailureCause.UPSTREAM_STALLED)
        assertNotNull(controller.continuationForFailure(overloaded))
    }

    @Test
    fun `a mid-stream rate limit continues, because the re-POST is where a real 429 becomes readable`() {
        // V4-57. A rate limit met AFTER the first frame arrives as 200 + an SSE rate_limit_error, and
        // the client's recovery trigger is HTTP status 429 — so a turn that dead-ends here has no
        // automatic recovery at all and the operator must continue it by hand.
        //
        // Continuing is the right answer, and the wait it needs is NOT owed by this seam. Retrying
        // re-POSTs, and a provider still limiting answers THAT with a genuine pre-stream 429 carrying
        // Retry-After headers — the one place a pushback is machine-readable. The pre-stream path
        // already knows what to do with it, including V4-48's short-wait branch; and a reset longer
        // than the interactive ceiling gives up with a real 429, which the client CAN retry on. So
        // the recovery is bought without inventing a pushback the wire never carried.
        //
        // What this must NOT become: a continuation whose every round is a blind re-POST. The
        // attempt budget below is the bound, shared with OVERLOADED rather than special-cased.
        val limited = round(body(), partial(bodyText = "1"), cause = FailureCause.VENDOR_RATE_LIMITED)
        assertNotNull(controller.continuationForFailure(limited))
    }

    @Test
    fun `a rate limit past the attempt budget still stops`() {
        // Pinned from both sides like the budget itself: RATE_LIMIT earns continuation on the same
        // terms as every other retryable type, so the ceiling that bounds OVERLOADED bounds it too.
        val limited = round(body(), partial(bodyText = "1"), attempt = 5, cause = FailureCause.VENDOR_RATE_LIMITED)
        assertNull(controller.continuationForFailure(limited))
    }

    @Test
    fun `the controller never mutates the request body it was handed`() {
        // A controller that edits its input corrupts the very retry it exists to serve: the same
        // JsonObject is the turn's request, re-read by every later round in this turn. Byte-compared
        // via toString because that is the whole object, not the fields this test happened to think
        // of — a partial field check is how a mutation slips through.
        val original = body(thinking = "enabled")
        val before = original.toString()
        val continued = controller.continuationForFailure(round(original, partial(bodyText = "1\n2")))
        assertNotNull(continued, "sanity: the call must actually take the rewriting path")
        assertEquals(before, original.toString(), "the input body must be byte-identical after the call")
    }

    @Test
    fun `thinking enabled still continues, with thinking forced off for the continuation round`() {
        // MEASURED LIVE against api.deepseek.com/anthropic on 2026-09-16, three probes over an
        // identical history, and they changed this behaviour. With thinking ENABLED the prefill is
        // IGNORED and the model answers 1..10, restarting from the top (probe A) — so appending
        // naively would re-send every word the client had already read. But with thinking DISABLED
        // the same prefill continues 4..10 and IS honoured (probe B), including with a prior
        // thinking block in the replayed history, which did not cause a rejection. So a reasoning
        // turn is recoverable: disable thinking on the CONTINUATION ROUND ONLY. That is the proxy's
        // call to make, not an upstream limit — we author what crosses this wire, and this turn's
        // reasoning already happened and was already streamed, so the continuation only ever needed
        // to append the remaining text. Refusing instead (this file's first shape) would have left
        // the operator's own case — 196, 44 and 989 frames of visible salvage — ending exactly as it
        // did before, with every test green.
        val thinking = body(thinking = "enabled")
        val continued = controller.continuationForFailure(round(thinking, partial(bodyText = "1\n2\n3")))
        assertNotNull(continued, "the turning point: this is recoverable, not refused")
        assertEquals(THINKING_OFF, continued!!["thinking"], "forced off, asserted by VALUE")
        assertEquals(expectedPrefill("1\n2\n3"), (continued["messages"] as JsonArray).last())
    }

    @Test
    fun `thinking enabled with nothing visible still restarts verbatim, thinking untouched`() {
        // With NO visible salvage there is nothing to append, so the verbatim path is taken and the
        // whole body rides back UNCHANGED, thinking included. That is deliberate rather than
        // overlooked: nothing was appended, so nothing needs disabling, and object identity proves
        // the field was not rewritten on the way through.
        val thinking = body(thinking = "enabled")
        val continued = controller.continuationForFailure(round(thinking, partial(bodyText = "")))
        assertSame(thinking, continued)
        // The type is still ENABLED — this path deliberately does NOT disable thinking, because it
        // appended nothing and so has nothing to stop the model re-planning.
        assertEquals(
            JsonPrimitive("enabled"),
            (continued!!["thinking"] as JsonObject)["type"],
            "the restart keeps the setting it asked for",
        )
    }

    @Test
    fun `thinking already disabled keeps the prefill and the field stays exactly disabled`() {
        // A request that was already not reasoning takes the same prefill path, and the field it
        // declared stays exactly what it declared — the continuation WRITES the same value rather
        // than dropping the key, so the two spellings cannot drift apart.
        val disabled = body(thinking = "disabled")
        val continued = controller.continuationForFailure(round(disabled, partial(bodyText = "1\n2")))
        assertEquals(expectedPrefill("1\n2"), (continued!!["messages"] as JsonArray).last())
        assertEquals(THINKING_OFF, continued["thinking"], "exactly disabled, not merely present")
    }
}

private const val MESSAGES = "messages"
private const val THINKING_KEY = "thinking"

/** The exact value every prefill continuation must carry. Built BY HAND rather than reusing the
 *  controller's own object, so this pins the contract instead of agreeing with the implementation.
 *  A continuation that OMITS the key is a different and wrong behaviour, and probe C is why: with
 *  thinking absent this model reasons by default, emits a thinking block and restarts. */
private val THINKING_OFF = buildJsonObject { put("type", "disabled") }
